package capstone2.voisk.recommend;

import capstone2.voisk.config.RuleConfig;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 룰베이스(키워드 매칭) 추천 서비스.
 *
 * <p>임베딩 추천({@link RecommendService})과 동일한 입력으로 동작하지만, 외부 임베딩 서버/pgvector 없이
 * 규칙만으로 결정론적으로 메뉴를 점수화한다. 특정 매장에 종속되지 않도록 세 가지 범용 축만 사용한다.
 *
 * <pre>
 * ① 감각 형용사({@link RuleSet}): 메뉴 이름+설명에서 "달콤/시원/진한" 등을 매칭 (도메인 독립)
 * ② 카테고리: 하드코딩 없이 "그 매장의 실제 category 이름"을 발화와 대조. "X 말고/아닌"이면 해당 카테고리 제외
 * ③ 가격: 매장 가격 분포 기준 '저렴/프리미엄' 판단
 * </pre>
 *
 * 보고서/발표에서 임베딩과 A/B 비교하기 위한 용도이며, 결과마다 매칭 근거(matchedRules)를 함께 반환한다.
 */
@Service
@RequiredArgsConstructor
public class RuleRecommendService {

    private final MenuRepository menuRepository;
    private final RuleConfig ruleConfig;

    private static final List<String> CHEAP_TRIGGERS =
            List.of("저렴", "싼", "싸고", "싸게", "가성비", "부담없", "부담 없");
    private static final List<String> PREMIUM_TRIGGERS =
            List.of("비싼", "비싸", "프리미엄", "고급", "특별한 거", "제일 좋은");
    private static final List<String> NEGATION_WORDS =
            List.of("말고", "말구", "아닌", "아니", "빼고", "빼줘", "제외", "대신", "싫");

    // 카테고리명에서 의미 토큰 추출: 한글/영숫자 2글자 이상 연속 (예: "티&음료" → [음료])
    private static final Pattern TOKEN = Pattern.compile("[가-힣A-Za-z0-9]{2,}");

    @Transactional(readOnly = true)
    public RuleRecommendResponse recommend(String text, Long storeId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId is required.");
        }
        String normalized = normalize(text);

        List<Menu> menus = menuRepository.findAvailableByStoreIdWithCategory(storeId);
        if (menus.isEmpty()) {
            return new RuleRecommendResponse(List.of(), emptyTts());
        }

        // ① 활성 감각 규칙
        Set<RuleSet> activeRules = EnumSet.noneOf(RuleSet.class);
        for (RuleSet rule : RuleSet.values()) {
            if (rule.isTriggeredBy(normalized)) {
                activeRules.add(rule);
            }
        }

        // ② 카테고리 의도 (매장 실제 카테고리명 기반, 부정 처리 포함)
        Set<String> distinctCategories = menus.stream()
                .map(m -> m.getCategory().getName())
                .collect(Collectors.toCollection(HashSet::new));
        CategoryIntent categoryIntent = resolveCategoryIntent(normalized, distinctCategories);

        // ③ 가격 의도
        boolean cheap = containsAny(normalized, CHEAP_TRIGGERS);
        boolean premium = containsAny(normalized, PREMIUM_TRIGGERS);
        int cheapThreshold = pricePercentile(menus, ruleConfig.getCheapThresholdRatio());
        int premiumThreshold = pricePercentile(menus, ruleConfig.getPremiumThresholdRatio());

        boolean hasPositiveIntent = !activeRules.isEmpty() || cheap || premium
                || !categoryIntent.boost().isEmpty();
        boolean hasAnyIntent = hasPositiveIntent || !categoryIntent.exclude().isEmpty();

        // 제외 카테고리는 후보에서 완전히 빼고 점수화
        List<RuleMenuRecommendation> scored = menus.stream()
                .filter(m -> !categoryIntent.exclude().contains(m.getCategory().getName()))
                .map(m -> score(m, activeRules, categoryIntent.boost(), cheap, premium, cheapThreshold, premiumThreshold))
                .toList();

