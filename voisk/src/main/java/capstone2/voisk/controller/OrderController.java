package capstone2.voisk.controller;

import capstone2.voisk.dto.MenuCacheResponse;
import capstone2.voisk.dto.CartMenuNamesResponse;
import capstone2.voisk.dto.CartOrderResponse;
import capstone2.voisk.dto.MenuDescriptionResponse;
import capstone2.voisk.dto.MenuOptionalOptionsResponse;
import capstone2.voisk.dto.OptionGroupDescriptionResponse;
import capstone2.voisk.dto.OrderOptionSelectionRequest;
import capstone2.voisk.dto.OrderOptionSelectionResponse;
import capstone2.voisk.dto.OrderProgressStatusEventResponse;
import capstone2.voisk.dto.OrderProgressStatusUpdateRequest;
import capstone2.voisk.dto.OrderRequest;
import capstone2.voisk.dto.OrderResponse;
import capstone2.voisk.dto.OwnerOrderEventResponse;
import capstone2.voisk.dto.RequiredOptionSummaryRequest;
import capstone2.voisk.dto.RequiredOptionSummaryResponse;
import capstone2.voisk.dto.SignatureMenuListResponse;
import capstone2.voisk.service.CustomerOrderSseService;
import capstone2.voisk.service.OwnerOrderSseService;
import capstone2.voisk.service.OrderOptionSelectionService;
import capstone2.voisk.service.OrderService;
import capstone2.voisk.service.RequiredOptionSummaryService;
import capstone2.voisk.service.SignatureMenuService;
import capstone2.voisk.service.StoreMenuCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Tag(name = "주문", description = "음성 키오스크 주문 API")
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final OrderOptionSelectionService orderOptionSelectionService;
    private final RequiredOptionSummaryService requiredOptionSummaryService;
    private final StoreMenuCacheService storeMenuCacheService;
    private final SignatureMenuService signatureMenuService;
    private final OwnerOrderSseService ownerOrderSseService;
    private final CustomerOrderSseService customerOrderSseService;

    @Operation(
            summary = "주문 대화 처리",
            description = "사용자의 발화를 받아 메뉴와 옵션을 인식하고 주문 세션 상태를 갱신합니다. 최초 요청에서 sessionId를 생략하면 서버가 발급합니다."
    )
    @PostMapping("/speak")
    public ResponseEntity<OrderResponse> speak(@RequestBody OrderRequest request) {
        OrderResponse response = orderService.process(request);
        log.info("speak API slot={}, slotFilling={}", response.getSlots(), !response.isSlotsComplete());
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "카트 메뉴명 목록 조회",
            description = "특정 카트에 현재 담겨 있는 주문 세션의 메뉴명 목록을 조회합니다."
    )
    @GetMapping("/carts/{cartId}/menus")
    public ResponseEntity<CartMenuNamesResponse> getCartMenuNames(@PathVariable String cartId) {
        return ResponseEntity.ok(orderService.getCartMenuNames(cartId));
    }

    @Operation(
            summary = "카트 주문 최종 확정",
            description = "특정 카트 ID에 담긴 최종 주문을 확정합니다. 확정된 주문은 사장님 주문 SSE로 전송됩니다."
    )
    @PostMapping("/carts/{cartId}/confirm")
    public ResponseEntity<CartOrderResponse> confirmCartOrder(@PathVariable String cartId) {
        return ResponseEntity.ok(orderService.confirmCartOrder(cartId));
    }

    @Operation(
            summary = "주문 제조 상태 변경",
            description = "확정된 카트 주문의 제조 상태를 변경하고 사장님/손님 SSE 구독자에게 상태 변경 알림을 전송합니다."
    )
    @PatchMapping("/carts/{cartId}/status")
    public ResponseEntity<OrderProgressStatusEventResponse> updateOrderProgressStatus(
            @PathVariable String cartId,
            @RequestBody OrderProgressStatusUpdateRequest request
    ) {
        return ResponseEntity.ok(orderService.updateOrderProgressStatus(
                cartId,
                request == null ? null : request.status()
        ));
    }

    @Operation(
            summary = "손님 주문 상태 SSE 구독",
            description = "특정 카트 주문의 제조 시작, 제조 완료 등 상태 변경 알림을 손님 화면으로 실시간 전송합니다."
    )
    @GetMapping(value = "/carts/{cartId}/status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCustomerOrderStatus(@PathVariable String cartId) {
        return customerOrderSseService.subscribe(cartId);
    }

    @Operation(
            summary = "사장님 주문 SSE 구독",
            description = "특정 매장의 새 주문 접수와 주문 제조 상태 변경 알림을 사장님 화면으로 실시간 전송합니다."
    )
    @GetMapping(value = "/stores/{storeId}/orders/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOwnerOrders(@PathVariable Long storeId) {
        return ownerOrderSseService.subscribe(storeId);
    }

    @Operation(
            summary = "사장님 확정 주문 목록 조회",
            description = "특정 매장의 확정된 주문 목록을 조회합니다. 사장님 화면에서 SSE 연결을 열기 전에 초기 주문 목록을 불러올 때 사용합니다."
    )
    @GetMapping("/stores/{storeId}/orders")
    public ResponseEntity<List<OwnerOrderEventResponse>> getOwnerOrders(@PathVariable Long storeId) {
        return ResponseEntity.ok(orderService.getConfirmedOwnerOrders(storeId));
    }

    @Operation(
            summary = "카트 주문 세션 삭제",
            description = "특정 카트에서 지정한 주문 세션을 제거하고, 남아 있는 카트 메뉴명 목록을 반환합니다."
    )
    @DeleteMapping("/carts/{cartId}/sessions/{sessionId}")
    public ResponseEntity<CartMenuNamesResponse> removeCartSession(
            @PathVariable String cartId,
            @PathVariable String sessionId
    ) {
        return ResponseEntity.ok(orderService.removeCartSession(cartId, sessionId));
    }

    @Operation(
            summary = "메뉴 선택 옵션 조회",
            description = "메뉴 ID로 비필수 선택 옵션 그룹과 옵션 아이템을 조회합니다."
    )
    @GetMapping("/menus/{menuId}/optional-options")
    public ResponseEntity<MenuOptionalOptionsResponse> getOptionalOptions(@PathVariable Long menuId) {
        return ResponseEntity.ok(orderService.getOptionalOptions(menuId));
    }

    @Operation(
            summary = "메뉴 설명 조회",
            description = "메뉴 ID로 메뉴명과 메뉴 설명만 조회합니다."
    )
    @GetMapping("/menus/{menuId}/description")
    public ResponseEntity<MenuDescriptionResponse> getMenuDescription(@PathVariable Long menuId) {
        return ResponseEntity.ok(orderService.getMenuDescription(menuId));
    }

    @Operation(
            summary = "옵션 그룹 설명 조회",
            description = "옵션 그룹 ID로 옵션 그룹명과 옵션 그룹 설명만 조회합니다."
    )
    @GetMapping("/option-groups/{optionGroupId}/description")
    public ResponseEntity<OptionGroupDescriptionResponse> getOptionGroupDescription(@PathVariable Long optionGroupId) {
        return ResponseEntity.ok(orderService.getOptionGroupDescription(optionGroupId));
    }

    @Operation(
            summary = "주문 세션 옵션 변경",
            description = "활성 주문 세션에서 특정 메뉴의 필수 또는 선택 옵션을 지정한 옵션 아이템으로 변경합니다."
    )
    @PostMapping("/option-selection")
    public ResponseEntity<OrderOptionSelectionResponse> selectOption(@RequestBody OrderOptionSelectionRequest request) {
        return ResponseEntity.ok(orderOptionSelectionService.selectOption(request));
    }

    @Operation(
            summary = "선택된 필수 옵션 요약",
            description = "활성 주문 세션에서 특정 메뉴에 선택된 필수 옵션과 가격을 자연어 문장으로 요약합니다."
    )
    @PostMapping("/required-option-summary")
    public ResponseEntity<RequiredOptionSummaryResponse> summarizeRequiredOptions(
            @RequestBody RequiredOptionSummaryRequest request
    ) {
        return ResponseEntity.ok(requiredOptionSummaryService.summarize(request));
    }

    @Operation(
            summary = "매장 메뉴 정보 캐싱",
            description = "매장 ID를 받아 해당 매장의 메뉴, 카테고리, 옵션 정보를 백엔드 메모리 캐시에 저장합니다."
    )
    @PostMapping({"/restaurants/{restaurantId}/menus/cache", "/stores/{restaurantId}/menus/cache"})
    public ResponseEntity<MenuCacheResponse> cacheMenus(@PathVariable Long restaurantId) {
        return ResponseEntity.ok(storeMenuCacheService.cacheMenus(restaurantId));
    }

    @Operation(
            summary = "매장 시그니처 메뉴 목록 조회",
            description = "매장 ID를 받아 해당 매장의 시그니처 메뉴 목록을 반환합니다."
    )
    @GetMapping({"/restaurants/{restaurantId}/menus/signatures", "/stores/{restaurantId}/menus/signatures"})
    public ResponseEntity<SignatureMenuListResponse> getSignatureMenus(@PathVariable Long restaurantId) {
        return ResponseEntity.ok(signatureMenuService.getSignatureMenus(restaurantId));
    }
}
