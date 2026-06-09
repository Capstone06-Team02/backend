package capstone2.voisk.recommend;

import capstone2.voisk.converter.RecommendResponseConverter;
import capstone2.voisk.embedding.client.EmbedClient;
import capstone2.voisk.embedding.repository.MenuEmbeddingRepository;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecommendService {

    private final EmbedClient embedClient;
    private final MenuEmbeddingRepository menuEmbeddingRepository;
    private final MenuRepository menuRepository;
    private final RecommendResponseConverter recommendResponseConverter;

    // pgvector는 storeId를 모르므로(menu_id·벡터만 보관) 전역 top-K를 뽑은 뒤 MySQL에서 매장 필터를 한다.
    // 여러 매장 임베딩이 한 테이블에 공존하면 K가 작을수록 매장 필터 후 후보가 부족해진다 → 넉넉히 둔다.
    private static final int CANDIDATE_POOL_SIZE = 100;
    /** 반환 후보 기본 개수(프로덕션 TTS 낭독분). topK 미지정 시 이 값. */
    private static final int DEFAULT_TOP_K = 5;

    /** 기본 top-5 추천(프로덕션 경로). */
    public RecommendResponse recommend(String text, Long storeId) {
        return recommend(text, storeId, null);
    }

    /**
     * 순수 임베딩 추천 — pgvector 코사인 유사도로만 정렬한다(사전·규칙 없이 의미 유사도만 사용 → 범용성 유지).
     * 패시지는 옵션 텍스트를 포함할 수 있고({@code embedding.include-options}), 점수는 0~1로 정규화해 반환한다.
     *
     * @param topK 반환 후보 개수. null/0 이하면 기본 5. recall@K 측정·펀넬 후보 입구 용도로 더 크게 줄 수 있다
     *             (랭킹·정규화는 동일, 잘라내는 개수만 달라짐 → 측정과 프로덕션이 같은 순위를 본다).
     */
    public RecommendResponse recommend(String text, Long storeId, Integer topK) {
        int limit = (topK == null || topK <= 0) ? DEFAULT_TOP_K : topK;
        // Step 1: 쿼리 임베딩 — isQuery=true (e5 계열은 쿼리/패시지 프리픽스 구분)
        float[] queryVec;
        try {
            queryVec = embedClient.embed(text, true);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "추천 서버에 연결할 수 없습니다.");
        }

        // Step 2: pgvector 코사인 유사도 전역 top-K (storeId 무관, 이후 Step 4에서 매장 필터)
        String queryVecStr = toVectorString(queryVec);
        List<Object[]> rawResults = menuEmbeddingRepository.findTopKBySimilarity(queryVecStr, CANDIDATE_POOL_SIZE);

        // Step 3: [menu_id, similarity] → Map (BigInteger 등 Number 하위 타입 대응)
        Map<Long, Double> similarityMap = rawResults.stream()
                .collect(Collectors.toMap(
                        r -> ((Number) r[0]).longValue(),
                        r -> ((Number) r[1]).doubleValue()
                ));

        // Step 4: MySQL 조회 + storeId 필터 (다른 매장 메뉴 제거)
        List<Long> menuIds = new ArrayList<>(similarityMap.keySet());
        List<Menu> menus = menuRepository.findByMenuIdsAndStoreId(menuIds, storeId);

        // Step 5: 코사인 점수 정렬 → top-K (점수는 0~1 정규화해 표시)
        List<MenuRecommendation> recommendations = score(menus, similarityMap, limit);

        return recommendResponseConverter.toResponse(recommendations, buildTtsText(recommendations));
    }

    // 코사인 유사도로 정렬. min-max 정규화는 단조변환이라 순위는 코사인 원점수와 동일하며,
    // score 필드를 0~1 스케일로 보기 좋게 만들어 줄 뿐이다(평탄도 진단용 score 분리도 확인에도 사용).
    private List<MenuRecommendation> score(List<Menu> menus, Map<Long, Double> similarityMap, int limit) {
        if (menus.isEmpty()) {
            return List.of();
        }

        double min = menus.stream().mapToDouble(m -> similarityMap.get(m.getMenuId())).min().orElse(0);
        double max = menus.stream().mapToDouble(m -> similarityMap.get(m.getMenuId())).max().orElse(0);
        double range = max - min;

        return menus.stream()
                .map(menu -> {
                    double cosine = similarityMap.get(menu.getMenuId());
                    double norm = range == 0 ? 0.0 : (cosine - min) / range;
                    return recommendResponseConverter.toRecommendation(menu, norm);
                })
                .sorted(Comparator.comparingDouble(MenuRecommendation::score).reversed())
                .limit(limit)
                .toList();
    }

    private String buildTtsText(List<MenuRecommendation> list) {
        if (list.isEmpty()) {
            return "죄송합니다, 조건에 맞는 메뉴를 찾지 못했어요.";
        }
        if (list.size() == 1) {
            return list.get(0).name() + "을(를) 추천드려요.";
        }
        String names = list.stream().map(MenuRecommendation::name).collect(Collectors.joining(", "));
        return "추천 메뉴로는 " + names + "를 추천드려요.";
    }

    // Arrays.toString() 사용 금지 → 공백 포함으로 pgvector 파싱 실패
    private String toVectorString(float[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
