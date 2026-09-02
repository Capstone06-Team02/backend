package capstone2.voisk.recommend;

import capstone2.voisk.config.GeminiProperties;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Gemini 추천
 * - 입력: 후보 메뉴만 제공
 * - 출력: 가격 조건·순위 ID
 * - 검증: DB 원본값 기준
 * - 실패: 503 반환
 */
@Slf4j
@Service
public class LlmRecommendService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            너는 카페 메뉴 추천 도우미다. 사용자 발화를 읽고 아래 [후보 메뉴] 중에서 가장 적합한 메뉴를 골라 추천한다.
            가격은 옵션 추가금을 제외하고 후보에 표시된 메뉴 기본 가격만 사용한다.

            규칙:
            - 반드시 [후보 메뉴]에 있는 menuId 중에서만 고른다. 후보에 없는 메뉴는 절대 만들지 않는다.
            - 사용자가 수치 가격 경계를 명시한 경우에만 minPrice 또는 maxPrice를 설정한다.
            - "5천 원 이하", "5천 원 안 넘는", "5천 원까지"는 maxPrice=5000, maxPriceInclusive=true다.
            - "5천 원 미만", "5천 원보다 싼"은 maxPrice=5000, maxPriceInclusive=false다.
            - "4천 원 이상"은 minPrice=4000, minPriceInclusive=true이고, "4천 원 초과"는 false다.
            - "4천 원에서 6천 원 사이"처럼 별도 제외 표현이 없는 범위는 양쪽 경계를 포함한다.
            - "저렴한", "가성비 좋은", "5천 원 정도", "5천 원 안팎"은 임의의 하드 가격 경계로 바꾸지 않는다.
            - 가격 수치 조건이 없으면 minPrice와 maxPrice는 반드시 null이다.
            - "커피 말고", "달지 않은" 같은 부정/제외 표현을 반영해 해당 메뉴를 제외한다.
            - rankedMenuIds에는 추출한 가격 조건까지 만족하는 후보만 적합한 순서로 최대 10개까지 넣는다.
            - 중복 ID를 넣거나 개수를 채우기 위해 부적합한 메뉴를 넣지 않는다.
            - 적합한 메뉴가 하나도 없으면 rankedMenuIds를 빈 배열로 반환한다.
            - 설명 문장 없이 아래 JSON 하나만 반환한다.

            {
              "constraints": {
                "minPrice": 정수 | null,
                "minPriceInclusive": true | false,
                "maxPrice": 정수 | null,
                "maxPriceInclusive": true | false
              },
              "rankedMenuIds": [정수, ...]
            }
            """;

    private static final int MAX_ATTEMPTS = 2;
    private static final long DEFAULT_RETRY_BACKOFF_MILLIS = 400L;

    private final RestClient recommendGeminiRestClient;
    private final GeminiProperties geminiProperties;
    private final MenuRepository menuRepository;
    private final GeminiRecommendationParser parser;
    private final RecommendationValidationService validationService;
    private final long retryBackoffMillis;

    @Autowired
    public LlmRecommendService(
            @Qualifier("recommendGeminiRestClient") RestClient recommendGeminiRestClient,
            GeminiProperties geminiProperties,
            MenuRepository menuRepository,
            GeminiRecommendationParser parser,
            RecommendationValidationService validationService
    ) {
        this(
                recommendGeminiRestClient,
                geminiProperties,
                menuRepository,
                parser,
                validationService,
                DEFAULT_RETRY_BACKOFF_MILLIS
        );
    }

    LlmRecommendService(
            RestClient recommendGeminiRestClient,
            GeminiProperties geminiProperties,
            MenuRepository menuRepository,
            GeminiRecommendationParser parser,
            RecommendationValidationService validationService,
            long retryBackoffMillis
    ) {
        this.recommendGeminiRestClient = recommendGeminiRestClient;
        this.geminiProperties = geminiProperties;
        this.menuRepository = menuRepository;
        this.parser = parser;
        this.validationService = validationService;
        this.retryBackoffMillis = retryBackoffMillis;
    }

    /** LLM 단독 추천 — 매장 전체 판매중 메뉴를 후보로 LLM에 넘긴다. */
    public LlmRecommendResponse recommend(String text, Long storeId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId is required.");
        }
        return recommendFromCandidates(
                text,
                storeId,
                menuRepository.findAvailableByStoreIdWithCategory(storeId)
        );
    }

    /** 임베딩 top-K 후보 대상 LLM 재랭킹·DB 검증 */
    public LlmRecommendResponse recommendFromCandidates(String text, Long storeId, List<Menu> candidates) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId is required.");
        }
        if (candidates == null || candidates.isEmpty()) {
            return new LlmRecommendResponse(List.of(), emptyTts(), LlmRecommendResponse.TokenUsage.zero());
        }

        GeminiRecommendationParser.ParsedDecision decision = callGemini(
                text == null ? "" : text.trim(),
                candidates
        );
        List<Long> originalCandidateIds = candidates.stream().map(Menu::getMenuId).toList();
        List<Menu> validatedMenus = validationService.validate(
                storeId,
                originalCandidateIds,
                decision.constraints(),
                decision.rankedMenuIds()
        );
        List<LlmMenuRecommendation> result = validatedMenus.stream()
                .map(menu -> new LlmMenuRecommendation(
                        menu.getMenuId(),
                        menu.getName(),
                        menu.getPrice(),
                        menu.getCategory().getName()
                ))
                .toList();

        log.info(
                "[Recommend] storeId={}, candidates={}, ranked={}, minPrice={}({}), maxPrice={}({}), final={}",
                storeId,
                candidates.size(),
                decision.rankedMenuIds().size(),
                decision.constraints().minPrice(),
                decision.constraints().minPriceInclusive() ? "inclusive" : "exclusive",
                decision.constraints().maxPrice(),
                decision.constraints().maxPriceInclusive() ? "inclusive" : "exclusive",
                result.size()
        );

        return new LlmRecommendResponse(result, buildTtsText(result), decision.usage());
    }

    /** Gemini 호출: 429·503 1회 재시도, 최종 실패 503 */
    private GeminiRecommendationParser.ParsedDecision callGemini(String userInput, List<Menu> candidates) {
        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", buildPromptInput(userInput, candidates))))),
                "generationConfig", Map.of("temperature", 0, "responseMimeType", "application/json")
        );

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String raw = recommendGeminiRestClient.post()
                        .uri("/v1beta/models/{model}:generateContent", geminiProperties.getModel())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);

                JsonNode root = MAPPER.readTree(raw);
                String content = root.path("candidates").get(0)
                        .path("content").path("parts").get(0)
                        .path("text").asText();
                // 토큰 사용량
                JsonNode usage = root.path("usageMetadata");
                LlmRecommendResponse.TokenUsage tokenUsage = new LlmRecommendResponse.TokenUsage(
                        usage.path("promptTokenCount").asInt(0),
                        usage.path("candidatesTokenCount").asInt(0),
                        usage.path("totalTokenCount").asInt(0));
                return parser.parse(content, tokenUsage);
            } catch (RestClientResponseException e) {
                int sc = e.getStatusCode().value();
                if ((sc == 503 || sc == 429) && attempt < MAX_ATTEMPTS) {
                    log.warn("[Gemini] 추천 {} (시도 {}/{}) → 한 번 재시도", sc, attempt, MAX_ATTEMPTS);
                    sleepBeforeRetry();
                } else {
                    throw unavailable(e);
                }
            } catch (Exception e) {
                throw unavailable(e);
            }
        }
        throw unavailable(null);
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(retryBackoffMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(e);
        }
    }

    private ResponseStatusException unavailable(Exception cause) {
        log.error("[Gemini] 추천 처리 실패: {}", cause == null ? "unknown" : cause.getMessage());
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "추천을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                cause
        );
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

                [후보 메뉴] (가격은 옵션 추가금 없는 기본 가격, 목록 밖 추천 금지)
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
