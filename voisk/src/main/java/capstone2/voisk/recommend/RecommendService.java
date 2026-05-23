package capstone2.voisk.recommend;

import capstone2.voisk.config.BoostConfig;
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
    private final BoostConfig boostConfig;

    public RecommendResponse recommend(String text, Long storeId) {
        // Step 1: 쿼리 임베딩 — isQuery=true (e5 계열은 쿼리/패시지 프리픽스 구분)
        float[] queryVec;
        try {
            queryVec = embedClient.embed(text, true);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "추천 서버에 연결할 수 없습니다.");
        }

        // Step 2: pgvector 코사인 유사도 top-20 (storeId 무관, 이후 Step 4에서 필터)
        String queryVecStr = toVectorString(queryVec);
        List<Object[]> rawResults = menuEmbeddingRepository.findTopKBySimilarity(queryVecStr, 20);

        // Step 3: [menu_id, similarity] → Map (BigInteger 등 Number 하위 타입 대응)
        Map<Long, Double> similarityMap = rawResults.stream()
                .collect(Collectors.toMap(
                        r -> ((Number) r[0]).longValue(),
                        r -> ((Number) r[1]).doubleValue()
                ));

        // Step 4: MySQL 조회 + storeId 필터 (다른 매장 메뉴 제거)
        List<Long> menuIds = new ArrayList<>(similarityMap.keySet());
        List<Menu> menus = menuRepository.findByMenuIdsAndStoreId(menuIds, storeId);

        // Step 5: 부스트 적용 후 score 내림차순 정렬 → top-5
        List<MenuRecommendation> recommendations = menus.stream()
                .map(menu -> {
                    double score = similarityMap.get(menu.getMenuId());
                    // V3: score += boostConfig.getWCat() * catMatch + boostConfig.getWPrice() * priceMatch
                    return new MenuRecommendation(
                            menu.getMenuId(),
                            menu.getName(),
                            menu.getPrice(),
                            menu.getCategory().getName(),
                            score
                    );
                })
                .sorted(Comparator.comparingDouble(MenuRecommendation::score).reversed())
                .limit(5)
                .toList();

        return new RecommendResponse(recommendations, buildTtsText(recommendations));
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
