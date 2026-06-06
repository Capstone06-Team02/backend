package capstone2.voisk.controller;

import capstone2.voisk.dto.MenuCacheResponse;
import capstone2.voisk.dto.MenuDescriptionResponse;
import capstone2.voisk.dto.MenuOptionalOptionsResponse;
import capstone2.voisk.dto.OptionGroupDescriptionResponse;
import capstone2.voisk.dto.OrderOptionSelectionRequest;
import capstone2.voisk.dto.OrderOptionSelectionResponse;
import capstone2.voisk.dto.OrderRequest;
import capstone2.voisk.dto.OrderResponse;
import capstone2.voisk.dto.RequiredOptionSummaryRequest;
import capstone2.voisk.dto.RequiredOptionSummaryResponse;
import capstone2.voisk.service.OrderOptionSelectionService;
import capstone2.voisk.service.OrderService;
import capstone2.voisk.service.RequiredOptionSummaryService;
import capstone2.voisk.service.StoreMenuCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
