package capstone2.voisk.converter;

import capstone2.voisk.dto.MenuCacheResponse;
import capstone2.voisk.entity.Category;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.entity.MenuOptionGroup;
import capstone2.voisk.entity.MenuOptionItem;
import capstone2.voisk.entity.OptionGroupTemplateAlias;
import capstone2.voisk.entity.OptionItemTemplateAlias;
import capstone2.voisk.entity.Store;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Component
public class MenuCacheResponseConverter {

    public MenuCacheResponse toResponse(Store store, List<Menu> menus) {
        List<MenuCacheResponse.MenuInfo> menuInfos = menus.stream()
                .map(this::toMenuInfo)
                .toList();

        return new MenuCacheResponse(
                store.getId(),
                store.getName(),
                Instant.now(),
                menuInfos.size(),
                menuInfos
        );
    }

    private MenuCacheResponse.MenuInfo toMenuInfo(Menu menu) {
        return new MenuCacheResponse.MenuInfo(
                menu.getMenuId(),
                menu.getName(),
                menu.getPrice(),
                menu.getDescription(),
                menu.getIsAvailable(),
                toCategoryInfo(menu.getCategory()),
                emptyIfNull(menu.getMenuOptionGroups()).stream()
                        .sorted((left, right) -> compareSortOrder(left.getSortOrder(), right.getSortOrder()))
                        .map(this::toOptionGroupInfo)
                        .toList()
        );
    }

    private MenuCacheResponse.CategoryInfo toCategoryInfo(Category category) {
        if (category == null) {
            return null;
        }
        return new MenuCacheResponse.CategoryInfo(
                category.getCategoryId(),
                category.getName(),
                category.getDepth()
        );
    }

    private MenuCacheResponse.OptionGroupInfo toOptionGroupInfo(MenuOptionGroup optionGroup) {
        MenuOptionItem parentOptionItem = optionGroup.getParentMenuOptionItem();
        return new MenuCacheResponse.OptionGroupInfo(
                optionGroup.getId(),
                parentOptionItem == null ? null : parentOptionItem.getId(),
                optionGroup.getOptionGroupTemplate().getName(),
                emptyIfNull(optionGroup.getOptionGroupTemplate().getAliases()).stream()
                        .map(OptionGroupTemplateAlias::getAlias)
                        .toList(),
                optionGroup.getIsRequired(),
                optionGroup.getMinSelect(),
                optionGroup.getMaxSelect(),
                optionGroup.getIsAvailable(),
                emptyIfNull(optionGroup.getOptionItems()).stream()
                        .sorted((left, right) -> compareSortOrder(left.getSortOrder(), right.getSortOrder()))
                        .map(this::toOptionItemInfo)
                        .toList()
        );
    }

    private MenuCacheResponse.OptionItemInfo toOptionItemInfo(MenuOptionItem optionItem) {
        return new MenuCacheResponse.OptionItemInfo(
                optionItem.getId(),
                optionItem.getOptionItemTemplate().getName(),
                emptyIfNull(optionItem.getOptionItemTemplate().getAliases()).stream()
                        .map(OptionItemTemplateAlias::getAlias)
                        .toList(),
                optionItem.getExtraPrice(),
                optionItem.getIsAvailable(),
                optionItem.getDefaultQuantity(),
                optionItem.getMaxQuantity(),
                optionItem.getIsDefault()
        );
    }

    private <T> Collection<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : values;
    }

    private int compareSortOrder(Integer left, Integer right) {
        int leftValue = left == null ? Integer.MAX_VALUE : left;
        int rightValue = right == null ? Integer.MAX_VALUE : right;
        return Integer.compare(leftValue, rightValue);
    }
}
