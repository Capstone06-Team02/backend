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
 * 임베딩(/api/recommend) vs 룰베이스(/api/recommend/rule) vs LLM(/api/recommend/llm) 3-way 라이브 비교 평가.
 *
 * <p><b>왜 라이브인가:</b> 과거 @SpringBootTest 방식은 테스트용 H2(빈 DB)를 띄우면서 정답 라벨은 실제 메뉴 이름을
 * 하드코딩해, "채점 기준이 보는 세계 ≠ 테스트가 보는 DB"라는 구조적 애매함이 있었다. 이 테스트는 Spring 컨텍스트를
 * 띄우지 않고 <b>실제로 떠 있는 서버(localhost:8080)</b>에 HTTP로 직접 호출한다 → 데모가 쓰는 바로 그
 * MySQL/pgvector/Gemini를 그대로 평가한다.
 *
 * <p><b>정답 자동 생성(DB에서):</b> 각 케이스는 정답을 메뉴 이름으로 박지 않고 <b>시드(이름/카테고리 부분문자열)로만
 * 선언</b>한다. 실행 시점에 실서버 카탈로그(/api/order/restaurants/{id}/menus/cache)를 받아 시드를 실제
 * {@code menuId} 집합으로 materialize한다. 채점은 이름 매칭이 아니라 <b>menuId 교집합</b>으로 한다. 이 DB에
 * 매칭되는 메뉴가 하나도 없는 시드(예: 다른 매장)는 자동으로 hit 통계에서 빠진다 → 라벨 드리프트 0.
 *
 * <p><b>측정 지표(현상 유형별 집계):</b>
 * <ul>
 *   <li>관련성 hit — 반환 결과에 정답 후보(relevant) menuId가 1개 이상 포함됐는가 (label 없는 케이스는 제외)</li>
 *   <li>커버리지 — 추천을 1건이라도 반환했는가</li>
 *   <li>유효성(환각) — 반환한 menuId가 전부 실카탈로그에 존재하는가 (LLM 환각 차단 검증)</li>
 *   <li>부정 위반 — "X 말고"인데 X 카테고리 menuId를 추천했는가</li>
 *   <li>평균 레이턴시</li>
 * </ul>
 *
 * <p><b>전제:</b> {@code ./gradlew bootRun}으로 서버가 실DB에 연결된 채 떠 있어야 한다. 서버가 없으면 테스트는
 * 실패가 아니라 <b>skip</b>된다(Assumptions). 임베딩 서버/pgvector가 없으면 임베딩 arm만 "N/A"로 표기된다.
 *
 * 실행: ./gradlew test --tests "capstone2.voisk.RecommendComparisonEvaluationTest"
 */
class RecommendComparisonEvaluationTest {

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

    // ── 자연어 현상 유형 ─────────────────────────────────────────────────────────

    enum Phenom {
        CLEAN("정형"), FILLER("추임새"), PARAPHRASE("풀어설명"),
        STT_ERROR("발음뭉개짐"), VERBOSE("구어체장황"), NEGATION("부정");
        final String label;
        Phenom(String label) { this.label = label; }
    }

    /**
     * @param relevant  반환되면 "맞은 것"으로 보는 시드(메뉴명/카테고리명 부분문자열). 실카탈로그로 menuId 집합으로 해석
     * @param forbidden "X 말고" 위반 판정용 시드. 같은 방식으로 해석. 없으면 빈 리스트
     */
    record Case(Phenom phenom, String input, List<String> relevant, List<String> forbidden, String note) {
        static Case of(Phenom p, String input, List<String> relevant, String note) {
            return new Case(p, input, relevant, List.of(), note);
        }
        static Case neg(Phenom p, String input, List<String> relevant, List<String> forbidden, String note) {
            return new Case(p, input, relevant, forbidden, note);
        }
    }

    record MethodResult(List<Long> ids, List<String> names, long latencyMs, boolean available) {}
    record CaseResult(Case c, MethodResult embed, MethodResult rule, MethodResult llm) {}

