package capstone2.voisk;

import capstone2.voisk.config.GeminiProperties;
import capstone2.voisk.dto.SlotExtractionResult;
import capstone2.voisk.entity.OrderSession;
import capstone2.voisk.service.LlmSlotFillerService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * LlmSlotFillerService 단독 평가 테스트.
 *
 * 실행: ./gradlew test --tests "capstone2.voisk.LlmProcessorEvaluationTest"
 */
class LlmProcessorEvaluationTest {
    private static final GeminiProperties geminiProperties = new GeminiProperties();

    // ── 데이터 모델 ──────────────────────────────────────────────────────────

    /**
     * @param type 유형A(정형) / 유형B(자연어) / 유형C(충돌) / 유형D(수량) / 유형E(상태의존) / 유형F(경계)
     */
    record TestCase(
            String type,
            String input,
            String expectedIntent,
            String expectedMenu,
            Integer expectedQuantity
    ) {}

    record TestResult(TestCase tc, SlotExtractionResult result, long latencyMs) {

        boolean intentCorrect() {
            return tc.expectedIntent().equals(result.intent());
        }

        boolean menuCorrect() {
            return Objects.equals(tc.expectedMenu(), result.menu());
        }

        boolean quantityCorrect() {
            return Objects.equals(tc.expectedQuantity(), result.quantity());
        }

        boolean allCorrect() {
            return intentCorrect() && menuCorrect() && quantityCorrect();
        }
    }

    // ── 테스트 케이스 86개 ────────────────────────────────────────────────────

