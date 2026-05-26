package capstone2.voisk.converter;

import capstone2.voisk.entity.Menu;
import capstone2.voisk.recommend.MenuRecommendation;
import capstone2.voisk.recommend.RecommendResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RecommendResponseConverter {

    public MenuRecommendation toRecommendation(Menu menu, double score) {
        return new MenuRecommendation(
                menu.getMenuId(),
                menu.getName(),
                menu.getPrice(),
                menu.getCategory().getName(),
                score
        );
    }

    public RecommendResponse toResponse(List<MenuRecommendation> recommendations, String ttsText) {
        return new RecommendResponse(recommendations, ttsText);
    }
}
