package capstone2.voisk.service;

import capstone2.voisk.config.GeminiProperties;
import capstone2.voisk.dto.SlotExtractionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmSlotFillerService {

    private static final Logger FALLBACK_LOG = LoggerFactory.getLogger("hybrid.fallback");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── 키워드 리스트 ───────────────────────────────────────────────────────────
    private static final List<String> ORDER_KW   = List.of("주세요", "줘", "먹을게", "드릴게요", "주문");
    private static final List<String> CONFIRM_KW = List.of("맞아요", "맞아", "확인", "그래요", "그래", "맞습니다", "응", "네", "예");
    private static final List<String> CANCEL_KW  = List.of("취소", "아니요", "아니", "다시", "틀려요");

    private static final List<String> NORMAL_MENU_KW  = List.of("일반", "1번", "첫번째", "기본", "보통", "저렴", "싼");
    private static final List<String> SPECIAL_MENU_KW = List.of("특식", "2번", "두번째", "특별", "프리미엄", "스페셜", "비싼");

    private static final Map<String, Integer> KO_NUM_WITH_UNIT = Map.ofEntries(
            Map.entry("하나", 1), Map.entry("한", 1),
            Map.entry("둘", 2), Map.entry("두", 2),
            Map.entry("셋", 3), Map.entry("세", 3),
            Map.entry("다섯", 5), Map.entry("여섯", 6),
            Map.entry("일곱", 7), Map.entry("여덟", 8),
            Map.entry("아홉", 9), Map.entry("열", 10)
    );
    private static final Map<String, Integer> KO_NUM_STANDALONE = Map.of(
            "하나", 1, "둘", 2, "셋", 3, "다섯", 5
    );

    private static final String SYSTEM_PROMPT = """
            너는 키오스크 주문 분석기야. 사용자의 한국어 음성 입력에서 아래 정보를 추출해서 JSON만 반환해. 설명 없이 JSON만.

            메뉴 종류 (순서 기준):
              1번 = "일반 메뉴" (8,000원) — 기본, 저렴한, 보통, 싼 쪽
              2번 = "특식 메뉴" (12,000원) — 특별한, 비싼, 좋은, 프리미엄 쪽

            반환 형식:
            {"intent": "...", "menu": "..." 또는 null, "quantity": 숫자 또는 null, "option": "..." 또는 null}

            intent 분류 기준:
            - ORDER: 메뉴 선택, 수량 언급, 주문 의사 표현 (예: 주세요, 줘, 먹을게, 드릴게요, 하나 주세요)
            - CONFIRM: 확인/동의 (예: 네, 응, 맞아요, 맞아, 확인, 그래요)
            - CANCEL: 취소/거부/재시작 (예: 아니요, 아니, 취소, 다시, 틀려요)
            - UNKNOWN: 위 세 가지 모두 해당 없음

            메뉴 추론 기준 (다양한 표현 → 메뉴 매핑):
            - "일반 메뉴"로 판단: 1번, 첫번째, 앞에 거, 기본, 보통, 일반, 싼 거, 저렴한 거, 작은 거
            - "특식 메뉴"로 판단: 2번, 두번째, 뒤에 거, 특식, 특별한, 비싼 거, 좋은 거, 프리미엄, 스페셜

            수량 추출 기준:
            숫자+번(1번, 2번) → 메뉴 번호(수량 아님), 숫자+개/명/사람분 → 수량
            "두 개" → 2, "둘" → 2, "2개" → 2, "하나" → 1, "한 개" → 1, "세 개" → 3, "열 개" → 10
            "두 사람분" → 2, "세 명이서" → 3
            예시: "특식 메뉴 2개" → menu: "특식 메뉴", quantity: 2 (2는 수량, 메뉴번호 아님)

            option: 특별 요청 사항이 있으면 문자열, 없으면 null.
            없는 정보는 null로 반환. 메뉴 이름은 반드시 "일반 메뉴" 또는 "특식 메뉴" 중 하나만 사용.
            """;

    private final RestClient geminiRestClient;
    private final GeminiProperties geminiProperties;

    public SlotExtractionResult extract(String userInput) {
        // ── Step 1: 키워드 매칭 ──────────────────────────────────────────────────
        boolean orderMatch   = matchesAny(userInput, ORDER_KW);
        boolean confirmMatch = matchesAny(userInput, CONFIRM_KW);
        boolean cancelMatch  = matchesAny(userInput, CANCEL_KW);
        long intentCount = (orderMatch ? 1 : 0) + (confirmMatch ? 1 : 0) + (cancelMatch ? 1 : 0);

        if (intentCount == 1) {
            if (cancelMatch)  return new SlotExtractionResult("CANCEL",  null, null, null);
            if (confirmMatch) return new SlotExtractionResult("CONFIRM", null, null, null);
            // ORDER: 키워드로 슬롯 추출
            String  menu     = extractMenu(userInput);
            Integer quantity = extractQuantity(userInput);
            return new SlotExtractionResult("ORDER", menu, quantity, null);
        }

        // ── Step 2: 키워드 실패 → LLM 폴백 ─────────────────────────────────────
        FALLBACK_LOG.warn("[KEYWORD_MISS] input=\"{}\" order={} confirm={} cancel={}",
                userInput, orderMatch, confirmMatch, cancelMatch);

        SlotExtractionResult llmResult = callGemini(userInput);

        FALLBACK_LOG.info("[LLM_RESULT] input=\"{}\" intent={} menu={} quantity={} option={}",
                userInput, llmResult.intent(), llmResult.menu(),
                llmResult.quantity(), llmResult.option());
        return llmResult;
    }

    // ─── 키워드 기반 슬롯 추출 ─────────────────────────────────────────────────

    private boolean matchesAny(String input, List<String> keywords) {
        return keywords.stream().anyMatch(input::contains);
    }

    private String extractMenu(String input) {
        boolean hasNormal  = NORMAL_MENU_KW.stream().anyMatch(input::contains);
        boolean hasSpecial = SPECIAL_MENU_KW.stream().anyMatch(input::contains);
        if (hasNormal && !hasSpecial) return "일반 메뉴";
        if (hasSpecial && !hasNormal) return "특식 메뉴";
        return null;
    }

    private Integer extractQuantity(String input) {
        // 아랍 숫자 + 단위
        Matcher m = Pattern.compile("(\\d+)\\s*(?:개|명|사람분)").matcher(input);
        if (m.find()) return Integer.parseInt(m.group(1));

        // 한국어 숫자 + 단위
        for (Map.Entry<String, Integer> e : KO_NUM_WITH_UNIT.entrySet()) {
            if (Pattern.compile(e.getKey() + "\\s*(?:개|명|사람분)").matcher(input).find()) {
                return e.getValue();
            }
        }

        // 단독 고유어 수량 ("하나", "둘", "셋", ...)
        for (Map.Entry<String, Integer> e : KO_NUM_STANDALONE.entrySet()) {
            if (input.contains(e.getKey())) return e.getValue();
        }

        return null;
    }

    // ─── Gemini API 호출 ───────────────────────────────────────────────────────

    private SlotExtractionResult callGemini(String userInput) {
        try {
            Map<String, Object> systemInstruction = Map.of(
                    "parts", List.of(Map.of("text", SYSTEM_PROMPT))
            );
            Map<String, Object> userContent = Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", userInput))
            );
            Map<String, Object> generationConfig = Map.of(
                    "temperature", 0,
                    "responseMimeType", "application/json"
            );
            Map<String, Object> body = Map.of(
                    "system_instruction", systemInstruction,
                    "contents", List.of(userContent),
                    "generationConfig", generationConfig
            );

            String raw = geminiRestClient.post()
                    .uri("/v1beta/models/{model}:generateContent", geminiProperties.getModel())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root    = MAPPER.readTree(raw);
            String content   = root.path("candidates").get(0)
                                   .path("content").path("parts").get(0)
                                   .path("text").asText();
            JsonNode parsed  = MAPPER.readTree(content);

            String  intent   = parsed.path("intent").asText("UNKNOWN");
            String  menu     = parsed.path("menu").isNull()     ? null : parsed.path("menu").asText(null);
            Integer quantity = parsed.path("quantity").isNull() ? null : parsed.path("quantity").asInt();
            String  option   = parsed.path("option").isNull()   ? null : parsed.path("option").asText(null);

            return new SlotExtractionResult(intent, menu, quantity, option);
        } catch (Exception e) {
            log.error("[Gemini] 슬롯 추출 실패 — input: \"{}\", error: {}", userInput, e.getMessage());
            return SlotExtractionResult.fallback();
        }
    }
}