    static final List<TestCase> TEST_CASES = List.of(

            // ── 유형 A : 키워드 포함 정형 발화 ──────────────────────────────────
            new TestCase("A", "일반 주세요",               "ORDER",   "일반 메뉴", null),
            new TestCase("A", "특식 줘",                   "ORDER",   "특식 메뉴", null),
            new TestCase("A", "네 맞아요",                 "CONFIRM", null,        null),
            new TestCase("A", "아니 취소할게요",            "CANCEL",  null,        null),
            new TestCase("A", "일반 두 개 주세요",          "ORDER",   "일반 메뉴", 2   ),
            new TestCase("A", "특식으로 줘",               "ORDER",   "특식 메뉴", null),
            new TestCase("A", "응 그걸로 할게",            "CONFIRM", null,        null),
            new TestCase("A", "다시 할게요",               "CANCEL",  null,        null),
            new TestCase("A", "일반으로 주세요",            "ORDER",   "일반 메뉴", null),
            new TestCase("A", "맞아 그거",                 "CONFIRM", null,        null),

            // ── 유형 B : 키워드 없는 자연 발화 ──────────────────────────────────
            new TestCase("B", "일반 하나요",               "ORDER",   "일반 메뉴", 1   ),
            new TestCase("B", "그걸로 할게요",             "CONFIRM", null,        null),
            new TestCase("B", "됐어요",                    "CANCEL",  null,        null),
            new TestCase("B", "특식 하나 부탁해",           "ORDER",   "특식 메뉴", 1   ),
            new TestCase("B", "일반으로 할까요",            "ORDER",   "일반 메뉴", null),
            new TestCase("B", "그냥 그걸로",               "CONFIRM", null,        null),
            new TestCase("B", "다른 걸로 할게",            "CANCEL",  null,        null),
            new TestCase("B", "특식 두 개요",              "ORDER",   "특식 메뉴", 2   ),
            new TestCase("B", "그게 뭐였죠",               "UNKNOWN", null,        null),
            new TestCase("B", "아 그냥 처음부터",           "CANCEL",  null,        null),

            // ── 유형 C : 키워드 충돌 / 경계 케이스 ─────────────────────────────
            new TestCase("C", "아니 그거 맞아요",                "CONFIRM", null,        null),
            new TestCase("C", "취소 말고 일반 주세요",           "ORDER",   "일반 메뉴", null),
            new TestCase("C", "네 아니 잠깐만요",               "CANCEL",  null,        null),
            new TestCase("C", "다시 특식 주세요",               "ORDER",   "특식 메뉴", null),
            new TestCase("C", "아니요 일반으로 줘",             "ORDER",   "일반 메뉴", null),
            new TestCase("C", "응 취소요",                     "CANCEL",  null,        null),
            new TestCase("C", "맞아요 근데 특식으로 줘",        "ORDER",   "특식 메뉴", null),
            new TestCase("C", "주세요 아니 됐어요",             "CANCEL",  null,        null),
            new TestCase("C", "네 그런데 일반 하나 더",         "ORDER",   "일반 메뉴", null),
            new TestCase("C", "맞긴 한데 다시 해줘",            "CANCEL",  null,        null),

            // ── 유형 D : 수량(Quantity) 중심 및 엣지 케이스 ──────────────────────
            new TestCase("D", "일반 1개 주세요",          "ORDER",   "일반 메뉴", 1),
            new TestCase("D", "특식 2개요",               "ORDER",   "특식 메뉴", 2),
            new TestCase("D", "일반 세 개 줘",            "ORDER",   "일반 메뉴", 3),
            new TestCase("D", "특식 넷 주세요",           "ORDER",   "특식 메뉴", 4),
            new TestCase("D", "일반 다섯 개요",           "ORDER",   "일반 메뉴", 5),
            new TestCase("D", "특식 3개 주세요",          "ORDER",   "특식 메뉴", 3),
            new TestCase("D", "일반 하나만요",            "ORDER",   "일반 메뉴", 1),
            new TestCase("D", "특식 두개요",              "ORDER",   "특식 메뉴", 2),
            new TestCase("D", "일반 1 주세요",            "ORDER",   "일반 메뉴", 1),
            new TestCase("D", "특식 하나둘 주세요",       "UNKNOWN", null,        null),
            new TestCase("D", "일반 열 개 주세요",        "ORDER",   "일반 메뉴", 10),
            new TestCase("D", "특식 한 개만",             "ORDER",   "특식 메뉴", 1),
            new TestCase("D", "일반 두개 하나 더",        "ORDER",   "일반 메뉴", null),
            new TestCase("D", "특식 0개요",               "UNKNOWN", null,        null),
            new TestCase("D", "일반 여러 개 주세요",      "ORDER",   "일반 메뉴", null),

            // ── 유형 E : 상태(Context) 의존적 짧은 발화 및 재주문 ──────────────
            new TestCase("E", "응",                       "CONFIRM", null,        null),
            new TestCase("E", "어",                       "CONFIRM", null,        null),
            new TestCase("E", "그거요",                   "CONFIRM", null,        null),
            new TestCase("E", "아뇨",                     "CANCEL",  null,        null),
            new TestCase("E", "아니요",                   "CANCEL",  null,        null),
            new TestCase("E", "싫어요",                   "CANCEL",  null,        null),
            new TestCase("E", "하나 더요",                "ORDER",   null,        1),
            new TestCase("E", "같은 걸로 하나 더",        "ORDER",   null,        1),
            new TestCase("E", "그것도 주세요",            "ORDER",   null,        null),
            new TestCase("E", "하나 빼주세요",            "CANCEL",  null,        null),
            new TestCase("E", "그걸로 두 개",             "ORDER",   null,        2),
            new TestCase("E", "역시 됐어요",              "CANCEL",  null,        null),
            new TestCase("E", "잠깐만요",                 "CANCEL",  null,        null),
            new TestCase("E", "잠시만요",                 "CANCEL",  null,        null),
            new TestCase("E", "그냥 넘어갈게요",          "CANCEL",  null,        null),

            // ── 유형 F : 문맥 단절, 수정, 잉여/패딩 및 비주문 발화 ─────────────
            new TestCase("F", "일반으로... 아 아니다",    "CANCEL",  null,        null),
            new TestCase("F", "특식 주... 잠깐",          "CANCEL",  null,        null),
            new TestCase("F", "일반",                     "ORDER",   "일반 메뉴", null),
            new TestCase("F", "특식",                     "ORDER",   "특식 메뉴", null),
            new TestCase("F", "저 그냥 일반 하나요",      "ORDER",   "일반 메뉴", 1),
            new TestCase("F", "음... 일반 주세요",        "ORDER",   "일반 메뉴", null),
            new TestCase("F", "그냥 특식으로 할게요",     "ORDER",   "특식 메뉴", null),
            new TestCase("F", "일단 일반 두 개요",        "ORDER",   "일반 메뉴", 2),
            new TestCase("F", "일반 주세요 아 특식으로요","ORDER",   "특식 메뉴", null),
            new TestCase("F", "두 개 아니 세 개요",       "ORDER",   null,        3),
            new TestCase("F", "특식 두 개 아니 한 개요",  "ORDER",   "특식 메뉴", 1),
            new TestCase("F", "이게 뭐예요",              "UNKNOWN", null,        null),
            new TestCase("F", "얼마예요",                 "UNKNOWN", null,        null),
            new TestCase("F", "잘 모르겠어요",            "UNKNOWN", null,        null),
            new TestCase("F", "아무거나요",               "UNKNOWN", null,        null),

            // ── 유형 F 추가 : intent 키워드 충돌 — 우선순위로 예측이 어긋나는 케이스 ──
            new TestCase("F", "일반 주세요 아니 취소",          "CANCEL",  "일반 메뉴", null),
            new TestCase("F", "특식으로 줘 아니 됐어요",         "CANCEL",  "특식 메뉴", null),
            new TestCase("F", "응 두 개요",                     "ORDER",   null,        2   ),
            new TestCase("F", "맞아요 세 개로요",               "ORDER",   null,        3   ),
            new TestCase("F", "아니 좋아요 그걸로요",            "CONFIRM", null,        null),

            // ── 유형 F 추가 : 방언 취소 표현 — 키워드 미등록으로 UNKNOWN 예상 ──────
            new TestCase("F", "이거 안 하겄심더",               "CANCEL",  null,        null),
            new TestCase("F", "고마 해도 됐노",                 "CANCEL",  null,        null),
            new TestCase("F", "이거 됐구먼 빼주이소",            "CANCEL",  null,        null),

            // ── 유형 F 추가 : 방언 취소 + 슬롯 감지 충돌 — menu/qty로 ORDER 오분류 예상 ──
            new TestCase("F", "일반 한 개 안 혀도 되겄어",       "CANCEL",  null,        null),
            new TestCase("F", "특식 그냥 됐노",                 "CANCEL",  "특식 메뉴", null),

            // ── 유형 F 추가 : 방언 메뉴 문의 — 주문 아닌데 menu 감지로 ORDER 오분류 예상 ──
            new TestCase("F", "이 집 특식이 뭐시기여",           "UNKNOWN", "특식 메뉴", null)
    );

