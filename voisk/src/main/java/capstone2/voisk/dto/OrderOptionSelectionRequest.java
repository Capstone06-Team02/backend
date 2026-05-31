package capstone2.voisk.dto;

public record OrderOptionSelectionRequest(
        String sessionId,
        Long menuId,
        Long optionGroupId,
        Long optionItemId
) {
}
