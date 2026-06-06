package capstone2.voisk.recommend;

import java.util.List;

/** LLM 추천 응답: 검증을 통과한 추천 목록(최대 3개)과 TTS 안내 문구. */
public record LlmRecommendResponse(List<LlmMenuRecommendation> recommendations, String ttsText) {}
