package capstone2.voisk.service;

import capstone2.voisk.dto.OrderRequest;
import capstone2.voisk.dto.OrderResponse;
import capstone2.voisk.dto.SlotExtractionResult;
import capstone2.voisk.entity.OrderSession;
import capstone2.voisk.repository.OrderSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderSessionRepository sessionRepository;
    private final LlmSlotFillerService llmSlotFillerService;

    public static final Map<String, Integer> MENU_PRICE = Map.of(
            "일반 메뉴", 8000,
            "특식 메뉴", 12000
    );

    @Transactional
    public OrderResponse process(OrderRequest request) {
        String sid      = resolveId(request.getSessionId());
        OrderSession session = sessionRepository.findById(sid)
                .orElseGet(() -> sessionRepository.save(new OrderSession(sid)));
        String text     = request.getInput() == null ? "" : request.getInput().trim();

        if (session.getPhase() == OrderSession.Phase.DONE) {
            session.reset();
        }

        SlotExtractionResult result = llmSlotFillerService.extract(text, session);
        String intent = result.intent();

        if ("CANCEL".equals(intent)) {
            if (session.getPhase() == OrderSession.Phase.CONFIRMING) {
                // 확인 단계 거부 → 메뉴는 유지, 수량만 초기화해 재입력 유도
                session.setQuantity(null);
                session.setPhase(OrderSession.Phase.ORDERING);
                sessionRepository.save(session);
                return build(sid, intent, session,
                        String.format("%s로 계속하시겠어요? 수량을 다시 말씀해주세요.", session.getMenu()),
                        List.of("1개", "2개", "3개"));
            }
            session.reset();
            sessionRepository.save(session);
            return build(sid, intent, session,
                    "취소되었습니다. 처음부터 다시 말씀해주세요.",
                    List.of("일반 메뉴", "특식 메뉴"));
        }

        if (session.getPhase() == OrderSession.Phase.CONFIRMING && "CONFIRM".equals(intent)) {
            String msg = String.format("주문 완료되었습니다. %s %d개 나올게요!", session.getMenu(), session.getQuantity());
            session.setPhase(OrderSession.Phase.DONE);
            sessionRepository.save(session);
            return build(sid, intent, session, msg, List.of());
        }

        if ("UNKNOWN".equals(intent)) {
            return build(sid, intent, session, buildRetryMessage(session), buildRetryQuickReplies(session));
        }

        // 슬롯 채우기 — LLM이 명시적으로 추출한 값은 메뉴·수량 모두 덮어씀(수정 허용)
        if (result.menu() != null) {
            if (MENU_PRICE.containsKey(result.menu())) {
                session.setMenu(result.menu());
            }
        }
        if (result.quantity() != null) {
            if (isValidQuantity(result.quantity())) {
                session.setQuantity(result.quantity());
            } else {
                sessionRepository.save(session);
                return build(sid, intent, session,
                        "수량은 1개 이상으로 말씀해주세요.",
                        List.of("1개", "2개", "3개"));
            }
        }

        String msg;
        List<String> qr;

        if (session.isSlotsComplete()) {
            session.setPhase(OrderSession.Phase.CONFIRMING);
            msg = String.format("%s %d개 맞으시죠? 확인해 주세요.", session.getMenu(), session.getQuantity());
            qr  = List.of("네", "아니요");
        } else if (session.getMenu() == null) {
            msg = "일반 메뉴와 특식 메뉴 중 어떤 걸 드릴까요?";
            qr  = List.of("일반 메뉴", "특식 메뉴");
        } else {
            msg = "몇 개 드릴까요?";
            qr  = List.of("1개", "2개", "3개");
        }

        sessionRepository.save(session);
        return build(sid, intent, session, msg, qr);
    }

    private String buildRetryMessage(OrderSession session) {
        String prefix = "잘 이해하지 못했습니다. ";
        if (session.getPhase() == OrderSession.Phase.CONFIRMING) {
            return prefix + String.format("%s %d개 맞으시죠? 네 또는 아니요로 말씀해주세요.",
                    session.getMenu(), session.getQuantity());
        }
        if (session.getMenu() == null) {
            return prefix + "일반 메뉴와 특식 메뉴 중 어떤 걸 드릴까요?";
        }
        return prefix + "몇 개 드릴까요?";
    }

    private List<String> buildRetryQuickReplies(OrderSession session) {
        if (session.getPhase() == OrderSession.Phase.CONFIRMING) return List.of("네", "아니요");
        if (session.getMenu() == null) return List.of("일반 메뉴", "특식 메뉴");
        return List.of("1개", "2개", "3개");
    }

    private boolean isValidQuantity(int quantity) {
        return quantity >= 1;
    }

    private String resolveId(String sessionId) {
        return (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString()
                : sessionId;
    }

    private OrderResponse build(String sid, String intent, OrderSession session, String msg, List<String> qr) {
        return OrderResponse.builder()
                .sessionId(sid)
                .intent(intent)
                .response(msg)
                .slots(OrderResponse.SlotInfo.builder()
                        .menu(session.getMenu())
                        .quantity(session.getQuantity())
                        .build())
                .slotsComplete(session.isSlotsComplete())
                .quickReplies(qr)
                .build();
    }
}