    // ── 정답 시드 (메뉴명/카테고리명 부분문자열; 실카탈로그로 런타임 해석) ───────────────
    //   라이브 DB(storeId=1) 어휘 기준: 커피[아메리카노·카페라떼·바닐라라떼·카라멜마키아토],
    //   논커피[녹차라떼·딸기스무디·초코라떼·레몬에이드], 디저트[크로플·티라미수].
    //   가능하면 카테고리명("논커피"/"디저트")으로 잡아 메뉴 변동에 덜 민감하게 한다.
    static final List<String> SWEET = List.of("카라멜마키아토", "바닐라라떼", "초코라떼", "티라미수");
    static final List<String> COLD = List.of("딸기스무디", "레몬에이드");
    static final List<String> STRONG_COFFEE = List.of("아메리카노", "카페라떼");
    static final List<String> FRUITY = List.of("딸기스무디", "레몬에이드");
    static final List<String> SMOOTH = List.of("카페라떼", "바닐라라떼", "녹차라떼", "초코라떼");
    static final List<String> NON_COFFEE = List.of("논커피");          // 카테고리 전체
    static final List<String> FOOD = List.of("디저트");                // 카테고리 전체
    static final List<String> SWEET_FOOD = List.of("크로플", "티라미수");
    // 부정 위반 판정용. "논커피"가 "커피"를 substring으로 포함하므로 카테고리명 대신 메뉴명을 직접 나열한다.
    static final List<String> COFFEE_MENUS = List.of("아메리카노", "카페라떼", "바닐라라떼", "카라멜마키아토");
    static final List<String> DRINKS = List.of("아메리카노", "카페라떼", "바닐라라떼", "카라멜마키아토",
            "녹차라떼", "딸기스무디", "초코라떼", "레몬에이드");

    // ── 테스트 케이스 ────────────────────────────────────────────────────────────

    static final List<Case> CASES = List.of(
            // 정형
            Case.of(Phenom.CLEAN, "달콤한 음료 추천해줘", SWEET, "기본 단맛"),
            Case.of(Phenom.CLEAN, "시원한 거 마시고 싶어요", COLD, "기본 시원함"),
            Case.of(Phenom.CLEAN, "진한 커피 주세요", STRONG_COFFEE, "기본 진함"),
            Case.of(Phenom.CLEAN, "상큼한 과일 음료 있어요?", FRUITY, "기본 상큼"),
            Case.of(Phenom.CLEAN, "빵 종류 추천해주세요", FOOD, "푸드 카테고리"),
            // 추임새
            Case.of(Phenom.FILLER, "어... 그... 달달한 거 있나요?", SWEET, "앞쪽 추임새"),
            Case.of(Phenom.FILLER, "음 저기요 시원한 음료 좀 주세요", COLD, "중간 추임새"),
            Case.of(Phenom.FILLER, "아 뭐랄까 진한 커피요", STRONG_COFFEE, "군말 + 단축"),
            Case.of(Phenom.FILLER, "그 있잖아요 상큼한 거", FRUITY, "지시어 추임새"),
            Case.of(Phenom.FILLER, "에... 단 디저트 하나 추천해주세요", SWEET_FOOD, "추임새 + 디저트"),
            Case.of(Phenom.FILLER, "음... 그냥 부드러운 라떼요", SMOOTH, "추임새 + 부드러움"),
            // 풀어설명
            Case.of(Phenom.PARAPHRASE, "당 떨어졌는데 기운 나게 단 거 없어요?", SWEET, "상황 설명형 단맛"),
            Case.of(Phenom.PARAPHRASE, "잠 좀 깨야 해서 진하고 쓴 커피요", STRONG_COFFEE, "의도 설명형 진함"),
            Case.of(Phenom.PARAPHRASE, "더워서 얼음 들어간 시원한 음료 마시고 싶어요", COLD, "상황 설명형 시원함"),
            Case.of(Phenom.PARAPHRASE, "커피는 속이 안 좋아서 차 종류로 부드러운 거", NON_COFFEE, "에둘러 표현한 비커피"),
            Case.of(Phenom.PARAPHRASE, "출출한데 든든하게 먹을 만한 거 있을까요", FOOD, "상황 설명형 푸드"),
            Case.of(Phenom.PARAPHRASE, "새콤달콤한 과일 맛 나는 음료요", FRUITY, "복합 미각 설명"),
            // 발음뭉개짐 / STT 오인식
            Case.of(Phenom.STT_ERROR, "달달한거추천해죠", SWEET, "띄어쓰기 붕괴 + 어미 오인식"),
            Case.of(Phenom.STT_ERROR, "시원항거 업나요", COLD, "받침 오인식(시원한→시원항)"),
            Case.of(Phenom.STT_ERROR, "아아 한잔 주세요", List.of("아메리카노"), "은어(아아=아이스 아메리카노)"),
            Case.of(Phenom.STT_ERROR, "뜨아 주세요", List.of("아메리카노"), "은어(뜨아=뜨거운 아메리카노)"),
            Case.of(Phenom.STT_ERROR, "단고 추천", SWEET, "오인식(단거→단고)"),
            Case.of(Phenom.STT_ERROR, "시언한거 머있어요", COLD, "모음 오인식(시원→시언)"),
            Case.of(Phenom.STT_ERROR, "초콜렛 들어간 달달이", List.of("초코라떼"), "표기 흔들림 + 구어"),
            // 구어체 장황
            Case.of(Phenom.VERBOSE, "제가 커피를 잘 못 마시는 편인데 그래도 뭔가 마실 만한 거 없을까요", NON_COFFEE, "장황한 비커피 요청"),
            Case.of(Phenom.VERBOSE, "날씨도 더운데 시원하면서 너무 달지 않은 음료 추천 좀 해주실 수 있나요", COLD, "조건 여러 개 + 정중"),
            Case.of(Phenom.VERBOSE, "친구 만나서 디저트랑 같이 먹을 달콤한 거 하나 골라줘", SWEET_FOOD, "맥락 설명 + 디저트"),
            Case.of(Phenom.VERBOSE, "아침이라 그런지 좀 든든하고 따뜻한 게 당기는데요", List.of("샌드위치"), "데이터상 따뜻함 빈약(한계 케이스)"),
            Case.of(Phenom.VERBOSE, "여기 진하고 묵직한 에스프레소 베이스 음료가 뭐가 있죠", STRONG_COFFEE, "전문 표현 섞인 진함"),
            // 부정 (forbidden 위반 체크)
            Case.neg(Phenom.NEGATION, "에스프레소 말고 다른 음료요", NON_COFFEE, COFFEE_MENUS, "에스프레소(커피) 카테고리 제외"),
            Case.neg(Phenom.NEGATION, "커피 말고 다른 거 추천해줘", NON_COFFEE, COFFEE_MENUS, "커피 카테고리명 직접 부정"),
            Case.neg(Phenom.NEGATION, "쓴 거 말고 달달한 걸로", SWEET, List.of("아메리카노"), "미각 부정 + 긍정 혼합"),
            Case.neg(Phenom.NEGATION, "음료 말고 먹을 거 주세요", FOOD, DRINKS, "음료 전체 제외 → 디저트만")
    );

