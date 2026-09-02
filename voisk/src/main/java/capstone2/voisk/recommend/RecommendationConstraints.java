package capstone2.voisk.recommend;

/** LLM 추출 가격 조건: 옵션 제외 메뉴 기본가 기준 */
public record RecommendationConstraints(
        Integer minPrice,
        boolean minPriceInclusive,
        Integer maxPrice,
        boolean maxPriceInclusive
) {

    public RecommendationConstraints {
        if (minPrice != null && minPrice < 0) {
            throw new IllegalArgumentException("minPrice must be zero or positive.");
        }
        if (maxPrice != null && maxPrice < 0) {
            throw new IllegalArgumentException("maxPrice must be zero or positive.");
        }
        if (minPrice != null && maxPrice != null) {
            if (minPrice > maxPrice) {
                throw new IllegalArgumentException("minPrice must not exceed maxPrice.");
            }
            if (minPrice.equals(maxPrice) && (!minPriceInclusive || !maxPriceInclusive)) {
                throw new IllegalArgumentException("Equal price bounds must both be inclusive.");
            }
        }
    }

    public static RecommendationConstraints none() {
        return new RecommendationConstraints(null, true, null, true);
    }
}
