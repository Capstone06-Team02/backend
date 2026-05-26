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
            너는 식당 주문 음성 텍스트를 슬롯으로 변환하는 분석기다.
            반드시 제공된 [현재 식당 캐시 카탈로그] 안의 메뉴명과 옵션명만 사용한다.
            카탈로그에 없는 메뉴명이나 옵션명은 추측하지 말고 null로 둔다.

            반환 형식은 설명 없이 JSON 하나만 사용한다.
            {
              "intent": "ORDER" | "CONFIRM" | "CANCEL" | "UNKNOWN",
              "menu": "카탈로그의 정확한 메뉴명" | null,
              "quantity": 숫자 | null,
              "option": "카탈로그의 옵션명들을 쉼표로 연결" | null,
              "orderDraft": 현재 주문 draft JSON 또는 null
            }

            판단 기준:
            - ORDER: 메뉴 선택, 수량 언급, 옵션 선택, 주문 의사
            - CONFIRM: 현재 서버 질문에 대한 긍정/확정
            - CANCEL: 취소, 거절, 다시 선택, 다른 것으로 변경
            - UNKNOWN: 위에 해당하지 않음

            수량은 숫자+개/잔/인분/명 또는 하나/두/세 같은 표현에서만 추출한다.
            메뉴와 옵션은 카탈로그에 있는 정확한 이름으로 반환한다.
            orderDraft가 제공되면 새 메뉴를 임의로 만들지 말고, draft 안의 items에서 quantity와 selectedOptionItemId/selectedOptionItemName만 채운다.
            사용자가 명확히 변경하지 않은 기존 draft 값은 유지한다.
            The current order slot JSON contains every recognized menu and its optionSlots.
            Use only candidates in that JSON. Do not create new menus, option groups, or candidates.
            If the utterance selects a required option, reflect that selection in orderDraft.
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

    private List<MenuCacheResponse.MenuInfo> menusForSession(
            Optional<MenuCacheResponse> catalog,
            OrderSession session
    ) {
        List<MenuCacheResponse.MenuInfo> menus = catalog.stream()
                .flatMap(response -> response.menus().stream())
                .toList();
        if (session != null && session.getOrderDraft() != null && session.getOrderDraft().items() != null) {
            Set<Long> draftMenuIds = session.getOrderDraft().items().stream()
                    .map(OrderDraft.Item::menuId)
                    .filter(id -> id != null)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> draftMenuNames = session.getOrderDraft().items().stream()
                    .map(OrderDraft.Item::menuName)
                    .map(this::normalize)
                    .filter(name -> !name.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<MenuCacheResponse.MenuInfo> draftMenus = menus.stream()
                    .filter(menu -> draftMenuIds.contains(menu.menuId())
                            || draftMenuNames.contains(normalize(menu.name())))
                    .toList();
            if (!draftMenus.isEmpty()) {
                return draftMenus;
            }
        }
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
            JsonNode draftNode = parsed.path("orderDraft");
            if (session != null && !draftNode.isMissingNode() && !draftNode.isNull()) {
                session.setOrderDraft(MAPPER.treeToValue(draftNode, OrderDraft.class));
            }

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

                [현재 주문 slot JSON]
                %s

                [현재 식당 캐시 카탈로그]
                %s

                [사용자 발화]
                %s
                """.formatted(
                session == null ? null : session.getRestaurantId(),
                session == null ? null : session.getMenu(),
                session == null ? null : session.getQuantity(),
                session == null ? null : session.getPreviousUtterance(),
                formatDraft(session, catalog),
                catalog.map(value -> formatCatalog(value, session)).orElse("캐시된 메뉴 데이터 없음"),
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
                    candidateJson.put("aliases", emptyIfNull(item.aliases()));
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
                        || normalize(menu.name()).equals(normalize(item.menuName())))
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

    private String formatAliases(Collection<String> aliases) {
        List<String> values = emptyIfNull(aliases).stream()
                .filter(alias -> alias != null && !alias.isBlank())
                .toList();
        return values.isEmpty() ? "" : " (alias: " + String.join(", ", values) + ")";
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }

    private <T> Collection<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : values;
    }
}
