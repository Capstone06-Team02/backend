package capstone2.voisk.dto;

import capstone2.voisk.entity.OrderProgressStatus;

import java.time.LocalDateTime;
import java.util.List;

public record OwnerOrderEventResponse(
        String eventType,
        String cartId,
        Long storeId,
        String storeName,
        LocalDateTime orderedAt,
        OrderProgressStatus progressStatus,
        Integer totalPrice,
        List<OrderItem> items
) {

    public record OrderItem(
            Long orderMenuId,
            Long menuId,
            String menuName,
            Integer quantity,
            Integer menuPrice,
            Integer optionExtraPrice,
            Integer unitPrice,
            Integer totalPrice,
            List<OptionItem> options
    ) {
    }

    public record OptionItem(
            Long optionItemId,
            String optionGroupName,
            String optionName,
            Integer extraPrice,
            Integer quantity
    ) {
    }
}
