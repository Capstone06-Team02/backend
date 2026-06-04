package capstone2.voisk.recommend;

import java.util.List;

public record RecommendHintResponse(List<MenuItem> menus, String ttsText) {

    public record MenuItem(Long menuId, String name, Integer price) {}
}
