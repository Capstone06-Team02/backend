package capstone2.voisk;

import capstone2.voisk.config.GeminiProperties;
import capstone2.voisk.dto.SlotExtractionResult;
import capstone2.voisk.entity.OrderSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LLM(Gemini)만 단독으로 평가하는 테스트 — 키워드 라우팅 없이 전 케이스 LLM 직접 호출.
 *
 * 실행: ./gradlew test --tests "capstone2.voisk.LlmOnlyEvaluationTest"
 */
class LlmOnlyEvaluationTest {

    // ── 데이터 모델 ──────────────────────────────────────────────────────────

    record TestCase(
            String type,
            String input,
            String expectedIntent,
            String expectedMenu,
            Integer expectedQuantity
    ) {}

    record TestResult(TestCase tc, SlotExtractionResult result, long latencyMs) {
        boolean intentCorrect()   { return tc.expectedIntent().equals(result.intent()); }
        boolean menuCorrect()     { return Objects.equals(tc.expectedMenu(), result.menu()); }
        boolean quantityCorrect() { return Objects.equals(tc.expectedQuantity(), result.quantity()); }
        boolean allCorrect()      { return intentCorrect() && menuCorrect() && quantityCorrect(); }
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
            new TestCase("F", "일반 주세요 아니 취소",          "CANCEL",  "일반 메뉴", null),
            new TestCase("F", "특식으로 줘 아니 됐어요",         "CANCEL",  "특식 메뉴", null),
            new TestCase("F", "응 두 개요",                     "ORDER",   null,        2   ),
            new TestCase("F", "맞아요 세 개로요",               "ORDER",   null,        3   ),
            new TestCase("F", "아니 좋아요 그걸로요",            "CONFIRM", null,        null),
            new TestCase("F", "이거 안 하겄심더",               "CANCEL",  null,        null),
            new TestCase("F", "고마 해도 됐노",                 "CANCEL",  null,        null),
            new TestCase("F", "이거 됐구먼 빼주이소",            "CANCEL",  null,        null),
            new TestCase("F", "일반 한 개 안 혀도 되겄어",       "CANCEL",  null,        null),
            new TestCase("F", "특식 그냥 됐노",                 "CANCEL",  "특식 메뉴", null),
            new TestCase("F", "이 집 특식이 뭐시기여",           "UNKNOWN", "특식 메뉴", null)
    );

    // ── Gemini 프롬프트 (LlmSlotFillerService와 동일) ─────────────────────────

    private static final String SYSTEM_PROMPT = """
            너는 키오스크 주문 분석기야. 사용자의 한국어 음성 입력에서 아래 정보를 추출해서 JSON만 반환해. 설명 없이 JSON만.

            메뉴 종류 (순서 기준):
              1번 = "일반 메뉴" (8,000원) — 기본, 저렴한, 보통, 싼 쪽
              2번 = "특식 메뉴" (12,000원) — 특별한, 비싼, 좋은, 프리미엄 쪽

            반환 형식:
            {"intent": "...", "menu": "..." 또는 null, "quantity": 숫자 또는 null, "option": "..." 또는 null}

            intent 분류 기준:
            - ORDER: 메뉴 선택, 수량 언급, 주문 의사 표현 (예: 주세요, 줘, 먹을게, 드릴게요, 하나 주세요)
            - CONFIRM: 확인/동의 (예: 네, 응, 맞아요, 맞아, 확인, 그래요)
            - CANCEL: 취소/거부/재시작 (예: 아니요, 아니, 취소, 다시, 틀려요)
            - UNKNOWN: 위 세 가지 모두 해당 없음

            메뉴 추론 기준 (다양한 표현 → 메뉴 매핑):
            - "일반 메뉴"로 판단: 1번, 첫번째, 앞에 거, 기본, 보통, 일반, 싼 거, 저렴한 거, 작은 거
            - "특식 메뉴"로 판단: 2번, 두번째, 뒤에 거, 특식, 특별한, 비싼 거, 좋은 거, 프리미엄, 스페셜

            수량 추출 기준:
            숫자+번(1번, 2번) → 메뉴 번호(수량 아님), 숫자+개/명/사람분 → 수량
            "두 개" → 2, "둘" → 2, "2개" → 2, "하나" → 1, "한 개" → 1, "세 개" → 3, "열 개" → 10
            "두 사람분" → 2, "세 명이서" → 3
            예시: "특식 메뉴 2개" → menu: "특식 메뉴", quantity: 2 (2는 수량, 메뉴번호 아님)

            option: 특별 요청 사항이 있으면 문자열, 없으면 null.
            없는 정보는 null로 반환. 메뉴 이름은 반드시 "일반 메뉴" 또는 "특식 메뉴" 중 하나만 사용.
            """;

    // ── 초기화 ────────────────────────────────────────────────────────────────

    static RestClient restClient;
    static GeminiProperties props;
    static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUp() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = readFromDotEnv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("GEMINI_API_KEY 환경변수가 설정되지 않았습니다.");

        props = new GeminiProperties();
        props.setApiKey(apiKey);
        props.setModel("gemini-2.5-flash");

