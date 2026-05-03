package capstone2.voisk.service;

import capstone2.voisk.config.GeminiProperties;
import capstone2.voisk.dto.SlotExtractionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmSlotFillerService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            너는 키오스크 주문 분석기야. 사용자의 한국어 음성 입력에서 아래 정보를 추출해서 JSON만 반환해. 설명 없이 JSON만.

            메뉴 종류 (순서 기준):
              1번 = "일반 메뉴" (8,000원) — 기본, 저렴한, 보통, 싼 쪽
              2번 = "특식 메뉴" (12,000원) — 특별한, 비싼, 좋은, 프리미엄 쪽

            반환 형식:
            {"intent": "...", "menu": "..." 또는 null, "quantity": 숫자 또는 null}

            intent 분류 기준:
            - ORDER: 메뉴 선택, 수량 언급, 주문 의사 표현 (예: 주세요, 줘, 먹을게, 드릴게요, 하나 주세요)
            - CONFIRM: 확인/동의 (예: 네, 응, 맞아요, 맞아, 확인, 그래요)
            - CANCEL: 취소/거부/재시작 (예: 아니요, 아니, 취소, 다시, 틀려요)
            - UNKNOWN: 위 세 가지 모두 해당 없음

            메뉴 추론 기준 (다양한 표현 → 메뉴 매핑):
            - "일반 메뉴"로 판단: 1번, 첫번째, 앞에 거, 기본, 보통, 일반, 싼 거, 저렴한 거, 작은 거
            - "특식 메뉴"로 판단: 2번, 두번째, 뒤에 거, 특식, 특별한, 비싼 거, 좋은 거, 프리미엄, 스페셜

            수량 추출 기준 (단위어 없어도 숫자 판단):
            "두 개" → 2, "둘" → 2, "2개" → 2, "하나" → 1, "한 개" → 1, "세 개" → 3, "열 개" → 10
            "두 사람분" → 2, "세 명이서" → 3

            없는 정보는 null로 반환. 메뉴 이름은 반드시 "일반 메뉴" 또는 "특식 메뉴" 중 하나만 사용.
            """;

    private final RestClient geminiRestClient;
    private final GeminiProperties geminiProperties;

    public SlotExtractionResult extract(String userInput) {
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

            JsonNode root   = MAPPER.readTree(raw);
            String content  = root.path("candidates").get(0)
                                  .path("content").path("parts").get(0)
                                  .path("text").asText();
            JsonNode parsed = MAPPER.readTree(content);

            String  intent   = parsed.path("intent").asText("UNKNOWN");
            String  menu     = parsed.path("menu").isNull() ? null : parsed.path("menu").asText(null);
            Integer quantity = parsed.path("quantity").isNull() ? null : parsed.path("quantity").asInt();

            return new SlotExtractionResult(intent, menu, quantity);
        } catch (Exception e) {
            log.error("[Gemini] 슬롯 추출 실패 — input: \"{}\", error: {}", userInput, e.getMessage());
            return SlotExtractionResult.fallback();
        }
    }
}
