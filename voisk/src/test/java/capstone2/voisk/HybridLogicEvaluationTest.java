package capstone2.voisk;

import capstone2.voisk.config.GeminiProperties;
import capstone2.voisk.dto.SlotExtractionResult;
import capstone2.voisk.entity.OrderSession;
import capstone2.voisk.service.LlmSlotFillerService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 하이브리드 로직(키워드 우선 → LLM 폴백) 자체를 평가하는 테스트.
 *
 * - 입력별 라우팅 경로(KW / LLM) 추적: hybrid.fallback 로거 캡처
 * - LLM 호출율 / 경로별 정확도 / 경로별 레이턴시 측정
 *
 * 실행: ./gradlew test --tests "capstone2.voisk.HybridLogicEvaluationTest"
 */
class HybridLogicEvaluationTest {

    // ── 데이터 모델 ──────────────────────────────────────────────────────────

    record TestCase(
            String type,
            String input,
            String expectedIntent,
            String expectedMenu,
            Integer expectedQuantity
    ) {}

    record TestResult(TestCase tc, SlotExtractionResult result, long latencyMs, boolean llmCalled) {
        boolean intentCorrect()   { return tc.expectedIntent().equals(result.intent()); }
        boolean menuCorrect()     { return Objects.equals(tc.expectedMenu(), result.menu()); }
        boolean quantityCorrect() { return Objects.equals(tc.expectedQuantity(), result.quantity()); }
        boolean allCorrect()      { return intentCorrect() && menuCorrect() && quantityCorrect(); }
        String  route()           { return llmCalled ? "LLM" : "KW "; }
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
            // 우선순위 충돌
            new TestCase("F", "일반 주세요 아니 취소",          "CANCEL",  "일반 메뉴", null),
            new TestCase("F", "특식으로 줘 아니 됐어요",         "CANCEL",  "특식 메뉴", null),
            new TestCase("F", "응 두 개요",                     "ORDER",   null,        2   ),
            new TestCase("F", "맞아요 세 개로요",               "ORDER",   null,        3   ),
            new TestCase("F", "아니 좋아요 그걸로요",            "CONFIRM", null,        null),
            // 방언
            new TestCase("F", "이거 안 하겄심더",               "CANCEL",  null,        null),
            new TestCase("F", "고마 해도 됐노",                 "CANCEL",  null,        null),
            new TestCase("F", "이거 됐구먼 빼주이소",            "CANCEL",  null,        null),
            new TestCase("F", "일반 한 개 안 혀도 되겄어",       "CANCEL",  null,        null),
            new TestCase("F", "특식 그냥 됐노",                 "CANCEL",  "특식 메뉴", null),
            new TestCase("F", "이 집 특식이 뭐시기여",           "UNKNOWN", "특식 메뉴", null)
    );

    // ── 서비스 & 로그 캡처 초기화 ────────────────────────────────────────────

    static LlmSlotFillerService service;
    static ListAppender<ILoggingEvent> logAppender;

    @BeforeAll
    static void setUp() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = readFromDotEnv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("GEMINI_API_KEY 환경변수가 설정되지 않았습니다.");

        GeminiProperties props = new GeminiProperties();
        props.setApiKey(apiKey);
        props.setModel("gemini-2.5-flash");

        RestClient restClient = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .defaultHeader("x-goog-api-key", apiKey)
                .build();

        service = new LlmSlotFillerService(restClient, props);

