package capstone2.voisk;

import capstone2.voisk.dto.OrderRequest;
import capstone2.voisk.dto.OrderResponse;
import capstone2.voisk.dto.SlotExtractionResult;
import capstone2.voisk.entity.OrderSession;
import capstone2.voisk.entity.OrderStatus;
import capstone2.voisk.repository.OrderSessionRepository;
import capstone2.voisk.service.LlmSlotFillerService;
import capstone2.voisk.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * 주문 전체 플로우 통합 시나리오 테스트.
 *
 * LlmSlotFillerService는 mock으로 대체하여 의도한 추출 결과를 주입하고,
 * OrderService의 상태 전이·응답 메시지·슬롯 관리 로직을 검증한다.
 *
 * 실행: ./gradlew test --tests "capstone2.voisk.OrderFlowTest"
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderFlowTest {

    @Mock
    private OrderSessionRepository sessionRepository;

    @Mock
    private LlmSlotFillerService llmSlotFillerService;

    @InjectMocks
    private OrderService orderService;

    private static final String SID = "test-session";
    private OrderSession session;

    @BeforeEach
    void setUp() {
        session = new OrderSession();
        // ID setting could be needed if id field was string. 
        // OrderSession uses Long id, no SID string is used in constructor.
        when(sessionRepository.findById(SID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(OrderSession.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── 시나리오 1: 정상 주문 — 메뉴·수량 순차 입력 후 확인 ──────────────────
    @Test
    void 정상_주문_단계별_입력() {
        // 1턴: 메뉴만 선택
        OrderResponse r1 = send("일반 메뉴 주세요", slot("ORDER", "일반 메뉴", null));
        assertThat(r1.getResponse()).contains("몇 개");
        assertThat(r1.getSlots().getMenu()).isEqualTo("일반 메뉴");
        assertThat(r1.getSlots().getQuantity()).isNull();
        assertThat(r1.isSlotsComplete()).isFalse();

        // 2턴: 수량 입력
        OrderResponse r2 = send("2개요", slot("ORDER", null, 2));
        assertThat(r2.getResponse()).contains("일반 메뉴").contains("2").contains("맞으시죠");
        assertThat(r2.isSlotsComplete()).isTrue();

        // 3턴: 확인
        OrderResponse r3 = send("네", slot("CONFIRM", null, null));
        assertThat(r3.getResponse()).contains("주문 완료");
        assertThat(session.getStatus()).isEqualTo(OrderStatus.DONE);
    }

    // ── 시나리오 2: 메뉴·수량 한 번에 입력 ──────────────────────────────────
    @Test
    void 메뉴와_수량_동시_입력() {
        // 1턴: 메뉴+수량 한번에
        OrderResponse r1 = send("특식 3개 주세요", slot("ORDER", "특식 메뉴", 3));
        assertThat(r1.getResponse()).contains("특식 메뉴").contains("3").contains("맞으시죠");
        assertThat(r1.isSlotsComplete()).isTrue();

        // 2턴: 확인
        OrderResponse r2 = send("네", slot("CONFIRM", null, null));
        assertThat(r2.getResponse()).contains("주문 완료");
        assertThat(session.getStatus()).isEqualTo(OrderStatus.DONE);
    }

    // ── 시나리오 3: 주문 진행 중 취소 ────────────────────────────────────────
    @Test
    void 주문_중_취소() {
        // 1턴: 메뉴 선택
        send("일반 메뉴", slot("ORDER", "일반 메뉴", null));

        // 2턴: 취소
        OrderResponse r2 = send("취소", slot("CANCEL", null, null));
        assertThat(r2.getResponse()).contains("취소");
        assertThat(session.getMenu()).isNull();
        assertThat(session.getQuantity()).isNull();
        assertThat(session.getStatus()).isEqualTo(OrderStatus.ORDERING);
    }

    // ── 시나리오 4: 확인 단계에서 거부 → 수량 재입력 후 완료 ─────────────────
    @Test
    void 확인_단계_거부_후_수량_수정() {
        // 1턴: 메뉴+수량
        send("특식 2개", slot("ORDER", "특식 메뉴", 2));

        // 2턴: 확인 거부 → 메뉴 유지, 수량만 초기화
        OrderResponse r2 = send("아니요", slot("CANCEL", null, null));
        assertThat(r2.getResponse()).contains("특식 메뉴").contains("수량을 다시");
        assertThat(session.getMenu()).isEqualTo("특식 메뉴");
        assertThat(session.getQuantity()).isNull();
        assertThat(session.getStatus()).isEqualTo(OrderStatus.ORDERING);

        // 3턴: 새 수량 입력
        OrderResponse r3 = send("3개", slot("ORDER", null, 3));
        assertThat(r3.getResponse()).contains("특식 메뉴").contains("3").contains("맞으시죠");

        // 4턴: 최종 확인
        OrderResponse r4 = send("네", slot("CONFIRM", null, null));
        assertThat(r4.getResponse()).contains("주문 완료");
        assertThat(session.getQuantity()).isEqualTo(3);
    }

    // ── 시나리오 5: UNKNOWN 발화 처리 — 세션 유지 후 재질문 ──────────────────
    @Test
    void 알_수_없는_발화_세션_유지() {
        // 1턴: 메뉴 선택
        send("일반 메뉴", slot("ORDER", "일반 메뉴", null));

        // 2턴: 알 수 없는 발화
        OrderResponse r2 = send("으음...", slot("UNKNOWN", null, null));
        assertThat(r2.getResponse()).contains("잘 이해하지 못했습니다");
        assertThat(r2.getResponse()).contains("몇 개"); // 현재 단계 맞는 재질문
        assertThat(session.getMenu()).isEqualTo("일반 메뉴"); // 세션 그대로 유지

        // 3턴: 수량 재입력
        OrderResponse r3 = send("2개", slot("ORDER", null, 2));
        assertThat(r3.getResponse()).contains("일반 메뉴").contains("2");
    }

    // ── 시나리오 6: 잘못된 수량(0 이하) 입력 후 재입력 ──────────────────────
    @Test
    void 잘못된_수량_입력_후_재입력() {
        // 1턴: 메뉴 선택
        send("일반 메뉴", slot("ORDER", "일반 메뉴", null));

        // 2턴: 0개 입력
        OrderResponse r2 = send("0개", slot("ORDER", null, 0));
        assertThat(r2.getResponse()).contains("1개 이상");
        assertThat(session.getQuantity()).isNull(); // 수량 저장 안 됨

        // 3턴: 정상 수량 입력
        OrderResponse r3 = send("1개", slot("ORDER", null, 1));
        assertThat(r3.getResponse()).contains("일반 메뉴").contains("1");
        assertThat(session.getQuantity()).isEqualTo(1);
    }

    // ── 시나리오 7: DONE 후 새 주문 시작 ─────────────────────────────────────
    @Test
    void 주문_완료_후_새_주문_시작() {
        // DONE 상태로 세션 사전 설정
        session.setMenu("일반 메뉴");
        session.setQuantity(2);
        session.setStatus(OrderStatus.DONE);

        // 새 발화 → 자동 reset 후 주문 시작
        OrderResponse r1 = send("특식 주세요", slot("ORDER", "특식 메뉴", null));
        assertThat(r1.getSlots().getMenu()).isEqualTo("특식 메뉴");
        assertThat(session.getStatus()).isEqualTo(OrderStatus.ORDERING);
    }

    // ── 시나리오 8: 존재하지 않는 메뉴명 무시 ───────────────────────────────
    @Test
    void 유효하지_않은_메뉴명_무시() {
        // LLM이 잘못된 메뉴명 반환
        OrderResponse r1 = send("특별한 거 주세요", slot("ORDER", "특별 메뉴", null));
        assertThat(session.getMenu()).isNull(); // 저장 안 됨
        assertThat(r1.getResponse()).contains("일반 메뉴와 특식 메뉴"); // 메뉴 재질문
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private OrderResponse send(String input, SlotExtractionResult extractResult) {
        doReturn(extractResult).when(llmSlotFillerService).extract(any(), any());
        OrderRequest req = new OrderRequest();
        req.setSessionId(SID);
        req.setInput(input);
        return orderService.process(req);
    }

    private SlotExtractionResult slot(String intent, String menu, Integer qty) {
        return new SlotExtractionResult(intent, menu, qty, null);
    }
}