    // ── 메인 테스트 ───────────────────────────────────────────────────────────

    @Test
    void compareEmbeddingVsRuleVsLlm() {
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
        printCaseDetails(results);
        printAggregateByPhenom(results);
        printNegationBreaches(results);
        printSummary(results);
    }

    // ── 추천 호출 (실패/서버다운 시 available=false) ──────────────────────────────

    private MethodResult call(String url, String input) {
        long start = System.currentTimeMillis();
        try {
            String body = MAPPER.writeValueAsString(Map.of("text", input, "storeId", STORE_ID));
            String raw = client.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            long latency = System.currentTimeMillis() - start;

            JsonNode recs = MAPPER.readTree(raw).path("recommendations");
            List<Long> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (JsonNode n : recs) {
                ids.add(n.path("menuId").asLong());
                names.add(n.path("name").asText(""));
            }
            return new MethodResult(ids, names, latency, true);
        } catch (Exception e) {
            return new MethodResult(List.of(), List.of(), System.currentTimeMillis() - start, false);
        }
    }

    // ── 정답 시드 → 실카탈로그 menuId 집합 (이름 또는 카테고리에 시드가 포함되면 매칭) ──────

    private Set<Long> resolve(List<String> seeds) {
        return catalog.values().stream()
                .filter(m -> {
                    String name = norm(m.name());
                    String cat = norm(m.category());
                    return seeds.stream().map(this::norm)
                            .anyMatch(s -> name.contains(s) || cat.contains(s));
                })
                .map(CatalogMenu::menuId)
                .collect(Collectors.toSet());
    }

    /** 공백 제거 후 비교 — "카라멜 마키아토"(시드) ↔ "카라멜마키아토"(실메뉴) 띄어쓰기 차이를 흡수. */
    private String norm(String s) { return s == null ? "" : s.replaceAll("\\s+", ""); }

    // ── 판정 헬퍼 (menuId 기준) ──────────────────────────────────────────────────

    /** relevant 시드가 이 DB에서 하나도 안 잡히면 라벨 없음 → hit 통계에서 제외. */
    private boolean hasLabel(Case c) { return !resolve(c.relevant()).isEmpty(); }

