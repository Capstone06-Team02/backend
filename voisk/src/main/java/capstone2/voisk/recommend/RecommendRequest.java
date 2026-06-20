package capstone2.voisk.recommend;

/**
 * @param topK 임베딩 추천에서 반환할 후보 개수(선택). null/0 이하면 기본 5(프로덕션 동작).
 *             측정(recall@K)·펀넬 후보 입구 용도로 더 큰 값을 지정할 수 있다. 룰·LLM 추천에선 무시된다.
 */
public record RecommendRequest(String text, Long storeId, Integer topK) {}
