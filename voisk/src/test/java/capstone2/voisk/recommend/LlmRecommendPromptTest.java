package capstone2.voisk.recommend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRecommendPromptTest {

    @Test
    void 모든_사용자_조건을_동시에_충족하도록_지시한다() {
        assertThat(LlmRecommendService.SYSTEM_PROMPT)
                .contains("모두 동시에 만족해야 한다")
                .contains("일부 조건만 만족하는 메뉴는 대체 후보로 넣지 않는다");
    }

    @Test
    void 비슷하지만_다른_감각_표현을_대체_조건으로_보지_않는다() {
        assertThat(LlmRecommendService.SYSTEM_PROMPT)
                .contains("상큼함(과일·시트러스의 산미)")
                .contains("상쾌함(민트·허브 향)")
                .contains("청량함(탄산감)")
                .contains("시원함(차가운 온도)")
                .contains("대신 충족한 것으로 판단하지 않는다");
    }
}
