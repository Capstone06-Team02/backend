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
        MenuCacheResponse.OptionItemInfo selectedOption = emptyIfNull(group.optionItems()).stream()
                .filter(item -> selectedOptionIds.contains(item.optionItemId()))
                .findFirst()
                .orElse(null);
        return new OptionSlot(
                group.name(),
                group.isRequired(),
                selectedOption == null ? null : selectedOption.name(),
                selectedOption == null ? null : isDefaultSelected(selectedOption),
                emptyIfNull(group.optionItems()).stream()
                        .filter(item -> !Boolean.FALSE.equals(item.isAvailable()))
                        .map(item -> new OptionSlot.OptionCandidate(
                                item.name(),
                                item.extraPrice(),
                                isDefaultSelected(item),
                                selectedOptionIds.contains(item.optionItemId())
                        ))
                        .toList()
        );
    }

    private boolean isDefaultSelected(MenuCacheResponse.OptionItemInfo item) {
        return Boolean.TRUE.equals(item.isDefault())
                || (item.defaultQuantity() != null && item.defaultQuantity() > 0);
    }

    private <T> Collection<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : values;
    }
}
