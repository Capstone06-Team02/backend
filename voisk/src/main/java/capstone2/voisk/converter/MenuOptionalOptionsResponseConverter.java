package capstone2.voisk.converter;

import capstone2.voisk.dto.MenuOptionalOptionsResponse;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.entity.MenuOptionGroup;
import capstone2.voisk.entity.MenuOptionItem;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

@Component
public class MenuOptionalOptionsResponseConverter {

    public MenuOptionalOptionsResponse toResponse(Menu menu, List<MenuOptionGroup> optionGroups) {
        return new MenuOptionalOptionsResponse(
                menu.getMenuId(),
                menu.getName(),
                emptyIfNull(optionGroups).stream()
                        .sorted(groupComparator())
                        .map(this::toOptionGroupInfo)
                        .filter(group -> !group.optionItems().isEmpty())
                        .toList()
        );
    }

    private MenuOptionalOptionsResponse.OptionGroupInfo toOptionGroupInfo(MenuOptionGroup optionGroup) {
        return new MenuOptionalOptionsResponse.OptionGroupInfo(
                optionGroup.getOptionGroupTemplate().getName(),
                emptyIfNull(optionGroup.getOptionItems()).stream()
                        .filter(item -> !Boolean.FALSE.equals(item.getIsAvailable()))
                        .sorted(itemComparator())
                        .map(this::toOptionItemInfo)
                        .toList()
        );
    }

    private MenuOptionalOptionsResponse.OptionItemInfo toOptionItemInfo(MenuOptionItem optionItem) {
        return new MenuOptionalOptionsResponse.OptionItemInfo(
                optionItem.getOptionItemTemplate().getName(),
                isDefaultSelected(optionItem),
                optionItem.getDefaultQuantity(),
                optionItem.getExtraPrice()
        );
    }

    private boolean isDefaultSelected(MenuOptionItem optionItem) {
        return Boolean.TRUE.equals(optionItem.getIsDefault())
                || (optionItem.getDefaultQuantity() != null && optionItem.getDefaultQuantity() > 0);
    }

    private Comparator<MenuOptionGroup> groupComparator() {
        return Comparator.comparing(MenuOptionGroup::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MenuOptionGroup::getId, Comparator.nullsLast(Long::compareTo));
    }

    private Comparator<MenuOptionItem> itemComparator() {
        return Comparator.comparing(MenuOptionItem::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(MenuOptionItem::getId, Comparator.nullsLast(Long::compareTo));
    }

    private <T> Collection<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : values;
    }
}
