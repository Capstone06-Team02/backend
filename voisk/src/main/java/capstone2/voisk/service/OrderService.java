package capstone2.voisk.service;

import capstone2.voisk.converter.MenuOptionalOptionsResponseConverter;
import capstone2.voisk.converter.OptionSlotConverter;
import capstone2.voisk.converter.OrderResponseConverter;
import capstone2.voisk.dto.MenuCacheResponse;
import capstone2.voisk.dto.MenuDescriptionResponse;
import capstone2.voisk.dto.MenuOptionalOptionsResponse;
import capstone2.voisk.dto.OptionGroupDescriptionResponse;
import capstone2.voisk.dto.OptionSlot;
import capstone2.voisk.dto.OrderDraft;
import capstone2.voisk.dto.OrderRequest;
import capstone2.voisk.dto.OrderResponse;
import capstone2.voisk.dto.SlotExtractionResult;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.entity.MenuOptionGroup;
import capstone2.voisk.entity.MenuOptionItem;
import capstone2.voisk.entity.OrderMenu;
import capstone2.voisk.entity.OrderMenuOption;
import capstone2.voisk.entity.OrderSession;
import capstone2.voisk.entity.OrderStatus;
import capstone2.voisk.entity.Store;
import capstone2.voisk.repository.MenuOptionGroupRepository;
import capstone2.voisk.repository.MenuOptionItemRepository;
import capstone2.voisk.repository.MenuRepository;
import capstone2.voisk.repository.OrderMenuOptionRepository;
import capstone2.voisk.repository.OrderMenuRepository;
import capstone2.voisk.repository.OrderSessionRepository;
import capstone2.voisk.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private static final List<String> OPTION_REMOVE_KW = List.of("빼", "없이", "제외", "삭제", "안 넣", "넣지 마");
    private static final List<String> NO_OPTION_KW = List.of("없", "없어", "없어요", "없습니다", "안 해", "안할", "안 할", "괜찮", "확인");
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
    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;
    private final MenuOptionGroupRepository menuOptionGroupRepository;
    private final MenuOptionItemRepository menuOptionItemRepository;
    private final OrderSessionRepository orderSessionRepository;
    private final OrderMenuRepository orderMenuRepository;
    private final OrderMenuOptionRepository orderMenuOptionRepository;
    private final MenuOptionalOptionsResponseConverter menuOptionalOptionsResponseConverter;
    private final OptionSlotConverter optionSlotConverter;
    private final OrderResponseConverter orderResponseConverter;
    private final Map<String, OrderSession> sessions = new ConcurrentHashMap<>();

    Optional<OrderSession> findActiveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Transactional(readOnly = true)
    public MenuOptionalOptionsResponse getOptionalOptions(Long menuId) {
        Menu menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new IllegalArgumentException("Menu not found. menuId=" + menuId));

        return menuOptionalOptionsResponseConverter.toResponse(
                menu,
                menuOptionGroupRepository.findTopLevelOptionalGroupsByMenuId(menuId)
        );
    }

    @Transactional(readOnly = true)
    public MenuDescriptionResponse getMenuDescription(Long menuId) {
        Menu menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new IllegalArgumentException("Menu not found. menuId=" + menuId));
        return new MenuDescriptionResponse(menu.getMenuId(), menu.getName(), menu.getDescription());
    }

    @Transactional(readOnly = true)
    public OptionGroupDescriptionResponse getOptionGroupDescription(Long optionGroupId) {
        MenuOptionGroup optionGroup = menuOptionGroupRepository.findById(optionGroupId)
                .orElseThrow(() -> new IllegalArgumentException("Option group not found. optionGroupId=" + optionGroupId));
        return new OptionGroupDescriptionResponse(
                optionGroup.getId(),
                optionGroup.getOptionGroupTemplate().getName(),
                optionGroup.getOptionGroupTemplate().getDescription()
        );
    }

    @Transactional
    public OrderResponse process(OrderRequest request) {
        String sid = resolveId(request.getSessionId());
        OrderSession session = sessions.computeIfAbsent(sid, ignored -> newSession());

        if (request.getRestaurantId() != null) {
            session.setRestaurantId(request.getRestaurantId());
        }

        String text = request.getInput() == null ? "" : request.getInput().trim();
        Optional<MenuCacheResponse> catalog = resolveCatalog(session);
        Optional<OrderResponse> exactOptionResponse = handleExactOptionItemOnlyInput(sid, text, session, catalog);
        if (exactOptionResponse.isPresent()) {
            return exactOptionResponse.get();
        }
        seedOrderDraftFromText(text, session, catalog);
        log.info("[speak] route=LLM sessionId={} status={} input=\"{}\"", sid, session.getStatus(), text);
        SlotExtractionResult extracted = extractSlots(text, session, catalog);
        String intent = extracted.intent();
        String currentOptionText = optionAwareText(text, extracted);
        applyOptionMentionsToDraft(currentOptionText, session, catalog);
        session.setPreviousUtterance(text);
        String optionText = mergeOptionText(session.getPendingOptionText(), currentOptionText);

        if (session.getStatus() == OrderStatus.DONE) {
            Long restaurantId = session.getRestaurantId();
            session.resetCurrentItem();
            session.setRestaurantId(restaurantId);
        }

        if (hasPendingOptionConfirmation(session)) {
            return handleOptionSelectionConfirmation(sid, intent, session, catalog);
        }

        if ("CANCEL".equals(intent)
                && !(session.getStatus() == OrderStatus.OPTION_FILLING && isNoAdditionalOptionText(text))) {
            return handleCancel(sid, session, catalog);
        }

        Optional<OrderResponse> draftResponse = handleDraftFlow(sid, intent, session, catalog);
        if (draftResponse.isPresent()) {
            return draftResponse.get();
        }

        if (session.getStatus() == OrderStatus.OPTION_FILLING) {
            fillMenuAndQuantitySlots(text, session, catalog, extracted);
            session.setPendingOptionText(null);
            return handleOptionUtterance(sid, intent, optionText, session, catalog);
        }

        if (session.getStatus() == OrderStatus.MENU_CONFIRMING) {
            if ("CONFIRM".equals(intent)) {
                session.setStatus(OrderStatus.CONFIRMING);
                return confirmMenuAndStartOptionFilling(sid, intent, session, catalog);
            }
            return build(sid, intent, session,
                    multiMenuConfirmationPrompt(session),
                    List.of("네", "아니요"),
                    List.of());
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

        if (seedPendingMenusFromText(currentOptionText, session, catalog)) {
            return build(sid, intent, session,
                    multiMenuConfirmationPrompt(session),
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
        List<MenuCacheResponse.OptionGroupInfo> optionalGroups = List.of();
        boolean requiredPhase = false;
        if (selectedMenu.isPresent()) {
            MenuCacheResponse.MenuInfo menu = selectedMenu.get();
            Set<Long> selected = defaultOptionIds(menu);
            selected = mergeSelectedOptionIds(menu, selected, selectedOptionIds(session));
            Set<Long> currentSelectedOptionIds = selected;
            session.setSelectedOptionItemIds(new LinkedHashSet<>(selected));
            List<OptionSlot> requiredSlots = requiredOptionSlots(menu, selected);
            requiredPhase = !requiredSlots.isEmpty();
            optionalGroups = optionalOptionGroups(menu, selected);
            activeSlots = requiredPhase ? requiredSlots : optionalGroups.stream()
                    .map(group -> optionSlotConverter.toOptionSlot(group, currentSelectedOptionIds))
                    .toList();
        }

        if (activeSlots.isEmpty()) {
            return selectedMenu
                    .map(menu -> completeCurrentItemAndContinue(sid, intent, session, menu))
                    .orElseGet(() -> build(sid, intent, session,
                            String.format("주문 완료되었습니다. %s %d개 나올게요!", session.getMenu(), session.getQuantity()),
                            List.of(),
                            List.of()));
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
                        ? requiredOptionPrompt(session.getMenu(), activeSlots)
                        : optionalOptionListPrompt(session.getMenu(), optionalGroups),
                requiredPhase ? quickRepliesForOptions(activeSlots, true) : quickRepliesForOptionalGroups(optionalGroups),
                requiredPhase ? activeSlots : List.of());
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
            return completeCurrentItemAndContinue(sid, intent, session, menu);
        }

        List<MenuCacheResponse.OptionGroupInfo> selectableGroups = requiredOptionsCompleteBefore
                ? optionalOptionGroups(menu, selectedBefore)
                : requiredGroupsBefore.stream().limit(1).toList();
        if (requiredOptionsCompleteBefore) {
            return handleOptionalOptionUtterance(sid, intent, text, session, menu, selectableGroups);
        }

        Optional<OptionSelection> optionSelection = findOptionSelection(text, menu, selectableGroups);
        if (optionSelection.isPresent()) {
            return askOptionSelectionConfirmation(sid, intent, session, menu, optionSelection.get());
        }

        Set<Long> selected = selectedOptionIds(session);
        List<OptionSlot> requiredSlots = requiredOptionSlots(menu, selected);
        if (!requiredSlots.isEmpty()) {
            return build(sid, intent, session,
                    requiredOptionPrompt(session.getMenu(), requiredSlots),
                    quickRepliesForOptions(requiredSlots, true),
                    requiredSlots);
        }

        List<MenuCacheResponse.OptionGroupInfo> optionalGroups = optionalOptionGroups(menu, selected);
        if (optionalGroups.isEmpty()) {
            return completeCurrentItemAndContinue(sid, intent, session, menu);
        }

        return build(sid, intent, session,
                optionalOptionListPrompt(session.getMenu(), optionalGroups),
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
            return completeCurrentItemAndContinue(sid, intent, session, menu);
        }
        if ("CONFIRM".equals(intent) || isNoAdditionalOptionText(text)) {
            return completeCurrentItemAndContinue(sid, intent, session, menu);
        }

        Optional<MenuCacheResponse.OptionGroupInfo> selectedGroup = findPendingOptionalGroup(session, optionalGroups)
                .or(() -> findMentionedOptionGroup(text, optionalGroups));
        if (selectedGroup.isPresent()) {
            MenuCacheResponse.OptionGroupInfo group = selectedGroup.get();
            if (session.getPendingOptionalGroupId() != null) {
                Optional<OptionSelection> optionSelection = findOptionSelection(text, menu, List.of(group));
                if (optionSelection.isPresent()) {
                    return askOptionSelectionConfirmation(sid, intent, session, menu, optionSelection.get());
                }

                OptionSlot optionSlot = optionSlotConverter.toOptionSlot(group, selectedOptionIds(session));
                return build(sid, intent, session,
                        optionChangePrompt(session.getMenu(), group.name()),
                        quickRepliesForOptions(List.of(optionSlot), false),
                        List.of(optionSlot));
            }

            session.setPendingOptionalGroupId(group.optionGroupId());
            OptionSlot optionSlot = optionSlotConverter.toOptionSlot(group, selectedOptionIds(session));
            return build(sid, intent, session,
                    optionChangePrompt(session.getMenu(), group.name()),
                    quickRepliesForOptions(List.of(optionSlot), false),
                    List.of(optionSlot));
        }

        return build(sid, intent, session,
                optionalOptionListPrompt(session.getMenu(), optionalGroups),
                quickRepliesForOptionalGroups(optionalGroups),
                List.of());
    }

    private Optional<OptionSelection> findOptionSelection(
            String text,
            MenuCacheResponse.MenuInfo menu,
            Collection<MenuCacheResponse.OptionGroupInfo> targetGroups
    ) {
        boolean removeMode = containsAny(text, OPTION_REMOVE_KW);
        if (removeMode) {
            return Optional.empty();
        }
        String normalizedText = normalize(text);
        for (MenuCacheResponse.OptionGroupInfo group : targetGroups) {
            for (MenuCacheResponse.OptionItemInfo item : emptyIfNull(group.optionItems())) {
                if (Boolean.FALSE.equals(item.isAvailable())) {
                    continue;
                }
                if (optionItemMatchesText(normalizedText, item)) {
                    return Optional.of(new OptionSelection(group, item));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<OrderResponse> handleExactOptionItemOnlyInput(
            String sid,
            String text,
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        if (session.getStatus() != OrderStatus.OPTION_FILLING
                || hasPendingOptionConfirmation(session)
                || text == null
                || text.isBlank()) {
            return Optional.empty();
        }

        Optional<MenuCacheResponse.MenuInfo> selectedMenu = findSelectedMenu(catalog, session);
        if (selectedMenu.isEmpty()) {
            return Optional.empty();
        }

        MenuCacheResponse.MenuInfo menu = selectedMenu.get();
        Set<Long> selected = selectedOptionIds(session);
        List<MenuCacheResponse.OptionGroupInfo> targetGroups = exactOptionTargetGroups(session, menu, selected);
        Optional<OptionSelection> selection = findExactOptionSelection(text, targetGroups);
        if (selection.isEmpty()) {
            log.info(
                    "[speak] route=OPTION_ITEM_EXACT_MISS sessionId={} input=\"{}\" menu=\"{}\" targetOptions={}",
                    sid,
                    text,
                    menu.name(),
                    exactOptionCandidates(targetGroups)
            );
            return Optional.empty();
        }

        OptionSelection selectedOption = selection.get();
        log.info(
                "[speak] route=OPTION_ITEM_EXACT sessionId={} input=\"{}\" menu=\"{}\" optionGroup=\"{}\" optionItem=\"{}\" matchedBy={}",
                sid,
                text,
                menu.name(),
                selectedOption.group().name(),
                selectedOption.item().name(),
                exactOptionMatchType(text, selectedOption.item())
        );

        applyConfirmedOptionSelection(session, menu, selectedOption.group(), selectedOption.item());
        session.setPendingOptionalGroupId(null);
        return Optional.of(continueAfterConfirmedOptionSelection(sid, "ORDER", session, menu));
    }

    private List<MenuCacheResponse.OptionGroupInfo> exactOptionTargetGroups(
            OrderSession session,
            MenuCacheResponse.MenuInfo menu,
            Set<Long> selectedOptionIds
    ) {
        List<MenuCacheResponse.OptionGroupInfo> requiredGroups = requiredOptionGroups(menu, selectedOptionIds);
        if (!requiredGroups.isEmpty()) {
            return requiredGroups.stream().limit(1).toList();
        }

        List<MenuCacheResponse.OptionGroupInfo> optionalGroups = optionalOptionGroups(menu, selectedOptionIds);
        return findPendingOptionalGroup(session, optionalGroups)
                .map(List::of)
                .orElse(optionalGroups);
    }

    private Optional<OptionSelection> findExactOptionSelection(
            String text,
            Collection<MenuCacheResponse.OptionGroupInfo> targetGroups
    ) {
        String normalizedText = normalize(text);
        if (normalizedText.isBlank()) {
            return Optional.empty();
        }

        List<OptionSelection> matches = new java.util.ArrayList<>();
        for (MenuCacheResponse.OptionGroupInfo group : targetGroups) {
            for (MenuCacheResponse.OptionItemInfo item : emptyIfNull(group.optionItems())) {
                if (!Boolean.FALSE.equals(item.isAvailable())
                        && optionItemExactlyMatchesText(normalizedText, item)) {
                    matches.add(new OptionSelection(group, item));
                }
            }
        }
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private String exactOptionCandidates(Collection<MenuCacheResponse.OptionGroupInfo> targetGroups) {
        return targetGroups.stream()
                .flatMap(group -> emptyIfNull(group.optionItems()).stream()
                        .filter(item -> !Boolean.FALSE.equals(item.isAvailable()))
                        .map(item -> String.format(
                                "%s:%s aliases=[%s]",
                                group.name(),
                                item.name(),
                                String.join(",", emptyIfNull(item.aliases()))
                        )))
                .limit(20)
                .collect(Collectors.joining(" | "));
    }

    private boolean optionItemExactlyMatchesText(
            String normalizedText,
            MenuCacheResponse.OptionItemInfo item
    ) {
        String normalizedName = normalize(item.name());
        if (!normalizedName.isBlank() && normalizedName.equals(normalizedText)) {
            return true;
        }
        return emptyIfNull(item.aliases()).stream()
                .map(this::normalize)
                .filter(alias -> !alias.isBlank())
                .anyMatch(normalizedText::equals);
    }

    private String exactOptionMatchType(String text, MenuCacheResponse.OptionItemInfo item) {
        String normalizedText = normalize(text);
        String normalizedName = normalize(item.name());
        if (!normalizedName.isBlank() && normalizedName.equals(normalizedText)) {
            return "ITEM_NAME";
        }
        return "ITEM_ALIAS";
    }

    private OrderResponse askOptionSelectionConfirmation(
            String sid,
            String intent,
            OrderSession session,
            MenuCacheResponse.MenuInfo menu,
            OptionSelection selection
    ) {
        session.setPendingOptionConfirmGroupId(selection.group().optionGroupId());
        session.setPendingOptionConfirmItemId(selection.item().optionItemId());
        return build(sid, intent, session,
                optionSelectionConfirmationPrompt(menu.name(), selection.group().name(), selection.item().name()),
                List.of("네", "아니요"),
                List.of());
    }

    private OrderResponse handleOptionSelectionConfirmation(
            String sid,
            String intent,
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        Optional<MenuCacheResponse.MenuInfo> selectedMenu = findSelectedMenu(catalog, session);
        if (selectedMenu.isEmpty()) {
            clearPendingOptionConfirmation(session);
            return build(sid, intent, session,
                    "메뉴 정보를 찾지 못했습니다. 옵션을 다시 말씀해 주세요.",
                    List.of(),
                    List.of());
        }

        MenuCacheResponse.MenuInfo menu = selectedMenu.get();
        Optional<MenuCacheResponse.OptionGroupInfo> group = findOptionGroupById(
                menu,
                session.getPendingOptionConfirmGroupId()
        );
        Optional<MenuCacheResponse.OptionItemInfo> item = group.stream()
                .flatMap(value -> emptyIfNull(value.optionItems()).stream())
                .filter(value -> value.optionItemId().equals(session.getPendingOptionConfirmItemId()))
                .findFirst();
        if (group.isEmpty() || item.isEmpty()) {
            clearPendingOptionConfirmation(session);
            return build(sid, intent, session,
                    "확인할 옵션 정보를 찾지 못했습니다. 옵션을 다시 선택해 주세요.",
                    List.of(),
                    List.of());
        }

        if ("CONFIRM".equals(intent)) {
            applyConfirmedOptionSelection(session, menu, group.get(), item.get());
            clearPendingOptionConfirmation(session);
            session.setPendingOptionalGroupId(null);
            return continueAfterConfirmedOptionSelection(sid, intent, session, menu);
        }

        clearPendingOptionConfirmation(session);
        OptionSlot optionSlot = optionSlotConverter.toOptionSlot(group.get(), selectedOptionIds(session));
        return build(sid, intent, session,
                optionRejectedPrompt(menu.name(), group.get().name()),
                quickRepliesForOptions(List.of(optionSlot), Boolean.TRUE.equals(group.get().isRequired())),
                List.of(optionSlot));
    }

    private OrderResponse continueAfterConfirmedOptionSelection(
            String sid,
            String intent,
            OrderSession session,
            MenuCacheResponse.MenuInfo menu
    ) {
        Set<Long> selected = selectedOptionIds(session);
        List<OptionSlot> requiredSlots = requiredOptionSlots(menu, selected);
        if (!requiredSlots.isEmpty()) {
            return build(sid, intent, session,
                    requiredOptionPrompt(session.getMenu(), requiredSlots),
                    quickRepliesForOptions(requiredSlots, true),
                    requiredSlots);
        }

        List<MenuCacheResponse.OptionGroupInfo> optionalGroups = optionalOptionGroups(menu, selected);
        if (optionalGroups.isEmpty()) {
            return completeCurrentItemAndContinue(sid, intent, session, menu);
        }

        return build(sid, intent, session,
                optionalOptionListPrompt(session.getMenu(), optionalGroups),
                quickRepliesForOptionalGroups(optionalGroups),
                List.of());
    }

    private void applyConfirmedOptionSelection(
            OrderSession session,
            MenuCacheResponse.MenuInfo menu,
            MenuCacheResponse.OptionGroupInfo group,
            MenuCacheResponse.OptionItemInfo item
    ) {
        Set<Long> selected = selectedOptionIds(session);
        if (maxSelect(group) == 1) {
            removeGroupSelections(selected, group);
        }
        selected.add(item.optionItemId());
        pruneInactiveSelections(selected, menu);
        session.setSelectedOptionItemIds(new LinkedHashSet<>(selected));
    }

    private boolean hasPendingOptionConfirmation(OrderSession session) {
        return session.getPendingOptionConfirmGroupId() != null
                && session.getPendingOptionConfirmItemId() != null;
    }

    private void clearPendingOptionConfirmation(OrderSession session) {
        session.setPendingOptionConfirmGroupId(null);
        session.setPendingOptionConfirmItemId(null);
    }

    private record OptionSelection(
            MenuCacheResponse.OptionGroupInfo group,
            MenuCacheResponse.OptionItemInfo item
    ) {
    }

    private boolean seedPendingMenusFromText(
            String text,
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        if (session.getMenu() != null || !pendingMenuItems(session).isEmpty()) {
            return false;
        }
        List<MenuCacheResponse.MenuInfo> menus = findMenusInText(text, catalog);
        if (menus.size() < 2) {
            return false;
        }

        MenuCacheResponse.MenuInfo firstMenu = menus.get(0);
        session.setMenu(firstMenu.name());
        session.setMenuId(firstMenu.menuId());
        session.setQuantity(1);
        session.setPendingOptionText(text);

        menus.stream()
                .skip(1)
                .forEach(menu -> pendingMenuItems(session).addLast(
                        new OrderSession.PendingMenuItem(menu.menuId(), menu.name(), 1, null, null)
                ));
        session.setStatus(OrderStatus.MENU_CONFIRMING);
        return true;
    }

    private void seedOrderDraftFromText(
            String text,
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        if (session.getOrderDraft() != null) {
            return;
        }
        List<MenuCacheResponse.MenuInfo> menus = findMenusInText(text, catalog);
        if (menus.isEmpty()) {
            return;
        }
        List<OrderDraft.Item> items = menus.stream()
                .map(menu -> toDraftItem(menu, text))
                .toList();
        session.setOrderDraft(new OrderDraft(items));
    }

    private OrderDraft.Item toDraftItem(MenuCacheResponse.MenuInfo menu, String sourceText) {
        List<OrderDraft.OptionValue> optionValues = activeOptionGroups(menu, Set.of()).stream()
                .map(this::toDraftOptionValue)
                .toList();
        return new OrderDraft.Item(
                menu.menuId(),
                menu.name(),
                1,
                optionValues.stream()
                        .filter(option -> Boolean.TRUE.equals(option.required()))
                        .toList(),
                optionValues.stream()
                        .filter(option -> !Boolean.TRUE.equals(option.required()))
                        .toList(),
                sourceText
        );
    }

    private OrderDraft.OptionValue toDraftOptionValue(MenuCacheResponse.OptionGroupInfo group) {
        return toDraftOptionValue(group, Set.of());
    }

    private OrderDraft.OptionValue toDraftOptionValue(
            MenuCacheResponse.OptionGroupInfo group,
            Set<Long> selectedOptionIds
    ) {
        Optional<MenuCacheResponse.OptionItemInfo> selectedOption = selectedOption(group, selectedOptionIds);
        return new OrderDraft.OptionValue(
                group.optionGroupId(),
                group.name(),
                group.isRequired(),
                selectedOption.map(MenuCacheResponse.OptionItemInfo::optionItemId).orElse(null),
                selectedOption.map(MenuCacheResponse.OptionItemInfo::name).orElse(null),
                emptyIfNull(group.optionItems()).stream()
                        .filter(item -> !Boolean.FALSE.equals(item.isAvailable()))
                        .map(item -> new OrderDraft.OptionCandidate(
                                item.optionItemId(),
                                item.name(),
                                item.aliases()
                        ))
                        .toList()
        );
    }

    private void applyOptionMentionsToDraft(
            String text,
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        OrderDraft draft = session.getOrderDraft();
        if (draft == null || draft.items() == null || draft.items().isEmpty()) {
            return;
        }

        List<OrderDraft.Item> items = draft.items().stream()
                .map(item -> applyOptionMentionsToDraftItem(text, item, catalog))
                .toList();
        session.setOrderDraft(new OrderDraft(items));
    }

    private OrderDraft.Item applyOptionMentionsToDraftItem(
            String text,
            OrderDraft.Item item,
            Optional<MenuCacheResponse> catalog
    ) {
        Optional<MenuCacheResponse.MenuInfo> menu = findMenuById(item.menuId(), catalog)
                .or(() -> findMenuByName(item.menuName(), catalog));
        if (menu.isEmpty()) {
            return item;
        }

        String optionText = mergeOptionText(text, item.sourceText());
        Set<Long> selectedOptionIds = inferOptionIdsFromText(
                optionText,
                menu.get(),
                selectedOptionIdsFromDraft(item, menu.get())
        );

        List<OrderDraft.OptionValue> optionValues = activeOptionGroups(menu.get(), selectedOptionIds).stream()
                .map(group -> toDraftOptionValue(group, selectedOptionIds))
                .toList();

        return new OrderDraft.Item(
                menu.get().menuId(),
                menu.get().name(),
                item.quantity() == null || item.quantity() < 1 ? 1 : item.quantity(),
                optionValues.stream()
                        .filter(option -> Boolean.TRUE.equals(option.required()))
                        .toList(),
                optionValues.stream()
                        .filter(option -> !Boolean.TRUE.equals(option.required()))
                        .toList(),
                item.sourceText()
        );
    }

    private Set<Long> inferOptionIdsFromText(
            String text,
            MenuCacheResponse.MenuInfo menu,
            Set<Long> initialSelectedOptionIds
    ) {
        String normalizedText = normalize(text);
        Set<Long> selectedOptionIds = new LinkedHashSet<>(initialSelectedOptionIds);
        if (normalizedText.isBlank()) {
            return selectedOptionIds;
        }

        boolean changed;
        do {
            changed = false;
            for (MenuCacheResponse.OptionGroupInfo group : activeOptionGroups(menu, selectedOptionIds)) {
                for (MenuCacheResponse.OptionItemInfo item : emptyIfNull(group.optionItems())) {
                    if (Boolean.FALSE.equals(item.isAvailable())
                            || !optionItemMatchesText(normalizedText, item)) {
                        continue;
                    }

                    Set<Long> before = new LinkedHashSet<>(selectedOptionIds);
                    if (maxSelect(group) == 1) {
                        removeGroupSelections(selectedOptionIds, group);
                    }
                    selectedOptionIds.add(item.optionItemId());
                    if (!before.equals(selectedOptionIds)) {
                        changed = true;
                    }
                }
            }
            pruneInactiveSelections(selectedOptionIds, menu);
        } while (changed);

        return selectedOptionIds;
    }

    private Optional<MenuCacheResponse.OptionItemInfo> selectedOption(
            MenuCacheResponse.OptionGroupInfo group,
            Set<Long> selectedOptionIds
    ) {
        return emptyIfNull(group.optionItems()).stream()
                .filter(item -> selectedOptionIds.contains(item.optionItemId()))
                .findFirst();
    }

    private Optional<OrderResponse> handleDraftFlow(
            String sid,
            String intent,
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        OrderDraft draft = session.getOrderDraft();
        if (draft == null || draft.items() == null || draft.items().isEmpty()) {
            return Optional.empty();
        }

        Optional<DraftMissingOption> missingOption = findFirstMissingRequiredOption(draft, catalog);
        if (missingOption.isPresent()) {
            DraftMissingOption missing = missingOption.get();
            hydrateSessionFromDraftItem(session, missing.item(), catalog);
            session.setStatus(OrderStatus.OPTION_FILLING);
            OptionSlot optionSlot = optionSlotConverter.toOptionSlot(
                    missing.optionGroup(),
                    selectedOptionIdsFromDraft(missing.item(), missing.menu())
            );
            return Optional.of(build(sid, intent, session,
                    requiredOptionPrompt(missing.item().menuName(), List.of(optionSlot)),
                    quickRepliesForOptions(List.of(optionSlot), true),
                    List.of(optionSlot)));
        }

        hydrateLegacySessionFromDraft(session, draft, catalog);
        session.setOrderDraft(null);
        session.setStatus(OrderStatus.CONFIRMING);
        return Optional.of(confirmMenuAndStartOptionFilling(sid, intent, session, catalog));
    }

    private Optional<DraftMissingOption> findFirstMissingRequiredOption(
            OrderDraft draft,
            Optional<MenuCacheResponse> catalog
    ) {
        for (OrderDraft.Item item : emptyIfNull(draft.items())) {
            Optional<MenuCacheResponse.MenuInfo> menu = findMenuById(item.menuId(), catalog)
                    .or(() -> findMenuByName(item.menuName(), catalog));
            if (menu.isEmpty()) {
                continue;
            }
            for (OrderDraft.OptionValue option : emptyIfNull(item.requiredOptions())) {
                Optional<MenuCacheResponse.OptionGroupInfo> optionGroup = findOptionGroupById(
                        menu.get(),
                        option.optionGroupId()
                );
                if (optionGroup.isPresent() && resolveSelectedOptionId(menu.get(), option).isPresent()) {
                    continue;
                }
                if (optionGroup.isPresent()) {
                    return Optional.of(new DraftMissingOption(item, menu.get(), optionGroup.get()));
                }
            }
        }
        return Optional.empty();
    }

    private void hydrateLegacySessionFromDraft(
            OrderSession session,
            OrderDraft draft,
            Optional<MenuCacheResponse> catalog
    ) {
        session.resetCurrentItem();
        pendingMenuItems(session).clear();

        List<OrderDraft.Item> items = emptyIfNull(draft.items()).stream()
                .filter(item -> findMenuById(item.menuId(), catalog)
                        .or(() -> findMenuByName(item.menuName(), catalog))
                        .isPresent())
                .toList();
        if (items.isEmpty()) {
            return;
        }

        hydrateSessionFromDraftItem(session, items.get(0), catalog);
        items.stream()
                .skip(1)
                .forEach(item -> pendingMenuItems(session).addLast(
                        new OrderSession.PendingMenuItem(
                                item.menuId(),
                                item.menuName(),
                                item.quantity() == null || item.quantity() < 1 ? 1 : item.quantity(),
                                item.sourceText(),
                                selectedOptionIdsFromDraft(item, findMenuById(item.menuId(), catalog)
                                        .or(() -> findMenuByName(item.menuName(), catalog))
                                        .orElse(null))
                        )
                ));
    }

    private void hydrateSessionFromDraftItem(
            OrderSession session,
            OrderDraft.Item item,
            Optional<MenuCacheResponse> catalog
    ) {
        Optional<MenuCacheResponse.MenuInfo> menu = findMenuById(item.menuId(), catalog)
                .or(() -> findMenuByName(item.menuName(), catalog));
        session.setMenuId(menu.map(MenuCacheResponse.MenuInfo::menuId).orElse(item.menuId()));
        session.setMenu(menu.map(MenuCacheResponse.MenuInfo::name).orElse(item.menuName()));
        session.setQuantity(item.quantity() == null || item.quantity() < 1 ? 1 : item.quantity());
        session.setSelectedOptionItemIds(selectedOptionIdsFromDraft(item, menu.orElse(null)));
        session.setPendingOptionText(item.sourceText());
    }

    private Set<Long> selectedOptionIdsFromDraft(OrderDraft.Item item, MenuCacheResponse.MenuInfo menu) {
        if (menu == null) {
            return Set.of();
        }
        Set<Long> selected = new LinkedHashSet<>();
        java.util.stream.Stream.concat(
                        emptyIfNull(item.requiredOptions()).stream(),
                        emptyIfNull(item.optionalOptions()).stream()
                )
                .forEach(option -> resolveSelectedOptionId(menu, option)
                        .ifPresent(selected::add));
        return selected;
    }

    private Optional<Long> resolveSelectedOptionId(
            MenuCacheResponse.MenuInfo menu,
            OrderDraft.OptionValue option
    ) {
        if (option.selectedOptionItemId() != null
                && findOptionGroupById(menu, option.optionGroupId()).stream()
                .flatMap(group -> emptyIfNull(group.optionItems()).stream())
                .map(MenuCacheResponse.OptionItemInfo::optionItemId)
                .anyMatch(option.selectedOptionItemId()::equals)) {
            return Optional.of(option.selectedOptionItemId());
        }
        return resolveSelectedOptionIdByName(menu, option);
    }

    private Optional<Long> resolveSelectedOptionIdByName(
            MenuCacheResponse.MenuInfo menu,
            OrderDraft.OptionValue option
    ) {
        if (option.selectedOptionItemName() == null || option.selectedOptionItemName().isBlank()) {
            return Optional.empty();
        }
        String normalizedName = normalize(option.selectedOptionItemName());
        return findOptionGroupById(menu, option.optionGroupId()).stream()
                .flatMap(group -> emptyIfNull(group.optionItems()).stream())
                .filter(item -> normalize(item.name()).equals(normalizedName))
                .map(MenuCacheResponse.OptionItemInfo::optionItemId)
                .findFirst();
    }

    private Optional<MenuCacheResponse.MenuInfo> findMenuById(
            Long menuId,
            Optional<MenuCacheResponse> catalog
    ) {
        if (menuId == null) {
            return Optional.empty();
        }
        return catalog.stream()
                .flatMap(response -> response.menus().stream())
                .filter(menu -> menuId.equals(menu.menuId()))
                .findFirst();
    }

    private Optional<MenuCacheResponse.OptionGroupInfo> findOptionGroupById(
            MenuCacheResponse.MenuInfo menu,
            Long optionGroupId
    ) {
        if (menu == null || optionGroupId == null) {
            return Optional.empty();
        }
        return emptyIfNull(menu.optionGroups()).stream()
                .filter(group -> optionGroupId.equals(group.optionGroupId()))
                .findFirst();
    }

    private record DraftMissingOption(
            OrderDraft.Item item,
            MenuCacheResponse.MenuInfo menu,
            MenuCacheResponse.OptionGroupInfo optionGroup
    ) {
    }

    private List<MenuCacheResponse.MenuInfo> findMenusInText(
            String text,
            Optional<MenuCacheResponse> catalog
    ) {
        String normalizedText = normalize(text);
        List<MatchedMenu> matches = catalog.stream()
                .flatMap(response -> response.menus().stream())
                .flatMap(menu -> menuMatchTerms(menu).stream()
                        .map(term -> {
                            int start = normalizedText.indexOf(term.value());
                            return new MatchedMenu(menu, start, start + term.value().length(), term.canonical());
                        }))
                .filter(match -> match.start() >= 0)
                .sorted(Comparator
                        .comparingInt(MatchedMenu::start)
                        .thenComparing(match -> match.end() - match.start(), Comparator.reverseOrder())
                        .thenComparing(MatchedMenu::canonical, Comparator.reverseOrder()))
                .toList();

        List<MatchedMenu> nonOverlappingMatches = new java.util.ArrayList<>();
        for (MatchedMenu match : matches) {
            boolean overlaps = nonOverlappingMatches.stream()
                    .anyMatch(existing -> match.start() < existing.end() && existing.start() < match.end());
            if (!overlaps) {
                nonOverlappingMatches.add(match);
            }
        }
        return nonOverlappingMatches.stream()
                .map(MatchedMenu::menu)
                .toList();
    }

    private List<MenuMatchTerm> menuMatchTerms(MenuCacheResponse.MenuInfo menu) {
        List<MenuMatchTerm> terms = new java.util.ArrayList<>();
        String normalizedName = normalize(menu.name());
        if (!normalizedName.isBlank()) {
            terms.add(new MenuMatchTerm(normalizedName, true));
        }
        emptyIfNull(menu.aliases()).stream()
                .map(this::normalize)
                .filter(alias -> !alias.isBlank())
                .distinct()
                .map(alias -> new MenuMatchTerm(alias, false))
                .forEach(terms::add);
        return terms.stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                MenuMatchTerm::value,
                                term -> term,
                                (left, right) -> left.canonical() ? left : right,
                                java.util.LinkedHashMap::new
                        ),
                        map -> List.copyOf(map.values())
                ));
    }

    private List<String> menuMatchTermValues(MenuCacheResponse.MenuInfo menu) {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(menu.name()),
                        emptyIfNull(menu.aliases()).stream()
                )
                .map(this::normalize)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
    }

    private record MenuMatchTerm(String value, boolean canonical) {
    }

    private record MatchedMenu(MenuCacheResponse.MenuInfo menu, int start, int end, boolean canonical) {
    }

    private boolean hasOptionSelection(String text, Optional<MenuCacheResponse> catalog, OrderSession session) {
        String normalizedText = normalize(text);
        return findSelectedMenu(catalog, session)
                .map(menu -> activeOptionGroups(menu, selectedOptionIds(session)).stream()
                        .anyMatch(group -> emptyIfNull(group.optionItems()).stream()
                                .anyMatch(item -> optionItemMatchesText(normalizedText, item))))
                .orElse(false);
    }

    private boolean hasAnyOptionSelection(String text, Optional<MenuCacheResponse> catalog) {
        String normalizedText = normalize(text);
        return catalog.stream()
                .flatMap(response -> response.menus().stream())
                .flatMap(menu -> emptyIfNull(menu.optionGroups()).stream())
                .anyMatch(group -> emptyIfNull(group.optionItems()).stream()
                        .anyMatch(item -> optionItemMatchesText(normalizedText, item)));
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
            MenuCacheResponse.OptionItemInfo item
    ) {
        return matchesNameOrAlias(normalizedText, item.name(), item.aliases());
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
                .map(group -> optionSlotConverter.toOptionSlot(group, selectedOptionIds))
                .toList();
    }

    private List<OptionSlot> requiredOptionSlots(MenuCacheResponse.MenuInfo menu, Set<Long> selectedOptionIds) {
        return requiredOptionGroups(menu, selectedOptionIds).stream()
                .limit(1)
                .map(group -> optionSlotConverter.toOptionSlot(group, selectedOptionIds))
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
        return findMenusInText(text, catalog).stream()
                .findFirst();
    }

    private Optional<MenuCacheResponse.MenuInfo> findMenuByName(String menuName, Optional<MenuCacheResponse> catalog) {
        if (menuName == null || menuName.isBlank()) {
            return Optional.empty();
        }
        String normalizedMenuName = normalize(menuName);
        return catalog.stream()
                .flatMap(response -> response.menus().stream())
                .filter(menu -> menuMatchTermValues(menu).stream().anyMatch(term -> term.equals(normalizedMenuName)))
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

    private String multiMenuConfirmationPrompt(OrderSession session) {
        List<String> menuNames = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(session.getMenu()),
                        pendingMenuItems(session).stream().map(OrderSession.PendingMenuItem::getMenu)
                )
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .toList();

        String menuLineup = String.join(", ", menuNames);
        return String.format("%s, 이렇게 총 %d개 메뉴로 라인업 잡았습니다. 주문하신 메뉴명 맞으신가요?",
                menuLineup, menuNames.size());
    }

    private String requiredOptionPrompt(List<OptionSlot> slots) {
        return requiredOptionPrompt(null, slots);
    }

    private String requiredOptionPrompt(String menuName, List<OptionSlot> slots) {
        if (slots.isEmpty()) {
            return "필수 옵션을 선택해주세요.";
        }
        OptionSlot slot = slots.get(0);
        String optionName = slot.name();
        if (optionName == null || optionName.isBlank()) {
            return "필수 옵션을 선택해주세요.";
        }
        String defaultMessage = defaultOptionName(slot)
                .map(defaultOption -> " 기본 " + defaultOption + "입니다.")
                .orElse("");
        if (menuName != null && !menuName.isBlank()) {
            return String.format("%s의 필수 옵션 %s를 선택해주세요.%s", menuName, optionName, defaultMessage);
        }
        return optionName + " 옵션을 선택해주세요." + defaultMessage;
    }

    private Optional<String> defaultOptionName(OptionSlot slot) {
        return emptyIfNull(slot.candidates()).stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.defaultSelected()))
                .map(OptionSlot.OptionCandidate::name)
                .filter(name -> name != null && !name.isBlank())
                .findFirst();
    }

    private String optionalOptionListPrompt(List<MenuCacheResponse.OptionGroupInfo> optionalGroups) {
        return optionalOptionListPrompt(null, optionalGroups);
    }

    private String optionalOptionListPrompt(
            String menuName,
            List<MenuCacheResponse.OptionGroupInfo> optionalGroups
    ) {
        if (optionalGroups.isEmpty()) {
            return "추가로 변경할 수 있는 선택 옵션이 없습니다. 확인해 주세요.";
        }
        String optionNames = optionalGroups.stream()
                .map(MenuCacheResponse.OptionGroupInfo::name)
                .collect(Collectors.joining(", "));
        String prefix = menuName == null || menuName.isBlank() ? "" : menuName + "에서 ";
        return prefix + "변경 가능한 선택 옵션은 " + optionNames + "입니다. 추가할 옵션이 있으면 선택해 주시고, 없으면 없다고 말씀해 주세요.";
    }

    private String optionChangePrompt(String menuName, String optionGroupName) {
        if (menuName == null || menuName.isBlank()) {
            return optionGroupName + " 옵션에서 변경할 값을 선택해 주세요.";
        }
        return String.format("%s의 %s 옵션에서 변경할 값을 선택해 주세요.", menuName, optionGroupName);
    }

    private String optionSelectionConfirmationPrompt(
            String menuName,
            String optionGroupName,
            String optionItemName
    ) {
        if (menuName == null || menuName.isBlank()) {
            return String.format("%s 옵션을 %s(으)로 선택할까요?", optionGroupName, optionItemName);
        }
        return String.format("%s의 %s 옵션을 %s(으)로 선택할까요?", menuName, optionGroupName, optionItemName);
    }

    private String optionRejectedPrompt(String menuName, String optionGroupName) {
        if (menuName == null || menuName.isBlank()) {
            return optionGroupName + " 옵션은 반영하지 않았습니다. 다시 선택해 주세요.";
        }
        return String.format("%s의 %s 옵션은 반영하지 않았습니다. 다시 선택해 주세요.", menuName, optionGroupName);
    }

    private List<String> quickRepliesForOptionalGroups(List<MenuCacheResponse.OptionGroupInfo> optionalGroups) {
        return java.util.stream.Stream.concat(
                        optionalGroups.stream()
                                .map(MenuCacheResponse.OptionGroupInfo::name)
                                .limit(6),
                        java.util.stream.Stream.of("없음", "확인")
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

    private Set<Long> mergeSelectedOptionIds(
            MenuCacheResponse.MenuInfo menu,
            Set<Long> defaults,
            Set<Long> overrides
    ) {
        Set<Long> merged = new LinkedHashSet<>(defaults);
        if (overrides.isEmpty()) {
            return merged;
        }
        for (MenuCacheResponse.OptionGroupInfo group : emptyIfNull(menu.optionGroups())) {
            Set<Long> groupOptionIds = emptyIfNull(group.optionItems()).stream()
                    .map(MenuCacheResponse.OptionItemInfo::optionItemId)
                    .collect(Collectors.toSet());
            Set<Long> groupOverrides = overrides.stream()
                    .filter(groupOptionIds::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (groupOverrides.isEmpty()) {
                continue;
            }
            if (maxSelect(group) == 1) {
                merged.removeAll(groupOptionIds);
            }
            merged.addAll(groupOverrides);
        }
        return merged;
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

    private boolean isNoAdditionalOptionText(String text) {
        String normalizedText = normalize(text);
        return NO_OPTION_KW.stream()
                .map(this::normalize)
                .filter(keyword -> !keyword.isBlank())
                .anyMatch(normalizedText::contains);
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
            List<OptionSlot> nextOptionSlots
    ) {
        Optional<MenuCacheResponse> catalog = resolveCatalog(session);
        List<OrderResponse.OrderItemSlot> orderItems = orderItemSlots(session, catalog);
        OrderResponse.PriceInfo priceInfo = calculatePrice(session, orderItems);
        boolean slotsComplete = requiredSlotsComplete(session, catalog);
        session.setPreviousBotResponse(message);
        return orderResponseConverter().toResponse(
                sid,
                intent,
                session,
                message,
                quickReplies,
                nextOptionSlots,
                orderItems,
                slotsComplete,
                priceInfo
        );
    }

    private List<OrderResponse.OrderItemSlot> orderItemSlots(
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        if (session.getOrderDraft() != null
                && session.getOrderDraft().items() != null
                && !session.getOrderDraft().items().isEmpty()) {
            List<OrderResponse.OrderItemSlot> draftItems = draftOrderItemSlots(session.getOrderDraft(), catalog);
            if (!draftItems.isEmpty()) {
                return draftItems;
            }
        }

        List<OrderResponse.OrderItemSlot> items = new java.util.ArrayList<>();
        if (session.getMenu() != null || session.getMenuId() != null) {
            orderItemSlot(
                    session.getMenuId(),
                    session.getMenu(),
                    session.getQuantity(),
                    selectedOptionIds(session),
                    catalog
            ).ifPresent(items::add);
        }
        pendingMenuItems(session).forEach(pendingMenuItem -> orderItemSlot(
                pendingMenuItem.getMenuId(),
                pendingMenuItem.getMenu(),
                pendingMenuItem.getQuantity(),
                new LinkedHashSet<>(emptyIfNull(pendingMenuItem.getSelectedOptionItemIds())),
                catalog
        ).ifPresent(items::add));
        return items;
    }

    private List<OrderResponse.OrderItemSlot> draftOrderItemSlots(
            OrderDraft draft,
            Optional<MenuCacheResponse> catalog
    ) {
        return emptyIfNull(draft.items()).stream()
                .map(item -> {
                    Optional<MenuCacheResponse.MenuInfo> menu = findMenuById(item.menuId(), catalog)
                            .or(() -> findMenuByName(item.menuName(), catalog));
                    if (menu.isEmpty()) {
                        return OrderResponse.OrderItemSlot.builder()
                                .menu(item.menuName())
                                .quantity(item.quantity())
                                .optionSlots(List.of())
                                .build();
                    }
                    Set<Long> selectedOptionIds = displaySelectedOptionIds(
                            menu.get(),
                            selectedOptionIdsFromDraft(item, menu.get())
                    );
                    return OrderResponse.OrderItemSlot.builder()
                            .menu(menu.get().name())
                            .quantity(item.quantity())
                            .menuPrice(menuPrice(menu.get()))
                            .optionExtraPrice(optionExtraPrice(selectedOptionIds, menu.get()))
                            .unitPrice(menuPrice(menu.get()) + optionExtraPrice(selectedOptionIds, menu.get()))
                            .totalPrice(lineTotal(item.quantity(), menu.get(), selectedOptionIds))
                            .optionSlots(activeOptionSlots(menu.get(), selectedOptionIds))
                            .build();
                })
                .toList();
    }

    private Optional<OrderResponse.OrderItemSlot> orderItemSlot(
            Long menuId,
            String menuName,
            Integer quantity,
            Set<Long> selectedOptionIds,
            Optional<MenuCacheResponse> catalog
    ) {
        Optional<MenuCacheResponse.MenuInfo> menu = findMenuById(menuId, catalog)
                .or(() -> findMenuByName(menuName, catalog));
        if (menu.isEmpty()) {
            if (menuName == null && quantity == null) {
                return Optional.empty();
            }
            return Optional.of(OrderResponse.OrderItemSlot.builder()
                    .menu(menuName)
                    .quantity(quantity)
                    .optionSlots(List.of())
                    .build());
        }

        Set<Long> displaySelectedOptionIds = displaySelectedOptionIds(menu.get(), selectedOptionIds);
        return Optional.of(OrderResponse.OrderItemSlot.builder()
                .menu(menu.get().name())
                .quantity(quantity)
                .menuPrice(menuPrice(menu.get()))
                .optionExtraPrice(optionExtraPrice(displaySelectedOptionIds, menu.get()))
                .unitPrice(menuPrice(menu.get()) + optionExtraPrice(displaySelectedOptionIds, menu.get()))
                .totalPrice(lineTotal(quantity, menu.get(), displaySelectedOptionIds))
                .optionSlots(activeOptionSlots(menu.get(), displaySelectedOptionIds))
                .build());
    }

    private Set<Long> displaySelectedOptionIds(
            MenuCacheResponse.MenuInfo menu,
            Set<Long> selectedOptionIds
    ) {
        return mergeSelectedOptionIds(menu, defaultOptionIds(menu), selectedOptionIds);
    }

    private boolean requiredSlotsComplete(
            OrderSession session,
            Optional<MenuCacheResponse> catalog
    ) {
        if (session.getOrderDraft() != null
                && session.getOrderDraft().items() != null
                && !session.getOrderDraft().items().isEmpty()) {
            return session.getOrderDraft().items().stream()
                    .allMatch(item -> requiredSlotsComplete(
                            item.menuId(),
                            item.menuName(),
                            item.quantity(),
                            selectedOptionIdsFromDraft(item, findMenuById(item.menuId(), catalog)
                                    .or(() -> findMenuByName(item.menuName(), catalog))
                                    .orElse(null)),
                            catalog
                    ));
        }

        if (session.getMenu() == null && session.getMenuId() == null) {
            return false;
        }
        boolean currentComplete = requiredSlotsComplete(
                session.getMenuId(),
                session.getMenu(),
                session.getQuantity(),
                selectedOptionIds(session),
                catalog
        );
        if (!currentComplete) {
            return false;
        }
        return pendingMenuItems(session).stream()
                .allMatch(pendingMenuItem -> requiredSlotsComplete(
                        pendingMenuItem.getMenuId(),
                        pendingMenuItem.getMenu(),
                        pendingMenuItem.getQuantity(),
                        new LinkedHashSet<>(emptyIfNull(pendingMenuItem.getSelectedOptionItemIds())),
                        catalog
                ));
    }

    private boolean requiredSlotsComplete(
            Long menuId,
            String menuName,
            Integer quantity,
            Set<Long> selectedOptionIds,
            Optional<MenuCacheResponse> catalog
    ) {
        if (quantity == null || quantity < 1) {
            return false;
        }
        Optional<MenuCacheResponse.MenuInfo> menu = findMenuById(menuId, catalog)
                .or(() -> findMenuByName(menuName, catalog));
        return menu.map(value -> requiredOptionGroups(value, selectedOptionIds).isEmpty())
                .orElse(false);
    }

    private int lineTotal(
            Integer quantity,
            MenuCacheResponse.MenuInfo menu,
            Set<Long> selectedOptionIds
    ) {
        int safeQuantity = quantity == null || quantity < 1 ? 1 : quantity;
        return (menuPrice(menu) + optionExtraPrice(selectedOptionIds, menu)) * safeQuantity;
    }

    private OrderResponse.PriceInfo calculatePrice(
            OrderSession session,
            List<OrderResponse.OrderItemSlot> orderItems
    ) {
        int accumulatedTotalPrice = accumulatedTotalPrice(session);
        int menuPrice = emptyIfNull(orderItems).stream()
                .mapToInt(item -> (item.getMenuPrice() == null ? 0 : item.getMenuPrice())
                        * (item.getQuantity() == null || item.getQuantity() < 1 ? 1 : item.getQuantity()))
                .sum();
        int optionExtraPrice = emptyIfNull(orderItems).stream()
                .mapToInt(item -> (item.getOptionExtraPrice() == null ? 0 : item.getOptionExtraPrice())
                        * (item.getQuantity() == null || item.getQuantity() < 1 ? 1 : item.getQuantity()))
                .sum();
        int itemTotalPrice = session.isCurrentItemFinalized()
                ? 0
                : emptyIfNull(orderItems).stream()
                .map(OrderResponse.OrderItemSlot::getTotalPrice)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        int totalPrice = accumulatedTotalPrice + itemTotalPrice;
        Integer unitPrice = orderItems.size() == 1 ? orderItems.get(0).getUnitPrice() : null;
        session.setTotalPrice(totalPrice == 0 ? null : totalPrice);

        if (totalPrice == 0) {
            return null;
        }
        return orderResponseConverter().toPriceInfo(menuPrice, optionExtraPrice, unitPrice, totalPrice);
    }

    private OrderResponseConverter orderResponseConverter() {
        return orderResponseConverter == null ? new OrderResponseConverter() : orderResponseConverter;
    }

    private void completeCurrentItem(OrderSession session, MenuCacheResponse.MenuInfo menu) {
        if (session.isCurrentItemFinalized() || session.getQuantity() == null) {
            return;
        }
        int unitPrice = menuPrice(menu) + optionExtraPrice(session, menu);
        int lineTotal = unitPrice * session.getQuantity();
        session.setAccumulatedTotalPrice(accumulatedTotalPrice(session) + lineTotal);
        session.setTotalPrice(session.getAccumulatedTotalPrice());
        persistCompletedItem(session, menu, unitPrice);
        session.setCurrentItemFinalized(true);
    }

    private OrderResponse completeCurrentItemAndContinue(
            String sid,
            String intent,
            OrderSession session,
            MenuCacheResponse.MenuInfo menu
    ) {
        String completedMenu = session.getMenu() == null || session.getMenu().isBlank()
                ? menu.name()
                : session.getMenu();
        Integer completedQuantity = session.getQuantity() == null || session.getQuantity() < 1
                ? 1
                : session.getQuantity();
        completeCurrentItem(session, menu);

        if (startNextPendingMenu(session)) {
            session.setStatus(OrderStatus.CONFIRMING);
            saveOrderSessionState(session);
            return build(sid, intent, session,
                    String.format("%s %d개 담았습니다. 다음 메뉴는 %s %d개 맞으시죠? 확인해 주세요.",
                            completedMenu, completedQuantity, session.getMenu(), session.getQuantity()),
                    List.of("네", "아니요"),
                    List.of());
        }

        session.setStatus(OrderStatus.DONE);
        saveOrderSessionState(session);
        return build(sid, intent, session,
                String.format("주문 완료되었습니다. %s %d개 나올게요!", completedMenu, completedQuantity),
                List.of(),
                List.of());
    }

    private boolean startNextPendingMenu(OrderSession session) {
        OrderSession.PendingMenuItem pendingMenuItem = pendingMenuItems(session).pollFirst();
        if (pendingMenuItem == null) {
            return false;
        }

        session.resetCurrentItem();
        session.setMenuId(pendingMenuItem.getMenuId());
        session.setMenu(pendingMenuItem.getMenu());
        session.setQuantity(pendingMenuItem.getQuantity());
        session.setPendingOptionText(pendingMenuItem.getPendingOptionText());
        session.setSelectedOptionItemIds(pendingMenuItem.getSelectedOptionItemIds() == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(pendingMenuItem.getSelectedOptionItemIds()));
        return true;
    }

    private Deque<OrderSession.PendingMenuItem> pendingMenuItems(OrderSession session) {
        if (session.getPendingMenuItems() == null) {
            session.setPendingMenuItems(new ArrayDeque<>());
        }
        return session.getPendingMenuItems();
    }

    private int menuPrice(MenuCacheResponse.MenuInfo menu) {
        return menu.price() == null ? 0 : menu.price();
    }

    private void persistCompletedItem(OrderSession session, MenuCacheResponse.MenuInfo menuInfo, int unitPrice) {
        OrderSession persistentSession = ensurePersistentOrderSession(session);
        Menu menu = menuRepository.getReferenceById(menuInfo.menuId());
        OrderMenu orderMenu = orderMenuRepository.save(OrderMenu.builder()
                .orderSession(persistentSession)
                .menu(menu)
                .quantity(session.getQuantity())
                .priceWithOption(unitPrice)
                .build());

        selectedOptionIds(session).forEach(optionItemId -> {
            MenuOptionItem menuOptionItem = menuOptionItemRepository.getReferenceById(optionItemId);
            orderMenuOptionRepository.save(OrderMenuOption.builder()
                    .orderMenu(orderMenu)
                    .menuOptionItem(menuOptionItem)
                    .quantity(1)
                    .build());
        });
    }

    private OrderSession ensurePersistentOrderSession(OrderSession session) {
        LocalDateTime now = LocalDateTime.now();
        if (session.getId() == null) {
            session.setCreatedAt(now);
            if (session.getRestaurantId() != null) {
                Store store = storeRepository.getReferenceById(session.getRestaurantId());
                session.setStore(store);
            }
        }
        session.setUpdatedAt(now);
        return orderSessionRepository.save(session);
    }

    private void saveOrderSessionState(OrderSession session) {
        if (session.getId() == null) {
            return;
        }
        session.setUpdatedAt(LocalDateTime.now());
        orderSessionRepository.save(session);
    }

    private int optionExtraPrice(OrderSession session, MenuCacheResponse.MenuInfo menu) {
        return optionExtraPrice(selectedOptionIds(session), menu);
    }

    private int optionExtraPrice(Set<Long> selectedOptionIds, MenuCacheResponse.MenuInfo menu) {
        return emptyIfNull(menu.optionGroups()).stream()
                .flatMap(group -> emptyIfNull(group.optionItems()).stream())
                .filter(item -> selectedOptionIds.contains(item.optionItemId()))
                .map(MenuCacheResponse.OptionItemInfo::extraPrice)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private int accumulatedTotalPrice(OrderSession session) {
        return session.getAccumulatedTotalPrice() == null ? 0 : session.getAccumulatedTotalPrice();
    }
}
