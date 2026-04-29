package capstone2.voisk.controller;

import capstone2.voisk.dto.OrderRequest;
import capstone2.voisk.dto.OrderResponse;
import capstone2.voisk.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Order", description = "음성 키오스크 주문 API")
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(
        summary = "주문 처리",
        description = "사용자의 음성 인식 텍스트를 받아 주문 대화를 한 턴 진행합니다. " +
                      "sessionId로 대화 상태를 유지하며, 최초 요청 시 sessionId를 생략하면 서버가 발급합니다."
    )
    @PostMapping("/speak")
    public ResponseEntity<OrderResponse> speak(@RequestBody OrderRequest request) {
        return ResponseEntity.ok(orderService.process(request));
    }
}
