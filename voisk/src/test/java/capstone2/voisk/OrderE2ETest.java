package capstone2.voisk;

import capstone2.voisk.dto.OrderRequest;
import capstone2.voisk.dto.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 전체 E2E 테스트 — 실제 Gemini API, H2 인메모리 DB, MockMvc HTTP 레이어 사용.
 *
 * GEMINI_API_KEY 환경변수가 없으면 테스트를 건너뜁니다.
 * 실행: ./gradlew test --tests "capstone2.voisk.OrderE2ETest"
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderE2ETest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void checkApiKey() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = readFromDotEnv("GEMINI_API_KEY");
        }
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "GEMINI_API_KEY 미설정 — E2E 테스트 건너뜀");
    }

    // ── 시나리오 1: 정상 주문 — 메뉴·수량 순차 입력 후 확인 ──────────────────
    @Test
    void 정상_주문_플로우() throws Exception {
        // 1턴: 세션 없이 시작, 메뉴 선택
        OrderResponse r1 = speak(null, "일반 메뉴 주세요");
        String sid = r1.getSessionId();
        assertThat(sid).isNotBlank();
        assertThat(r1.getSlots().getMenu()).isEqualTo("일반 메뉴");
        assertThat(r1.getSlots().getQuantity()).isNull();
        assertThat(r1.isSlotsComplete()).isFalse();

        // 2턴: 수량 입력
        OrderResponse r2 = speak(sid, "2개 주세요");
        assertThat(r2.getSlots().getMenu()).isEqualTo("일반 메뉴");
        assertThat(r2.getSlots().getQuantity()).isEqualTo(2);
        assertThat(r2.isSlotsComplete()).isTrue();
        assertThat(r2.getResponse()).contains("맞으시죠");

        // 3턴: 확인
        OrderResponse r3 = speak(sid, "네");
        assertThat(r3.getResponse()).contains("주문 완료");
    }

    // ── 시나리오 2: 메뉴·수량 한 번에 입력 ──────────────────────────────────
    @Test
    void 메뉴와_수량_동시_입력() throws Exception {
        OrderResponse r1 = speak(null, "특식 메뉴 3개 주세요");
        String sid = r1.getSessionId();
        assertThat(r1.getSlots().getMenu()).isEqualTo("특식 메뉴");
        assertThat(r1.getSlots().getQuantity()).isEqualTo(3);
        assertThat(r1.isSlotsComplete()).isTrue();

        OrderResponse r2 = speak(sid, "네");
        assertThat(r2.getResponse()).contains("주문 완료");
    }

    // ── 시나리오 3: 주문 진행 중 취소 ────────────────────────────────────────
    @Test
    void 주문_중_취소() throws Exception {
        OrderResponse r1 = speak(null, "일반 메뉴 주세요");
        String sid = r1.getSessionId();

        OrderResponse r2 = speak(sid, "취소");
        assertThat(r2.getIntent()).isEqualTo("CANCEL");
        assertThat(r2.getResponse()).contains("취소");
        assertThat(r2.getSlots().getMenu()).isNull();
        assertThat(r2.isSlotsComplete()).isFalse();
    }

    // ── 시나리오 4: 확인 단계에서 거부 → 수량 재입력 ─────────────────────────
    @Test
    void 확인_단계_거부_후_수량_재입력() throws Exception {
        // 1턴: 메뉴+수량
        OrderResponse r1 = speak(null, "특식 메뉴 2개 주세요");
        String sid = r1.getSessionId();
        assertThat(r1.isSlotsComplete()).isTrue();

        // 2턴: 확인 거부 → 메뉴 유지, 수량 초기화
        OrderResponse r2 = speak(sid, "아니요");
        assertThat(r2.getSlots().getMenu()).isEqualTo("특식 메뉴");
        assertThat(r2.getSlots().getQuantity()).isNull();
        assertThat(r2.getResponse()).contains("수량을 다시");

        // 3턴: 수량 재입력
        OrderResponse r3 = speak(sid, "3개 주세요");
        assertThat(r3.getSlots().getQuantity()).isEqualTo(3);
        assertThat(r3.isSlotsComplete()).isTrue();

        // 4턴: 최종 확인
        OrderResponse r4 = speak(sid, "네");
        assertThat(r4.getResponse()).contains("주문 완료");
    }

    // ── 시나리오 5: 잘못된 수량 입력 후 재입력 ───────────────────────────────
    @Test
    void 잘못된_수량_입력_후_재입력() throws Exception {
        OrderResponse r1 = speak(null, "일반 메뉴 주세요");
        String sid = r1.getSessionId();

        // 0개 입력
        OrderResponse r2 = speak(sid, "0개 주세요");
        assertThat(r2.getResponse()).contains("1개 이상");
        assertThat(r2.getSlots().getQuantity()).isNull();

        // 정상 수량 재입력
        OrderResponse r3 = speak(sid, "1개 주세요");
        assertThat(r3.getSlots().getQuantity()).isEqualTo(1);
        assertThat(r3.isSlotsComplete()).isTrue();
    }

    // ── 시나리오 6: 세션 재사용 — 완료 후 새 주문 ───────────────────────────
    @Test
    void 주문_완료_후_새_주문() throws Exception {
        // 첫 주문 완료
        OrderResponse r1 = speak(null, "일반 메뉴 주세요");
        String sid = r1.getSessionId();
        speak(sid, "2개 주세요");
        speak(sid, "네");

        // 같은 세션으로 새 주문 시작
        OrderResponse r4 = speak(sid, "특식 메뉴 주세요");
        assertThat(r4.getSlots().getMenu()).isEqualTo("특식 메뉴");
        assertThat(r4.getSlots().getQuantity()).isNull();
    }

    // ── 시나리오 7: LLM 경로 — 키워드 없는 자연 발화 ─────────────────────────
    @Test
    void 자연어_발화_LLM_처리() throws Exception {
        // "하나요"는 ORDER_KW 없음 → LLM 호출
        OrderResponse r1 = speak(null, "일반 하나요");
        String sid = r1.getSessionId();
        assertThat(r1.getIntent()).isEqualTo("ORDER");
        assertThat(r1.getSlots().getMenu()).isEqualTo("일반 메뉴");
        assertThat(r1.getSlots().getQuantity()).isEqualTo(1);

        OrderResponse r2 = speak(sid, "네");
        assertThat(r2.getResponse()).contains("주문 완료");
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private OrderResponse speak(String sessionId, String input) throws Exception {
        OrderRequest req = new OrderRequest();
        req.setSessionId(sessionId);
        req.setInput(input);

        String body = mockMvc.perform(post("/api/order/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return mapper.readValue(body, OrderResponse.class);
    }

    private static String readFromDotEnv(String key) throws IOException {
        Path envFile = Path.of(System.getProperty("user.dir"), ".env");
        if (!Files.exists(envFile)) return null;
        String prefix = key + "=";
        return Files.lines(envFile)
                .filter(l -> l.startsWith(prefix))
                .map(l -> l.substring(prefix.length()).trim())
                .findFirst()
                .orElse(null);
    }
}
