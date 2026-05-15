package capstone2.voisk;

import capstone2.voisk.config.GeminiProperties;
import capstone2.voisk.dto.SlotExtractionResult;
import capstone2.voisk.entity.OrderSession;
import capstone2.voisk.entity.OrderStatus;
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
 * OrderAccuracyEvaluationTest의 50개 시나리오를 LLM 단독으로 평가하는 테스트.
 * 키워드 라우팅 없이 모든 턴을 Gemini에 직접 호출하고,
 * OrderService와 동일한 상태 전이 규칙을 로컬에서 시뮬레이션한다.
 *
 * 실행: ./gradlew test --tests "capstone2.voisk.LlmOnlyOrderAccuracyEvaluationTest"
 */
class LlmOnlyOrderAccuracyEvaluationTest {

    // ── 데이터 모델 ────────────────────────────────────────────────────────────

    record TurnSpec(String input, String expectedIntent, String expectedMenu, Integer expectedQty) {}

    record Scenario(String name, List<TurnSpec> turns, String finalMenu, int finalQty) {}

    record TurnResult(TurnSpec spec, SlotExtractionResult actual, boolean intentOk, boolean menuOk, boolean qtyOk, long latencyMs) {
        boolean allOk() { return intentOk && menuOk && qtyOk; }
    }

    record ScenarioResult(Scenario scenario, List<TurnResult> turns, boolean completed, boolean orderCorrect) {}

    // ── 시나리오 정의 (OrderAccuracyEvaluationTest와 동일) ────────────────────

