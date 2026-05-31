package capstone2.voisk.dto;

import java.util.List;

public record RequiredOptionSummaryResponse(
        Long menuId,
        String menuName,
        List<SelectedRequiredOption> selectedRequiredOptions,
        Integer unitPrice,
        String message
) {

    public record SelectedRequiredOption(
            String optionGroupName,
            String optionItemName
    ) {
    }
}
