package capstone2.voisk.dto;

public record OrderOptionSelectionResponse(
        String sessionId,
        Long menuId,
        Long optionGroupId,
        String optionGroupName,
        Long selectedOptionItemId,
        String selectedOptionItemName,
        Integer extraPrice
) {
}
