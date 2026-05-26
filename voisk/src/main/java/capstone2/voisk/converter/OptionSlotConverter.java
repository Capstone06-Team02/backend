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

    private <T> Collection<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : values;
    }
}