    static final List<Scenario> SCENARIOS = List.of(

            // ── Group A: 정형 단계별 주문 ─────────────────────────────────────

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

            // ── Group B: 메뉴+수량 동시 입력 ────────────────────────────────────

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

            // ── Group C: 자연어 발화 ──────────────────────────────────────────

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

            // ── Group D: 취소·수정 흐름 ──────────────────────────────────────

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

    // ── Gemini 프롬프트 ───────────────────────────────────────────────────────

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

    private static final Map<String, Integer> MENU_PRICE = Map.of("일반 메뉴", 8000, "특식 메뉴", 12000);

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
    void evaluateLlmOnlyOrderAccuracy() {
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

    private ScenarioResult runScenario(int no, Scenario scenario) {
        System.out.printf("%n[시나리오 %d] %s%n", no, scenario.name());
        System.out.println("─".repeat(70));

        List<TurnResult> turnResults = new ArrayList<>();
        OrderSession session = new OrderSession();

        for (int i = 0; i < scenario.turns().size(); i++) {
            TurnSpec spec = scenario.turns().get(i);

            long start = System.currentTimeMillis();
            SlotExtractionResult result = callGemini(spec.input(), session);
            long latencyMs = System.currentTimeMillis() - start;

            // OrderService와 동일한 상태 전이 시뮬레이션
            applyStateTransition(session, result);

            boolean intentOk = spec.expectedIntent().equals(result.intent());
            boolean menuOk   = spec.expectedMenu() == null || spec.expectedMenu().equals(session.getMenu());
            boolean qtyOk    = spec.expectedQty()  == null || spec.expectedQty().equals(session.getQuantity());

            TurnResult tr = new TurnResult(spec, result, intentOk, menuOk, qtyOk, latencyMs);
            turnResults.add(tr);
            printTurnRow(i + 1, tr, session);
        }

        boolean completed    = session.getStatus() == OrderStatus.DONE;
        boolean orderCorrect = completed
                && scenario.finalMenu().equals(Objects.toString(session.getMenu(), ""))
                && scenario.finalQty() == Objects.requireNonNullElse(session.getQuantity(), -1);

        System.out.printf(" → 최종 주문: %s %s개  [%s]%n",
                completed ? Objects.toString(session.getMenu(), "?") : "미완료",
                completed ? Objects.toString(session.getQuantity(), "?") : "-",
                orderCorrect ? "O" : "X");

        return new ScenarioResult(scenario, turnResults, completed, orderCorrect);
    }

    /**
     * OrderService.process()의 상태 전이 로직을 로컬에서 동일하게 재현한다.
     */
    private void applyStateTransition(OrderSession session, SlotExtractionResult result) {
        String intent = result.intent();

        if ("CANCEL".equals(intent)) {
            if (session.getStatus() == OrderStatus.CONFIRMING) {
                // 확인 단계 거부 → 메뉴 유지, 수량만 초기화
                session.setQuantity(null);
                session.setStatus(OrderStatus.ORDERING);
            } else {
                session.reset();
            }
            return;
        }

        if (session.getStatus() == OrderStatus.CONFIRMING && "CONFIRM".equals(intent)) {
            session.setStatus(OrderStatus.DONE);
            return;
        }

        if ("UNKNOWN".equals(intent)) {
            return; // 상태 변경 없음
        }

        // ORDER: 슬롯 채우기
        if (result.menu() != null && MENU_PRICE.containsKey(result.menu())) {
            session.setMenu(result.menu());
        }
        if (result.quantity() != null) {
            if (result.quantity() >= 1) {
                session.setQuantity(result.quantity());
            }
            // 0 이하 수량은 무시 (session 슬롯 그대로 유지)
        }

        if (session.isSlotsComplete()) {
            session.setStatus(OrderStatus.CONFIRMING);
        }
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

            JsonNode root    = MAPPER.readTree(raw);
            String content   = root.path("candidates").get(0)
                                   .path("content").path("parts").get(0)
                                   .path("text").asText();
            JsonNode parsed  = MAPPER.readTree(content);

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
        if (session.getStatus() == OrderStatus.CONFIRMING) {
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
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║          주문 정확도 평가 — LLM 단독 (Gemini Direct)               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
    }

    private void printTurnRow(int turnNo, TurnResult tr, OrderSession session) {
        String input = tr.spec().input();
        if (input.length() > 18) input = input.substring(0, 17) + "…";

        System.out.printf("  턴%d  %-20s → intent=%-8s menu=%-8s qty=%-4s  [%s%s%s] %dms%n",
                turnNo,
                "\"" + input + "\"",
                tr.actual().intent(),
                Objects.toString(session.getMenu(), "null"),
                Objects.toString(session.getQuantity(), "null"),
                tr.intentOk() ? "O" : "X",
                tr.menuOk()   ? "O" : "X",
                tr.qtyOk()    ? "O" : "X",
                tr.latencyMs());
    }

    private void printMetricsTable(List<ScenarioResult> results) {
        long totalTurns   = results.stream().mapToLong(r -> r.turns().size()).sum();
        long intentOk     = results.stream().flatMap(r -> r.turns().stream()).filter(TurnResult::intentOk).count();
        long menuOk       = results.stream().flatMap(r -> r.turns().stream()).filter(TurnResult::menuOk).count();
        long qtyOk        = results.stream().flatMap(r -> r.turns().stream()).filter(TurnResult::qtyOk).count();
        long completed    = results.stream().filter(ScenarioResult::completed).count();
        long correct      = results.stream().filter(ScenarioResult::orderCorrect).count();
        double avgTurns   = results.stream().mapToInt(r -> r.turns().size()).average().orElse(0);
        double avgLatency = results.stream().flatMap(r -> r.turns().stream())
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
                            "[%s / 턴%d] 입력=\"%s\"  예측: intent=%-8s  기대: intent=%-8s menu=%-8s qty=%s",
                            sr.scenario().name(), i + 1,
                            tr.spec().input(),
                            tr.actual().intent(),
                            tr.spec().expectedIntent(),
                            Objects.toString(tr.spec().expectedMenu(), "(무관)"),
                            Objects.toString(tr.spec().expectedQty(), "(무관)")));
                }
            }
            if (!sr.orderCorrect()) {
                TurnResult last = sr.turns().get(sr.turns().size() - 1);
                failures.add(String.format(
                        "[%s] 최종 주문 불일치  기대=%s %d개  실제=%s %s개",
                        sr.scenario().name(),
                        sr.scenario().finalMenu(), sr.scenario().finalQty(),
                        sr.completed() ? Objects.toString(last.actual().menu(), "?") : "미완료",
                        sr.completed() ? Objects.toString(last.actual().quantity(), "?") : "-"));
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