        restClient = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .defaultHeader("x-goog-api-key", apiKey)
                .build();
    }

    // ── 메인 테스트 ───────────────────────────────────────────────────────────

    @Test
    void evaluateLlmOnly() {
        List<TestResult> results = new ArrayList<>();
        printHeader();

        for (int i = 0; i < TEST_CASES.size(); i++) {
            TestCase tc = TEST_CASES.get(i);

            long start = System.currentTimeMillis();
            SlotExtractionResult result = callGemini(tc.input(), new OrderSession("test"));
            long latencyMs = System.currentTimeMillis() - start;

            TestResult tr = new TestResult(tc, result, latencyMs);
            results.add(tr);
            printCaseRow(i + 1, tr);
        }

        printMetricsTable(results);
        printWrongCases(results);
    }

    // ── Gemini 직접 호출 ──────────────────────────────────────────────────────

    private SlotExtractionResult callGemini(String userInput, OrderSession session) {
        try {
            Map<String, Object> systemInstruction = Map.of(
                    "parts", List.of(Map.of("text", SYSTEM_PROMPT))
            );
            String contextedInput = buildContextText(session) + "\n\n[사용자 발화]\n" + userInput;
            Map<String, Object> userContent = Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", contextedInput))
            );
            Map<String, Object> body = Map.of(
                    "system_instruction", systemInstruction,
                    "contents", List.of(userContent),
                    "generationConfig", Map.of("temperature", 0, "responseMimeType", "application/json")
            );

            String raw = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent", props.getModel())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root   = MAPPER.readTree(raw);
            String content  = root.path("candidates").get(0)
                                  .path("content").path("parts").get(0)
                                  .path("text").asText();
            JsonNode parsed = MAPPER.readTree(content);

            String  intent   = parsed.path("intent").asText("UNKNOWN");
            String  menu     = parsed.path("menu").isNull()     ? null : parsed.path("menu").asText(null);
            Integer quantity = parsed.path("quantity").isNull() ? null : parsed.path("quantity").asInt();
            String  option   = parsed.path("option").isNull()   ? null : parsed.path("option").asText(null);

            return new SlotExtractionResult(intent, menu, quantity, option);
        } catch (Exception e) {
            System.err.printf("[Gemini 오류] input=\"%s\" error=%s%n", userInput, e.getMessage());
            return SlotExtractionResult.fallback();
        }
    }

    private String buildContextText(OrderSession session) {
        StringBuilder sb = new StringBuilder("[현재 대화 상태]\n");
        if (session.getPhase() == OrderSession.Phase.CONFIRMING) {
            sb.append("- 단계: 주문 확인 대기\n");
            sb.append(String.format("- 선택된 메뉴: %s%n", session.getMenu()));
            sb.append(String.format("- 선택된 수량: %d개%n", session.getQuantity()));
            sb.append(String.format("- 직전 시스템 질문: \"%s %d개 맞으시죠?\"",
                    session.getMenu(), session.getQuantity()));
        } else {
            sb.append("- 단계: 주문 진행 중\n");
            sb.append(String.format("- 선택된 메뉴: %s%n",
                    session.getMenu() != null ? session.getMenu() : "없음 (선택 대기)"));
            sb.append(String.format("- 선택된 수량: %s%n",
                    session.getQuantity() != null ? session.getQuantity() + "개" : "없음 (입력 대기)"));
            if (session.getMenu() == null) {
                sb.append("- 직전 시스템 질문: \"일반 메뉴와 특식 메뉴 중 어떤 걸 드릴까요?\"");
            } else {
                sb.append("- 직전 시스템 질문: \"몇 개 드릴까요?\"");
            }
        }
        return sb.toString();
    }

    // ── 출력 헬퍼 ─────────────────────────────────────────────────────────────

    private void printHeader() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                   LLM 단독 (Gemini Direct) 성능 평가                   ║");
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
                tr.allCorrect() ? "O" : "X");
    }

    private void printMetricsTable(List<TestResult> results) {
        int total     = results.size();
        long intentOk = results.stream().filter(TestResult::intentCorrect).count();
        long menuOk   = results.stream().filter(TestResult::menuCorrect).count();
        long qtyOk    = results.stream().filter(TestResult::quantityCorrect).count();
        long unknown  = results.stream().filter(r -> "UNKNOWN".equals(r.result().intent())).count();
        double avgMs  = results.stream().mapToLong(TestResult::latencyMs).average().orElse(0);

        long aTotal = countByType(results, "A"); long aOk = countCorrectByType(results, "A");
        long bTotal = countByType(results, "B"); long bOk = countCorrectByType(results, "B");
        long cTotal = countByType(results, "C"); long cOk = countCorrectByType(results, "C");
        long dTotal = countByType(results, "D"); long dOk = countCorrectByType(results, "D");
        long eTotal = countByType(results, "E"); long eOk = countCorrectByType(results, "E");
        long fTotal = countByType(results, "F"); long fOk = countCorrectByType(results, "F");

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
        List<TestResult> wrongs = results.stream().filter(r -> !r.allCorrect()).toList();

        System.out.println();
        if (wrongs.isEmpty()) {
            System.out.println("✔ 모든 케이스 정답!");
            return;
        }

        System.out.printf("=== 틀린 케이스 (%d건) ===%n%n", wrongs.size());
        for (TestResult r : wrongs) {
            int no = results.indexOf(r) + 1;
            System.out.printf("[%2d / %s] 발화  : %s%n", no, r.tc().type(), r.tc().input());
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
