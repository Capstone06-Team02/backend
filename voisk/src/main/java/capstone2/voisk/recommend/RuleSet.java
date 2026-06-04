package capstone2.voisk.recommend;

import java.util.List;

/**
 * 룰베이스 추천을 위한 감각/속성 형용사 사전 (도메인 독립).
 *
 * <p>특정 메뉴명·브랜드 키워드(예: 카라멜, 모카, 프라푸치노)를 박지 않고, 한국어 음식/음료에서 범용적으로 쓰이는
 * 감각 형용사만 담는다. trigger(사용자 표현)와 target(메뉴 텍스트)이 같은 어휘이므로, 매장이 무엇이든
 * 메뉴 설명(description)에 해당 형용사가 들어 있으면 매칭된다.
 *
 * <p>카테고리("커피 말고", "음료 종류")와 가격("저렴/프리미엄") 의도는 어휘 사전이 아니라 매장 데이터 기반이라
 * {@link RuleRecommendService} 에서 별도 처리한다.
 */
public enum RuleSet {

    SWEET("단맛", List.of("달콤", "달달", "단거", "단 거", "달다", "달았", "스윗", "당떨", "당 떨")),
    COLD("시원함", List.of("시원", "차가", "아이스", "얼음", "냉", "찬거", "찬 거")),
    HOT("따뜻함", List.of("따뜻", "뜨거", "핫", "따순", "데운")),
    FRESH("상큼함", List.of("상큼", "새콤", "상쾌", "청량", "톡쏘", "톡 쏘")),
    SMOOTH("부드러움", List.of("부드러", "순한", "마일드", "크리미")),
    RICH("진함", List.of("진한", "진하", "묵직", "깊은", "꾸덕")),
    NUTTY("고소함", List.of("고소", "너티")),
    SPICY("매운맛", List.of("매운", "매콤", "얼큰", "칼칼")),
    HEARTY("든든함", List.of("든든", "배부", "푸짐"));

    private final String displayName;
    private final List<String> keywords;

    RuleSet(String displayName, List<String> keywords) {
        this.displayName = displayName;
        this.keywords = keywords;
    }

    public String displayName() {
        return displayName;
    }

    public double weight() {
        return 1.0;
    }

    /** 사용자 발화에 이 속성의 표현이 하나라도 포함되면 true (활성 규칙). */
    public boolean isTriggeredBy(String normalizedText) {
        return keywords.stream().anyMatch(normalizedText::contains);
    }

    /** 메뉴 텍스트(이름+설명)에 이 속성의 형용사가 하나라도 포함되면 true. */
    public boolean matchesMenu(String menuText) {
        return keywords.stream().anyMatch(menuText::contains);
    }
}
