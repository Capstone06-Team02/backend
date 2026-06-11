package capstone2.voisk.recommend;

import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 하이브리드 펀넬 추천 — <b>임베딩으로 후보를 top-K개로 좁힌 뒤 LLM이 재랭킹</b>한다.
 *
 * <p>LLM 단독({@link LlmRecommendService})은 매장 전체 메뉴를 프롬프트에 통째로 넣어 N에 비례해
 * 토큰·thinking·지연이 커진다. 펀넬은 LLM에 넣는 후보를 <b>K개로 고정</b>해 그 증가를 끊는 게 목적이다.
 *
 * <ul>
 *   <li><b>Stage 1 (리트리버):</b> {@link RecommendService}의 임베딩 검색으로 top-K menuId를 추린다.
 *       임베딩은 랭커로는 약하지만 recall@K는 높아(측정상 K=20이면 ≤50개에서 recall≥94.6%) 후보 입구로 적합.</li>
 *   <li><b>Stage 2 (재랭커):</b> 추린 K개만 {@link LlmRecommendService#recommendFromCandidates}로 넘긴다.
 *       부정·추론·환각차단은 모두 LLM 단독 경로와 동일하게 동작(로직 공유).</li>
 * </ul>
 *
 * 임베딩이 K개를 못 추리면(빈 결과) 빈 추천 + 안내 TTS로 graceful degradation 한다.
 */
@Service
@RequiredArgsConstructor
public class FunnelRecommendService {

    /** 펀넬 후보 입구 기본 K. recall@K 측정상 20이면 ≤50개 매장에서 recall≥94.6%, 30이면 100%. */
    private static final int DEFAULT_FUNNEL_K = 20;

    private final RecommendService recommendService;       // Stage 1: 임베딩 리트리버
    private final LlmRecommendService llmRecommendService; // Stage 2: LLM 재랭커
    private final MenuRepository menuRepository;

    @Transactional(readOnly = true)
    public LlmRecommendResponse recommend(String text, Long storeId, Integer funnelK) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId is required.");
        }
        int k = (funnelK == null || funnelK <= 0) ? DEFAULT_FUNNEL_K : funnelK;

        // Stage 1: 임베딩으로 후보 top-K 추림 (의미 유사도 기준 랭크드 menuId)
        RecommendResponse retrieved = recommendService.recommend(text, storeId, k);
        List<Long> candidateIds = retrieved.recommendations().stream()
                .map(MenuRecommendation::menuId)
                .toList();
        if (candidateIds.isEmpty()) {
            return new LlmRecommendResponse(List.of(), emptyTts(), LlmRecommendResponse.TokenUsage.zero());
        }

        // Stage 2: 추린 후보만 LLM에 넘겨 재랭킹 (전체 N개 대신 K개 → 토큰·thinking 축소 노림)
        List<Menu> candidates = menuRepository.findByMenuIdsAndStoreId(candidateIds, storeId);
        return llmRecommendService.recommendFromCandidates(text, candidates);
    }

    private String emptyTts() {
        return "죄송합니다, 조건에 맞는 메뉴를 찾지 못했어요.";
    }
}
