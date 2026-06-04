package capstone2.voisk.recommend;

import java.util.List;

/**
 * 룰베이스 추천 결과 1건. 임베딩의 {@link MenuRecommendation} 과 같은 필드에
 * {@code matchedRules}(매칭된 규칙 이름 목록)를 추가해 "왜 추천됐는지"를 설명한다.
 */
public record RuleMenuRecommendation(
        Long menuId,
        String name,
        int price,
        String category,
        double score,
        List<String> matchedRules
) {}
