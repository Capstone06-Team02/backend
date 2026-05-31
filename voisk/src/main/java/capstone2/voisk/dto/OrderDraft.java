package capstone2.voisk.dto;

import java.util.List;

public record OrderDraft(
        List<Item> items
) {

    public record Item(
            Long menuId,
            String menuName,
            Integer quantity,
            List<OptionValue> requiredOptions,
            List<OptionValue> optionalOptions,
            String sourceText
    ) {
    }

    public record OptionValue(
            Long optionGroupId,
            String optionGroupName,
            Boolean required,
            Long selectedOptionItemId,
            String selectedOptionItemName,
            List<OptionCandidate> candidates
    ) {
    }

    public record OptionCandidate(
            Long optionItemId,
            String name,
            List<String> aliases
    ) {
    }
}
