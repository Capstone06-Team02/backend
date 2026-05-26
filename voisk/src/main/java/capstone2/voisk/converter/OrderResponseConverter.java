package capstone2.voisk.converter;

import capstone2.voisk.dto.OptionSlot;
import capstone2.voisk.dto.OrderResponse;
import capstone2.voisk.entity.OrderSession;
import capstone2.voisk.entity.OrderStatus;
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
            List<OptionSlot> optionSlots,
            OrderResponse.PriceInfo priceInfo
    ) {
        boolean slotsComplete = session.isSlotsComplete() && session.getStatus() != OrderStatus.OPTION_FILLING;
        return OrderResponse.builder()
                .sessionId(sessionId)
                .intent(intent)
                .response(message)
                .slots(OrderResponse.SlotInfo.builder()
                        .menu(session.getMenu())
                        .quantity(session.getQuantity())
                        .optionSlots(optionSlots)
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
