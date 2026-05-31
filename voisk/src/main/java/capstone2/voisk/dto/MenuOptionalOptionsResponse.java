package capstone2.voisk.dto;

import java.util.List;

public record MenuOptionalOptionsResponse(
        Long menuId,
        String menuName,
        List<OptionGroupInfo> optionGroups
) {

    public record OptionGroupInfo(
            Long optionGroupId,
            String optionGroupName,
            List<OptionItemInfo> optionItems
    ) {
    }

    public record OptionItemInfo(
            Long optionItemId,
            String optionItemName,
            Boolean defaultSelected,
            Integer defaultQuantity,
            Integer extraPrice
    ) {
    }
}
