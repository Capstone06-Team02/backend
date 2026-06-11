package capstone2.voisk.converter;

import capstone2.voisk.dto.MenuCacheResponse;
import capstone2.voisk.dto.OptionSlot;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Component
public class OptionSlotConverter {

    public OptionSlot toOptionSlot(MenuCacheResponse.OptionGroupInfo group, Set<Long> selectedOptionIds) {
        List<MenuCacheResponse.OptionItemInfo> availableItems = emptyIfNull(group.optionItems()).stream()
                .filter(item -> !Boolean.FALSE.equals(item.isAvailable()))
                .toList();
        MenuCacheResponse.OptionItemInfo requiredDefaultOption = requiredDefaultOption(group, availableItems);
        MenuCacheResponse.OptionItemInfo selectedOption = availableItems.stream()
                .filter(item -> selectedOptionIds.contains(item.optionItemId()))
                .findFirst()
                .orElse(null);
        return new OptionSlot(
                group.name(),
                group.isRequired(),
                selectedOption == null ? null : selectedOption.name(),
                selectedOption == null ? null : isDefaultSelected(selectedOption),
                availableItems.stream()
                        .map(item -> new OptionSlot.OptionCandidate(
                                item.name(),
                                item.extraPrice(),
                                isCandidateDefault(group, item, requiredDefaultOption),
                                selectedOptionIds.contains(item.optionItemId())
                        ))
                        .toList()
        );
    }

    private MenuCacheResponse.OptionItemInfo requiredDefaultOption(
            MenuCacheResponse.OptionGroupInfo group,
            List<MenuCacheResponse.OptionItemInfo> availableItems
    ) {
        if (!Boolean.TRUE.equals(group.isRequired()) || availableItems.isEmpty()) {
            return null;
        }
        return availableItems.stream()
                .filter(this::isDefaultSelected)
                .findFirst()
                .orElse(availableItems.get(0));
    }

    private boolean isCandidateDefault(
            MenuCacheResponse.OptionGroupInfo group,
            MenuCacheResponse.OptionItemInfo item,
            MenuCacheResponse.OptionItemInfo requiredDefaultOption
    ) {
        if (!Boolean.TRUE.equals(group.isRequired())) {
            return isDefaultSelected(item);
        }
        return requiredDefaultOption != null
                && item.optionItemId() != null
                && item.optionItemId().equals(requiredDefaultOption.optionItemId());
    }

    private boolean isDefaultSelected(MenuCacheResponse.OptionItemInfo item) {
        return Boolean.TRUE.equals(item.isDefault())
                || (item.defaultQuantity() != null && item.defaultQuantity() > 0);
    }

    private <T> Collection<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : values;
    }
}
