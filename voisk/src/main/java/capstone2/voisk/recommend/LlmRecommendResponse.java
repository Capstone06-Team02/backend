package capstone2.voisk.recommend;

import java.util.List;

/**
 * LLM 추천 응답: 검증을 통과한 추천 목록(최대 3개)과 TTS 안내 문구.
 * 비용 측정을 위해 이번 호출의 Gemini 토큰 사용량({@link TokenUsage})을 함께 싣는다.
 */
public record LlmRecommendResponse(List<LlmMenuRecommendation> recommendations, String ttsText, TokenUsage usage) {

    /** Gemini usageMetadata — 호출당 입력/출력/합계 토큰. 호출 실패 시 0. */
    public record TokenUsage(int promptTokens, int outputTokens, int totalTokens) {
        public static TokenUsage zero() {
            return new TokenUsage(0, 0, 0);
        }
    }
}