    // ── 서비스 초기화 ─────────────────────────────────────────────────────────

    static LlmSlotFillerService service;

    @BeforeAll
    static void setUp() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = readFromDotEnv("GEMINI_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY 환경변수가 설정되지 않았습니다.");
        }

        GeminiProperties props = new GeminiProperties();
        props.setApiKey(apiKey);
        props.setModel("gemini-2.5-flash");

        RestClient restClient = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .defaultHeader("x-goog-api-key", apiKey)
                .build();

        service = new LlmSlotFillerService(restClient, props, null);
    }

    // ── 메인 테스트 ───────────────────────────────────────────────────────────

    @Test
    void evaluateLlmProcessor() {
        List<TestResult> results = new ArrayList<>();

        printHeader();

        for (int i = 0; i < TEST_CASES.size(); i++) {
            TestCase tc = TEST_CASES.get(i);

            long start = System.currentTimeMillis();
            SlotExtractionResult result = service.extract(tc.input(), new OrderSession());
            long latencyMs = System.currentTimeMillis() - start;

            TestResult tr = new TestResult(tc, result, latencyMs);
            results.add(tr);

            printCaseRow(i + 1, tr);
        }

        printMetricsTable(results);
        printWrongCases(results);
    }

    // ── 출력 헬퍼 ─────────────────────────────────────────────────────────────

    private void printHeader() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              LLMProcessor (LlmSlotFillerService) 성능 평가              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("%-4s %-3s %-28s %8s  %-10s %-8s %-5s  %s%n",
                "No", "유형", "발화", "레이턴시", "예측Intent", "menu", "qty", "O/X");
        System.out.println("─".repeat(80));
    }

    private void printCaseRow(int no, TestResult tr) {
        String input = tr.tc().input();
        if (input.length() > 26) input = input.substring(0, 25) + "…";

        System.out.printf("%-4d %-3s %-28s %6dms  %-10s %-8s %-5s  %s%n",
                no,
                tr.tc().type(),
                input,
                tr.latencyMs(),
                tr.result().intent(),
                Objects.toString(tr.result().menu(), "null"),
                Objects.toString(tr.result().quantity(), "null"),
                tr.allCorrect() ? "O" : "X"
        );
    }

    private void printMetricsTable(List<TestResult> results) {
        int total = results.size();

        long intentOk = results.stream().filter(TestResult::intentCorrect).count();
        long menuOk   = results.stream().filter(TestResult::menuCorrect).count();
        long qtyOk    = results.stream().filter(TestResult::quantityCorrect).count();
        long unknown  = results.stream().filter(r -> "UNKNOWN".equals(r.result().intent())).count();
        double avgMs  = results.stream().mapToLong(r -> r.latencyMs()).average().orElse(0);

        long aTotal   = countByType(results, "A");
        long aOk      = countCorrectByType(results, "A");
        long bTotal   = countByType(results, "B");
        long bOk      = countCorrectByType(results, "B");
        long cTotal   = countByType(results, "C");
        long cOk      = countCorrectByType(results, "C");
        long dTotal   = countByType(results, "D");
        long dOk      = countCorrectByType(results, "D");
        long eTotal   = countByType(results, "E");
        long eOk      = countCorrectByType(results, "E");
        long fTotal   = countByType(results, "F");
        long fOk      = countCorrectByType(results, "F");

        System.out.println();
        System.out.println("┌──────────────────────────────────────────────┬─────────────────┐");
        System.out.println("│ 지표                                         │       값        │");
        System.out.println("├──────────────────────────────────────────────┼─────────────────┤");
        System.out.printf( "│ Intent 정확도 (전체  %2d / %2d)               │     %6.1f %%   │%n",
                intentOk, total, pct(intentOk, total));
        System.out.printf( "│ Intent 정확도 (유형A 정형    %2d / %2d)       │     %6.1f %%   │%n",
                aOk, aTotal, pct(aOk, aTotal));
        System.out.printf( "│ Intent 정확도 (유형B 자연어  %2d / %2d)       │     %6.1f %%   │%n",
                bOk, bTotal, pct(bOk, bTotal));
        System.out.printf( "│ Intent 정확도 (유형C 충돌    %2d / %2d)       │     %6.1f %%   │%n",
                cOk, cTotal, pct(cOk, cTotal));
        System.out.printf( "│ Intent 정확도 (유형D 수량    %2d / %2d)       │     %6.1f %%   │%n",
                dOk, dTotal, pct(dOk, dTotal));
        System.out.printf( "│ Intent 정확도 (유형E 상태    %2d / %2d)       │     %6.1f %%   │%n",
                eOk, eTotal, pct(eOk, eTotal));
        System.out.printf( "│ Intent 정확도 (유형F 경계    %2d / %2d)       │     %6.1f %%   │%n",
                fOk, fTotal, pct(fOk, fTotal));
        System.out.println("├──────────────────────────────────────────────┼─────────────────┤");
        System.out.printf( "│ Slot 정확도 (menu     %2d / %2d)              │     %6.1f %%   │%n",
                menuOk, total, pct(menuOk, total));
        System.out.printf( "│ Slot 정확도 (quantity %2d / %2d)              │     %6.1f %%   │%n",
                qtyOk, total, pct(qtyOk, total));
        System.out.println("├──────────────────────────────────────────────┼─────────────────┤");
        System.out.printf( "│ 처리 실패율 (UNKNOWN 반환 %2d / %2d)          │     %6.1f %%   │%n",
                unknown, total, pct(unknown, total));
        System.out.printf( "│ 평균 응답 레이턴시                            │   %8.0f ms   │%n", avgMs);
        System.out.println("└──────────────────────────────────────────────┴─────────────────┘");
    }

    private void printWrongCases(List<TestResult> results) {
        List<TestResult> wrongs = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            if (!results.get(i).allCorrect()) wrongs.add(results.get(i));
        }

        System.out.println();
        if (wrongs.isEmpty()) {
            System.out.println("✔ 모든 케이스 정답!");
            return;
        }

        System.out.printf("=== 틀린 케이스 (%d건) ===%n", wrongs.size());
        System.out.println();

        for (int i = 0; i < wrongs.size(); i++) {
            TestResult r = wrongs.get(i);
            int globalNo = results.indexOf(r) + 1;

            System.out.printf("[%2d] 발화  : %s%n", globalNo, r.tc().input());
            System.out.printf("     예측  : intent=%-8s  menu=%-8s  qty=%s%n",
                    r.result().intent(),
                    Objects.toString(r.result().menu(), "null"),
                    Objects.toString(r.result().quantity(), "null"));
            System.out.printf("     정답  : intent=%-8s  menu=%-8s  qty=%s%n",
                    r.tc().expectedIntent(),
                    Objects.toString(r.tc().expectedMenu(), "null"),
                    Objects.toString(r.tc().expectedQuantity(), "null"));
            System.out.printf("     오류  : intent=%s  menu=%s  quantity=%s%n",
                    r.intentCorrect()  ? "O" : "X",
                    r.menuCorrect()    ? "O" : "X",
                    r.quantityCorrect()? "O" : "X");
            System.out.printf("     레이턴시: %dms%n%n", r.latencyMs());
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    private double pct(long correct, long total) {
        return total == 0 ? 0.0 : 100.0 * correct / total;
    }

    private long countByType(List<TestResult> results, String type) {
        return results.stream().filter(r -> type.equals(r.tc().type())).count();
    }

    private long countCorrectByType(List<TestResult> results, String type) {
        return results.stream()
                .filter(r -> type.equals(r.tc().type()) && r.intentCorrect())
                .count();
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
