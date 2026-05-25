package capstone2.voisk.recommend;

import java.util.List;

public record RecommendResponse(List<MenuRecommendation> recommendations, String ttsText) {}
