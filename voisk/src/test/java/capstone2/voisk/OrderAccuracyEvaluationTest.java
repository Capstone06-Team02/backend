package capstone2.voisk;

import capstone2.voisk.dto.OrderRequest;
import capstone2.voisk.dto.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 정확도 종합 평가 테스트.
 *
 * 단편 발화가 아닌 완전한 주문 시나리오 단위로 평가한다.
 * - 턴별 인식 정확도: intent / menu / quantity 각각 측정
 * - 최종 주문 정확도: 완료된 주문의 메뉴·수량이 기대값과 일치하는지
 *
 * 실행: ./gradlew test --tests "capstone2.voisk.OrderAccuracyEvaluationTest"
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderAccuracyEvaluationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── 데이터 모델 ────────────────────────────────────────────────────────────

    /**
     * 한 턴의 입력과 이 턴 처리 후 세션에 누적될 기대 슬롯 상태.
     * null은 "이 턴에서 해당 슬롯 기대값 없음 (검사 생략)"을 의미한다.
     */
    record TurnSpec(String input, String expectedIntent, String expectedMenu, Integer expectedQty) {}

    record Scenario(String name, List<TurnSpec> turns, String finalMenu, int finalQty) {}

    record TurnResult(TurnSpec spec, OrderResponse actual, boolean intentOk, boolean menuOk, boolean qtyOk, long latencyMs) {
        boolean allOk() { return intentOk && menuOk && qtyOk; }
    }

    record ScenarioResult(Scenario scenario, List<TurnResult> turns, boolean completed, boolean orderCorrect) {}

    // ── 시나리오 정의 ──────────────────────────────────────────────────────────

    static final List<Scenario> SCENARIOS = List.of(

            // ── Group A: 정형 단계별 주문 (키워드 경로) ──────────────────────

            new Scenario("A01 단계별 (일반·2개·네)", List.of(
                    new TurnSpec("일반 메뉴 주세요", "ORDER",   "일반 메뉴", null),
                    new TurnSpec("2개 주세요",        "ORDER",   "일반 메뉴", 2),
                    new TurnSpec("네",                "CONFIRM", "일반 메뉴", 2)
            ), "일반 메뉴", 2),

            new Scenario("A02 단계별 (특식·1개·맞습니다)", List.of(
                    new TurnSpec("특식 메뉴 주세요", "ORDER",   "특식 메뉴", null),
                    new TurnSpec("1개 주세요",        "ORDER",   "특식 메뉴", 1),
                    new TurnSpec("맞습니다",           "CONFIRM", "특식 메뉴", 1)
            ), "특식 메뉴", 1),

            new Scenario("A03 단계별 (일반·3개·그래)", List.of(
                    new TurnSpec("일반 메뉴 주문해줘", "ORDER",   "일반 메뉴", null),
                    new TurnSpec("3개 주세요",          "ORDER",   "일반 메뉴", 3),
                    new TurnSpec("그래",                "CONFIRM", "일반 메뉴", 3)
            ), "일반 메뉴", 3),

            new Scenario("A04 단계별 (특식·2개·좋아)", List.of(
                    new TurnSpec("특식 드릴게요", "ORDER",   "특식 메뉴", null),
                    new TurnSpec("2개 주세요",    "ORDER",   "특식 메뉴", 2),
                    new TurnSpec("좋아",           "CONFIRM", "특식 메뉴", 2)
            ), "특식 메뉴", 2),

            new Scenario("A05 단계별 (일반·1개·응)", List.of(
                    new TurnSpec("일반 먹을게", "ORDER",   "일반 메뉴", null),
                    new TurnSpec("1개 주세요",  "ORDER",   "일반 메뉴", 1),
                    new TurnSpec("응",           "CONFIRM", "일반 메뉴", 1)
            ), "일반 메뉴", 1),

            new Scenario("A06 단계별 (특식·4개·예)", List.of(
                    new TurnSpec("특식 메뉴 주문", "ORDER",   "특식 메뉴", null),
                    new TurnSpec("4개 주세요",     "ORDER",   "특식 메뉴", 4),
                    new TurnSpec("예",              "CONFIRM", "특식 메뉴", 4)
            ), "특식 메뉴", 4),

            new Scenario("A07 단계별 (일반·3개·맞아)", List.of(
                    new TurnSpec("일반 메뉴 줘", "ORDER",   "일반 메뉴", null),
                    new TurnSpec("3개 줘",        "ORDER",   "일반 메뉴", 3),
                    new TurnSpec("맞아",           "CONFIRM", "일반 메뉴", 3)
            ), "일반 메뉴", 3),

            new Scenario("A08 단계별 (특식·2개·오케이)", List.of(
                    new TurnSpec("특식 메뉴 주문해줘", "ORDER",   "특식 메뉴", null),
                    new TurnSpec("2개 드릴게요",        "ORDER",   "특식 메뉴", 2),
                    new TurnSpec("오케이",              "CONFIRM", "특식 메뉴", 2)
            ), "특식 메뉴", 2),

            new Scenario("A09 단계별 (일반·1개·ㅇㅋ)", List.of(
                    new TurnSpec("일반 주세요", "ORDER",   "일반 메뉴", null),
                    new TurnSpec("1개 주세요",  "ORDER",   "일반 메뉴", 1),
                    new TurnSpec("ㅇㅋ",         "CONFIRM", "일반 메뉴", 1)
            ), "일반 메뉴", 1),

            new Scenario("A10 단계별 (특식·5개·확인)", List.of(
                    new TurnSpec("특식 주세요", "ORDER",   "특식 메뉴", null),
                    new TurnSpec("5개 주세요",  "ORDER",   "특식 메뉴", 5),
                    new TurnSpec("확인",         "CONFIRM", "특식 메뉴", 5)
            ), "특식 메뉴", 5),

            // ── Group B: 메뉴+수량 동시 입력 (키워드 경로) ───────────────────

            new Scenario("B11 동시입력 (일반·1개·네)", List.of(
                    new TurnSpec("일반 메뉴 1개 주세요", "ORDER",   "일반 메뉴", 1),
                    new TurnSpec("네",                   "CONFIRM", "일반 메뉴", 1)
            ), "일반 메뉴", 1),

            new Scenario("B12 동시입력 (특식·2개·맞습니다)", List.of(
                    new TurnSpec("특식 메뉴 2개 주세요", "ORDER",   "특식 메뉴", 2),
                    new TurnSpec("맞습니다",              "CONFIRM", "특식 메뉴", 2)
            ), "특식 메뉴", 2),

            new Scenario("B13 동시입력 (일반·3개·그래)", List.of(
                    new TurnSpec("일반 메뉴 3개 주문", "ORDER",   "일반 메뉴", 3),
                    new TurnSpec("그래",               "CONFIRM", "일반 메뉴", 3)
            ), "일반 메뉴", 3),

            new Scenario("B14 동시입력 (특식·4개·좋아)", List.of(
                    new TurnSpec("특식 메뉴 4개 먹을게", "ORDER",   "특식 메뉴", 4),
                    new TurnSpec("좋아",                  "CONFIRM", "특식 메뉴", 4)
            ), "특식 메뉴", 4),

            new Scenario("B15 동시입력 (일반·2개·응)", List.of(
                    new TurnSpec("일반 2개 드릴게요", "ORDER",   "일반 메뉴", 2),
                    new TurnSpec("응",                "CONFIRM", "일반 메뉴", 2)
            ), "일반 메뉴", 2),

            new Scenario("B16 동시입력 (특식·5개·예)", List.of(
                    new TurnSpec("특식 메뉴 5개 주문해줘", "ORDER",   "특식 메뉴", 5),
                    new TurnSpec("예",                     "CONFIRM", "특식 메뉴", 5)
            ), "특식 메뉴", 5),

            new Scenario("B17 동시입력 (일반·2개·맞아)", List.of(
                    new TurnSpec("일반 메뉴 2개 줘", "ORDER",   "일반 메뉴", 2),
                    new TurnSpec("맞아",              "CONFIRM", "일반 메뉴", 2)
            ), "일반 메뉴", 2),

            new Scenario("B18 동시입력 (특식·1개·오케이)", List.of(
                    new TurnSpec("특식 1개 주세요", "ORDER",   "특식 메뉴", 1),
                    new TurnSpec("오케이",           "CONFIRM", "특식 메뉴", 1)
            ), "특식 메뉴", 1),

            new Scenario("B19 동시입력 (일반·4개·ㅇㅋ)", List.of(
                    new TurnSpec("일반 메뉴 4개 주세요", "ORDER",   "일반 메뉴", 4),
                    new TurnSpec("ㅇㅋ",                  "CONFIRM", "일반 메뉴", 4)
            ), "일반 메뉴", 4),

            new Scenario("B20 동시입력 (특식·3개·확인)", List.of(
                    new TurnSpec("특식 메뉴 3개 주문해줘", "ORDER",   "특식 메뉴", 3),
                    new TurnSpec("확인",                   "CONFIRM", "특식 메뉴", 3)
            ), "특식 메뉴", 3),

            // ── Group C: 자연어 발화 (LLM 경로) ─────────────────────────────

            new Scenario("C21 자연어 일괄 (일반·1개)", List.of(
                    new TurnSpec("일반 하나요",   "ORDER",   "일반 메뉴", 1),
                    new TurnSpec("그걸로 할게요", "CONFIRM", "일반 메뉴", 1)
            ), "일반 메뉴", 1),

            new Scenario("C22 자연어 일괄 (특식·2개·고유어)", List.of(
                    new TurnSpec("특식 둘이요", "ORDER",   "특식 메뉴", 2),
                    new TurnSpec("맞아요",      "CONFIRM", "특식 메뉴", 2)
            ), "특식 메뉴", 2),

            new Scenario("C23 자연어 단계별 (일반→두 개)", List.of(
                    new TurnSpec("일반이요",  "ORDER",   "일반 메뉴", null),
                    new TurnSpec("두 개요",   "ORDER",   "일반 메뉴", 2),
                    new TurnSpec("맞아",      "CONFIRM", "일반 메뉴", 2)
            ), "일반 메뉴", 2),

            new Scenario("C24 자연어 일괄 (특식·3개)", List.of(
                    new TurnSpec("특식으로 세 개", "ORDER",   "특식 메뉴", 3),
                    new TurnSpec("네",              "CONFIRM", "특식 메뉴", 3)
            ), "특식 메뉴", 3),

            new Scenario("C25 자연어 간접 표현 (저렴한 거·1개)", List.of(
                    new TurnSpec("저렴한 걸로 하나요", "ORDER",   "일반 메뉴", 1),
                    new TurnSpec("네",                 "CONFIRM", "일반 메뉴", 1)
            ), "일반 메뉴", 1),

            new Scenario("C26 자연어 간접 표현 (비싼 거·2개)", List.of(
                    new TurnSpec("비싼 걸로 둘이요", "ORDER",   "특식 메뉴", 2),
                    new TurnSpec("응",               "CONFIRM", "특식 메뉴", 2)
            ), "특식 메뉴", 2),

            new Scenario("C27 자연어 단계별 (특식→하나)", List.of(
                    new TurnSpec("특식이요", "ORDER",   "특식 메뉴", null),
                    new TurnSpec("하나요",  "ORDER",   "특식 메뉴", 1),
                    new TurnSpec("그래",    "CONFIRM", "특식 메뉴", 1)
            ), "특식 메뉴", 1),

            new Scenario("C28 자연어 일괄 (일반·4개)", List.of(
                    new TurnSpec("일반 메뉴 네 개", "ORDER",   "일반 메뉴", 4),
                    new TurnSpec("좋아",             "CONFIRM", "일반 메뉴", 4)
            ), "일반 메뉴", 4),

            new Scenario("C29 자연어 간접 표현 (좋은 거·1개→특식)", List.of(
                    new TurnSpec("좋은 걸로 하나 주세요", "ORDER",   "특식 메뉴", 1),
                    new TurnSpec("네",                    "CONFIRM", "특식 메뉴", 1)
            ), "특식 메뉴", 1),

            new Scenario("C30 자연어 번호 선택 (1번→일반·2개)", List.of(
                    new TurnSpec("1번으로 주세요", "ORDER",   "일반 메뉴", null),
                    new TurnSpec("두 개 주세요",   "ORDER",   "일반 메뉴", 2),
                    new TurnSpec("그래요",          "CONFIRM", "일반 메뉴", 2)
            ), "일반 메뉴", 2),

            // ── Group D: 취소·수정 흐름 ─────────────────────────────────────

            new Scenario("D31 ORDERING 취소 후 재주문", List.of(
                    new TurnSpec("일반 메뉴 주세요", "ORDER",   "일반 메뉴", null),
                    new TurnSpec("취소",              "CANCEL",  null,        null),
                    new TurnSpec("특식 2개 주세요",  "ORDER",   "특식 메뉴", 2),
                    new TurnSpec("네",               "CONFIRM", "특식 메뉴", 2)
            ), "특식 메뉴", 2),

            new Scenario("D32 CONFIRMING 거부·수량 수정 (특식 2→3개)", List.of(
                    new TurnSpec("특식 메뉴 2개 주세요", "ORDER",   "특식 메뉴", 2),
                    new TurnSpec("아니요",                "CANCEL",  "특식 메뉴", null),
                    new TurnSpec("3개 주세요",            "ORDER",   "특식 메뉴", 3),
                    new TurnSpec("네",                    "CONFIRM", "특식 메뉴", 3)
            ), "특식 메뉴", 3),

            new Scenario("D33 CONFIRMING 거부·수량 수정 (일반 3→1개)", List.of(
                    new TurnSpec("일반 메뉴 3개 주세요", "ORDER",   "일반 메뉴", 3),
                    new TurnSpec("아니요",                "CANCEL",  "일반 메뉴", null),
                    new TurnSpec("1개 주세요",            "ORDER",   "일반 메뉴", 1),
                    new TurnSpec("네",                    "CONFIRM", "일반 메뉴", 1)
            ), "일반 메뉴", 1),

            new Scenario("D34 CONFIRMING 취소 후 동일 메뉴 재주문 (특식 1→2개)", List.of(
                    new TurnSpec("특식 1개 주세요",  "ORDER",   "특식 메뉴", 1),
                    new TurnSpec("취소",              "CANCEL",  "특식 메뉴", null),
                    new TurnSpec("특식 2개 주세요",  "ORDER",   "특식 메뉴", 2),
                    new TurnSpec("맞습니다",          "CONFIRM", "특식 메뉴", 2)
            ), "특식 메뉴", 2),

            new Scenario("D35 단계별 후 거부·재입력 (일반 2→3개)", List.of(
                    new TurnSpec("일반 메뉴 주세요", "ORDER",   "일반 메뉴", null),
                    new TurnSpec("2개 주세요",        "ORDER",   "일반 메뉴", 2),
                    new TurnSpec("아니요",            "CANCEL",  "일반 메뉴", null),
                    new TurnSpec("3개 주세요",        "ORDER",   "일반 메뉴", 3),
                    new TurnSpec("그래",              "CONFIRM", "일반 메뉴", 3)
            ), "일반 메뉴", 3),

            new Scenario("D36 단계별 후 거부·재입력 (특식 1→2개)", List.of(
                    new TurnSpec("특식 메뉴 주세요", "ORDER",   "특식 메뉴", null),
                    new TurnSpec("1개 주세요",        "ORDER",   "특식 메뉴", 1),
                    new TurnSpec("아니요",            "CANCEL",  "특식 메뉴", null),
                    new TurnSpec("2개 주세요",        "ORDER",   "특식 메뉴", 2),
                    new TurnSpec("맞아",              "CONFIRM", "특식 메뉴", 2)
            ), "특식 메뉴", 2),

            new Scenario("D37 CONFIRMING 거부·수량 수정 (일반 3→1개·응)", List.of(
                    new TurnSpec("일반 3개 주세요", "ORDER",   "일반 메뉴", 3),
                    new TurnSpec("아니요",           "CANCEL",  "일반 메뉴", null),
                    new TurnSpec("1개 주세요",       "ORDER",   "일반 메뉴", 1),
                    new TurnSpec("응",               "CONFIRM", "일반 메뉴", 1)
            ), "일반 메뉴", 1),

            new Scenario("D38 CONFIRMING 거부·수량 수정 (특식 2→5개·오케이)", List.of(
                    new TurnSpec("특식 2개 주세요", "ORDER",   "특식 메뉴", 2),
                    new TurnSpec("아니요",           "CANCEL",  "특식 메뉴", null),
                    new TurnSpec("5개 주세요",       "ORDER",   "특식 메뉴", 5),
                    new TurnSpec("오케이",           "CONFIRM", "특식 메뉴", 5)
            ), "특식 메뉴", 5),

            new Scenario("D39 ORDERING 취소 후 동일 메뉴 재주문", List.of(
                    new TurnSpec("일반 메뉴 주세요", "ORDER",   "일반 메뉴", null),
                    new TurnSpec("취소",              "CANCEL",  null,        null),
                    new TurnSpec("일반 1개 주세요",  "ORDER",   "일반 메뉴", 1),
                    new TurnSpec("응",               "CONFIRM", "일반 메뉴", 1)
            ), "일반 메뉴", 1),

            new Scenario("D40 CONFIRMING 거부·수량 수정 (특식 3→4개·예)", List.of(
                    new TurnSpec("특식 메뉴 3개 주세요", "ORDER",   "특식 메뉴", 3),
                    new TurnSpec("아니요",               "CANCEL",  "특식 메뉴", null),
                    new TurnSpec("4개 주세요",           "ORDER",   "특식 메뉴", 4),
                    new TurnSpec("예",                   "CONFIRM", "특식 메뉴", 4)
            ), "특식 메뉴", 4),

            // ── Group E: 복합·경계 케이스 ────────────────────────────────────

            new Scenario("E41 UNKNOWN 개입 (일반·2개)", List.of(
                    new TurnSpec("일반 메뉴 주세요", "ORDER",   "일반 메뉴", null),
                    new TurnSpec("음...",             "UNKNOWN", "일반 메뉴", null),
                    new TurnSpec("2개 주세요",        "ORDER",   "일반 메뉴", 2),
                    new TurnSpec("네",                "CONFIRM", "일반 메뉴", 2)
            ), "일반 메뉴", 2),

            new Scenario("E42 UNKNOWN 개입 (특식·3개)", List.of(
                    new TurnSpec("특식 메뉴 주세요", "ORDER",   "특식 메뉴", null),
                    new TurnSpec("어...",             "UNKNOWN", "특식 메뉴", null),
                    new TurnSpec("3개 주세요",        "ORDER",   "특식 메뉴", 3),
                    new TurnSpec("맞습니다",          "CONFIRM", "특식 메뉴", 3)
            ), "특식 메뉴", 3),

            new Scenario("E43 잘못된 수량(0개) 후 재입력 (일반·1개)", List.of(
                    new TurnSpec("일반 메뉴 주세요", "ORDER",   "일반 메뉴", null),
                    new TurnSpec("0개 주세요",        "ORDER",   "일반 메뉴", null),
                    new TurnSpec("1개 주세요",        "ORDER",   "일반 메뉴", 1),
                    new TurnSpec("네",                "CONFIRM", "일반 메뉴", 1)
            ), "일반 메뉴", 1),

            new Scenario("E44 UNKNOWN 개입 후 정상 흐름 (특식·1개)", List.of(
                    new TurnSpec("특식 메뉴 주세요", "ORDER",   "특식 메뉴", null),
                    new TurnSpec("으음...",            "UNKNOWN", "특식 메뉴", null),
                    new TurnSpec("1개 주세요",        "ORDER",   "특식 메뉴", 1),
                    new TurnSpec("그래",              "CONFIRM", "특식 메뉴", 1)
            ), "특식 메뉴", 1),

            new Scenario("E45 AMBIGUOUS 경로 (아니+일반+줘→LLM)", List.of(
                    new TurnSpec("아니 일반으로 해줘", "ORDER",   "일반 메뉴", null),
                    new TurnSpec("2개 주세요",          "ORDER",   "일반 메뉴", 2),
                    new TurnSpec("네",                  "CONFIRM", "일반 메뉴", 2)
            ), "일반 메뉴", 2),

            new Scenario("E46 CONFIRMING 메뉴 정정 (특식→일반, AMBIGUOUS→LLM)", List.of(
                    new TurnSpec("특식 2개 주세요",      "ORDER",   "특식 메뉴", 2),
                    new TurnSpec("아니 일반으로 바꿔줘", "ORDER",   "일반 메뉴", 2),
                    new TurnSpec("네",                   "CONFIRM", "일반 메뉴", 2)
            ), "일반 메뉴", 2),

            new Scenario("E47 번호로 메뉴 선택 (2번→특식·1개)", List.of(
                    new TurnSpec("2번으로 주세요", "ORDER",   "특식 메뉴", null),
                    new TurnSpec("1개 주세요",     "ORDER",   "특식 메뉴", 1),
                    new TurnSpec("맞습니다",       "CONFIRM", "특식 메뉴", 1)
            ), "특식 메뉴", 1),

            new Scenario("E48 고유어 수량 (특식·둘이요)", List.of(
                    new TurnSpec("특식으로 줘", "ORDER",   "특식 메뉴", null),
                    new TurnSpec("둘이요",       "ORDER",   "특식 메뉴", 2),
                    new TurnSpec("맞아요",       "CONFIRM", "특식 메뉴", 2)
            ), "특식 메뉴", 2),

            new Scenario("E49 자연어 긴 문장 (일반·1개)", List.of(
                    new TurnSpec("배가 고파서 일반 메뉴 하나 시켜볼게요", "ORDER",   "일반 메뉴", 1),
                    new TurnSpec("네",                                    "CONFIRM", "일반 메뉴", 1)
            ), "일반 메뉴", 1),

            new Scenario("E50 자연어 고유어 (특식·셋)", List.of(
                    new TurnSpec("특식 메뉴 셋이요", "ORDER",   "특식 메뉴", 3),
                    new TurnSpec("그걸로 할게요",    "CONFIRM", "특식 메뉴", 3)
            ), "특식 메뉴", 3)
    );

    // ── API 키 체크 ───────────────────────────────────────────────────────────

    @BeforeAll
    static void checkApiKey() throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = readFromDotEnv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "GEMINI_API_KEY 미설정 — 정확도 평가 테스트 건너뜀");
    }

    // ── 메인 테스트 ───────────────────────────────────────────────────────────

    @Test
    void evaluateOrderAccuracy() throws Exception {
        List<ScenarioResult> results = new ArrayList<>();

        printHeader();

        for (int i = 0; i < SCENARIOS.size(); i++) {
            Scenario scenario = SCENARIOS.get(i);
            ScenarioResult result = runScenario(i + 1, scenario);
            results.add(result);
        }

        printMetricsTable(results);
        printFailedDetails(results);
    }

    // ── 시나리오 실행 ─────────────────────────────────────────────────────────

    private ScenarioResult runScenario(int no, Scenario scenario) throws Exception {
        System.out.printf("%n[시나리오 %d] %s%n", no, scenario.name());
        System.out.println("─".repeat(70));

        List<TurnResult> turnResults = new ArrayList<>();
        String sid = null;
        OrderResponse lastResponse = null;

        for (int i = 0; i < scenario.turns().size(); i++) {
            TurnSpec spec = scenario.turns().get(i);

            long start = System.currentTimeMillis();
            OrderResponse response = speak(sid, spec.input());
            long latencyMs = System.currentTimeMillis() - start;

            if (sid == null) sid = response.getSessionId();

            boolean intentOk = spec.expectedIntent().equals(response.getIntent());
            boolean menuOk = spec.expectedMenu() == null
                    || spec.expectedMenu().equals(response.getSlots().getMenu());
            boolean qtyOk = spec.expectedQty() == null
                    || spec.expectedQty().equals(response.getSlots().getQuantity());

            TurnResult tr = new TurnResult(spec, response, intentOk, menuOk, qtyOk, latencyMs);
            turnResults.add(tr);
            lastResponse = response;

            printTurnRow(i + 1, tr);
        }

        boolean completed = lastResponse != null
                && lastResponse.getResponse().contains("주문 완료");
        boolean orderCorrect = completed
                && scenario.finalMenu().equals(Objects.toString(lastResponse.getSlots().getMenu(), ""))
                && scenario.finalQty() == Objects.requireNonNullElse(lastResponse.getSlots().getQuantity(), -1);

        System.out.printf(" → 최종 주문: %s %s개  [%s]%n",
                completed ? Objects.toString(lastResponse.getSlots().getMenu(), "?") : "미완료",
                completed ? Objects.toString(lastResponse.getSlots().getQuantity(), "?") : "-",
                orderCorrect ? "O" : "X");

        return new ScenarioResult(scenario, turnResults, completed, orderCorrect);
    }

    // ── 출력 헬퍼 ─────────────────────────────────────────────────────────────

    private void printHeader() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                  주문 정확도 종합 평가 (Order Accuracy)              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
    }

    private void printTurnRow(int turnNo, TurnResult tr) {
        String input = tr.spec().input();
        if (input.length() > 18) input = input.substring(0, 17) + "…";

        System.out.printf("  턴%d  %-20s → intent=%-8s menu=%-8s qty=%-4s  [%s%s%s] %dms%n",
                turnNo,
                "\"" + input + "\"",
                tr.actual().getIntent(),
                Objects.toString(tr.actual().getSlots().getMenu(), "null"),
                Objects.toString(tr.actual().getSlots().getQuantity(), "null"),
                tr.intentOk() ? "O" : "X",
                tr.menuOk()   ? "O" : "X",
                tr.qtyOk()    ? "O" : "X",
                tr.latencyMs());
    }

    private void printMetricsTable(List<ScenarioResult> results) {
        long totalTurns    = results.stream().mapToLong(r -> r.turns().size()).sum();
        long intentOk      = results.stream().flatMap(r -> r.turns().stream()).filter(TurnResult::intentOk).count();
        long menuOk        = results.stream().flatMap(r -> r.turns().stream()).filter(TurnResult::menuOk).count();
        long qtyOk         = results.stream().flatMap(r -> r.turns().stream()).filter(TurnResult::qtyOk).count();
        long completed     = results.stream().filter(ScenarioResult::completed).count();
        long correct       = results.stream().filter(ScenarioResult::orderCorrect).count();
        double avgTurns    = results.stream().mapToInt(r -> r.turns().size()).average().orElse(0);
        double avgLatency  = results.stream().flatMap(r -> r.turns().stream())
                .mapToLong(TurnResult::latencyMs).average().orElse(0);

        int total = results.size();

        System.out.println();
        System.out.println("┌──────────────────────────────────────────────┬─────────────────┐");
        System.out.println("│ 지표                                         │       값        │");
        System.out.println("├──────────────────────────────────────────────┼─────────────────┤");
        System.out.printf( "│ 평가 시나리오 수                              │    %3d 건       │%n", total);
        System.out.printf( "│ 주문 완료율           (%2d / %2d)             │    %6.1f %%   │%n", completed, total, pct(completed, total));
        System.out.printf( "│ 최종 주문 정확도       (%2d / %2d)            │    %6.1f %%   │%n", correct, total, pct(correct, total));
        System.out.println("├──────────────────────────────────────────────┼─────────────────┤");
        System.out.printf( "│ 턴별 Intent 정확도    (%2d / %2d)             │    %6.1f %%   │%n", intentOk, totalTurns, pct(intentOk, totalTurns));
        System.out.printf( "│ 턴별 Menu 슬롯 정확도 (%2d / %2d)             │    %6.1f %%   │%n", menuOk, totalTurns, pct(menuOk, totalTurns));
        System.out.printf( "│ 턴별 Qty  슬롯 정확도 (%2d / %2d)             │    %6.1f %%   │%n", qtyOk, totalTurns, pct(qtyOk, totalTurns));
        System.out.println("├──────────────────────────────────────────────┼─────────────────┤");
        System.out.printf( "│ 평균 주문 소요 턴                             │   %5.1f 턴     │%n", avgTurns);
        System.out.printf( "│ 평균 응답 레이턴시                            │  %6.0f ms     │%n", avgLatency);
        System.out.println("└──────────────────────────────────────────────┴─────────────────┘");
    }

    private void printFailedDetails(List<ScenarioResult> results) {
        List<String> failures = new ArrayList<>();

        for (ScenarioResult sr : results) {
            for (int i = 0; i < sr.turns().size(); i++) {
                TurnResult tr = sr.turns().get(i);
                if (!tr.allOk()) {
                    failures.add(String.format(
                            "[%s / 턴%d] 입력=\"%s\"  예측: intent=%-8s menu=%-8s qty=%s  기대: intent=%-8s menu=%-8s qty=%s",
                            sr.scenario().name(), i + 1,
                            tr.spec().input(),
                            tr.actual().getIntent(),
                            Objects.toString(tr.actual().getSlots().getMenu(), "null"),
                            Objects.toString(tr.actual().getSlots().getQuantity(), "null"),
                            tr.spec().expectedIntent(),
                            Objects.toString(tr.spec().expectedMenu(), "(무관)"),
                            Objects.toString(tr.spec().expectedQty(), "(무관)")));
                }
            }
            if (!sr.orderCorrect()) {
                failures.add(String.format(
                        "[%s] 최종 주문 불일치  기대=%s %d개  실제=%s %s개",
                        sr.scenario().name(),
                        sr.scenario().finalMenu(), sr.scenario().finalQty(),
                        sr.completed() ? Objects.toString(sr.turns().get(sr.turns().size() - 1).actual().getSlots().getMenu(), "?") : "미완료",
                        sr.completed() ? Objects.toString(sr.turns().get(sr.turns().size() - 1).actual().getSlots().getQuantity(), "?") : "-"));
            }
        }

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("✔ 모든 시나리오 완벽 정답!");
            return;
        }
        System.out.printf("=== 실패 항목 (%d건) ===%n%n", failures.size());
        failures.forEach(System.out::println);
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

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

    private double pct(long correct, long total) {
        return total == 0 ? 0.0 : 100.0 * correct / total;
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
