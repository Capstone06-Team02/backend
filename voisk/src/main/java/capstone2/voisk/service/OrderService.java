package capstone2.voisk.service;

import capstone2.voisk.dto.MenuCacheResponse;
import capstone2.voisk.dto.OptionSlot;
import capstone2.voisk.dto.OrderRequest;
import capstone2.voisk.dto.OrderResponse;
import capstone2.voisk.dto.SlotExtractionResult;
import capstone2.voisk.entity.OrderSession;
import capstone2.voisk.entity.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final List<String> CONFIRM_KW = List.of("네", "예", "맞아", "맞아요", "확인", "응", "좋아");
    private static final List<String> CANCEL_KW = List.of("아니", "취소", "다시", "말고");
    private static final List<String> OPTION_REMOVE_KW = List.of("빼", "없이", "제외", "삭제", "안 넣", "넣지 마");
    private static final Pattern QTY_DIGIT = Pattern.compile("(\\d+)\\s*(?:개|잔|인분|명)");
    private static final Map<String, Integer> KO_QTY = Map.ofEntries(
            Map.entry("하나", 1),
            Map.entry("한", 1),
            Map.entry("둘", 2),
            Map.entry("두", 2),
            Map.entry("셋", 3),
            Map.entry("세", 3),
            Map.entry("넷", 4),
            Map.entry("다섯", 5)
    );

    private final StoreMenuCacheService storeMenuCacheService;
    private final LlmSlotFillerService llmSlotFillerService;
    private final Map<String, OrderSession> sessions = new ConcurrentHashMap<>();

    public OrderResponse process(OrderRequest request) {
        String sid = resolveId(request.getSessionId());
        OrderSession session = sessions.computeIfAbsent(sid, ignored -> newSession());

        if (request.getRestaurantId() != null) {
            session.setRestaurantId(request.getRestaurantId());
        }

        String text = request.getInput() == null ? "" : request.getInput().trim();
        Optional<MenuCacheResponse> catalog = resolveCatalog(session);
        SlotExtractionResult extracted = extractSlots(text, session, catalog);
        String intent = "UNKNOWN".equals(extracted.intent()) ? classifyIntent(text, catalog) : extracted.intent();

        if (session.getStatus() == OrderStatus.DONE) {
            Long restaurantId = session.getRestaurantId();
            session.reset();
            session.setRestaurantId(restaurantId);
        }

        if ("CANCEL".equals(intent)) {
            return handleCancel(sid, session, catalog);
        }

        if (session.getStatus() == OrderStatus.OPTION_FILLING) {
            return handleOptionUtterance(sid, intent, text, session, catalog);
        }

        if (session.getStatus() == OrderStatus.CONFIRMING && "CONFIRM".equals(intent)) {
            return confirmMenuAndStartOptionFilling(sid, intent, session, catalog);
        }

        fillMenuAndQuantitySlots(text, session, catalog, extracted);

        String message;
        List<String> quickReplies;

        if (session.isSlotsComplete()) {
            session.setStatus(OrderStatus.CONFIRMING);
            message = String.format("%s %d개 맞으시죠? 확인해 주세요.", session.getMenu(), session.getQuantity());
            quickReplies = List.of("네", "아니요");
        } else if (session.getMenu() == null) {
            message = "어떤 메뉴를 드릴까요?";
            quickReplies = menuQuickReplies(catalog);
        } else {
            message = "몇 개 드릴까요?";
            quickReplies = List.of("1개", "2개", "3개");
        }

        return build(sid, intent, session, message, quickReplies, List.of());
    }

    private OrderSession newSession() {
        OrderSession session = new OrderSession();
        session.setStatus(OrderStatus.ORDERING);
        session.setSelectedOptionItemIds(new LinkedHashSet<>());
        return session;
    }

    private OrderResponse handleCancel(String sid, OrderSession session, Optional<MenuCacheResponse> catalog) {
        Long restaurantId = session.getRestaurantId();
        session.reset();
        session.setRestaurantId(restaurantId);
        return build(sid, "CANCEL", session,
                "취소했습니다. 처음부터 다시 말씀해 주세요.",
                menuQuickReplies(catalog),
                List.of());
    }

    private OrderResponse confirmMenuAndStartOptionFilling(
            String sid,
            String intent,
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        Optional<MenuCacheResponse.MenuInfo> selectedMenu = findSelectedMenu(catalog, session);
        List<OptionSlot> activeSlots = selectedMenu
                .map(menu -> {
                    Set<Long> selected = defaultOptionIds(menu);
                    session.setSelectedOptionItemIds(new LinkedHashSet<>(selected));
                    return activeOptionSlots(menu, selected);
                })
                .orElse(List.of());

        if (activeSlots.isEmpty()) {
            session.setStatus(OrderStatus.DONE);
            return build(sid, intent, session,
                    String.format("주문 완료되었습니다. %s %d개 나올게요!", session.getMenu(), session.getQuantity()),
                    List.of(),
                    List.of());
        }

        session.setStatus(OrderStatus.OPTION_FILLING);
        return build(sid, intent, session,
                String.format("%s %d개로 확인했습니다. 옵션을 선택해 주세요.", session.getMenu(), session.getQuantity()),
                quickRepliesForOptions(activeSlots),
                activeSlots);
    }

    private OrderResponse handleOptionUtterance(
            String sid,
            String intent,
            String text,
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        Optional<MenuCacheResponse.MenuInfo> selectedMenu = findSelectedMenu(catalog, session);
        if (selectedMenu.isEmpty()) {
            session.setStatus(OrderStatus.DONE);
            return build(sid, intent, session,
                    "메뉴 캐시를 찾지 못해 옵션 선택 없이 주문을 완료합니다.",
                    List.of(),
                    List.of());
        }

        MenuCacheResponse.MenuInfo menu = selectedMenu.get();
        applyOptionSelection(text, session, menu);
        List<OptionSlot> activeSlots = activeOptionSlots(menu, selectedOptionIds(session));

        return build(sid, intent, session,
                "옵션 선택을 반영했습니다. 필요한 옵션을 계속 말씀해 주세요.",
                quickRepliesForOptions(activeSlots),
                activeSlots);
    }

    private void applyOptionSelection(String text, OrderSession session, MenuCacheResponse.MenuInfo menu) {
        Set<Long> selected = selectedOptionIds(session);
        List<MenuCacheResponse.OptionGroupInfo> activeGroups = activeOptionGroups(menu, selected);
        boolean removeMode = containsAny(text, OPTION_REMOVE_KW);
        String normalizedText = normalize(text);

        for (MenuCacheResponse.OptionGroupInfo group : activeGroups) {
            for (MenuCacheResponse.OptionItemInfo item : emptyIfNull(group.optionItems())) {
                if (Boolean.FALSE.equals(item.isAvailable())) {
                    continue;
                }
                if (!normalizedText.contains(normalize(item.name()))) {
                    continue;
                }

                if (removeMode) {
                    selected.remove(item.optionItemId());
                } else {
                    if (maxSelect(group) == 1) {
                        removeGroupSelections(selected, group);
                    }
                    selected.add(item.optionItemId());
                }
            }
        }

        pruneInactiveSelections(selected, menu);
        session.setSelectedOptionItemIds(new LinkedHashSet<>(selected));
    }

    private void pruneInactiveSelections(Set<Long> selected, MenuCacheResponse.MenuInfo menu) {
        boolean changed;
        do {
            Set<Long> activeGroupIds = activeOptionGroups(menu, selected).stream()
                    .map(MenuCacheResponse.OptionGroupInfo::optionGroupId)
                    .collect(Collectors.toSet());
            Set<Long> removable = emptyIfNull(menu.optionGroups()).stream()
                    .filter(group -> !activeGroupIds.contains(group.optionGroupId()))
                    .flatMap(group -> emptyIfNull(group.optionItems()).stream())
                    .map(MenuCacheResponse.OptionItemInfo::optionItemId)
                    .collect(Collectors.toSet());
            changed = selected.removeAll(removable);
        } while (changed);
    }

    private List<OptionSlot> activeOptionSlots(MenuCacheResponse.MenuInfo menu, Set<Long> selectedOptionIds) {
        return activeOptionGroups(menu, selectedOptionIds).stream()
                .map(group -> toOptionSlot(group, selectedOptionIds))
                .toList();
    }

    private Set<Long> defaultOptionIds(MenuCacheResponse.MenuInfo menu) {
        Set<Long> selected = new LinkedHashSet<>();
        boolean changed;
        do {
            changed = false;
            for (MenuCacheResponse.OptionGroupInfo group : activeOptionGroups(menu, selected)) {
                if (hasSelectionInGroup(selected, group)) {
                    continue;
                }
                for (MenuCacheResponse.OptionItemInfo item : emptyIfNull(group.optionItems())) {
                    if (Boolean.FALSE.equals(item.isAvailable())) {
                        continue;
                    }
                    boolean defaultSelected = Boolean.TRUE.equals(item.isDefault())
                            || (item.defaultQuantity() != null && item.defaultQuantity() > 0);
                    if (!defaultSelected) {
                        continue;
                    }
                    if (maxSelect(group) == 1) {
                        removeGroupSelections(selected, group);
                    }
                    changed = selected.add(item.optionItemId()) || changed;
                    if (maxSelect(group) == 1) {
                        break;
                    }
                }
            }
        } while (changed);
        return selected;
    }

    private List<MenuCacheResponse.OptionGroupInfo> activeOptionGroups(
            MenuCacheResponse.MenuInfo menu,
            Set<Long> selectedOptionIds
    ) {
        List<MenuCacheResponse.OptionGroupInfo> groups = emptyIfNull(menu.optionGroups()).stream()
                .filter(group -> !Boolean.FALSE.equals(group.isAvailable()))
                .sorted(Comparator.comparing(MenuCacheResponse.OptionGroupInfo::optionGroupId,
                        Comparator.nullsLast(Long::compareTo)))
                .toList();

        Map<Long, List<MenuCacheResponse.OptionGroupInfo>> childGroupsByParentItem = groups.stream()
                .filter(group -> group.parentOptionItemId() != null)
                .collect(Collectors.groupingBy(MenuCacheResponse.OptionGroupInfo::parentOptionItemId));

        Set<Long> activeGroupIds = new LinkedHashSet<>();
        Queue<MenuCacheResponse.OptionGroupInfo> queue = new ArrayDeque<>();
        groups.stream()
                .filter(group -> group.parentOptionItemId() == null)
                .forEach(queue::add);

        while (!queue.isEmpty()) {
            MenuCacheResponse.OptionGroupInfo group = queue.poll();
            if (!activeGroupIds.add(group.optionGroupId())) {
                continue;
            }
            emptyIfNull(group.optionItems()).stream()
                    .map(MenuCacheResponse.OptionItemInfo::optionItemId)
                    .filter(selectedOptionIds::contains)
                    .flatMap(optionItemId -> emptyIfNull(childGroupsByParentItem.get(optionItemId)).stream())
                    .forEach(queue::add);
        }

        return groups.stream()
                .filter(group -> activeGroupIds.contains(group.optionGroupId()))
                .toList();
    }

    private OptionSlot toOptionSlot(MenuCacheResponse.OptionGroupInfo group, Set<Long> selectedOptionIds) {
        return new OptionSlot(
                group.optionGroupId(),
                group.parentOptionItemId(),
                group.name(),
                group.isRequired(),
                group.minSelect(),
                group.maxSelect(),
                emptyIfNull(group.optionItems()).stream()
                        .filter(item -> !Boolean.FALSE.equals(item.isAvailable()))
                        .map(item -> new OptionSlot.OptionCandidate(
                                item.optionItemId(),
                                item.name(),
                                item.extraPrice(),
                                item.isAvailable(),
                                item.defaultQuantity(),
                                item.maxQuantity(),
                                item.isDefault(),
                                selectedOptionIds.contains(item.optionItemId())
                        ))
                        .toList()
        );
    }

    private SlotExtractionResult extractSlots(
            String text,
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        if (llmSlotFillerService == null) {
            return SlotExtractionResult.fallback();
        }
        return llmSlotFillerService.extract(text, session);
    }

    private void fillMenuAndQuantitySlots(
            String text,
            OrderSession session,
            Optional<MenuCacheResponse> catalog,
            SlotExtractionResult extracted
    ) {
        if (session.getMenu() == null) {
            findMenuByName(extracted.menu(), catalog)
                    .or(() -> findMenuInText(text, catalog))
                    .ifPresent(menu -> {
                session.setMenu(menu.name());
                session.setMenuId(menu.menuId());
            });
        }

        if (session.getQuantity() == null) {
            Integer quantity = extracted.quantity() != null ? extracted.quantity() : extractQty(text);
            if (quantity != null && quantity > 0) {
                session.setQuantity(quantity);
            }
        }
    }

    private Optional<MenuCacheResponse> resolveCatalog(OrderSession session) {
        if (storeMenuCacheService == null) {
            return Optional.empty();
        }
        if (session.getRestaurantId() != null) {
            return storeMenuCacheService.getCachedMenus(session.getRestaurantId());
        }
        return storeMenuCacheService.getLatestCachedMenus()
                .map(catalog -> {
                    session.setRestaurantId(catalog.restaurantId());
                    return catalog;
                });
    }

    private Optional<MenuCacheResponse.MenuInfo> findMenuInText(String text, Optional<MenuCacheResponse> catalog) {
        String normalizedText = normalize(text);
        return catalog.stream()
                .flatMap(response -> response.menus().stream())
                .filter(menu -> normalizedText.contains(normalize(menu.name())))
                .findFirst();
    }

    private Optional<MenuCacheResponse.MenuInfo> findMenuByName(String menuName, Optional<MenuCacheResponse> catalog) {
        if (menuName == null || menuName.isBlank()) {
            return Optional.empty();
        }
        String normalizedMenuName = normalize(menuName);
        return catalog.stream()
                .flatMap(response -> response.menus().stream())
                .filter(menu -> normalize(menu.name()).equals(normalizedMenuName))
                .findFirst();
    }

    private Optional<MenuCacheResponse.MenuInfo> findSelectedMenu(
            Optional<MenuCacheResponse> catalog,
            OrderSession session
    ) {
        return catalog.stream()
                .flatMap(response -> response.menus().stream())
                .filter(menu -> session.getMenuId() != null
                        ? session.getMenuId().equals(menu.menuId())
                        : normalize(menu.name()).equals(normalize(session.getMenu())))
                .findFirst();
    }

    private String classifyIntent(String text, Optional<MenuCacheResponse> catalog) {
        if (containsAny(text, CANCEL_KW)) {
            return "CANCEL";
        }
        if (containsAny(text, CONFIRM_KW) && extractQty(text) == null) {
            return "CONFIRM";
        }
        if (findMenuInText(text, catalog).isPresent() || extractQty(text) != null) {
            return "ORDER";
        }
        return "UNKNOWN";
    }

    private Integer extractQty(String text) {
        Matcher matcher = QTY_DIGIT.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        for (Map.Entry<String, Integer> entry : KO_QTY.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private List<String> menuQuickReplies(Optional<MenuCacheResponse> catalog) {
        List<String> cachedMenus = catalog.stream()
                .flatMap(response -> response.menus().stream())
                .map(MenuCacheResponse.MenuInfo::name)
                .limit(6)
                .toList();
        return cachedMenus;
    }

    private List<String> quickRepliesForOptions(List<OptionSlot> slots) {
        return slots.stream()
                .flatMap(slot -> emptyIfNull(slot.candidates()).stream())
                .map(OptionSlot.OptionCandidate::name)
                .limit(8)
                .toList();
    }

    private Set<Long> selectedOptionIds(OrderSession session) {
        if (session.getSelectedOptionItemIds() == null) {
            session.setSelectedOptionItemIds(new LinkedHashSet<>());
        }
        return new LinkedHashSet<>(session.getSelectedOptionItemIds());
    }

    private void removeGroupSelections(Set<Long> selected, MenuCacheResponse.OptionGroupInfo group) {
        Set<Long> groupOptionIds = emptyIfNull(group.optionItems()).stream()
                .map(MenuCacheResponse.OptionItemInfo::optionItemId)
                .collect(Collectors.toSet());
        selected.removeAll(groupOptionIds);
    }

    private boolean hasSelectionInGroup(Set<Long> selected, MenuCacheResponse.OptionGroupInfo group) {
        return emptyIfNull(group.optionItems()).stream()
                .map(MenuCacheResponse.OptionItemInfo::optionItemId)
                .anyMatch(selected::contains);
    }

    private int maxSelect(MenuCacheResponse.OptionGroupInfo group) {
        return group.maxSelect() == null ? Integer.MAX_VALUE : group.maxSelect();
    }

    private String resolveId(String sessionId) {
        return (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString()
                : sessionId;
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

    private OrderResponse build(
            String sid,
            String intent,
            OrderSession session,
            String message,
            List<String> quickReplies,
            List<OptionSlot> optionSlots
    ) {
        boolean slotsComplete = session.isSlotsComplete() && session.getStatus() != OrderStatus.OPTION_FILLING;
        return OrderResponse.builder()
                .sessionId(sid)
                .intent(intent)
                .response(message)
                .slots(OrderResponse.SlotInfo.builder()
                        .menu(session.getMenu())
                        .quantity(session.getQuantity())
                        .optionSlots(optionSlots)
                        .build())
                .slotsComplete(slotsComplete)
                .quickReplies(quickReplies)
                .build();
    }
}
