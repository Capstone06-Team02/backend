package capstone2.voisk.recommend;

import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 추천 펀넬
 * - 1차: 임베딩 top-K 후보
 * - 2차: LLM 조건 추출·재랭킹
 * - 3차: DB 최종 검증
 */
@Service
@RequiredArgsConstructor
public class FunnelRecommendService {

    /** 임베딩 기본 후보 수 */
    private static final int DEFAULT_FUNNEL_K = 20;

    private final RecommendService recommendService;       // 1차: 임베딩
    private final LlmRecommendService llmRecommendService; // 2차: LLM
    private final MenuRepository menuRepository;

    public LlmRecommendResponse recommend(String text, Long storeId, Integer funnelK) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId is required.");
        }
        int k = (funnelK == null || funnelK <= 0) ? DEFAULT_FUNNEL_K : funnelK;

        // 1차: 임베딩 top-K
        RecommendResponse retrieved = recommendService.recommend(text, storeId, k);
        List<Long> candidateIds = retrieved.recommendations().stream()
                .map(MenuRecommendation::menuId)
                .toList();
        if (candidateIds.isEmpty()) {
            return new LlmRecommendResponse(List.of(), emptyTts(), LlmRecommendResponse.TokenUsage.zero());
        }

        // 2차: LLM 조건 추출·재랭킹
        List<Menu> loadedCandidates = menuRepository.findAvailableByMenuIdsAndStoreId(candidateIds, storeId);
        Map<Long, Menu> candidateById = loadedCandidates.stream()
                .collect(Collectors.toMap(Menu::getMenuId, Function.identity(), (first, ignored) -> first));
        List<Menu> candidates = candidateIds.stream()
                .map(candidateById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        return llmRecommendService.recommendFromCandidates(text, storeId, candidates);
    }

    private String emptyTts() {
        return "죄송합니다, 조건에 맞는 메뉴를 찾지 못했어요.";
    }
}
