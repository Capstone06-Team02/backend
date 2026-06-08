package capstone2.voisk.recommend;

/**
 * LLM(Gemini) 추천 결과 1건.
 *
 * <p>임베딩의 {@link MenuRecommendation}, 룰베이스의 {@link RuleMenuRecommendation}과 같은 식별 필드를 갖되,
 * 모든 값은 LLM이 뱉은 문자열이 아니라 DB의 실제 {@code Menu} 엔티티에서 채운다(환각 차단). LLM은 menuId만 고르고,
 * 서버가 그 id를 후보 집합과 대조해 검증한 뒤 DB 원본값으로 이 레코드를 구성한다.
 */
public record LlmMenuRecommendation(Long menuId, String name, int price, String category) {}
