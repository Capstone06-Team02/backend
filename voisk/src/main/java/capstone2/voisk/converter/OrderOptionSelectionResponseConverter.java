package capstone2.voisk.converter;

import capstone2.voisk.dto.OrderOptionSelectionResponse;
import capstone2.voisk.entity.MenuOptionGroup;
import capstone2.voisk.entity.MenuOptionItem;
import org.springframework.stereotype.Component;

@Component
public class OrderOptionSelectionResponseConverter {

    public OrderOptionSelectionResponse toResponse(
            String sessionId,
            Long menuId,
            MenuOptionGroup optionGroup,
            MenuOptionItem selectedOptionItem
    ) {
        return new OrderOptionSelectionResponse(
                sessionId,
                menuId,
                optionGroup.getId(),
                optionGroup.getOptionGroupTemplate().getName(),
                selectedOptionItem.getId(),
                selectedOptionItem.getOptionItemTemplate().getName(),
                selectedOptionItem.getExtraPrice()
        );
    }
}
