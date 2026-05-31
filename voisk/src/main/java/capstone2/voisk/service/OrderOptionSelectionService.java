package capstone2.voisk.service;

import capstone2.voisk.converter.OrderOptionSelectionResponseConverter;
import capstone2.voisk.dto.OrderDraft;
import capstone2.voisk.dto.OrderOptionSelectionRequest;
import capstone2.voisk.dto.OrderOptionSelectionResponse;
import capstone2.voisk.entity.MenuOptionGroup;
import capstone2.voisk.entity.MenuOptionItem;
import capstone2.voisk.entity.OrderSession;
import capstone2.voisk.repository.MenuOptionGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class OrderOptionSelectionService {

    private final OrderService orderService;
    private final MenuOptionGroupRepository menuOptionGroupRepository;
    private final OrderOptionSelectionResponseConverter converter;

    @Transactional(readOnly = true)
    public OrderOptionSelectionResponse selectOption(OrderOptionSelectionRequest request) {
        validateRequest(request);

        OrderSession session = orderService.findActiveSession(request.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("Order session not found. sessionId=" + request.sessionId()));
        MenuOptionGroup optionGroup = menuOptionGroupRepository.findByIdWithItems(request.optionGroupId())
                .orElseThrow(() -> new IllegalArgumentException("Option group not found. optionGroupId=" + request.optionGroupId()));

        validateOptionGroup(request.menuId(), optionGroup);
        MenuOptionItem selectedOptionItem = findSelectedOptionItem(optionGroup, request.optionItemId());

        boolean updated = applySelection(session, request.menuId(), optionGroup, selectedOptionItem);
        if (!updated) {
            throw new IllegalArgumentException("Menu not found in order session. menuId=" + request.menuId());
        }

        return converter.toResponse(request.sessionId(), request.menuId(), optionGroup, selectedOptionItem);
    }

    private void validateRequest(OrderOptionSelectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required.");
        }
        if (request.menuId() == null) {
            throw new IllegalArgumentException("menuId is required.");
        }
        if (request.optionGroupId() == null) {
            throw new IllegalArgumentException("optionGroupId is required.");
        }
        if (request.optionItemId() == null) {
            throw new IllegalArgumentException("optionItemId is required.");
        }
    }

    private void validateOptionGroup(Long menuId, MenuOptionGroup optionGroup) {
        if (optionGroup.getMenu() == null || !Objects.equals(optionGroup.getMenu().getMenuId(), menuId)) {
            throw new IllegalArgumentException("Option group does not belong to menu. menuId=" + menuId);
        }
        if (Boolean.TRUE.equals(optionGroup.getIsRequired())) {
            throw new IllegalArgumentException("Option group is not optional. optionGroupId=" + optionGroup.getId());
        }
        if (Boolean.FALSE.equals(optionGroup.getIsAvailable())) {
            throw new IllegalArgumentException("Option group is not available. optionGroupId=" + optionGroup.getId());
        }
    }

    private MenuOptionItem findSelectedOptionItem(MenuOptionGroup optionGroup, Long optionItemId) {
        return emptyIfNull(optionGroup.getOptionItems()).stream()
                .filter(item -> Objects.equals(item.getId(), optionItemId))
                .findFirst()
                .filter(item -> !Boolean.FALSE.equals(item.getIsAvailable()))
                .orElseThrow(() -> new IllegalArgumentException("Option item is not selectable. optionItemId=" + optionItemId));
    }

    private boolean applySelection(
            OrderSession session,
            Long menuId,
            MenuOptionGroup optionGroup,
            MenuOptionItem selectedOptionItem
    ) {
        if (applySelectionToDraft(session, menuId, optionGroup, selectedOptionItem)) {
            return true;
        }
        if (Objects.equals(session.getMenuId(), menuId)) {
            session.setSelectedOptionItemIds(replaceGroupSelection(
                    session.getSelectedOptionItemIds(),
                    optionGroup,
                    selectedOptionItem.getId()
            ));
            return true;
        }
        return applySelectionToPendingItem(session, menuId, optionGroup, selectedOptionItem);
    }

    private boolean applySelectionToDraft(
            OrderSession session,
            Long menuId,
            MenuOptionGroup optionGroup,
            MenuOptionItem selectedOptionItem
    ) {
        OrderDraft draft = session.getOrderDraft();
        if (draft == null || draft.items() == null || draft.items().isEmpty()) {
            return false;
        }

        AtomicBoolean updated = new AtomicBoolean(false);
        List<OrderDraft.Item> items = draft.items().stream()
                .map(item -> {
                    if (updated.get() || !Objects.equals(item.menuId(), menuId)) {
                        return item;
                    }
                    updated.set(true);
                    return replaceDraftOption(item, optionGroup, selectedOptionItem);
                })
                .toList();

        if (updated.get()) {
            session.setOrderDraft(new OrderDraft(items));
        }
        return updated.get();
    }

    private OrderDraft.Item replaceDraftOption(
            OrderDraft.Item item,
            MenuOptionGroup optionGroup,
            MenuOptionItem selectedOptionItem
    ) {
        OrderDraft.OptionValue selectedOption = toDraftOptionValue(optionGroup, selectedOptionItem);
        List<OrderDraft.OptionValue> requiredOptions = replaceOptionValue(item.requiredOptions(), selectedOption);
        List<OrderDraft.OptionValue> optionalOptions = replaceOptionValue(item.optionalOptions(), selectedOption);

        boolean exists = containsOptionGroup(item.requiredOptions(), optionGroup.getId())
                || containsOptionGroup(item.optionalOptions(), optionGroup.getId());
        if (!exists) {
            optionalOptions = new ArrayList<>(optionalOptions);
            optionalOptions.add(selectedOption);
        }

        return new OrderDraft.Item(
                item.menuId(),
                item.menuName(),
                item.quantity(),
                requiredOptions,
                optionalOptions,
                item.sourceText()
        );
    }

    private List<OrderDraft.OptionValue> replaceOptionValue(
            List<OrderDraft.OptionValue> options,
            OrderDraft.OptionValue selectedOption
    ) {
        return emptyIfNull(options).stream()
                .map(option -> Objects.equals(option.optionGroupId(), selectedOption.optionGroupId())
                        ? selectedOption
                        : option)
                .toList();
    }

    private boolean containsOptionGroup(List<OrderDraft.OptionValue> options, Long optionGroupId) {
        return emptyIfNull(options).stream()
                .anyMatch(option -> Objects.equals(option.optionGroupId(), optionGroupId));
    }

    private OrderDraft.OptionValue toDraftOptionValue(
            MenuOptionGroup optionGroup,
            MenuOptionItem selectedOptionItem
    ) {
        return new OrderDraft.OptionValue(
                optionGroup.getId(),
                optionGroup.getOptionGroupTemplate().getName(),
                optionGroup.getIsRequired(),
                selectedOptionItem.getId(),
                selectedOptionItem.getOptionItemTemplate().getName(),
                emptyIfNull(optionGroup.getOptionItems()).stream()
                        .filter(item -> !Boolean.FALSE.equals(item.getIsAvailable()))
                        .map(item -> new OrderDraft.OptionCandidate(
                                item.getId(),
                                item.getOptionItemTemplate().getName(),
                                List.of()
                        ))
                        .toList()
        );
    }

    private boolean applySelectionToPendingItem(
            OrderSession session,
            Long menuId,
            MenuOptionGroup optionGroup,
            MenuOptionItem selectedOptionItem
    ) {
        for (OrderSession.PendingMenuItem pendingMenuItem : emptyIfNull(session.getPendingMenuItems())) {
            if (!Objects.equals(pendingMenuItem.getMenuId(), menuId)) {
                continue;
            }
            pendingMenuItem.setSelectedOptionItemIds(replaceGroupSelection(
                    pendingMenuItem.getSelectedOptionItemIds(),
                    optionGroup,
                    selectedOptionItem.getId()
            ));
            return true;
        }
        return false;
    }

    private Set<Long> replaceGroupSelection(
            Collection<Long> selectedOptionItemIds,
            MenuOptionGroup optionGroup,
            Long selectedOptionItemId
    ) {
        Set<Long> optionItemIdsInGroup = emptyIfNull(optionGroup.getOptionItems()).stream()
                .map(MenuOptionItem::getId)
                .collect(java.util.stream.Collectors.toSet());

        Set<Long> selected = new LinkedHashSet<>(emptyIfNull(selectedOptionItemIds));
        selected.removeIf(optionItemIdsInGroup::contains);
        selected.add(selectedOptionItemId);
        return selected;
    }

    private <T> Collection<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : values;
    }
}
