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
    private static final List<String> DEFAULT_OPTION_KW = List.of("그대로", "기본", "디폴트", "원래대로");
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
        String currentOptionText = optionAwareText(text, extracted);
        String optionText = mergeOptionText(session.getPendingOptionText(), currentOptionText);

        if (session.getStatus() == OrderStatus.DONE) {
            Long restaurantId = session.getRestaurantId();
            session.reset();
            session.setRestaurantId(restaurantId);
        }

        if ("CANCEL".equals(intent)) {
            return handleCancel(sid, session, catalog);
        }

        if (session.getStatus() == OrderStatus.OPTION_FILLING) {
            fillMenuAndQuantitySlots(text, session, catalog, extracted);
            session.setPendingOptionText(null);
            return handleOptionUtterance(sid, intent, optionText, session, catalog);
        }

        if (session.getStatus() == OrderStatus.CONFIRMING && "CONFIRM".equals(intent)) {
            return confirmMenuAndStartOptionFilling(sid, intent, session, catalog);
        }

        if (session.getStatus() == OrderStatus.CONFIRMING && hasOptionSelection(optionText, catalog, session)) {
            session.setPendingOptionText(optionText);
            return build(sid, intent, session,
                    menuConfirmationPrompt(session),
                    List.of("네", "아니요"),
                    List.of());
        }

        fillMenuAndQuantitySlots(text, session, catalog, extracted);
        optionText = mergeOptionText(session.getPendingOptionText(), currentOptionText);

        if (session.isSlotsComplete() && hasOptionSelection(optionText, catalog, session)) {
            session.setPendingOptionText(optionText);
        }

        if (hasPendingOptionText(optionText, catalog, session)) {
            session.setPendingOptionText(optionText);
        }

        String message;
        List<String> quickReplies;

        if (session.isSlotsComplete()) {
            session.setStatus(OrderStatus.CONFIRMING);
            message = menuConfirmationPrompt(session);
            quickReplies = List.of("네", "아니요");
        } else if (session.getMenu() == null) {
            message = "어떤 메뉴를 드릴까요?";
            quickReplies = menuQuickReplies(catalog);
        } else {
            message = String.format("%s 맞으세요? 몇 개 드릴까요?", session.getMenu());
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
        List<OptionSlot> activeSlots = List.of();
        boolean requiredPhase = false;
        if (selectedMenu.isPresent()) {
            MenuCacheResponse.MenuInfo menu = selectedMenu.get();
            Set<Long> selected = defaultOptionIds(menu);
            session.setSelectedOptionItemIds(new LinkedHashSet<>(selected));
            List<OptionSlot> requiredSlots = requiredOptionSlots(menu, selected);
            requiredPhase = !requiredSlots.isEmpty();
            activeSlots = requiredPhase ? requiredSlots : optionalOptionSlots(menu, selected);
        }

        if (activeSlots.isEmpty()) {
            session.setStatus(OrderStatus.DONE);
            return build(sid, intent, session,
                    String.format("주문 완료되었습니다. %s %d개 나올게요!", session.getMenu(), session.getQuantity()),
                    List.of(),
                    List.of());
        }

        session.setStatus(OrderStatus.OPTION_FILLING);
        String pendingOptionText = session.getPendingOptionText();
        if (pendingOptionText != null && !pendingOptionText.isBlank()
                && hasOptionSelection(pendingOptionText, catalog, session)) {
            session.setPendingOptionText(null);
            return handleOptionUtterance(sid, "ORDER", pendingOptionText, session, catalog);
        }

        return build(sid, intent, session,
                requiredPhase
                        ? String.format("%s %d개로 확인했습니다. %s",
                        session.getMenu(), session.getQuantity(), requiredOptionPrompt(activeSlots))
                        : String.format("%s %d개로 확인했습니다. 추가 옵션을 선택하거나 확인해 주세요.",
                        session.getMenu(), session.getQuantity()),
                quickRepliesForOptions(activeSlots, requiredPhase),
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
        Set<Long> selectedBefore = selectedOptionIds(session);
        List<MenuCacheResponse.OptionGroupInfo> requiredGroupsBefore = requiredOptionGroups(menu, selectedBefore);
        boolean requiredOptionsCompleteBefore = requiredGroupsBefore.isEmpty();

        if (requiredOptionsCompleteBefore && "CONFIRM".equals(intent)) {
            session.setStatus(OrderStatus.DONE);
            return build(sid, intent, session,
                    String.format("주문 완료되었습니다. %s %d개 나올게요!", session.getMenu(), session.getQuantity()),
                    List.of(),
                    List.of());
        }

        List<MenuCacheResponse.OptionGroupInfo> selectableGroups = requiredOptionsCompleteBefore
                ? optionalOptionGroups(menu, selectedBefore)
                : requiredGroupsBefore.stream().limit(1).toList();
        if (requiredOptionsCompleteBefore) {
            return handleOptionalOptionUtterance(sid, intent, text, session, menu, selectableGroups);
        }

        applyOptionSelection(text, session, menu, selectableGroups);

        Set<Long> selected = selectedOptionIds(session);
        List<OptionSlot> requiredSlots = requiredOptionSlots(menu, selected);
        if (!requiredSlots.isEmpty()) {
            return build(sid, intent, session,
                    requiredOptionPrompt(requiredSlots),
                    quickRepliesForOptions(requiredSlots, true),
                    requiredSlots);
        }

        List<MenuCacheResponse.OptionGroupInfo> optionalGroups = optionalOptionGroups(menu, selected);
        if (optionalGroups.isEmpty()) {
            session.setStatus(OrderStatus.DONE);
            return build(sid, intent, session,
                    String.format("주문 완료되었습니다. %s %d개 나올게요!", session.getMenu(), session.getQuantity()),
                    List.of(),
                    List.of());
        }

        return build(sid, intent, session,
                optionalOptionListPrompt(optionalGroups),
                quickRepliesForOptionalGroups(optionalGroups),
                List.of());
    }

    private OrderResponse handleOptionalOptionUtterance(
            String sid,
            String intent,
            String text,
            OrderSession session,
            MenuCacheResponse.MenuInfo menu,
            List<MenuCacheResponse.OptionGroupInfo> optionalGroups
    ) {
        if (optionalGroups.isEmpty()) {
            session.setStatus(OrderStatus.DONE);
            return build(sid, intent, session,
                    String.format("주문 완료되었습니다. %s %d개 나올게요!", session.getMenu(), session.getQuantity()),
                    List.of(),
                    List.of());
        }

        Optional<MenuCacheResponse.OptionGroupInfo> selectedGroup = findPendingOptionalGroup(session, optionalGroups)
                .or(() -> findMentionedOptionGroup(text, optionalGroups));
        if (selectedGroup.isPresent()) {
            MenuCacheResponse.OptionGroupInfo group = selectedGroup.get();
            if (session.getPendingOptionalGroupId() != null) {
                applyOptionSelection(text, session, menu, List.of(group));
                session.setPendingOptionalGroupId(null);

                Set<Long> selected = selectedOptionIds(session);
                List<OptionSlot> requiredSlots = requiredOptionSlots(menu, selected);
                if (!requiredSlots.isEmpty()) {
                    return build(sid, intent, session,
                            requiredOptionPrompt(requiredSlots),
                            quickRepliesForOptions(requiredSlots, true),
                            requiredSlots);
                }

                List<MenuCacheResponse.OptionGroupInfo> nextOptionalGroups = optionalOptionGroups(menu, selected);
                return build(sid, intent, session,
                        optionalOptionListPrompt(nextOptionalGroups),
                        quickRepliesForOptionalGroups(nextOptionalGroups),
                        List.of());
            }

            session.setPendingOptionalGroupId(group.optionGroupId());
            OptionSlot optionSlot = toOptionSlot(group, selectedOptionIds(session));
            return build(sid, intent, session,
                    group.name() + " 옵션에서 변경할 값을 선택해 주세요.",
                    quickRepliesForOptions(List.of(optionSlot), false),
                    List.of(optionSlot));
        }

        return build(sid, intent, session,
                optionalOptionListPrompt(optionalGroups),
                quickRepliesForOptionalGroups(optionalGroups),
                List.of());
    }

    private void applyOptionSelection(
            String text,
            OrderSession session,
            MenuCacheResponse.MenuInfo menu,
            Collection<MenuCacheResponse.OptionGroupInfo> targetGroups
    ) {
        Set<Long> selected = selectedOptionIds(session);
        boolean removeMode = containsAny(text, OPTION_REMOVE_KW);
        String normalizedText = normalize(text);

        for (MenuCacheResponse.OptionGroupInfo group : targetGroups) {
            for (MenuCacheResponse.OptionItemInfo item : emptyIfNull(group.optionItems())) {
                if (Boolean.FALSE.equals(item.isAvailable())) {
                    continue;
                }
                if (!optionItemMatchesText(normalizedText, group, item)) {
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

    private boolean hasOptionSelection(String text, Optional<MenuCacheResponse> catalog, OrderSession session) {
        String normalizedText = normalize(text);
        return findSelectedMenu(catalog, session)
                .map(menu -> activeOptionGroups(menu, selectedOptionIds(session)).stream()
                        .anyMatch(group -> emptyIfNull(group.optionItems()).stream()
                                .anyMatch(item -> optionItemMatchesText(normalizedText, group, item))))
                .orElse(false);
    }

    private boolean hasAnyOptionSelection(String text, Optional<MenuCacheResponse> catalog) {
        String normalizedText = normalize(text);
        return catalog.stream()
                .flatMap(response -> response.menus().stream())
                .flatMap(menu -> emptyIfNull(menu.optionGroups()).stream())
                .anyMatch(group -> emptyIfNull(group.optionItems()).stream()
                        .anyMatch(item -> optionItemMatchesText(normalizedText, group, item)));
    }

    private boolean hasPendingOptionText(String optionText, Optional<MenuCacheResponse> catalog, OrderSession session) {
        if (optionText == null || optionText.isBlank() || session.isSlotsComplete()) {
            return false;
        }
        return session.getMenu() == null
                ? hasAnyOptionSelection(optionText, catalog)
                : hasOptionSelection(optionText, catalog, session);
    }

    private Optional<MenuCacheResponse.OptionGroupInfo> findPendingOptionalGroup(
            OrderSession session,
            List<MenuCacheResponse.OptionGroupInfo> optionalGroups
    ) {
        if (session.getPendingOptionalGroupId() == null) {
            return Optional.empty();
        }
        return optionalGroups.stream()
                .filter(group -> session.getPendingOptionalGroupId().equals(group.optionGroupId()))
                .findFirst();
    }

    private Optional<MenuCacheResponse.OptionGroupInfo> findMentionedOptionGroup(
            String text,
            List<MenuCacheResponse.OptionGroupInfo> optionGroups
    ) {
        String normalizedText = normalize(text);
        return optionGroups.stream()
                .filter(group -> matchesNameOrAlias(normalizedText, group.name(), group.aliases()))
                .findFirst();
    }

    private boolean optionItemMatchesText(
            String normalizedText,
            MenuCacheResponse.OptionGroupInfo group,
            MenuCacheResponse.OptionItemInfo item
    ) {
        String normalizedItemName = normalize(item.name());
        if (matchesNameOrAlias(normalizedText, item.name(), item.aliases())) {
            return true;
        }
        if (matchesTemperatureAlias(normalizedText, normalizedItemName)) {
            return true;
        }
        if (matchesCaffeineAlias(normalizedText, normalizedItemName)) {
            return true;
        }
        return isDefaultOption(item)
                && optionGroupMatchesText(normalizedText, group)
                && containsAnyNormalized(normalizedText, DEFAULT_OPTION_KW);
    }

    private boolean matchesTemperatureAlias(String normalizedText, String normalizedItemName) {
        boolean hotItem = normalizedItemName.equals("hot")
                || normalizedItemName.contains("핫")
                || normalizedItemName.contains("뜨거");
        boolean hotText = normalizedText.contains("hot")
                || normalizedText.contains("핫")
                || normalizedText.contains("뜨거");
        if (hotItem && hotText) {
            return true;
        }

        boolean icedItem = normalizedItemName.equals("ice")
                || normalizedItemName.equals("iced")
                || normalizedItemName.contains("아이스")
                || normalizedItemName.contains("차가");
        boolean icedText = normalizedText.contains("ice")
                || normalizedText.contains("iced")
                || normalizedText.contains("아이스")
                || normalizedText.contains("차가");
        return icedItem && icedText;
    }

    private boolean matchesCaffeineAlias(String normalizedText, String normalizedItemName) {
        boolean decafItem = normalizedItemName.contains("디카페인")
                || normalizedItemName.contains("decaf")
                || normalizedItemName.contains("디카");
        boolean decafText = normalizedText.contains("디카페인")
                || normalizedText.contains("decaf")
                || normalizedText.contains("디카");
        if (decafItem && decafText) {
            return true;
        }

        boolean caffeineItem = normalizedItemName.contains("카페인")
                || normalizedItemName.contains("regular")
                || normalizedItemName.contains("일반");
        boolean caffeineText = normalizedText.contains("카페인")
                || normalizedText.contains("regular")
                || normalizedText.contains("일반");
        return caffeineItem && caffeineText && !decafText;
    }

    private boolean optionGroupMatchesText(String normalizedText, MenuCacheResponse.OptionGroupInfo group) {
        if (matchesNameOrAlias(normalizedText, group.name(), group.aliases())) {
            return true;
        }
        String normalizedGroupName = normalize(group.name());
        return List.of("원두", "온도", "사이즈", "얼음", "당도", "샷", "우유").stream()
                .anyMatch(keyword -> normalizedGroupName.contains(keyword) && normalizedText.contains(keyword));
    }

    private boolean matchesNameOrAlias(String normalizedText, String name, Collection<String> aliases) {
        String normalizedName = normalize(name);
        if (!normalizedName.isBlank() && normalizedText.contains(normalizedName)) {
            return true;
        }
        return emptyIfNull(aliases).stream()
                .map(this::normalize)
                .filter(alias -> !alias.isBlank())
                .anyMatch(normalizedText::contains);
    }

    private boolean isDefaultOption(MenuCacheResponse.OptionItemInfo item) {
        return Boolean.TRUE.equals(item.isDefault())
                || (item.defaultQuantity() != null && item.defaultQuantity() > 0);
    }

    private boolean containsAnyNormalized(String normalizedText, List<String> keywords) {
        return keywords.stream()
                .map(this::normalize)
                .anyMatch(normalizedText::contains);
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

    private List<OptionSlot> requiredOptionSlots(MenuCacheResponse.MenuInfo menu, Set<Long> selectedOptionIds) {
        return requiredOptionGroups(menu, selectedOptionIds).stream()
                .limit(1)
                .map(group -> toOptionSlot(group, selectedOptionIds))
                .toList();
    }

    private List<OptionSlot> optionalOptionSlots(MenuCacheResponse.MenuInfo menu, Set<Long> selectedOptionIds) {
        return optionalOptionGroups(menu, selectedOptionIds).stream()
                .map(group -> toOptionSlot(group, selectedOptionIds))
                .toList();
    }

    private List<MenuCacheResponse.OptionGroupInfo> requiredOptionGroups(
            MenuCacheResponse.MenuInfo menu,
            Set<Long> selectedOptionIds
    ) {
        return activeOptionGroups(menu, selectedOptionIds).stream()
                .filter(this::isRequiredGroup)
                .filter(group -> !isRequiredGroupSatisfied(group, selectedOptionIds))
                .toList();
    }

    private List<MenuCacheResponse.OptionGroupInfo> optionalOptionGroups(
            MenuCacheResponse.MenuInfo menu,
            Set<Long> selectedOptionIds
    ) {
        return activeOptionGroups(menu, selectedOptionIds).stream()
                .filter(group -> !isRequiredGroup(group))
                .toList();
    }

    private Set<Long> defaultOptionIds(MenuCacheResponse.MenuInfo menu) {
        Set<Long> selected = new LinkedHashSet<>();
        boolean changed;
        do {
            changed = false;
            for (MenuCacheResponse.OptionGroupInfo group : activeOptionGroups(menu, selected)) {
                if (isRequiredGroup(group)) {
                    continue;
                }
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

    private String optionAwareText(String text, SlotExtractionResult extracted) {
        if (extracted.option() == null || extracted.option().isBlank()) {
            return text;
        }
        return text + " " + extracted.option();
    }

    private String mergeOptionText(String previousText, String currentText) {
        if (previousText == null || previousText.isBlank()) {
            return currentText == null ? "" : currentText;
        }
        if (currentText == null || currentText.isBlank()) {
            return previousText;
        }
        if (normalize(previousText).contains(normalize(currentText))) {
            return previousText;
        }
        if (normalize(currentText).contains(normalize(previousText))) {
            return currentText;
        }
        return previousText + " " + currentText;
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

    private String menuConfirmationPrompt(OrderSession session) {
        if (session.getQuantity() == null) {
            return String.format("%s 맞으세요?", session.getMenu());
        }
        return String.format("%s %d개 맞으시죠? 확인해 주세요.", session.getMenu(), session.getQuantity());
    }

    private String requiredOptionPrompt(List<OptionSlot> slots) {
        if (slots.isEmpty()) {
            return "필수 옵션을 선택해 주세요.";
        }
        String optionName = slots.get(0).name();
        if (optionName == null || optionName.isBlank()) {
            return "필수 옵션을 선택해 주세요.";
        }
        return optionName + " 옵션을 선택해 주세요.";
    }

    private String optionalOptionListPrompt(List<MenuCacheResponse.OptionGroupInfo> optionalGroups) {
        if (optionalGroups.isEmpty()) {
            return "추가로 변경할 수 있는 선택 옵션이 없습니다. 확인해 주세요.";
        }
        String optionNames = optionalGroups.stream()
                .map(MenuCacheResponse.OptionGroupInfo::name)
                .collect(Collectors.joining(", "));
        return "변경 가능한 선택 옵션은 " + optionNames + "입니다. 어떤 옵션을 변경하시겠어요? 없으면 확인이라고 말씀해 주세요.";
    }

    private List<String> quickRepliesForOptionalGroups(List<MenuCacheResponse.OptionGroupInfo> optionalGroups) {
        return java.util.stream.Stream.concat(
                        optionalGroups.stream()
                                .map(MenuCacheResponse.OptionGroupInfo::name)
                                .limit(7),
                        java.util.stream.Stream.of("확인")
                )
                .toList();
    }

    private List<String> quickRepliesForOptions(List<OptionSlot> slots, boolean requiredPhase) {
        List<String> replies = slots.stream()
                .flatMap(slot -> emptyIfNull(slot.candidates()).stream())
                .map(OptionSlot.OptionCandidate::name)
                .limit(8)
                .toList();
        if (requiredPhase) {
            return replies;
        }
        return java.util.stream.Stream.concat(replies.stream().limit(7), java.util.stream.Stream.of("확인"))
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

    private boolean isRequiredGroup(MenuCacheResponse.OptionGroupInfo group) {
        return Boolean.TRUE.equals(group.isRequired());
    }

    private boolean isRequiredGroupSatisfied(
            MenuCacheResponse.OptionGroupInfo group,
            Set<Long> selectedOptionIds
    ) {
        long selectedCount = emptyIfNull(group.optionItems()).stream()
                .map(MenuCacheResponse.OptionItemInfo::optionItemId)
                .filter(selectedOptionIds::contains)
                .count();
        return selectedCount >= requiredMinSelect(group);
    }

    private int requiredMinSelect(MenuCacheResponse.OptionGroupInfo group) {
        if (!Boolean.TRUE.equals(group.isRequired())) {
            return 0;
        }
        if (group.minSelect() != null && group.minSelect() > 0) {
            return group.minSelect();
        }
        return 1;
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
