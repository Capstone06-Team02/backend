package capstone2.voisk.recommend;

import java.util.List;

public record RecommendHintListResponse(List<HintInfo> hints) {

    public record HintInfo(Long hintId, String label) {}
}
