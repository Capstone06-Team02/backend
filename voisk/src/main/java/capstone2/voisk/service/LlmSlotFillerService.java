package capstone2.voisk.service;

import capstone2.voisk.config.GeminiProperties;
import capstone2.voisk.dto.MenuCacheResponse;
import capstone2.voisk.dto.OrderDraft;
import capstone2.voisk.dto.SlotExtractionResult;
import capstone2.voisk.entity.OrderSession;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmSlotFillerService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static final String SYSTEM_PROMPT = """
            너는 식당 주문 발화를 서버 슬롯 처리용 JSON으로만 변환한다.
            설명 문장 없이 아래 JSON 하나만 반환한다.

            {
              "intent": "ORDER" | "CONFIRM" | "CANCEL" | "UNKNOWN",
              "menu": "제공된 메뉴 후보의 정확한 메뉴명" | null,
              "quantity": 숫자 | null,
              "option": "현재 주문 slot JSON 후보에 있는 정확한 옵션값 이름들을 쉼표로 연결" | null,
              "orderDraft": null
            }

            규칙:
            - 현재 주문 slot JSON에 있는 메뉴/옵션 후보를 최우선으로 사용한다.
            - 현재 주문 slot JSON에 없는 새 메뉴가 발화에 있으면 [메뉴 후보]에서만 고른다.
            - 후보에 없는 메뉴명/옵션명은 추측하지 말고 null로 둔다.
            - 옵션은 optionGroup 이름이 아니라 candidate 이름을 반환한다. 예: "디카페인", "벤티"
            - previousBotResponse에 "기본 X입니다" 또는 "기본 X에서"가 있고 사용자 발화가 "그대로", "그대로 주세요", "기본으로", "기본으로 주세요"처럼 기본값 수락이면 intent는 ORDER, option은 X로 반환한다.
            - previousBotResponse가 "기본 X에서 변경하시겠어요?" 형태이고 사용자 발화가 "아니", "아니요", "변경 안 할게요", "괜찮아요"처럼 변경 거절이면 주문 취소가 아니라 기본값 유지이다. 이 경우 intent는 ORDER, option은 X로 반환한다.
            - 수량은 숫자+개/잔/인분/명 또는 하나/두/세 같은 표현에서만 추출한다.
            - 긍정 답변은 CONFIRM, 거절/취소/다시 선택은 CANCEL, 판단이 어려우면 UNKNOWN. 단, 직전 봇 응답의 기본 옵션 변경 여부를 묻는 질문에 대한 거절은 CANCEL이 아니다.
            - orderDraft는 서버가 관리하므로 항상 null로 둔다.
            """;

    private final RestClient geminiRestClient;
    private final GeminiProperties geminiProperties;
    private final StoreMenuCacheService storeMenuCacheService;

    public SlotExtractionResult extract(String userInput, OrderSession session) {
        String input = userInput == null ? "" : userInput.trim();
        Optional<MenuCacheResponse> catalog = resolveCatalog(session);
        return callGemini(input, session, catalog);
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
                previousUtterance: %s
                previousBotResponse: %s

                [현재 주문 slot JSON]
                %s

                %s

                [사용자 발화]
                %s
                """.formatted(
                session == null ? null : session.getRestaurantId(),
                session == null ? null : session.getMenu(),
                session == null ? null : session.getQuantity(),
                session == null ? null : session.getPreviousUtterance(),
                session == null ? null : session.getPreviousBotResponse(),
                formatDraft(session, catalog),
                formatMenuCandidateBlock(userInput, session, catalog),
                userInput
        );
    }

    private String formatDraft(OrderSession session, Optional<MenuCacheResponse> catalog) {
        if (session == null || session.getOrderDraft() == null) {
            return "null";
        }
        try {
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("items", emptyIfNull(session.getOrderDraft().items()).stream()
                    .map(item -> formatDraftItem(item, catalog))
                    .toList());
            return MAPPER.writeValueAsString(draft);
        } catch (Exception e) {
            return "null";
        }
    }

    private Map<String, Object> formatDraftItem(
            OrderDraft.Item item,
            Optional<MenuCacheResponse> catalog
    ) {
        Map<String, Object> itemJson = new LinkedHashMap<>();
        Optional<MenuCacheResponse.MenuInfo> menu = findMenu(item, catalog);
        itemJson.put("menu", menu.map(MenuCacheResponse.MenuInfo::name).orElse(item.menuName()));
        itemJson.put("menuPrice", menu.map(MenuCacheResponse.MenuInfo::price).orElse(null));
        itemJson.put("quantity", item.quantity());
        if (menu.isEmpty()) {
            itemJson.put("optionSlots", List.of());
            return itemJson;
        }

        Set<Long> selectedOptionIds = selectedOptionIdsFromDraft(item, menu.get());
        itemJson.put("optionSlots", emptyIfNull(menu.get().optionGroups()).stream()
                .map(group -> formatOptionSlot(group, selectedOptionIds))
                .toList());
        return itemJson;
    }

    private Map<String, Object> formatOptionSlot(
            MenuCacheResponse.OptionGroupInfo group,
            Set<Long> selectedOptionIds
    ) {
        Map<String, Object> groupJson = new LinkedHashMap<>();
        groupJson.put("name", group.name());
        groupJson.put("isRequired", Boolean.TRUE.equals(group.isRequired()));
        groupJson.put("candidates", emptyIfNull(group.optionItems()).stream()
                .filter(item -> !Boolean.FALSE.equals(item.isAvailable()))
                .map(item -> {
                    Map<String, Object> candidateJson = new LinkedHashMap<>();
                    candidateJson.put("name", item.name());
                    candidateJson.put("extraPrice", item.extraPrice());
                    candidateJson.put("defaultQuantity", item.defaultQuantity() == null ? 0 : item.defaultQuantity());
                    candidateJson.put("defaultSelected", isDefaultSelected(item));
                    candidateJson.put("selected", selectedOptionIds.contains(item.optionItemId()));
                    return candidateJson;
                })
                .toList());
        return groupJson;
    }

    private Optional<MenuCacheResponse.MenuInfo> findMenu(
            OrderDraft.Item item,
            Optional<MenuCacheResponse> catalog
    ) {
        return catalog.stream()
                .flatMap(response -> response.menus().stream())
                .filter(menu -> (item.menuId() != null && item.menuId().equals(menu.menuId()))
                        || menuNameOrAliasEquals(menu, item.menuName()))
                .findFirst();
    }

    private Set<Long> selectedOptionIdsFromDraft(
            OrderDraft.Item item,
            MenuCacheResponse.MenuInfo menu
    ) {
        Set<Long> selected = new LinkedHashSet<>();
        java.util.stream.Stream.concat(
                        emptyIfNull(item.requiredOptions()).stream(),
                        emptyIfNull(item.optionalOptions()).stream()
                )
                .forEach(option -> resolveSelectedOptionId(menu, option).ifPresent(selected::add));
        return selected;
    }

    private Optional<Long> resolveSelectedOptionId(
            MenuCacheResponse.MenuInfo menu,
            OrderDraft.OptionValue option
    ) {
        if (option.selectedOptionItemId() != null) {
            return Optional.of(option.selectedOptionItemId());
        }
        if (option.selectedOptionItemName() == null || option.selectedOptionItemName().isBlank()) {
            return Optional.empty();
        }
        String normalizedOptionName = normalize(option.selectedOptionItemName());
        return emptyIfNull(menu.optionGroups()).stream()
                .filter(group -> option.optionGroupId() != null && option.optionGroupId().equals(group.optionGroupId()))
                .flatMap(group -> emptyIfNull(group.optionItems()).stream())
                .filter(candidate -> normalize(candidate.name()).equals(normalizedOptionName))
                .map(MenuCacheResponse.OptionItemInfo::optionItemId)
                .findFirst();
    }

    private boolean isDefaultSelected(MenuCacheResponse.OptionItemInfo item) {
        return Boolean.TRUE.equals(item.isDefault())
                || (item.defaultQuantity() != null && item.defaultQuantity() > 0);
    }

    private String formatMenuCandidateBlock(
            String userInput,
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        if (catalog.isEmpty() || !shouldIncludeMenuCandidates(userInput, session)) {
            return "[메뉴 후보]\n생략";
        }
        String candidates = catalog.stream()
                .flatMap(response -> response.menus().stream())
                .filter(menu -> !Boolean.FALSE.equals(menu.isAvailable()))
                .map(menu -> {
                    String aliases = emptyIfNull(menu.aliases()).stream()
                            .filter(alias -> alias != null && !alias.isBlank())
                            .collect(Collectors.joining(", "));
                    return aliases.isBlank()
                            ? "- " + menu.name()
                            : "- " + menu.name() + " (aliases: " + aliases + ")";
                })
                .collect(Collectors.joining("\n"));
        return "[메뉴 후보]\n" + (candidates.isBlank() ? "없음" : candidates);
    }

    private boolean menuNameOrAliasEquals(MenuCacheResponse.MenuInfo menu, String value) {
        String normalizedValue = normalize(value);
        if (normalizedValue.isBlank()) {
            return false;
        }
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(menu.name()),
                        emptyIfNull(menu.aliases()).stream()
                )
                .map(this::normalize)
                .anyMatch(normalizedValue::equals);
    }

    private boolean shouldIncludeMenuCandidates(String userInput, OrderSession session) {
        if (session == null || session.getOrderDraft() == null
                || session.getOrderDraft().items() == null
                || session.getOrderDraft().items().isEmpty()) {
            return true;
        }
        String normalizedInput = normalize(userInput);
        return List.of("추가", "그리고", "또", "랑", "하고", "도").stream()
                .map(this::normalize)
                .anyMatch(normalizedInput::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }

    private <T> Collection<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : values;
    }
}
