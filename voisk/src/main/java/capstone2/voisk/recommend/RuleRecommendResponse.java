package capstone2.voisk.recommend;

import java.util.List;

public record RuleRecommendResponse(List<RuleMenuRecommendation> recommendations, String ttsText) {}
