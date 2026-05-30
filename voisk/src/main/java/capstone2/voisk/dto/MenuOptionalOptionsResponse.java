package capstone2.voisk.dto;

import java.util.List;

public record MenuOptionalOptionsResponse(
        Long menuId,
        String menuName,
        List<OptionGroupInfo> optionGroups
) {

    public record OptionGroupInfo(
            String optionGroupName,
            List<OptionItemInfo> optionItems
    ) {
    }

    public record OptionItemInfo(
            String optionItemName,
            Boolean defaultSelected,
            Integer defaultQuantity,
            Integer extraPrice
    ) {
    }
}