    private boolean hit(MethodResult r, Case c) {
        if (!r.available()) return false;
        Set<Long> relevant = resolve(c.relevant());
        return r.ids().stream().anyMatch(relevant::contains);
    }

    private boolean covered(MethodResult r) { return r.available() && !r.ids().isEmpty(); }

    private long breachCount(MethodResult r, Case c) {
        if (!r.available() || c.forbidden().isEmpty()) return 0;
        Set<Long> forbidden = resolve(c.forbidden());
        return r.ids().stream().filter(forbidden::contains).count();
    }

    /** 반환 menuId 중 실카탈로그에 없는 것의 수(환각). 룰/임베딩은 DB에서 오므로 0이어야 하고, LLM 검증 확인용. */
    private long invalidCount(MethodResult r) {
        if (!r.available()) return 0;
        return r.ids().stream().filter(id -> !catalog.containsKey(id)).count();
    }

    // ── 출력 ──────────────────────────────────────────────────────────────────

    private void printHeader(List<CaseResult> results) {
        boolean embedUp = results.stream().anyMatch(r -> r.embed().available());
        boolean llmUp = results.stream().anyMatch(r -> r.llm().available());
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║   임베딩 vs 룰베이스 vs LLM  3-way 라이브 비교 평가 (실서버·실DB·실 STT)   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("서버: %s · storeId=%d · 메뉴 %d개 · 케이스 %d개%n", BASE_URL, STORE_ID, catalog.size(), CASES.size());
        System.out.printf("임베딩 arm: %s · LLM arm: %s%n",
                embedUp ? "가동" : "N/A", llmUp ? "가동" : "N/A");
    }

    private void printCaseDetails(List<CaseResult> results) {
        System.out.println();
        System.out.println("── 케이스별 상위 추천 ──────────────────────────────────────────────────────");
        for (int i = 0; i < results.size(); i++) {
            CaseResult r = results.get(i);
            String labelMark = hasLabel(r.c()) ? "" : "  [정답라벨 없음→hit제외]";
            System.out.printf("%n[%02d] (%s) \"%s\"   ※ %s%s%n",
                    i + 1, r.c().phenom().label, r.c().input(), r.c().note(), labelMark);
            System.out.printf("    임베딩 %s : %-45s → hit %s%n", latency(r.embed()), names(r.embed()), mark(hit(r.embed(), r.c())));
            System.out.printf("    룰베이스%s : %-45s → hit %s%n", latency(r.rule()), names(r.rule()), mark(hit(r.rule(), r.c())));
            System.out.printf("    LLM    %s : %-45s → hit %s%n", latency(r.llm()), names(r.llm()), mark(hit(r.llm(), r.c())));
            if (!r.c().forbidden().isEmpty()) {
                System.out.printf("    부정 위반 → 임베딩 %d / 룰 %d / LLM %d%n",
                        breachCount(r.embed(), r.c()), breachCount(r.rule(), r.c()), breachCount(r.llm(), r.c()));
            }
        }
    }

