package capstone2.voisk.service;

import capstone2.voisk.dto.OrderDraft;
import capstone2.voisk.dto.RequiredOptionSummaryRequest;
import capstone2.voisk.dto.RequiredOptionSummaryResponse;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.entity.MenuOptionGroup;
import capstone2.voisk.entity.MenuOptionItem;
import capstone2.voisk.entity.OrderSession;
import capstone2.voisk.repository.MenuOptionGroupRepository;
import capstone2.voisk.repository.MenuOptionItemRepository;
import capstone2.voisk.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.NumberFormat;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class RequiredOptionSummaryService {

    private final OrderService orderService;
    private final MenuRepository menuRepository;
    private final MenuOptionGroupRepository menuOptionGroupRepository;
    private final MenuOptionItemRepository menuOptionItemRepository;

    @Transactional(readOnly = true)
    public RequiredOptionSummaryResponse summarize(RequiredOptionSummaryRequest request) {
        validateRequest(request);

        OrderSession session = orderService.findActiveSession(request.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("Order session not found. sessionId=" + request.sessionId()));
        Menu menu = menuRepository.findById(request.menuId())
                .orElseThrow(() -> new IllegalArgumentException("Menu not found. menuId=" + request.menuId()));
        Set<Long> selectedOptionItemIds = selectedOptionItemIds(session, request.menuId())
                .orElseThrow(() -> new IllegalArgumentException("Menu not found in order session. menuId=" + request.menuId()));

        List<MenuOptionGroup> requiredGroups = menuOptionGroupRepository.findRequiredGroupsByMenuId(request.menuId());
        List<RequiredOptionSummaryResponse.SelectedRequiredOption> selectedRequiredOptions = requiredGroups.stream()
                .flatMap(group -> selectedRequiredOption(group, selectedOptionItemIds).stream())
                .toList();
        int unitPrice = menuPrice(menu) + optionExtraPrice(selectedOptionItemIds, request.menuId());

        return new RequiredOptionSummaryResponse(
                menu.getMenuId(),
                menu.getName(),
                selectedRequiredOptions,
                unitPrice,
                message(menu.getName(), selectedRequiredOptions, unitPrice)
        );
    }

    private void validateRequest(RequiredOptionSummaryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required.");
        }
        if (request.menuId() == null) {
            throw new IllegalArgumentException("menuId is required.");
        }
    }

    private Optional<Set<Long>> selectedOptionItemIds(OrderSession session, Long menuId) {
        Optional<Set<Long>> draftSelected = selectedOptionItemIdsFromDraft(session.getOrderDraft(), menuId);
        if (draftSelected.isPresent()) {
            return draftSelected;
        }
        if (Objects.equals(session.getMenuId(), menuId)) {
            return Optional.of(new LinkedHashSet<>(emptyIfNull(session.getSelectedOptionItemIds())));
        }
        return emptyIfNull(session.getPendingMenuItems()).stream()
                .filter(item -> Objects.equals(item.getMenuId(), menuId))
                .findFirst()
                .map(item -> new LinkedHashSet<>(emptyIfNull(item.getSelectedOptionItemIds())));
    }

    private Optional<Set<Long>> selectedOptionItemIdsFromDraft(OrderDraft draft, Long menuId) {
        if (draft == null || draft.items() == null || draft.items().isEmpty()) {
            return Optional.empty();
        }
        return draft.items().stream()
                .filter(item -> Objects.equals(item.menuId(), menuId))
                .findFirst()
                .map(item -> {
                    Set<Long> selected = new LinkedHashSet<>();
                    Stream.concat(
                                    emptyIfNull(item.requiredOptions()).stream(),
                                    emptyIfNull(item.optionalOptions()).stream()
                            )
                            .flatMap(option -> selectedOptionItemId(option).stream())
                            .forEach(selected::add);
                    return selected;
                });
    }

    private Optional<Long> selectedOptionItemId(OrderDraft.OptionValue option) {
        if (option.selectedOptionItemId() != null) {
            return Optional.of(option.selectedOptionItemId());
        }
        if (option.selectedOptionItemName() == null || option.selectedOptionItemName().isBlank()) {
            return Optional.empty();
        }

        String selectedName = normalize(option.selectedOptionItemName());
        return emptyIfNull(option.candidates()).stream()
                .filter(candidate -> selectedName.equals(normalize(candidate.name())))
                .map(OrderDraft.OptionCandidate::optionItemId)
                .filter(Objects::nonNull)
                .findFirst();
    }

    private Optional<RequiredOptionSummaryResponse.SelectedRequiredOption> selectedRequiredOption(
            MenuOptionGroup group,
            Set<Long> selectedOptionItemIds
    ) {
        return emptyIfNull(group.getOptionItems()).stream()
                .filter(item -> selectedOptionItemIds.contains(item.getId()))
                .findFirst()
                .map(item -> new RequiredOptionSummaryResponse.SelectedRequiredOption(
                        group.getOptionGroupTemplate().getName(),
                        item.getOptionItemTemplate().getName()
                ));
    }

    private int optionExtraPrice(Set<Long> selectedOptionItemIds, Long menuId) {
        if (selectedOptionItemIds.isEmpty()) {
            return 0;
        }
        return menuOptionItemRepository.findAllById(selectedOptionItemIds).stream()
                .filter(item -> item.getMenuOptionGroup() != null
                        && item.getMenuOptionGroup().getMenu() != null
                        && Objects.equals(item.getMenuOptionGroup().getMenu().getMenuId(), menuId))
                .map(MenuOptionItem::getExtraPrice)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private int menuPrice(Menu menu) {
        return menu.getPrice() == null ? 0 : menu.getPrice();
    }

    private String message(
            String menuName,
            List<RequiredOptionSummaryResponse.SelectedRequiredOption> selectedRequiredOptions,
            int unitPrice
    ) {
        String formattedPrice = NumberFormat.getNumberInstance(Locale.KOREA).format(unitPrice);
        if (selectedRequiredOptions.isEmpty()) {
            return String.format("%s는 아직 선택된 필수 옵션이 없습니다. 가격은 %s원입니다.", menuName, formattedPrice);
        }

        String optionNames = selectedRequiredOptions.stream()
                .map(RequiredOptionSummaryResponse.SelectedRequiredOption::optionItemName)
                .collect(java.util.stream.Collectors.joining(", "));
        return String.format("%s는 %s로 선택되었습니다. 가격은 %s원입니다.", menuName, optionNames, formattedPrice);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }

    private <T> Collection<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : values;
    }
}