        // hybrid.fallback 로거에 ListAppender를 붙여 LLM 호출 여부를 케이스별로 감지
        Logger hybridLogger = (Logger) LoggerFactory.getLogger("hybrid.fallback");
        logAppender = new ListAppender<>();
        logAppender.start();
        hybridLogger.addAppender(logAppender);
    }

    // ── 메인 테스트 ───────────────────────────────────────────────────────────

    @Test
    void evaluateHybridLogic() {
        List<TestResult> results = new ArrayList<>();
        printHeader();

        for (int i = 0; i < TEST_CASES.size(); i++) {
            TestCase tc = TEST_CASES.get(i);

            logAppender.list.clear();   // 케이스 시작 전 로그 초기화

            long start = System.currentTimeMillis();
            SlotExtractionResult result = service.extract(tc.input(), new OrderSession("test"));
            long latencyMs = System.currentTimeMillis() - start;

            // hybrid.fallback 로거에 로그가 찍혔으면 LLM 경로
            boolean llmCalled = !logAppender.list.isEmpty();

            TestResult tr = new TestResult(tc, result, latencyMs, llmCalled);
            results.add(tr);
            printCaseRow(i + 1, tr);
        }

        printMetricsTable(results);
        printWrongCases(results);
    }

    // ── 출력 헬퍼 ─────────────────────────────────────────────────────────────

    private void printHeader() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║            하이브리드 로직 (키워드 → LLM 폴백) 성능 평가                ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("%-4s %-3s %-5s %-28s %8s  %-10s %-8s %-5s  %s%n",
                "No", "유형", "경로", "발화", "레이턴시", "예측Intent", "menu", "qty", "O/X");
        System.out.println("─".repeat(86));
    }

    private void printCaseRow(int no, TestResult tr) {
        String input = tr.tc().input();
        if (input.length() > 26) input = input.substring(0, 25) + "…";

        System.out.printf("%-4d %-3s %-5s %-28s %6dms  %-10s %-8s %-5s  %s%n",
                no,
                tr.tc().type(),
                tr.route(),
                input,
                tr.latencyMs(),
                tr.result().intent(),
                Objects.toString(tr.result().menu(), "null"),
                Objects.toString(tr.result().quantity(), "null"),
                tr.allCorrect() ? "O" : "X");
    }

    private void printMetricsTable(List<TestResult> results) {
        int total = results.size();

        long intentOk = results.stream().filter(TestResult::intentCorrect).count();
        long unknown  = results.stream().filter(r -> "UNKNOWN".equals(r.result().intent())).count();

        long aOk = countCorrectByType(results, "A"); long aTotal = countByType(results, "A");
        long bOk = countCorrectByType(results, "B"); long bTotal = countByType(results, "B");
        long cOk = countCorrectByType(results, "C"); long cTotal = countByType(results, "C");
        long dOk = countCorrectByType(results, "D"); long dTotal = countByType(results, "D");
        long eOk = countCorrectByType(results, "E"); long eTotal = countByType(results, "E");
        long fOk = countCorrectByType(results, "F"); long fTotal = countByType(results, "F");

        // 라우팅 통계
        List<TestResult> kwResults  = results.stream().filter(r -> !r.llmCalled()).toList();
        List<TestResult> llmResults = results.stream().filter(TestResult::llmCalled).toList();

        long kwCount  = kwResults.size();
        long llmCount = llmResults.size();
        long kwOk     = kwResults.stream().filter(TestResult::intentCorrect).count();
        long llmOk    = llmResults.stream().filter(TestResult::intentCorrect).count();

        double avgAll = results.stream().mapToLong(TestResult::latencyMs).average().orElse(0);
        double avgKw  = kwResults.stream().mapToLong(TestResult::latencyMs).average().orElse(0);
        double avgLlm = llmResults.stream().mapToLong(TestResult::latencyMs).average().orElse(0);

        // 유형별 LLM 호출율
        long aLlm = countLlmByType(results, "A"); long bLlm = countLlmByType(results, "B");
        long cLlm = countLlmByType(results, "C"); long dLlm = countLlmByType(results, "D");
        long eLlm = countLlmByType(results, "E"); long fLlm = countLlmByType(results, "F");

        System.out.println();
        System.out.println("┌──────────────────────────────────────────────┬─────────────────┐");
        System.out.println("│ 지표                                         │       값        │");
        System.out.println("├──────────────────────────────────────────────┼─────────────────┤");
        System.out.printf( "│ Intent 정확도 (전체  %2d / %2d)               │     %6.1f %%   │%n",
                intentOk, total, pct(intentOk, total));
        System.out.printf( "│   유형A 정형    %2d / %2d                     │     %6.1f %%   │%n",
                aOk, aTotal, pct(aOk, aTotal));
        System.out.printf( "│   유형B 자연어  %2d / %2d                     │     %6.1f %%   │%n",
                bOk, bTotal, pct(bOk, bTotal));
        System.out.printf( "│   유형C 충돌    %2d / %2d                     │     %6.1f %%   │%n",
                cOk, cTotal, pct(cOk, cTotal));
        System.out.printf( "│   유형D 수량    %2d / %2d                     │     %6.1f %%   │%n",
                dOk, dTotal, pct(dOk, dTotal));
        System.out.printf( "│   유형E 상태    %2d / %2d                     │     %6.1f %%   │%n",
                eOk, eTotal, pct(eOk, eTotal));
        System.out.printf( "│   유형F 경계    %2d / %2d                     │     %6.1f %%   │%n",
                fOk, fTotal, pct(fOk, fTotal));
        System.out.println("├──────────────────────────────────────────────┼─────────────────┤");
        System.out.printf( "│ 라우팅 — 키워드 경로    (%2d / %2d)           │     %6.1f %%   │%n",
                kwCount, total, pct(kwCount, total));
        System.out.printf( "│ 라우팅 — LLM 경로       (%2d / %2d)           │     %6.1f %%   │%n",
                llmCount, total, pct(llmCount, total));
        System.out.println("│  ─ 유형별 LLM 호출 건수 ─                    │                 │");
        System.out.printf( "│   A:%2d  B:%2d  C:%2d  D:%2d  E:%2d  F:%2d   │                 │%n",
                aLlm, bLlm, cLlm, dLlm, eLlm, fLlm);
        System.out.println("├──────────────────────────────────────────────┼─────────────────┤");
        System.out.printf( "│ 키워드 경로 Intent 정확도 (%2d / %2d)         │     %6.1f %%   │%n",
                kwOk, kwCount, pct(kwOk, kwCount));
        System.out.printf( "│ LLM    경로 Intent 정확도 (%2d / %2d)         │     %6.1f %%   │%n",
                llmOk, llmCount, pct(llmOk, llmCount));
        System.out.println("├──────────────────────────────────────────────┼─────────────────┤");
        System.out.printf( "│ 처리 실패율 (UNKNOWN %2d / %2d)               │     %6.1f %%   │%n",
                unknown, total, pct(unknown, total));
        System.out.printf( "│ 평균 레이턴시 (전체)                          │   %8.0f ms   │%n", avgAll);
        System.out.printf( "│ 평균 레이턴시 (키워드 경로)                   │   %8.0f ms   │%n", avgKw);
        System.out.printf( "│ 평균 레이턴시 (LLM    경로)                   │   %8.0f ms   │%n", avgLlm);
        System.out.println("└──────────────────────────────────────────────┴─────────────────┘");
    }

    private void printWrongCases(List<TestResult> results) {
        List<TestResult> wrongs = results.stream().filter(r -> !r.allCorrect()).toList();

        System.out.println();
        if (wrongs.isEmpty()) {
            System.out.println("✔ 모든 케이스 정답!");
            return;
        }

        System.out.printf("=== 틀린 케이스 (%d건) ===%n%n", wrongs.size());
        for (TestResult r : wrongs) {
            int no = results.indexOf(r) + 1;
            System.out.printf("[%2d / %s / %s] 발화  : %s%n", no, r.tc().type(), r.route(), r.tc().input());
            System.out.printf("     예측  : intent=%-8s  menu=%-8s  qty=%s%n",
                    r.result().intent(),
                    Objects.toString(r.result().menu(), "null"),
                    Objects.toString(r.result().quantity(), "null"));
            System.out.printf("     정답  : intent=%-8s  menu=%-8s  qty=%s%n",
                    r.tc().expectedIntent(),
                    Objects.toString(r.tc().expectedMenu(), "null"),
                    Objects.toString(r.tc().expectedQuantity(), "null"));
            System.out.printf("     오류  : intent=%s  menu=%s  quantity=%s  레이턴시=%dms%n%n",
                    r.intentCorrect()   ? "O" : "X",
                    r.menuCorrect()     ? "O" : "X",
                    r.quantityCorrect() ? "O" : "X",
                    r.latencyMs());
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    private double pct(long n, long d) { return d == 0 ? 0.0 : 100.0 * n / d; }

    private long countByType(List<TestResult> r, String type) {
        return r.stream().filter(x -> type.equals(x.tc().type())).count();
    }
    private long countCorrectByType(List<TestResult> r, String type) {
        return r.stream().filter(x -> type.equals(x.tc().type()) && x.intentCorrect()).count();
    }
    private long countLlmByType(List<TestResult> r, String type) {
        return r.stream().filter(x -> type.equals(x.tc().type()) && x.llmCalled()).count();
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
