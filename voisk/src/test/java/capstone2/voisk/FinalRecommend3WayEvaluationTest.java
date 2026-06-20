package capstone2.voisk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 추천 3방식(룰 · 임베딩 · LLM) <b>최종 측정</b> — {@code 추천_3방식_측정설계.md} 구현.
 *
 * <p><b>실서버·실DB·API 방식:</b> Spring 컨텍스트를 띄우지 않고 <b>실제로 떠 있는 운영 서버</b>(기본 localhost:8080)에
 * HTTP로 직접 호출한다. 따라서 데모/운영이 쓰는 바로 그 MySQL·pgvector·Gemini를 그대로 평가한다. 서버가 없으면
 * 실패가 아니라 <b>skip</b>된다(Assumptions). 임베딩/LLM arm이 죽어 있으면 해당 arm만 "N/A"로 표기된다.
 * <pre>
 *   ./gradlew bootRun                       # 실DB 연결된 운영 서버 기동
 *   ./gradlew test --tests "*FinalRecommend3WayEvaluationTest" \
 *       -Dvoisk.baseUrl=http://localhost:8080 -Dvoisk.storeId=1
 * </pre>
 *
 * <p><b>채점:</b> 정답을 메뉴 이름으로 박지 않고 <b>시드(이름/카테고리 부분문자열)</b>로 선언 → 실행 시점에 실서버
 * 카탈로그로 <b>menuId 집합</b>으로 해석한다(라벨 드리프트 0). 채점은 menuId 교집합·순위 기준.
 *
 * <p><b>측정설계 매핑:</b>
 * <ul>
 *   <li><b>T1</b> 유형별 정확도 — 8 발화 유형 × {@code Hit@1/3/5}·{@code MRR} (규모 축은 -Dvoisk.storeId로 매장 교체해 반복)</li>
 *   <li><b>T2</b> 임베딩 한계 진단 — 부정/제외 <b>위반(violation)</b> 건수 + 임베딩 <b>점수 분리도</b>(1위−꼴찌 score차)</li>
 *   <li><b>T3</b> 비용·지연 — 방식별 <b>지연 p50/p95</b> (T1/T2 호출 로그 재사용)</li>
 * </ul>
 *
 * <p>규모 축(10/30/50)은 매장(storeId)별로 카탈로그를 분리 시드한 뒤 {@code -Dvoisk.storeId}를 바꿔
 * 본 테스트를 반복 실행해 수집한다(한 번의 실행은 한 카탈로그를 측정).
 */
class FinalRecommend3WayEvaluationTest {

    private static final String BASE_URL = System.getProperty("voisk.baseUrl", "http://localhost:8080");
    private static final long STORE_ID = Long.getLong("voisk.storeId", 1L);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static RestClient client;

    /** 실서버 카탈로그: menuId → {name, category}. @BeforeAll에서 1회 로드. */
    private static Map<Long, CatalogMenu> catalog;
    private static boolean serverUp;

    record CatalogMenu(long menuId, String name, String category) {}

