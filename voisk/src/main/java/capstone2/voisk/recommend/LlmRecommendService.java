package capstone2.voisk.recommend;

import capstone2.voisk.config.GeminiProperties;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM(Gemini) 기반 추천 서비스.
 *
 * <p>임베딩({@link RecommendService})·룰베이스({@link RuleRecommendService})와 별개의 세 번째 추천 방식.
 * stateless 1회 호출로 사용자 발화를 Gemini에 보내 최대 3개 메뉴를 추천받는다.
 *
 * <h3>환각(hallucination) 차단 2겹</h3>
 * <ol>
 *   <li><b>입력 제약</b>: 프롬프트에 해당 매장의 판매중 메뉴(menuId 포함)만 후보로 주고, 후보 밖 메뉴를 만들지 말라고 지시한다.</li>
 *   <li><b>출력 검증</b>: LLM은 menuId만 고르게 하고, 반환된 id를 후보 집합과 대조한다. 후보에 없는 id는 폐기하고,
 *       이름·가격·카테고리는 LLM 텍스트가 아니라 DB의 실제 {@code Menu} 엔티티 값으로 채운다.</li>
 * </ol>
 *
 * Gemini 호출 실패/파싱 실패/유효 추천 0건이면 빈 결과 + 안내 TTS로 graceful degradation 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmRecommendService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** 추천 최대 개수. 룰/임베딩(5)과 달리 LLM은 top-3로 제한한다. */
    private static final int MAX_RESULTS = 3;

    private static final String SYSTEM_PROMPT = """
            너는 카페 메뉴 추천 도우미다. 사용자 발화를 읽고 아래 [후보 메뉴] 중에서 가장 적합한 메뉴를 골라 추천한다.

            규칙:
            - 반드시 [후보 메뉴]에 있는 menuId 중에서만 고른다. 후보에 없는 메뉴는 절대 만들지 않는다.
            - 사용자의 의도에 맞는 순서로 정렬해 최대 3개까지 고른다.
            - "커피 말고", "달지 않은" 같은 부정/제외 표현을 반영해 해당 메뉴를 제외한다.
            - 적합한 메뉴가 하나도 없으면 빈 배열을 반환한다.
            - 설명 문장 없이 아래 JSON 하나만 반환한다.

            {
              "menuIds": [정수, ...]
            }
            """;

    private final RestClient geminiRestClient;
    private final GeminiProperties geminiProperties;
    private final MenuRepository menuRepository;

    @Transactional(readOnly = true)
    public LlmRecommendResponse recommend(String text, Long storeId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId is required.");
        }

        List<Menu> candidates = menuRepository.findAvailableByStoreIdWithCategory(storeId);
        if (candidates.isEmpty()) {
            return new LlmRecommendResponse(List.of(), emptyTts(), LlmRecommendResponse.TokenUsage.zero());
        }

        GeminiResult gemini = callGemini(text == null ? "" : text.trim(), candidates);

        // 출력 검증: 후보 집합에 실제로 존재하는 menuId만 통과시키고, DB 엔티티 값으로 결과를 구성한다.
        Map<Long, Menu> candidateById = candidates.stream()
                .collect(Collectors.toMap(Menu::getMenuId, m -> m, (a, b) -> a, LinkedHashMap::new));

        List<LlmMenuRecommendation> result = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : gemini.ids()) {
            Menu menu = candidateById.get(id);
            if (menu == null || !seen.add(id)) {
                continue; // 환각 id 또는 중복 폐기
            }
            result.add(new LlmMenuRecommendation(
                    menu.getMenuId(), menu.getName(), menu.getPrice(), menu.getCategory().getName()));
            if (result.size() == MAX_RESULTS) {
                break;
            }
        }

        return new LlmRecommendResponse(result, buildTtsText(result), gemini.usage());
    }

    /** callGemini 반환 묶음: 추천 menuId 목록 + 이번 호출 토큰 사용량. */
    private record GeminiResult(List<Long> ids, LlmRecommendResponse.TokenUsage usage) {}

    /** Gemini를 호출해 추천 menuId 목록과 토큰 사용량을 받는다. 실패 시 빈 목록·0 토큰으로 폴백. */
    private GeminiResult callGemini(String userInput, List<Menu> candidates) {
        try {
            Map<String, Object> systemInstruction = Map.of(
                    "parts", List.of(Map.of("text", SYSTEM_PROMPT))
            );
            Map<String, Object> userContent = Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", buildPromptInput(userInput, candidates)))
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

            JsonNode root = MAPPER.readTree(raw);
            String content = root.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();
            JsonNode parsed = MAPPER.readTree(content);

            List<Long> ids = new ArrayList<>();
            JsonNode menuIds = parsed.path("menuIds");
            if (menuIds.isArray()) {
                for (JsonNode node : menuIds) {
                    if (node.canConvertToLong()) {
                        ids.add(node.asLong());
                    }
                }
            }

            // 비용 측정용 토큰 사용량 (Gemini usageMetadata)
            JsonNode usage = root.path("usageMetadata");
            LlmRecommendResponse.TokenUsage tokenUsage = new LlmRecommendResponse.TokenUsage(
                    usage.path("promptTokenCount").asInt(0),
                    usage.path("candidatesTokenCount").asInt(0),
                    usage.path("totalTokenCount").asInt(0));
            return new GeminiResult(ids, tokenUsage);
        } catch (Exception e) {
            log.error("[Gemini] 추천 실패 - input: \"{}\", error: {}", userInput, e.getMessage());
            return new GeminiResult(List.of(), LlmRecommendResponse.TokenUsage.zero());
        }
    }

    private String buildPromptInput(String userInput, List<Menu> candidates) {
        String menuBlock = candidates.stream()
                .map(m -> "- menuId=%d | %s | %d원 | %s | %s".formatted(
                        m.getMenuId(),
                        m.getName(),
                        m.getPrice(),
                        m.getCategory().getName(),
                        safe(m.getDescription())))
                .collect(Collectors.joining("\n"));
        return """
                [사용자 발화]
                %s

                [후보 메뉴] (이 목록 밖의 메뉴는 추천 금지)
                %s
                """.formatted(userInput, menuBlock);
    }

    private String buildTtsText(List<LlmMenuRecommendation> list) {
        if (list.isEmpty()) {
            return emptyTts();
        }
        if (list.size() == 1) {
            return list.get(0).name() + "을(를) 추천드려요.";
        }
        String names = list.stream().map(LlmMenuRecommendation::name).collect(Collectors.joining(", "));
        return "추천 메뉴로는 " + names + "를 추천드려요.";
    }

    private String emptyTts() {
        return "죄송합니다, 조건에 맞는 메뉴를 찾지 못했어요.";
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
