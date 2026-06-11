package capstone2.voisk.recommend;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "추천", description = "메뉴 추천 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;
    private final RecommendHintService recommendHintService;
    private final RuleRecommendService ruleRecommendService;
    private final LlmRecommendService llmRecommendService;
    private final FunnelRecommendService funnelRecommendService;

    @Operation(
            summary = "추천 요청 직접 입력",
            description = "사용자가 직접 입력한 텍스트를 임베딩 모델로 분석해 유사한 메뉴를 최대 5개 추천합니다."
    )
    @PostMapping("/recommend")
    public RecommendResponse recommend(@RequestBody RecommendRequest request) {
        return recommendService.recommend(request.text(), request.storeId(), request.topK());
    }

    @Operation(
            summary = "룰베이스 추천 요청 직접 입력",
            description = "임베딩 모델 없이 키워드 규칙 사전으로 메뉴를 점수화해 최대 5개 추천합니다. "
                    + "각 결과에 매칭된 규칙(matchedRules)을 함께 반환해 추천 근거를 설명합니다."
    )
    @PostMapping("/recommend/rule")
    public RuleRecommendResponse recommendByRule(@RequestBody RecommendRequest request) {
        return ruleRecommendService.recommend(request.text(), request.storeId());
    }

    @Operation(
            summary = "LLM 추천 요청 직접 입력",
            description = "사용자 발화를 Gemini에 보내 메뉴를 최대 3개 추천합니다. 해당 매장의 판매중 메뉴만 후보로 제공하고, "
                    + "LLM이 고른 menuId를 DB 후보와 대조 검증해 환각을 차단합니다. 이름·가격·카테고리는 DB 원본값으로 채웁니다."
    )
    @PostMapping("/recommend/llm")
    public LlmRecommendResponse recommendByLlm(@RequestBody RecommendRequest request) {
        return llmRecommendService.recommend(request.text(), request.storeId());
    }

    @Operation(
            summary = "하이브리드 펀넬 추천 (임베딩 검색 → LLM 재랭킹)",
            description = "임베딩으로 후보를 topK개로 추린 뒤 그 후보만 LLM에 넘겨 재랭킹합니다. 전체 메뉴를 LLM에 넣는 "
                    + "LLM 단독 방식 대비 입력·thinking 토큰을 K로 묶어 비용·지연을 억제합니다. topK 미지정 시 기본 20."
    )
    @PostMapping("/recommend/funnel")
    public LlmRecommendResponse recommendByFunnel(@RequestBody RecommendRequest request) {
        return funnelRecommendService.recommend(request.text(), request.storeId(), request.topK());
    }

    @Operation(
            summary = "추천 힌트 목록 조회",
            description = "매장에 등록된 추천 힌트 버튼 목록을 반환합니다. 클라이언트는 이 목록을 버튼으로 렌더링해 사용자가 선택할 수 있게 합니다."
    )
    @GetMapping("/recommend/hints")
    public RecommendHintListResponse getHints(@RequestParam Long storeId) {
        return recommendHintService.getHints(storeId);
    }

    @Operation(
            summary = "힌트 기반 메뉴 추천",
            description = "선택한 힌트에 사전 매핑된 메뉴를 rank 순서대로 반환합니다. 임베딩 모델을 사용하지 않습니다."
    )
    @PostMapping("/recommend/hints/{hintId}")
    public RecommendHintResponse recommendByHint(@PathVariable Long hintId) {
        return recommendHintService.recommendByHint(hintId);
    }
}
