package capstone2.voisk.dto;

import capstone2.voisk.entity.OrderProgressStatus;

import java.time.LocalDateTime;

public record OrderProgressStatusEventResponse(
        String eventType,
        String cartId,
        Long storeId,
        OrderProgressStatus status,
        String message,
        LocalDateTime updatedAt
) {
}
