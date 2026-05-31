package capstone2.voisk.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    @Schema(description = "봇 응답 메시지")
    private String response;

    @Schema(description = "현재 주문 슬롯 상태")
    private SlotInfo slots;

    @Schema(description = "현재 주문 가격 정보")
    private PriceInfo price;

    @Schema(description = "인식된 모든 메뉴의 필수 옵션이 채워졌는지 여부")
    private boolean slotsComplete;

    @Schema(description = "클라이언트에 표시할 빠른 선택지")
    private List<String> quickReplies;

    @Data
    @Builder
    @Schema(description = "주문 슬롯 정보")
    public static class SlotInfo {

        @Schema(description = "메뉴별 전체 슬롯 상태")
        private List<OrderItemSlot> items;

        @JsonIgnore
        public String getMenu() {
            return firstItem() == null ? null : firstItem().getMenu();
        }

        @JsonIgnore
        public Integer getQuantity() {
            return firstItem() == null ? null : firstItem().getQuantity();
        }

        @JsonIgnore
        public List<OptionSlot> getOptionSlots() {
            return firstItem() == null ? List.of() : firstItem().getOptionSlots();
        }

        private OrderItemSlot firstItem() {
            return items == null || items.isEmpty() ? null : items.get(0);
        }
    }

    @Data
    @Builder
    @Schema(description = "메뉴별 전체 슬롯 상태")
    public static class OrderItemSlot {

        @Schema(description = "메뉴명")
        private String menu;

        @Schema(description = "주문 수량")
        private Integer quantity;

        @Schema(description = "메뉴 기본 가격")
        private Integer menuPrice;

        @Schema(description = "선택 옵션 추가 금액")
        private Integer optionExtraPrice;

        @Schema(description = "메뉴 1개당 최종 가격")
        private Integer unitPrice;

        @Schema(description = "수량까지 반영한 메뉴별 총 금액")
        private Integer totalPrice;

        @Schema(description = "메뉴의 전체 옵션 상태")
        private List<OptionSlot> optionSlots;
    }

    @Data
    @Builder
    @Schema(description = "주문 가격 정보")
    public static class PriceInfo {

        @Schema(description = "메뉴 기본 금액 합계", nullable = true)
        private Integer menuPrice;

        @Schema(description = "선택 옵션 추가 금액 합계", nullable = true)
        private Integer optionExtraPrice;

        @Schema(description = "메뉴 1개당 최종 가격. 여러 메뉴가 있으면 null", nullable = true)
        private Integer unitPrice;

        @Schema(description = "수량까지 반영한 총 주문 금액", nullable = true)
        private Integer totalPrice;
    }
}
