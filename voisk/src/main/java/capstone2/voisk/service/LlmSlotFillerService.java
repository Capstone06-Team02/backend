package capstone2.voisk.service;

import capstone2.voisk.config.GeminiProperties;
import capstone2.voisk.dto.MenuCacheResponse;
import capstone2.voisk.dto.SlotExtractionResult;
import capstone2.voisk.entity.OrderSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmSlotFillerService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> ORDER_KW = List.of("주세요", "줘", "먹을게", "주문", "할게", "담아");
    private static final List<String> CONFIRM_KW = List.of("네", "예", "맞아", "맞아요", "확인", "응", "좋아");
    private static final List<String> CANCEL_KW = List.of("아니", "취소", "다시", "말고", "됐어");
    private static final Pattern QTY_DIGIT = Pattern.compile("(\\d+)\\s*(?:개|잔|인분|명)");
    private static final Map<String, Integer> KO_QTY = Map.ofEntries(
            Map.entry("하나", 1),
            Map.entry("한", 1),
            Map.entry("둘", 2),
            Map.entry("두", 2),
            Map.entry("셋", 3),
            Map.entry("세", 3),
            Map.entry("넷", 4),
            Map.entry("다섯", 5),
            Map.entry("열", 10)
    );

    private static final String SYSTEM_PROMPT = """
            너는 식당 주문 음성 텍스트를 슬롯으로 변환하는 분석기다.
            반드시 제공된 [현재 식당 캐시 카탈로그] 안의 메뉴명과 옵션명만 사용한다.
            카탈로그에 없는 메뉴명이나 옵션명은 추측하지 말고 null로 둔다.

            반환 형식은 설명 없이 JSON 하나만 사용한다.
            {
              "intent": "ORDER" | "CONFIRM" | "CANCEL" | "UNKNOWN",
              "menu": "카탈로그의 정확한 메뉴명" | null,
              "quantity": 숫자 | null,
              "option": "카탈로그의 옵션명들을 쉼표로 연결" | null
            }

            판단 기준:
            - ORDER: 메뉴 선택, 수량 언급, 옵션 선택, 주문 의사
            - CONFIRM: 현재 서버 질문에 대한 긍정/확정
            - CANCEL: 취소, 거절, 다시 선택, 다른 것으로 변경
            - UNKNOWN: 위에 해당하지 않음

            수량은 숫자+개/잔/인분/명 또는 하나/두/세 같은 표현에서만 추출한다.
            메뉴와 옵션은 카탈로그에 있는 정확한 이름으로 반환한다.
            """;

    private final RestClient geminiRestClient;
    private final GeminiProperties geminiProperties;
    private final StoreMenuCacheService storeMenuCacheService;

    public SlotExtractionResult extract(String userInput, OrderSession session) {
        String input = userInput == null ? "" : userInput.trim();
        Optional<MenuCacheResponse> catalog = resolveCatalog(session);

        SlotExtractionResult ruleResult = extractFromCachedCatalog(input, session, catalog);
        if (!"UNKNOWN".equals(ruleResult.intent())) {
            return ruleResult;
        }

        return callGemini(input, session, catalog);
    }

    private SlotExtractionResult extractFromCachedCatalog(
            String input,
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        Optional<MenuCacheResponse.MenuInfo> menu = findMenu(input, catalog);
        Integer quantity = extractQuantity(input);
        String option = findOptions(input, catalog, session);

        if (containsAny(input, CANCEL_KW)) {
            return new SlotExtractionResult("CANCEL", menu.map(MenuCacheResponse.MenuInfo::name).orElse(null), null, option);
        }
        if (containsAny(input, CONFIRM_KW) && menu.isEmpty() && quantity == null && option == null) {
            return new SlotExtractionResult("CONFIRM", null, null, null);
        }
        if (menu.isPresent() || quantity != null || option != null || containsAny(input, ORDER_KW)) {
            return new SlotExtractionResult("ORDER", menu.map(MenuCacheResponse.MenuInfo::name).orElse(null), quantity, option);
        }
        return SlotExtractionResult.fallback();
    }

    private Optional<MenuCacheResponse> resolveCatalog(OrderSession session) {
        if (storeMenuCacheService == null) {
            return Optional.empty();
        }
        if (session != null && session.getRestaurantId() != null) {
            return storeMenuCacheService.getCachedMenus(session.getRestaurantId());
        }
        return storeMenuCacheService.getLatestCachedMenus();
    }

    private Optional<MenuCacheResponse.MenuInfo> findMenu(String input, Optional<MenuCacheResponse> catalog) {
        String normalizedInput = normalize(input);
        return catalog.stream()
                .flatMap(response -> response.menus().stream())
                .filter(menu -> menu.name() != null && !menu.name().isBlank())
                .map(menu -> Map.entry(menu, normalizedInput.indexOf(normalize(menu.name()))))
                .filter(entry -> entry.getValue() >= 0)
                .sorted(Comparator
                        .comparingInt((Map.Entry<MenuCacheResponse.MenuInfo, Integer> entry) -> entry.getValue())
                        .thenComparing(entry -> normalize(entry.getKey().name()).length(), Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private List<MenuCacheResponse.MenuInfo> menusForSession(
            Optional<MenuCacheResponse> catalog,
            OrderSession session
    ) {
        List<MenuCacheResponse.MenuInfo> menus = catalog.stream()
                .flatMap(response -> response.menus().stream())
                .toList();
        if (session == null || (session.getMenuId() == null && session.getMenu() == null)) {
            return menus;
        }
        List<MenuCacheResponse.MenuInfo> selectedMenus = menus.stream()
                .filter(menu -> session.getMenuId() != null
                        ? session.getMenuId().equals(menu.menuId())
                        : normalize(menu.name()).equals(normalize(session.getMenu())))
                .toList();
        return selectedMenus.isEmpty() ? menus : selectedMenus;
    }

    private String findOptions(String input, Optional<MenuCacheResponse> catalog, OrderSession session) {
        String normalizedInput = normalize(input);
        List<String> options = menusForSession(catalog, session).stream()
                .flatMap(menu -> emptyIfNull(menu.optionGroups()).stream())
                .flatMap(group -> emptyIfNull(group.optionItems()).stream())
                .filter(item -> matchesNameOrAlias(normalizedInput, item.name(), item.aliases()))
                .map(MenuCacheResponse.OptionItemInfo::name)
                .distinct()
                .toList();
        return options.isEmpty() ? null : String.join(", ", options);
    }

    private Integer extractQuantity(String input) {
        Matcher matcher = QTY_DIGIT.matcher(input);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        for (Map.Entry<String, Integer> entry : KO_QTY.entrySet()) {
            if (input.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private SlotExtractionResult callGemini(
            String userInput,
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        try {
            Map<String, Object> systemInstruction = Map.of(
                    "parts", List.of(Map.of("text", SYSTEM_PROMPT))
            );
            Map<String, Object> userContent = Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", buildPromptInput(userInput, session, catalog)))
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

            String intent = parsed.path("intent").asText("UNKNOWN");
            String menu = parsed.path("menu").isNull() ? null : parsed.path("menu").asText(null);
            Integer quantity = parsed.path("quantity").isNull() ? null : parsed.path("quantity").asInt();
            String option = parsed.path("option").isNull() ? null : parsed.path("option").asText(null);

            return new SlotExtractionResult(intent, menu, quantity, option);
        } catch (Exception e) {
            log.error("[Gemini] 슬롯 추출 실패 - input: \"{}\", error: {}", userInput, e.getMessage());
            return SlotExtractionResult.fallback();
        }
    }

    private String buildPromptInput(String userInput, OrderSession session, Optional<MenuCacheResponse> catalog) {
        return """
                [현재 주문 세션]
                restaurantId: %s
                selectedMenu: %s
                quantity: %s

                [현재 식당 캐시 카탈로그]
                %s

                [사용자 발화]
                %s
                """.formatted(
                session == null ? null : session.getRestaurantId(),
                session == null ? null : session.getMenu(),
                session == null ? null : session.getQuantity(),
                catalog.map(value -> formatCatalog(value, session)).orElse("캐시된 메뉴 데이터 없음"),
                userInput
        );
    }

    private String formatCatalog(MenuCacheResponse catalog, OrderSession session) {
        return menusForSession(Optional.of(catalog), session).stream()
                .map(menu -> "- 메뉴: %s\n  옵션:\n%s".formatted(menu.name(), formatOptionGroups(menu)))
                .collect(Collectors.joining("\n"));
    }

    private String formatOptionGroups(MenuCacheResponse.MenuInfo menu) {
        String groups = emptyIfNull(menu.optionGroups()).stream()
                .map(group -> "  - %s%s%s: %s".formatted(
                        group.name(),
                        formatAliases(group.aliases()),
                        group.parentOptionItemId() == null ? "" : " (parentOptionItemId=" + group.parentOptionItemId() + ")",
                        emptyIfNull(group.optionItems()).stream()
                                .map(item -> item.name() + formatAliases(item.aliases()))
                                .collect(Collectors.joining(", "))
                ))
                .collect(Collectors.joining("\n"));
        return groups.isBlank() ? "  - 옵션 없음" : groups;
    }

    private boolean matchesNameOrAlias(String normalizedInput, String name, Collection<String> aliases) {
        if (normalizedInput.contains(normalize(name))) {
            return true;
        }
        return emptyIfNull(aliases).stream()
                .map(this::normalize)
                .anyMatch(normalizedInput::contains);
    }

    private String formatAliases(Collection<String> aliases) {
        List<String> values = emptyIfNull(aliases).stream()
                .filter(alias -> alias != null && !alias.isBlank())
                .toList();
        return values.isEmpty() ? "" : " (alias: " + String.join(", ", values) + ")";
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }

    private <T> Collection<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : values;
    }
}
