package capstone2.voisk.dto;

import java.time.Instant;
import java.util.List;

public record MenuCacheResponse(
        Long restaurantId,
        String restaurantName,
        Instant cachedAt,
        int menuCount,
        List<MenuInfo> menus
) {

    public record MenuInfo(
            Long menuId,
            String name,
            Integer price,
            String description,
            Boolean isAvailable,
            CategoryInfo category,
            List<OptionGroupInfo> optionGroups
    ) {
    }

    public record CategoryInfo(
            Long categoryId,
            String name,
            Integer depth
    ) {
    }

    public record OptionGroupInfo(
            Long optionGroupId,
            Long parentOptionItemId,
            String name,
            Boolean isRequired,
            Integer minSelect,
            Integer maxSelect,
            Boolean isAvailable,
            List<OptionItemInfo> optionItems
    ) {
    }

    public record OptionItemInfo(
            Long optionItemId,
            String name,
            Integer extraPrice,
            Boolean isAvailable,
            Integer defaultQuantity,
            Integer maxQuantity,
            Boolean isDefault
    ) {
    }
}
