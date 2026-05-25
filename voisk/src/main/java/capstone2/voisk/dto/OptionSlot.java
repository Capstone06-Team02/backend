package capstone2.voisk.dto;

import java.util.List;

public record OptionSlot(
        Long optionGroupId,
        Long parentOptionItemId,
        String name,
        Boolean required,
        Integer minSelect,
        Integer maxSelect,
        List<OptionCandidate> candidates
) {

    public record OptionCandidate(
            Long optionItemId,
            String name,
            Integer extraPrice,
            Boolean available,
            Integer defaultQuantity,
            Integer maxQuantity,
            Boolean defaultSelected,
            boolean selected
    ) {
    }
}
