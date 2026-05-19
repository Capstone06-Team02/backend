package capstone2.voisk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "주문 요청")
public class OrderRequest {

    @Schema(description = "대화 세션 ID (최초 요청 시 클라이언트가 UUID 생성 후 유지)", example = "550e8400-e29b-41d4-a716-446655440000")
    private String sessionId;

    @Schema(description = "식당 ID. 생략하면 가장 최근 캐싱된 식당 메뉴 정보를 사용합니다.", example = "1")
    private Long restaurantId;

    @Schema(description = "사용자 음성 인식 텍스트", example = "슈크림 라떼 2개 주세요")
    private String input;
}