        List<RuleMenuRecommendation> result = select(scored, hasAnyIntent, hasPositiveIntent);
        return new RuleRecommendResponse(result, buildTtsText(result));
    }

    private List<RuleMenuRecommendation> select(List<RuleMenuRecommendation> scored,
                                                boolean hasAnyIntent, boolean hasPositiveIntent) {
        Comparator<RuleMenuRecommendation> byPrice = Comparator.comparingInt(RuleMenuRecommendation::price);

        if (!hasAnyIntent) {
            // 의도 없음 → 가격 오름차순 기본 추천
            return scored.stream().sorted(byPrice).limit(ruleConfig.getLimit()).toList();
        }
        if (hasPositiveIntent) {
            List<RuleMenuRecommendation> positive = scored.stream()
                    .filter(r -> r.score() > 0)
                    .sorted(Comparator.comparingDouble(RuleMenuRecommendation::score).reversed().thenComparing(byPrice))
                    .limit(ruleConfig.getLimit())
                    .toList();
            // 긍정 의도가 있었으나 매칭이 전혀 없으면 가격 오름차순 폴백
            return positive.isEmpty()
                    ? scored.stream().sorted(byPrice).limit(ruleConfig.getLimit()).toList()
                    : positive;
        }
        // 제외 의도만 있는 경우("커피 말고") → 남은 후보를 가격 오름차순
        return scored.stream().sorted(byPrice).limit(ruleConfig.getLimit()).toList();
    }

    private RuleMenuRecommendation score(Menu menu, Set<RuleSet> activeRules, Set<String> boostCategories,
                                         boolean cheap, boolean premium, int cheapThreshold, int premiumThreshold) {
        String category = menu.getCategory().getName();
        String menuText = menu.getName() + " " + safe(menu.getDescription());

        double score = 0.0;
        List<String> matchedRules = new ArrayList<>();

        for (RuleSet rule : activeRules) {
            if (rule.matchesMenu(menuText)) {
                score += rule.weight();
                matchedRules.add(rule.displayName());
            }
        }
        if (boostCategories.contains(category)) {
            score += ruleConfig.getCategoryMatchWeight();
            matchedRules.add("카테고리:" + category);
        }
        if (cheap && menu.getPrice() <= cheapThreshold) {
            score += ruleConfig.getPriceMatchWeight();
            matchedRules.add("저렴함");
        }
        if (premium && menu.getPrice() >= premiumThreshold) {
            score += ruleConfig.getPriceMatchWeight();
            matchedRules.add("프리미엄");
        }

        return new RuleMenuRecommendation(menu.getMenuId(), menu.getName(), menu.getPrice(), category, score, matchedRules);
    }

    /**
     * 발화에서 매장 카테고리명(토큰)을 찾아 긍정/부정(제외) 의도로 분류한다.
     * 카테고리명 자체를 박지 않고 매장 데이터에서 동적으로 가져오므로 도메인 독립적이다.
     */
    private CategoryIntent resolveCategoryIntent(String normalized, Set<String> categories) {
        Set<String> boost = new HashSet<>();
        Set<String> exclude = new HashSet<>();

        for (String category : categories) {
            Matcher m = TOKEN.matcher(category);
            while (m.find()) {
                String token = m.group();
                int idx = normalized.indexOf(token);
                if (idx < 0) {
                    continue;
                }
                if (isNegatedAfter(normalized, idx + token.length())) {
                    exclude.add(category);
                } else {
                    boost.add(category);
                }
            }
        }
        // 같은 카테고리가 긍정·부정 양쪽이면 부정(제외) 우선
        boost.removeAll(exclude);
        return new CategoryIntent(boost, exclude);
    }

    /** 토큰 바로 뒤(공백 포함 7글자 윈도)에서 부정 표현을 찾으면 true. 예: "커피 말고", "에스프레소 빼줘". */
    private boolean isNegatedAfter(String text, int from) {
        String window = text.substring(from, Math.min(from + 7, text.length()));
        return containsAny(window, NEGATION_WORDS);
    }

    /** 매장 가격을 정렬해 ratio 지점의 가격을 임계값으로 반환. */
    private int pricePercentile(List<Menu> menus, double ratio) {
        List<Integer> prices = menus.stream().map(Menu::getPrice).sorted().toList();
        int idx = (int) Math.floor((prices.size() - 1) * ratio);
        return prices.get(idx);
    }

    private boolean containsAny(String text, List<String> needles) {
        return needles.stream().anyMatch(text::contains);
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String buildTtsText(List<RuleMenuRecommendation> list) {
        if (list.isEmpty()) {
            return emptyTts();
        }
        if (list.size() == 1) {
            return list.get(0).name() + "을(를) 추천드려요.";
        }
        String names = list.stream().map(RuleMenuRecommendation::name).collect(Collectors.joining(", "));
        return "추천 메뉴로는 " + names + "를 추천드려요.";
    }

    private String emptyTts() {
        return "죄송합니다, 조건에 맞는 메뉴를 찾지 못했어요.";
    }

    /** 카테고리 의도 분류 결과: 가산할 카테고리(boost)와 제외할 카테고리(exclude). */
    private record CategoryIntent(Set<String> boost, Set<String> exclude) {}
}
