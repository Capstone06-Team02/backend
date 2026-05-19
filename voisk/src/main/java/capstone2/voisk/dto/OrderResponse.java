package capstone2.voisk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "주문 응답")
public class OrderResponse {

    @Schema(description = "현재 대화 세션 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private String sessionId;

    @Schema(description = "분류된 사용자 의도", allowableValues = {"ORDER", "CONFIRM", "CANCEL", "UNKNOWN"})
    private String intent;

    @Schema(description = "봇 응답 메시지", example = "일반 메뉴 2개 맞으시죠? 확인해 주세요.")
    private String response;

    @Schema(description = "현재까지 수집된 슬롯 정보")
    private SlotInfo slots;

    @Schema(description = "메뉴·수량이 모두 채워졌는지 여부")
    private boolean slotsComplete;

    @Schema(description = "클라이언트에 표시할 빠른 답변 선택지")
    private List<String> quickReplies;

    @Data
    @Builder
    @Schema(description = "주문 슬롯 정보")
    public static class SlotInfo {

        @Schema(description = "선택된 메뉴", allowableValues = {"일반 메뉴", "특식 메뉴"}, nullable = true)
        private String menu;

        @Schema(description = "주문 수량", nullable = true, example = "2")
        private Integer quantity;

        @Schema(description = "메뉴 확정 후 채워야 하는 옵션 슬롯 목록")
        private List<OptionSlot> optionSlots;
    }
}
