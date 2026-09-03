package capstone2.voisk.recommend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationConstraintsTest {

    @Test
    void 가격_조건이_없으면_유효하다() {
        RecommendationConstraints constraints = new RecommendationConstraints(null, true, null, true);

        assertThat(constraints.minPrice()).isNull();
        assertThat(constraints.maxPrice()).isNull();
    }

    @Test
    void 최소와_최대_가격이_같고_양쪽을_포함하면_정확한_가격_조건이다() {
        RecommendationConstraints constraints = new RecommendationConstraints(5000, true, 5000, true);

        assertThat(constraints.minPrice()).isEqualTo(5000);
        assertThat(constraints.maxPrice()).isEqualTo(5000);
    }

    @Test
    void 음수_가격은_거부한다() {
        assertThatThrownBy(() -> new RecommendationConstraints(-1, true, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecommendationConstraints(null, true, -1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최소_가격이_최대_가격보다_크면_거부한다() {
        assertThatThrownBy(() -> new RecommendationConstraints(6000, true, 5000, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_가격에서_한쪽이라도_제외하면_성립하지_않는_범위다() {
        assertThatThrownBy(() -> new RecommendationConstraints(5000, false, 5000, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecommendationConstraints(5000, true, 5000, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
