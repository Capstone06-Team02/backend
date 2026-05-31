package capstone2.voisk.converter;

import capstone2.voisk.dto.OptionSlot;
import capstone2.voisk.dto.OrderResponse;
import capstone2.voisk.entity.OrderSession;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderResponseConverter {

    public OrderResponse toResponse(
            String sessionId,
            String intent,
            OrderSession session,
            String message,
            List<String> quickReplies,
            List<OptionSlot> nextOptionSlots,
            List<OrderResponse.OrderItemSlot> orderItems,
            boolean slotsComplete,
            OrderResponse.PriceInfo priceInfo
    ) {
        return OrderResponse.builder()
                .sessionId(sessionId)
                .intent(intent)
                .response(message)
                .slots(OrderResponse.SlotInfo.builder()
                        .items(orderItems)
                        .build())
                .price(priceInfo)
                .slotsComplete(slotsComplete)
                .quickReplies(quickReplies)
                .build();
    }

    public OrderResponse.PriceInfo toPriceInfo(
            Integer menuPrice,
            Integer optionExtraPrice,
            Integer unitPrice,
            Integer totalPrice
    ) {
        return OrderResponse.PriceInfo.builder()
                .menuPrice(menuPrice)
                .optionExtraPrice(optionExtraPrice)
                .unitPrice(unitPrice)
                .totalPrice(totalPrice)
                .build();
    }
}