    @BeforeAll
    static void loadCatalog() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(30));
        client = RestClient.builder().baseUrl(BASE_URL).requestFactory(factory).build();

        catalog = new LinkedHashMap<>();
        try {
            String raw = client.post()
                    .uri("/api/order/restaurants/{id}/menus/cache", STORE_ID)
                    .retrieve()
                    .body(String.class);
            JsonNode menus = MAPPER.readTree(raw).path("menus");
            for (JsonNode m : menus) {
                long id = m.path("menuId").asLong();
                catalog.put(id, new CatalogMenu(
                        id,
                        m.path("name").asText(""),
                        m.path("category").path("name").asText("")));
            }
            serverUp = !catalog.isEmpty();
        } catch (Exception e) {
            serverUp = false;
            System.out.printf("%n[skip] 실서버(%s)에서 카탈로그를 받지 못했습니다. bootRun으로 서버를 띄운 뒤 실행하세요. (%s)%n",
                    BASE_URL, e.getMessage());
        }
    }

    // ── 발화 유형 8종 (측정설계 §2 축1) ────────────────────────────────────────────

    enum Phenom {
        CLEAN("정형"), FILLER("추임새"), PARAPHRASE("풀어설명"), STT_ERROR("발음뭉개짐"),
        NEGATION("부정/제외"), PRICE("가격제약"), RELATION("관계형"), SYNONYM("동의어");
        final String label;
        Phenom(String label) { this.label = label; }
    }

    /**
     * @param relevant  반환되면 정답으로 보는 시드. 실카탈로그로 menuId 집합 해석
     * @param forbidden "X 말고" 위반 판정용 시드. 없으면 빈 리스트
     */
    record Case(Phenom phenom, String input, List<String> relevant, List<String> forbidden, String note) {
        static Case of(Phenom p, String input, List<String> relevant, String note) {
            return new Case(p, input, relevant, List.of(), note);
        }
        static Case neg(Phenom p, String input, List<String> relevant, List<String> forbidden, String note) {
            return new Case(p, input, relevant, forbidden, note);
        }
    }

    /**
     * 한 방식의 호출 결과. ids는 추천 순서대로, scores는 임베딩 점수 분리도 진단용(다른 방식은 비어 있음),
     * promptTokens/outputTokens는 LLM 비용 산출용(다른 방식은 0).
     */
    record MethodResult(List<Long> ids, List<String> names, List<Double> scores,
                        int promptTokens, int totalTokens, long latencyMs, boolean available) {
        // gemini-2.5는 thinking 토큰을 totalTokenCount에 포함하고 출력 단가로 과금 → 과금 출력 = total - prompt
        int billableOutputTokens() { return Math.max(0, totalTokens - promptTokens); }
    }
    record CaseResult(Case c, MethodResult embed, MethodResult rule, MethodResult llm) {}

    // ── 정답 시드 (storeId=1 라이브 DB 어휘 기준; 카테고리명 우선) ────────────────────
    static final List<String> SWEET = List.of("카라멜마키아토", "바닐라라떼", "초코라떼", "티라미수");
    static final List<String> COLD = List.of("딸기스무디", "레몬에이드");
    static final List<String> STRONG_COFFEE = List.of("아메리카노", "카페라떼");
    static final List<String> FRUITY = List.of("딸기스무디", "레몬에이드");
    static final List<String> SMOOTH = List.of("카페라떼", "바닐라라떼", "녹차라떼", "초코라떼");
    static final List<String> NON_COFFEE = List.of("논커피");          // 카테고리 전체
    static final List<String> FOOD = List.of("디저트");                // 카테고리 전체
    static final List<String> SWEET_FOOD = List.of("크로플", "티라미수");
    static final List<String> CHEAP = List.of("아메리카노");           // 가성비/저가 대표
    // 부정 위반 판정용: "논커피"가 "커피"를 substring 포함하므로 카테고리명 대신 메뉴명 직접 나열
    static final List<String> COFFEE_MENUS = List.of("아메리카노", "카페라떼", "바닐라라떼", "카라멜마키아토");
    static final List<String> DRINKS = List.of("아메리카노", "카페라떼", "바닐라라떼", "카라멜마키아토",
            "녹차라떼", "딸기스무디", "초코라떼", "레몬에이드");

    // ── 테스트 케이스 (유형별 ≥4) ─────────────────────────────────────────────────

    static final List<Case> CASES = List.of(
            // ① 정형
            Case.of(Phenom.CLEAN, "달콤한 음료 추천해줘", SWEET, "기본 단맛"),
            Case.of(Phenom.CLEAN, "시원한 거 마시고 싶어요", COLD, "기본 시원함"),
            Case.of(Phenom.CLEAN, "진한 커피 주세요", STRONG_COFFEE, "기본 진함"),
            Case.of(Phenom.CLEAN, "빵 종류 추천해주세요", FOOD, "푸드 카테고리"),
            // ② 추임새
            Case.of(Phenom.FILLER, "어... 그... 달달한 거 있나요?", SWEET, "앞쪽 추임새"),
            Case.of(Phenom.FILLER, "음 저기요 시원한 음료 좀 주세요", COLD, "중간 추임새"),
            Case.of(Phenom.FILLER, "아 뭐랄까 진한 커피요", STRONG_COFFEE, "군말 + 단축"),
            Case.of(Phenom.FILLER, "에... 단 디저트 하나 추천해주세요", SWEET_FOOD, "추임새 + 디저트"),
            // ③ 풀어설명 (긴문장 + 핵심추출)
            Case.of(Phenom.PARAPHRASE, "당 떨어졌는데 기운 나게 단 거 없어요?", SWEET, "상황 설명형 단맛"),
            Case.of(Phenom.PARAPHRASE, "잠 좀 깨야 해서 진하고 쓴 커피요", STRONG_COFFEE, "의도 설명형 진함"),
            Case.of(Phenom.PARAPHRASE, "더워서 얼음 들어간 시원한 음료 마시고 싶어요", COLD, "상황 설명형 시원함"),
            Case.of(Phenom.PARAPHRASE, "출출한데 든든하게 먹을 만한 거 있을까요", FOOD, "상황 설명형 푸드"),
            // ④ 발음뭉개짐 / STT 오인식 (STT 입력 특성 집중 — 받침·모음·연음·띄어쓰기·은어·표기변형)
            Case.of(Phenom.STT_ERROR, "달달한거추천해죠", SWEET, "띄어쓰기 붕괴 + 어미 오인식(줘→죠)"),
            Case.of(Phenom.STT_ERROR, "시원항거 업나요", COLD, "받침 오인식(시원한→시원항)"),
            Case.of(Phenom.STT_ERROR, "아아 한잔 주세요", List.of("아메리카노"), "은어(아아=아이스 아메리카노)"),
            Case.of(Phenom.STT_ERROR, "뜨아 한잔 주세요", List.of("아메리카노"), "은어(뜨아=뜨거운 아메리카노)"),
            Case.of(Phenom.STT_ERROR, "초콜렛 들어간 달달이", List.of("초코라떼"), "표기 흔들림 + 구어"),
            Case.of(Phenom.STT_ERROR, "카페라데 주세요", List.of("카페라떼"), "표기 오인식(라떼→라데)"),
            Case.of(Phenom.STT_ERROR, "시언한 음뇨 머있어요", COLD, "모음+연음(시원한 음료→시언한 음뇨)"),
            Case.of(Phenom.STT_ERROR, "딸기스무디 업서요", List.of("딸기스무디"), "받침 오인식(없어요→업서요)"),
            Case.of(Phenom.STT_ERROR, "초콜릿라떼 되나요", List.of("초코라떼"), "표기 변형(초코→초콜릿)"),
            Case.of(Phenom.STT_ERROR, "달콤한거 머있나요", SWEET, "구어 연음(뭐있나요→머있나요)"),
            // ⑤ 부정/제외 (forbidden 위반 체크 → T2)
            Case.neg(Phenom.NEGATION, "에스프레소 말고 다른 음료요", NON_COFFEE, COFFEE_MENUS, "에스프레소(커피) 제외"),
            Case.neg(Phenom.NEGATION, "커피 말고 다른 거 추천해줘", NON_COFFEE, COFFEE_MENUS, "커피 카테고리명 직접 부정"),
            Case.neg(Phenom.NEGATION, "쓴 거 말고 달달한 걸로", SWEET, List.of("아메리카노"), "미각 부정 + 긍정 혼합"),
            Case.neg(Phenom.NEGATION, "음료 말고 먹을 거 주세요", FOOD, DRINKS, "음료 전체 제외 → 디저트만"),
            // ⑥ 가격제약 (수치·범위 → T2)
            Case.of(Phenom.PRICE, "제일 저렴한 거 추천해줘", CHEAP, "최저가 의도"),
            Case.of(Phenom.PRICE, "가성비 좋은 음료 있어요?", CHEAP, "가성비"),
            Case.of(Phenom.PRICE, "부담 없는 가격으로 마실 거", CHEAP, "저가 에두름"),
            Case.of(Phenom.PRICE, "5천원 이하 단 거 없나요", SWEET, "명시 상한 + 단맛(임베딩 구조 한계)"),
            // ⑦ 관계형
            Case.of(Phenom.RELATION, "커피랑 같이 먹을 디저트 추천", FOOD, "관계형(곁들임)"),
            Case.of(Phenom.RELATION, "케이크랑 어울리는 음료요", DRINKS, "관계형(페어링)"),
            Case.of(Phenom.RELATION, "친구랑 나눠 먹기 좋은 디저트", FOOD, "관계형(상황)"),
            // ⑧ 동의어/어휘불일치
            Case.of(Phenom.SYNONYM, "아메리카노 같은 거", STRONG_COFFEE, "예시 기반 유사어"),
            Case.of(Phenom.SYNONYM, "라떼류 부드러운 거", SMOOTH, "어휘 변형(라떼류)"),
            Case.of(Phenom.SYNONYM, "에이드 같은 상큼한 거", FRUITY, "동의어(에이드=상큼)"),
            Case.of(Phenom.SYNONYM, "스무디 종류 있어요?", FRUITY, "카테고리 외 어휘")
    );

    // ── 메인 ──────────────────────────────────────────────────────────────────────

    @Test
    void evaluate3Way() {
        Assumptions.assumeTrue(serverUp,
                "실서버(" + BASE_URL + ")가 떠 있지 않아 평가를 건너뜁니다. ./gradlew bootRun 후 다시 실행하세요.");

        List<CaseResult> results = new ArrayList<>();
        for (Case c : CASES) {
            MethodResult embed = call("/api/recommend", c.input());
            MethodResult rule = call("/api/recommend/rule", c.input());
            MethodResult llm = call("/api/recommend/llm", c.input());
            results.add(new CaseResult(c, embed, rule, llm));
        }

        printHeader(results);
        printT1(results);
        printT2(results);
        printT3(results);
        printT4Recall();
    }

    /**
     * T4 단독 실행 — recall@K만 측정(임베딩 arm만 호출, LLM/룰 비호출 → 빠르고 비용 0).
     * <pre>./gradlew test --tests "*FinalRecommend3WayEvaluationTest.recallAtK" -Dvoisk.storeId=1</pre>
     */
    @Test
    void recallAtK() {
        Assumptions.assumeTrue(serverUp,
                "실서버(" + BASE_URL + ")가 떠 있지 않아 평가를 건너뜁니다. ./gradlew bootRun 후 다시 실행하세요.");
        System.out.printf("%n[recall@K 단독] 서버 %s · storeId=%d · 메뉴 %d개 · 라벨케이스 %d개%n",
                BASE_URL, STORE_ID, catalog.size(), (int) CASES.stream().filter(this::hasLabel).count());
        printT4Recall();
    }

    /**
     * T5 — 펀넬(임베딩 top-K → LLM 재랭킹) vs LLM 단독 비교.
     * 같은 케이스를 LLM 단독 / 펀넬 K=20 / 펀넬 K=30 에 던져 Hit@5·지연·토큰·비용을 나란히 잰다.
     * 핵심 관찰: <b>후보수가 줄면 출력(thinking) 토큰·지연이 실제로 줄어드는가</b> + Hit@5는 유지되는가.
     * <pre>./gradlew test --tests "*FinalRecommend3WayEvaluationTest.funnelVsLlm" -Dvoisk.storeId=3</pre>
     */
    @Test
    void funnelVsLlm() {
        Assumptions.assumeTrue(serverUp,
                "실서버(" + BASE_URL + ")가 떠 있지 않아 평가를 건너뜁니다. ./gradlew bootRun 후 다시 실행하세요.");

        List<Case> labeled = CASES.stream().filter(this::hasLabel).toList();
        List<MethodResult> llm = new ArrayList<>();
        List<MethodResult> f20 = new ArrayList<>();
        List<MethodResult> f30 = new ArrayList<>();
        List<Case> cs = new ArrayList<>();
        for (Case c : labeled) {
            llm.add(call("/api/recommend/llm", c.input()));
            f20.add(call("/api/recommend/funnel", c.input(), 20));
            f30.add(call("/api/recommend/funnel", c.input(), 30));
            cs.add(c);
        }

        System.out.printf("%n── T5. 펀넬 vs LLM 단독 — storeId=%d · 메뉴 %d개 · 라벨케이스 %d개 ────────────%n",
                STORE_ID, catalog.size(), labeled.size());
        System.out.println("┌──────────────┬────────┬──────────┬──────────┬──────────┬───────────┐");
        System.out.println("│ 방식         │ Hit@5  │ 지연 p50 │ 입력 tok │ 출력 tok │ 비용/호출 │");
        System.out.println("├──────────────┼────────┼──────────┼──────────┼──────────┼───────────┤");
        printFunnelRow("LLM 단독", cs, llm);
        printFunnelRow("펀넬 K=20", cs, f20);
        printFunnelRow("펀넬 K=30", cs, f30);
        System.out.println("└──────────────┴────────┴──────────┴──────────┴──────────┴───────────┘");
        System.out.println("  ※ 출력 tok = total−prompt (thinking 포함). 후보수 줄여 이게 줄면 = 펀넬 효과의 핵심 증거.");
        System.out.printf("  ※ N=%d 기준: K≥N이면 펀넬은 no-op(LLM 단독과 동일). 실질 대비는 K<N 구간에서만.%n", catalog.size());
    }

    /**
     * T6 — 펀넬 정확도-vs-K 스윕. x=K, y=Hit@5(+지연·토큰·비용)를 K값별로 찍어 <b>정확도 봉우리 K</b>를 그래프로 정한다.
     * T1~T5와 동일한 37케이스·동일 채점(menuId 해석·Hit@5)·동일 서버/모델을 그대로 재사용 → 직접 비교 가능.
     * <pre>./gradlew test --tests "*FinalRecommend3WayEvaluationTest.kSweep" -Dvoisk.storeId=3 --info</pre>
     */
    // 기본은 N≤50용. N=100 스윕은 -Dvoisk.sweepKs=10,20,30,40,50,60,75,100 로 덮어쓴다(봉우리 우측 이동 확인).
    private static final int[] SWEEP_KS = parseSweepKs(System.getProperty("voisk.sweepKs", "5,10,15,20,25,30,40,50"));

    private static int[] parseSweepKs(String csv) {
        String[] parts = csv.split(",");
        int[] ks = new int[parts.length];
        for (int i = 0; i < parts.length; i++) ks[i] = Integer.parseInt(parts[i].trim());
        return ks;
    }

    @Test
    void kSweep() {
        Assumptions.assumeTrue(serverUp,
                "실서버(" + BASE_URL + ")가 떠 있지 않아 평가를 건너뜁니다. ./gradlew bootRun 후 다시 실행하세요.");

        List<Case> labeled = CASES.stream().filter(this::hasLabel).toList();
        System.out.printf("%n── T6. 펀넬 정확도-vs-K 스윕 — storeId=%d · 메뉴 %d개 · 라벨케이스 %d개 ────────────%n",
                STORE_ID, catalog.size(), labeled.size());
        System.out.println("┌──────────────┬────────┬──────────┬──────────┬──────────┬───────────┐");
        System.out.println("│ K (후보 수)  │ Hit@5  │ 지연 p50 │ 입력 tok │ 출력 tok │ 비용/호출 │");
        System.out.println("├──────────────┼────────┼──────────┼──────────┼──────────┼───────────┤");
        for (int k : SWEEP_KS) {
            List<MethodResult> rs = new ArrayList<>();
            for (Case c : labeled) {
                rs.add(call("/api/recommend/funnel", c.input(), k));
            }
            printFunnelRow("K=" + k, labeled, rs);
        }
        System.out.println("└──────────────┴────────┴──────────┴──────────┴──────────┴───────────┘");
        System.out.printf("  ※ x=K, y=Hit@5 → 정확도 봉우리 K를 그래프로 결정. K≥N(%d)이면 전체와 동일(LLM 단독 등가).%n", catalog.size());
        System.out.println("  ※ T1~T5와 동일 케이스·채점·서버 → 같은 그래프에 얹어 비교 가능. 출력 tok=total−prompt(thinking 포함).");
    }

    /**
     * T7 — 규모(N) 확장성: 각 매장에서 LLM 단독 vs 펀넬(K=25) 한 점씩. storeId=1/2/3/4(N=10/30/50/100) 반복.
     * x=N 그래프(LLM단독 vs 펀넬)의 데이터 포인트를 만든다. T1~T6과 동일 케이스·채점.
     * <pre>./gradlew test --tests "*FinalRecommend3WayEvaluationTest.scaling" -Dvoisk.storeId=4 --info</pre>
     */
    @Test
    void scaling() {
        Assumptions.assumeTrue(serverUp,
                "실서버(" + BASE_URL + ")가 떠 있지 않아 평가를 건너뜁니다. ./gradlew bootRun 후 다시 실행하세요.");

        List<Case> labeled = CASES.stream().filter(this::hasLabel).toList();
        List<MethodResult> llm = new ArrayList<>();
        List<MethodResult> f25 = new ArrayList<>();
        for (Case c : labeled) {
            llm.add(call("/api/recommend/llm", c.input()));
            f25.add(call("/api/recommend/funnel", c.input(), 25));
        }
        System.out.printf("%n── T7. 확장성 포인트 — storeId=%d · N=%d · 라벨케이스 %d ────────────%n",
                STORE_ID, catalog.size(), labeled.size());
        System.out.println("┌──────────────┬────────┬──────────┬──────────┬──────────┬───────────┐");
        System.out.println("│ 방식         │ Hit@5  │ 지연 p50 │ 입력 tok │ 출력 tok │ 비용/호출 │");
        System.out.println("├──────────────┼────────┼──────────┼──────────┼──────────┼───────────┤");
        printFunnelRow("LLM 단독", labeled, llm);
        printFunnelRow("펀넬 K=25", labeled, f25);
        System.out.println("└──────────────┴────────┴──────────┴──────────┴──────────┴───────────┘");
        System.out.printf("  ※ N=%d. K=25≥N이면 펀넬=LLM단독(no-op). x=N으로 4매장(10/30/50/100) 묶어 확장성 그래프.%n", catalog.size());
    }

    private void printFunnelRow(String label, List<Case> cs, List<MethodResult> rs) {
        List<MethodResult> ok = rs.stream().filter(MethodResult::available).toList();
        if (ok.isEmpty()) {
            System.out.printf("│ %-11s  │ %6s │ %8s │ %8s │ %8s │ %9s │%n", label, "N/A", "N/A", "N/A", "N/A", "N/A");
            return;
        }
        int n = cs.size();
        long hit = 0;
        for (int i = 0; i < n; i++) if (hitAtK(rs.get(i), cs.get(i), 5)) hit++;
        List<Long> lat = ok.stream().map(MethodResult::latencyMs).sorted().toList();
        double avgIn = ok.stream().mapToInt(MethodResult::promptTokens).average().orElse(0);
        double avgOut = ok.stream().mapToInt(MethodResult::billableOutputTokens).average().orElse(0);
        // gemini-2.5-flash 단가(per 1M): 입력 $0.30 / 출력 $2.50 · ₩1380/$
        double krw = (avgIn / 1_000_000 * 0.30 + avgOut / 1_000_000 * 2.50) * 1380;
        System.out.printf("│ %-11s  │ %5.1f%% │ %7dms │ %8.0f │ %8.0f │ %8.2f₩ │%n",
                label, pct(hit, n), percentile(lat, 50), avgIn, avgOut, krw);
    }

    // ── T4. 임베딩 recall@K (리트리버 성능) — top-5 메인 호출과 분리된 topK=100 별도 패스 ──────
    //   Hit@5(T1)=임베딩을 "랭커"로 본 지표. recall@K(여기)=임베딩을 "리트리버"로 본 지표(넓은 K).
    //   펀넬(검색→LLM)의 후보 입구 K를 정하는 곡선. T1/T2 수치는 건드리지 않는다(별도 호출).
    private static final int RECALL_POOL = 100;
    private static final int[] RECALL_KS = {1, 3, 5, 10, 20, 30, 50, 100};

    private void printT4Recall() {
        System.out.println();
        System.out.println("── T4. 임베딩 recall@K (리트리버 성능, topK=" + RECALL_POOL + " 별도 패스) ───────────────");

        List<Case> labeled = CASES.stream().filter(this::hasLabel).toList();
        // 케이스별: 임베딩 랭크드 리스트에서 정답이 처음 등장한 순위(1-base, 없으면 -1)
        List<Integer> goldRanks = new ArrayList<>();
        for (Case c : labeled) {
            MethodResult embed = callEmbedRanked(c.input(), RECALL_POOL);
            goldRanks.add(firstGoldRank(embed, c));
        }

        long armOk = goldRanks.stream().filter(r -> r != Integer.MIN_VALUE).count();
        if (armOk == 0) {
            System.out.println("   (임베딩 arm N/A — recall 측정 불가)");
            return;
        }

        System.out.println("┌────────┬──────────┬─────────────────────────────────────────┐");
        System.out.println("│   K    │ recall@K │  (정답이 임베딩 top-K 안에 든 케이스 비율) │");
        System.out.println("├────────┼──────────┼─────────────────────────────────────────┤");
        int n = labeled.size();
        double prev = -1;
        for (int k : RECALL_KS) {
            long hit = goldRanks.stream().filter(r -> r != Integer.MIN_VALUE && r >= 1 && r <= k).count();
            double recall = pct(hit, n);
            String bar = "█".repeat((int) Math.round(recall / 5));
            String plateau = (prev >= 0 && recall - prev < 0.001) ? " ← 평탄" : "";
            System.out.printf("│ %4d   │  %5.1f%%  │ %-39s │%n", k, recall, bar + plateau);
            prev = recall;
        }
        System.out.println("└────────┴──────────┴─────────────────────────────────────────┘");

        // 정답 최초 등장 순위 분포 — K 선정 보조 (펀넬 입구는 이 분포의 꼬리를 덮어야 함)
        List<Integer> found = goldRanks.stream().filter(r -> r != Integer.MIN_VALUE && r >= 1).sorted().toList();
        long miss = goldRanks.stream().filter(r -> r == -1).count();
        if (!found.isEmpty()) {
            System.out.printf("  정답 최초순위: 중앙값 %d · 최악 %d · pool(%d) 밖 miss %d건/%d%n",
                    found.get(found.size() / 2), found.get(found.size() - 1), RECALL_POOL, miss, n);
        }
        System.out.println("  ※ recall@K = 정답이 임베딩 랭크드 top-K 안에 있는 비율(리트리버 ceiling). Hit@5(T1)와 다른 역할 지표.");
        System.out.println("  ※ 평탄해지는 K가 펀넬 후보 입구 후보 — 규모(storeId)별로 본 테스트를 반복해 곡선 비교.");
    }

    /** 임베딩을 topK개까지 받아오는 전용 호출(recall 측정용). 메인 top-5 호출과 분리. */
    private MethodResult callEmbedRanked(String input, int topK) {
        long start = System.currentTimeMillis();
        try {
            String body = MAPPER.writeValueAsString(Map.of("text", input, "storeId", STORE_ID, "topK", topK));
            String raw = client.post().uri("/api/recommend")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            long latency = System.currentTimeMillis() - start;
            JsonNode recs = MAPPER.readTree(raw).path("recommendations");
            List<Long> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (JsonNode nd : recs) {
                ids.add(nd.path("menuId").asLong());
                names.add(nd.path("name").asText(""));
            }
            return new MethodResult(ids, names, List.of(), 0, 0, latency, true);
        } catch (Exception e) {
            return new MethodResult(List.of(), List.of(), List.of(), 0, 0, System.currentTimeMillis() - start, false);
        }
    }

    /** 정답 menuId가 랭크드 리스트에서 처음 등장한 순위(1-base). arm 죽으면 MIN_VALUE, pool 안에 없으면 -1. */
    private int firstGoldRank(MethodResult r, Case c) {
        if (!r.available()) return Integer.MIN_VALUE;
        Set<Long> relevant = resolve(c.relevant());
        for (int i = 0; i < r.ids().size(); i++) {
            if (relevant.contains(r.ids().get(i))) return i + 1;
        }
        return -1;
    }

    // ── 호출 (실패/서버다운 시 available=false) ──────────────────────────────────────

    private MethodResult call(String url, String input) {
        return call(url, input, null);
    }

    private MethodResult call(String url, String input, Integer topK) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            bodyMap.put("text", input);
            bodyMap.put("storeId", STORE_ID);
            if (topK != null) bodyMap.put("topK", topK);
            String body = MAPPER.writeValueAsString(bodyMap);
            String raw = client.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            long latency = System.currentTimeMillis() - start;

            JsonNode root = MAPPER.readTree(raw);
            JsonNode recs = root.path("recommendations");
            List<Long> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            for (JsonNode n : recs) {
                ids.add(n.path("menuId").asLong());
                names.add(n.path("name").asText(""));
                if (n.has("score")) scores.add(n.path("score").asDouble());
            }
            // LLM 응답에만 있는 토큰 사용량(비용 산출용). 없으면 0.
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("promptTokens").asInt(0);
            int totalTokens = usage.path("totalTokens").asInt(0);
            return new MethodResult(ids, names, scores, promptTokens, totalTokens, latency, true);
        } catch (Exception e) {
            return new MethodResult(List.of(), List.of(), List.of(), 0, 0, System.currentTimeMillis() - start, false);
        }
    }

    // ── 시드 → menuId 집합 ──────────────────────────────────────────────────────────

    private Set<Long> resolve(List<String> seeds) {
        return catalog.values().stream()
                .filter(m -> {
                    String name = norm(m.name());
                    String cat = norm(m.category());
                    return seeds.stream().map(this::norm).anyMatch(s -> name.contains(s) || cat.contains(s));
                })
                .map(CatalogMenu::menuId)
                .collect(Collectors.toSet());
    }

    private String norm(String s) { return s == null ? "" : s.replaceAll("\\s+", ""); }

    private boolean hasLabel(Case c) { return !resolve(c.relevant()).isEmpty(); }

    // ── 채점 지표 (측정설계 §1) ──────────────────────────────────────────────────────

    /** Hit@K: 상위 K개 안에 정답 menuId 포함 여부. */
    private boolean hitAtK(MethodResult r, Case c, int k) {
        if (!r.available()) return false;
        Set<Long> relevant = resolve(c.relevant());
        return r.ids().stream().limit(k).anyMatch(relevant::contains);
    }

    /** MRR: 정답이 처음 등장한 순위의 역수(없으면 0). */
    private double reciprocalRank(MethodResult r, Case c) {
        if (!r.available()) return 0.0;
        Set<Long> relevant = resolve(c.relevant());
        for (int i = 0; i < r.ids().size(); i++) {
            if (relevant.contains(r.ids().get(i))) return 1.0 / (i + 1);
        }
        return 0.0;
    }

    /** 위반: 제외 대상(forbidden) menuId가 결과에 포함된 횟수. */
    private long violations(MethodResult r, Case c) {
        if (!r.available() || c.forbidden().isEmpty()) return 0;
        Set<Long> forbidden = resolve(c.forbidden());
        return r.ids().stream().filter(forbidden::contains).count();
    }

    /** 점수 분리도: 1위 − 꼴찌 score (평탄할수록 0에 가까움). 점수 없으면 NaN. */
    private double scoreSpread(MethodResult r) {
        if (!r.available() || r.scores().size() < 2) return Double.NaN;
        double top = r.scores().get(0);
        double bottom = r.scores().get(r.scores().size() - 1);
        return top - bottom;
    }

    // ── 출력 ────────────────────────────────────────────────────────────────────────

    private void printHeader(List<CaseResult> results) {
        boolean embedUp = results.stream().anyMatch(r -> r.embed().available());
        boolean llmUp = results.stream().anyMatch(r -> r.llm().available());
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║   추천 3방식 최종 측정 (룰 · 임베딩 · LLM) — 실서버·실DB·API 방식         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("서버: %s · storeId=%d · 메뉴 %d개 · 케이스 %d개%n",
                BASE_URL, STORE_ID, catalog.size(), CASES.size());
        System.out.printf("임베딩 arm: %s · LLM arm: %s%n", embedUp ? "가동" : "N/A", llmUp ? "가동" : "N/A");
    }

    // T1 — 유형별 정확도. 제품 성공 지표는 Hit@5(top-5를 TTS로 읽어줌) → 주력으로 앞에 둔다.
    //       Hit@1·MRR은 순위 변별력(점수가 의미를 가르나) 진단용 보조.
    private void printT1(List<CaseResult> results) {
        System.out.println();
        System.out.println("── T1. 유형별 정확도 (정답라벨 있는 케이스만) — 주력=Hit@5, 보조=Hit@1·MRR ──────");
        System.out.println("┌────────────┬────┬───────────────────────┬───────────────────────┬───────────────────────┐");
        System.out.println("│ 유형       │라벨│  임베딩 H@5/3/1  MRR  │  룰베이스 H@5/3/1 MRR │   LLM   H@5/3/1  MRR  │");
        System.out.println("├────────────┼────┼───────────────────────┼───────────────────────┼───────────────────────┤");

        Map<Phenom, List<CaseResult>> byPhenom = results.stream()
                .collect(Collectors.groupingBy(r -> r.c().phenom(), LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<Phenom, List<CaseResult>> e : byPhenom.entrySet()) {
            List<CaseResult> labeled = e.getValue().stream().filter(r -> hasLabel(r.c())).toList();
            int n = labeled.size();
            System.out.printf("│ %-9s  │ %2d │ %s │ %s │ %s │%n",
                    e.getKey().label, n,
                    armCell(labeled, CaseResult::embed),
                    armCell(labeled, CaseResult::rule),
                    armCell(labeled, CaseResult::llm));
        }
        System.out.println("└────────────┴────┴───────────────────────┴───────────────────────┴───────────────────────┘");

        // 전체 (Hit@5 주력)
        List<CaseResult> all = results.stream().filter(r -> hasLabel(r.c())).toList();
        System.out.println();
        System.out.println("  [전체] " +
                "임베딩 " + armCell(all, CaseResult::embed).trim() +
                " | 룰 " + armCell(all, CaseResult::rule).trim() +
                " | LLM " + armCell(all, CaseResult::llm).trim());
        System.out.println("  ※ 주력 Hit@5 = 상위 5개(TTS 낭독분) 안 정답 포함율 / 보조 Hit@1·MRR = 순위 변별력 진단 (LLM은 최대 3개 반환)");
    }

    private String armCell(List<CaseResult> labeled, java.util.function.Function<CaseResult, MethodResult> arm) {
        int n = labeled.size();
        if (n == 0) return String.format("%5s %5s %5s %5s", "-", "-", "-", "-");
        double h5 = pct(labeled.stream().filter(r -> hitAtK(arm.apply(r), r.c(), 5)).count(), n);
        double h3 = pct(labeled.stream().filter(r -> hitAtK(arm.apply(r), r.c(), 3)).count(), n);
        double h1 = pct(labeled.stream().filter(r -> hitAtK(arm.apply(r), r.c(), 1)).count(), n);
        double mrr = labeled.stream().mapToDouble(r -> reciprocalRank(arm.apply(r), r.c())).average().orElse(0);
        return String.format("%4.0f%% %4.0f%% %4.0f%% %.2f", h5, h3, h1, mrr);
    }

    // T2 — 임베딩 한계 진단: 위반 + 점수 분리도
    private void printT2(List<CaseResult> results) {
        System.out.println();
        System.out.println("── T2. 임베딩 한계 진단 (부정 위반 · 점수 분리도) ────────────────────────────");

        List<CaseResult> negs = results.stream().filter(r -> !r.c().forbidden().isEmpty()).toList();
        System.out.println("  [부정/제외 위반] 제외 대상을 그대로 추천한 횟수 (낮을수록 좋음)");
        for (CaseResult r : negs) {
            System.out.printf("   \"%s\"%n", r.c().input());
            System.out.printf("       임베딩 %d / 룰 %d / LLM %d%n",
                    violations(r.embed(), r.c()), violations(r.rule(), r.c()), violations(r.llm(), r.c()));
        }
        System.out.printf("  → 위반 총합: 임베딩 %d / 룰 %d / LLM %d%n",
                negs.stream().mapToLong(r -> violations(r.embed(), r.c())).sum(),
                negs.stream().mapToLong(r -> violations(r.rule(), r.c())).sum(),
                negs.stream().mapToLong(r -> violations(r.llm(), r.c())).sum());

        // 점수 분리도 — 임베딩 score(0~1 정규화)의 1위−꼴찌 차. 평탄할수록 "모호함조차 못 가름".
        List<Double> spreads = results.stream()
                .map(r -> scoreSpread(r.embed()))
                .filter(d -> !Double.isNaN(d))
                .sorted()
                .toList();
        System.out.println();
        System.out.println("  [임베딩 점수 분리도] 1위−꼴찌 score차 (작을수록 평탄 → 순위 변별 불가)");
        if (spreads.isEmpty()) {
            System.out.println("       (임베딩 arm N/A 또는 점수 부족)");
        } else {
            System.out.printf("       n=%d · 평균 %.3f · 중앙값 %.3f · 최소 %.3f · 최대 %.3f%n",
                    spreads.size(),
                    spreads.stream().mapToDouble(d -> d).average().orElse(0),
                    spreads.get(spreads.size() / 2),
                    spreads.get(0),
                    spreads.get(spreads.size() - 1));
        }
    }

    // T3 — 지연 p50/p95 + LLM 호출당 토큰·비용 (T1/T2 호출 로그 재사용)
    private void printT3(List<CaseResult> results) {
        System.out.println();
        System.out.println("── T3. 비용·지연 (정확도는 T1, 동일 호출 로그 재사용) ──────────────────────────");
        System.out.println("┌────────────┬───────────┬───────────┬───────────┐");
        System.out.println("│ 방식       │  p50(ms)  │  p95(ms)  │  평균(ms) │");
        System.out.println("├────────────┼───────────┼───────────┼───────────┤");
        printLatencyRow("임베딩", results, CaseResult::embed);
        printLatencyRow("룰베이스", results, CaseResult::rule);
        printLatencyRow("LLM", results, CaseResult::llm);
        System.out.println("└────────────┴───────────┴───────────┴───────────┘");
        System.out.println("  ※ 임베딩 지연 = 임베딩 서버 추론 + pgvector 조회 포함 / LLM = Gemini 호출 포함");
        System.out.println("  ※ 환각은 측정 제외(세 방식 모두 결과 menuId를 DB로 검증 → 구조적 0)");

        printLlmCost(results);
    }

    // LLM 호출당 비용 — gemini-2.5-flash 단가(2026-06): 입력 $0.30 / 출력 $2.50 per 1M tokens.
    // -Dvoisk.usdkrw, -Dvoisk.llmInUsdPerM, -Dvoisk.llmOutUsdPerM 로 단가/환율 덮어쓰기 가능.
    private void printLlmCost(List<CaseResult> results) {
        double inUsdPerM = Double.parseDouble(System.getProperty("voisk.llmInUsdPerM", "0.30"));
        double outUsdPerM = Double.parseDouble(System.getProperty("voisk.llmOutUsdPerM", "2.50"));
        double usdkrw = Double.parseDouble(System.getProperty("voisk.usdkrw", "1380"));

        List<MethodResult> llmCalls = results.stream().map(CaseResult::llm)
                .filter(r -> r.available() && r.totalTokens() > 0).toList();
        System.out.println();
        System.out.println("── T3-b. LLM 호출당 토큰·비용 (gemini-2.5-flash, 직접발화 비율 미반영) ───────────");
        if (llmCalls.isEmpty()) {
            System.out.println("  (토큰 데이터 없음 — LLM arm N/A 또는 usageMetadata 미수신)");
            return;
        }
        double avgIn = llmCalls.stream().mapToInt(MethodResult::promptTokens).average().orElse(0);
        double avgOut = llmCalls.stream().mapToInt(MethodResult::billableOutputTokens).average().orElse(0);
        double costPerCallUsd = avgIn / 1_000_000 * inUsdPerM + avgOut / 1_000_000 * outUsdPerM;

        System.out.printf("  단가: 입력 $%.2f / 출력 $%.2f (per 1M) · 환율 ₩%.0f/$ · 호출 %d건%n",
                inUsdPerM, outUsdPerM, usdkrw, llmCalls.size());
        System.out.printf("  평균 토큰/호출: 입력 %.0f · 출력 %.0f(=total-prompt, thinking 포함)%n", avgIn, avgOut);
        System.out.printf("  → 호출당 비용: $%.6f  (≈ ₩%.3f)%n", costPerCallUsd, costPerCallUsd * usdkrw);
        System.out.printf("  → 1,000회 호출: $%.2f  (≈ ₩%.0f)%n", costPerCallUsd * 1000, costPerCallUsd * 1000 * usdkrw);
    }

    private void printLatencyRow(String label, List<CaseResult> results,
                                 java.util.function.Function<CaseResult, MethodResult> arm) {
        List<Long> lat = results.stream().map(arm).filter(MethodResult::available)
                .map(MethodResult::latencyMs).sorted().toList();
        if (lat.isEmpty()) {
            System.out.printf("│ %-9s  │ %9s │ %9s │ %9s │%n", label, "N/A", "N/A", "N/A");
            return;
        }
        System.out.printf("│ %-9s  │ %9d │ %9d │ %9.0f │%n",
                label, percentile(lat, 50), percentile(lat, 95),
                lat.stream().mapToLong(l -> l).average().orElse(0));
    }

    private long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private double pct(long a, long b) { return b == 0 ? 0.0 : 100.0 * a / b; }
}