    private void printAggregateByPhenom(List<CaseResult> results) {
        System.out.println();
        System.out.println("── 현상 유형별 hit율 (정답라벨 있는 케이스만) ──────────────────────────────────");
        System.out.println("┌────────────┬─────┬───────────────┬───────────────┬───────────────┐");
        System.out.println("│ 유형       │ 라벨│ 임베딩 hit    │ 룰베이스 hit  │ LLM hit       │");
        System.out.println("├────────────┼─────┼───────────────┼───────────────┼───────────────┤");

        Map<Phenom, List<CaseResult>> byPhenom = results.stream()
                .collect(Collectors.groupingBy(r -> r.c().phenom(), LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<Phenom, List<CaseResult>> e : byPhenom.entrySet()) {
            List<CaseResult> labeled = e.getValue().stream().filter(r -> hasLabel(r.c())).toList();
            int n = labeled.size();
            long eHit = labeled.stream().filter(r -> hit(r.embed(), r.c())).count();
            long rHit = labeled.stream().filter(r -> hit(r.rule(), r.c())).count();
            long lHit = labeled.stream().filter(r -> hit(r.llm(), r.c())).count();
            System.out.printf("│ %-9s  │ %3d │ %2d/%-2d (%5.1f%%)│ %2d/%-2d (%5.1f%%)│ %2d/%-2d (%5.1f%%)│%n",
                    e.getKey().label, n, eHit, n, pct(eHit, n), rHit, n, pct(rHit, n), lHit, n, pct(lHit, n));
        }
        System.out.println("└────────────┴─────┴───────────────┴───────────────┴───────────────┘");
        System.out.println("  ※ hit = 반환 결과에 정답후보 menuId 1개+ 포함 / 라벨 = 이 DB에서 정답이 잡힌 케이스 수");
    }

    private void printNegationBreaches(List<CaseResult> results) {
        List<CaseResult> negs = results.stream().filter(r -> !r.c().forbidden().isEmpty()).toList();
        if (negs.isEmpty()) return;
        System.out.println();
        System.out.println("── 부정 발화(\"X 말고\") 위반 상세 ───────────────────────────────────────────");
        for (CaseResult r : negs) {
            System.out.printf("  \"%s\"%n", r.c().input());
            System.out.printf("     임베딩 위반 %d: %s%n", breachCount(r.embed(), r.c()), names(r.embed()));
            System.out.printf("     룰베이스위반 %d: %s%n", breachCount(r.rule(), r.c()), names(r.rule()));
            System.out.printf("     LLM    위반 %d: %s%n", breachCount(r.llm(), r.c()), names(r.llm()));
        }
    }

    private void printSummary(List<CaseResult> results) {
        List<CaseResult> labeled = results.stream().filter(r -> hasLabel(r.c())).toList();
        int n = results.size();
        int ln = labeled.size();

        System.out.println();
        System.out.println("┌──────────────────────────────┬────────────┬────────────┬────────────┐");
        System.out.println("│ 종합 지표                    │   임베딩   │  룰베이스  │    LLM     │");
        System.out.println("├──────────────────────────────┼────────────┼────────────┼────────────┤");
        printSummaryRow("관련성 hit (" + ln + "라벨)",
                labeled.stream().filter(r -> hit(r.embed(), r.c())).count(),
                labeled.stream().filter(r -> hit(r.rule(), r.c())).count(),
                labeled.stream().filter(r -> hit(r.llm(), r.c())).count(), ln);
        printSummaryRow("커버리지(추천 반환율)",
                results.stream().filter(r -> covered(r.embed())).count(),
                results.stream().filter(r -> covered(r.rule())).count(),
                results.stream().filter(r -> covered(r.llm())).count(), n);
        System.out.printf("│ 환각(카탈로그 외 menuId)     │   %3d건    │   %3d건    │   %3d건    │%n",
                results.stream().mapToLong(r -> invalidCount(r.embed())).sum(),
                results.stream().mapToLong(r -> invalidCount(r.rule())).sum(),
                results.stream().mapToLong(r -> invalidCount(r.llm())).sum());
        System.out.printf("│ 부정 위반 총합               │   %3d건    │   %3d건    │   %3d건    │%n",
                results.stream().mapToLong(r -> breachCount(r.embed(), r.c())).sum(),
                results.stream().mapToLong(r -> breachCount(r.rule(), r.c())).sum(),
                results.stream().mapToLong(r -> breachCount(r.llm(), r.c())).sum());
        System.out.printf("│ 평균 레이턴시                │ %6.0f ms │ %6.0f ms │ %6.0f ms │%n",
                avgLatency(results, CaseResult::embed),
                avgLatency(results, CaseResult::rule),
                avgLatency(results, CaseResult::llm));
        System.out.println("└──────────────────────────────┴────────────┴────────────┴────────────┘");
    }

    private void printSummaryRow(String label, long e, long r, long l, int n) {
        System.out.printf("│ %-28s │ %4.1f%% (%2d)│ %4.1f%% (%2d)│ %4.1f%% (%2d)│%n",
                label, pct(e, n), e, pct(r, n), r, pct(l, n), l);
    }

    private double avgLatency(List<CaseResult> results, java.util.function.Function<CaseResult, MethodResult> arm) {
        return results.stream().map(arm).filter(MethodResult::available)
                .mapToLong(MethodResult::latencyMs).average().orElse(0);
    }

    // ── 포맷 유틸 ─────────────────────────────────────────────────────────────

    private String names(MethodResult r) {
        if (!r.available()) return "N/A (호출 실패/서버다운)";
        if (r.names().isEmpty()) return "(추천 0건)";
        return r.names().stream().limit(5).collect(Collectors.joining(", "));
    }

    private String latency(MethodResult r) {
        return r.available() ? String.format("(%5dms)", r.latencyMs()) : "(  N/A  )";
    }

    private String mark(boolean b) { return b ? "O" : "X"; }

    private double pct(long a, long b) { return b == 0 ? 0.0 : 100.0 * a / b; }
}
