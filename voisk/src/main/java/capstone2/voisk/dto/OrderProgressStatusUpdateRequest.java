package capstone2.voisk.dto;

import capstone2.voisk.entity.OrderProgressStatus;

public record OrderProgressStatusUpdateRequest(
        OrderProgressStatus status
) {
}
