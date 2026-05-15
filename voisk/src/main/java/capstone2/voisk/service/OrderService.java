package capstone2.voisk.service;

import capstone2.voisk.dto.OrderRequest;
import capstone2.voisk.dto.OrderResponse;
import capstone2.voisk.entity.OrderSession;
import capstone2.voisk.entity.OrderStatus;
import capstone2.voisk.repository.OrderSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderSessionRepository sessionRepository;

    // ── Intent 키워드 ────────────────────────────────────────────────────────
    private static final List<String> ORDER_KW   = List.of("주세요", "줘", "먹을게", "일반", "특식");
    private static final List<String> CONFIRM_KW = List.of("맞아요", "맞아", "네", "응", "확인");
    private static final List<String> CANCEL_KW  = List.of("아니", "취소", "다시");

    // "N개" 패턴 — 단위('개') 필수로 숫자 오인식 방지
    private static final Pattern QTY_DIGIT = Pattern.compile("(\\d+)\\s*개");
    private static final Map<String, Integer> KO_QTY = Map.of(
            "하나", 1, "둘", 2, "셋", 3, "넷", 4, "다섯", 5
    );

    // 메뉴별 가격 (추후 확장 용이)
    public static final Map<String, Integer> MENU_PRICE = Map.of(
            "일반 메뉴", 8000,
            "특식 메뉴", 12000
    );

    // ── 진입점 ────────────────────────────────────────────────────────────────

    public OrderResponse process(OrderRequest request) {
        String sid      = resolveId(request.getSessionId());
        
        // FIXME: OrderSession 엔티티의 id가 Long 타입이므로, String sid 사용 부분 수정이 필요할 수 있습니다.
        OrderSession session = sessionRepository.findById(sid)
                .orElseGet(() -> {
                    OrderSession newSession = new OrderSession();
                    return sessionRepository.save(newSession);
                });
                
        String text     = request.getInput() == null ? "" : request.getInput().trim();
        String intent   = classifyIntent(text);

        // DONE 상태 → 자동 리셋 후 새 주문
        if (session.getStatus() == OrderStatus.DONE) {
            session.reset();
        }

        // CANCEL: 단계 무관 즉시 리셋
        if ("CANCEL".equals(intent)) {
            session.reset();
            sessionRepository.save(session);
            return build(sid, intent, session,
                    "취소되었습니다. 처음부터 다시 말씀해주세요.",
                    List.of("일반 메뉴", "특식 메뉴"));
        }

        // CONFIRMING 단계 + CONFIRM → 주문 완료
        if (session.getStatus() == OrderStatus.CONFIRMING && "CONFIRM".equals(intent)) {
            String msg = String.format("주문 완료되었습니다. %s %d개 나올게요!", session.getMenu(), session.getQuantity());
            session.setStatus(OrderStatus.DONE);
            sessionRepository.save(session);
            return build(sid, intent, session, msg, List.of());
        }

        // 슬롯 채우기 (intent 무관하게 항상 시도)
        fillSlots(text, session);

        String msg;
        List<String> qr;

        if (session.isSlotsComplete()) {
            session.setStatus(OrderStatus.CONFIRMING);
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

    // ── Intent 분류 ───────────────────────────────────────────────────────────

    private String classifyIntent(String text) {
        if (containsAny(text, CANCEL_KW))  return "CANCEL";
        if (containsAny(text, CONFIRM_KW)) return "CONFIRM";
        if (containsAny(text, ORDER_KW))   return "ORDER";
        return "UNKNOWN";
    }

    // ── 슬롯 채우기 ───────────────────────────────────────────────────────────

    private void fillSlots(String text, OrderSession session) {
        if (session.getMenu() == null) {
            if (text.contains("특식"))      session.setMenu("특식 메뉴");
            else if (text.contains("일반")) session.setMenu("일반 메뉴");
        }
        if (session.getQuantity() == null) {
            session.setQuantity(extractQty(text));
        }
    }

    private Integer extractQty(String text) {
        Matcher m = QTY_DIGIT.matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        for (Map.Entry<String, Integer> e : KO_QTY.entrySet()) {
            if (text.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    // ── 유틸리티 ─────────────────────────────────────────────────────────────

    // sessionId가 없으면 새 UUID 발급 (클라이언트가 이후 응답의 sessionId를 재사용해야 함)
    private String resolveId(String sessionId) {
        return (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString()
                : sessionId;
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
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
