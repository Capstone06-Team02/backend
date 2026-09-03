package capstone2.voisk.recommend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiRecommendationParserTest {

    private final GeminiRecommendationParser parser = new GeminiRecommendationParser();
    private final LlmRecommendResponse.TokenUsage usage = new LlmRecommendResponse.TokenUsage(100, 20, 120);

    @Test
    void 가격_상한과_순위_ID를_파싱한다() {
        String content = """
                {
                  "constraints": {
                    "minPrice": null,
                    "minPriceInclusive": true,
                    "maxPrice": 5000,
                    "maxPriceInclusive": true
                  },
                  "rankedMenuIds": [7, 3, 9]
                }
                """;

        GeminiRecommendationParser.ParsedDecision result = parser.parse(content, usage);

        assertThat(result.constraints())
                .isEqualTo(new RecommendationConstraints(null, true, 5000, true));
        assertThat(result.rankedMenuIds()).containsExactly(7L, 3L, 9L);
        assertThat(result.usage()).isEqualTo(usage);
    }

    @Test
    void 가격_조건이_없는_정상_응답을_파싱한다() {
        String content = """
                {
                  "constraints": {
                    "minPrice": null,
                    "minPriceInclusive": true,
                    "maxPrice": null,
                    "maxPriceInclusive": true
                  },
                  "rankedMenuIds": []
                }
                """;

        GeminiRecommendationParser.ParsedDecision result = parser.parse(content, usage);

        assertThat(result.constraints()).isEqualTo(RecommendationConstraints.none());
        assertThat(result.rankedMenuIds()).isEqualTo(List.of());
    }

    @Test
    void 가격_경계가_null이면_포함여부가_누락되거나_null이어도_조건없음으로_정규화한다() {
        String content = """
                {
                  "constraints": {
                    "minPrice": null,
                    "maxPrice": null,
                    "maxPriceInclusive": null
                  },
                  "rankedMenuIds": [1]
                }
                """;

        GeminiRecommendationParser.ParsedDecision result = parser.parse(content, usage);

        assertThat(result.constraints()).isEqualTo(RecommendationConstraints.none());
        assertThat(result.rankedMenuIds()).containsExactly(1L);
    }

    @Test
    void 실제_가격_경계가_있으면_포함여부_누락을_허용하지_않는다() {
        String content = """
                {
                  "constraints": {
                    "minPrice": 5000,
                    "maxPrice": null,
                    "maxPriceInclusive": null
                  },
                  "rankedMenuIds": [1]
                }
                """;

        assertThatThrownBy(() -> parser.parse(content, usage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minPriceInclusive");
    }

    @Test
    void constraints가_누락되면_장애로_처리한다() {
        assertThatThrownBy(() -> parser.parse("{\"rankedMenuIds\":[1,2]}", usage))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rankedMenuIds가_누락되면_장애로_처리한다() {
        String content = """
                {
                  "constraints": {
                    "minPrice": null,
                    "minPriceInclusive": true,
                    "maxPrice": 5000,
                    "maxPriceInclusive": true
                  }
                }
                """;

        assertThatThrownBy(() -> parser.parse(content, usage))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 문자열_가격과_문자열_ID는_허용하지_않는다() {
        String stringPrice = """
                {
                  "constraints": {
                    "minPrice": null,
                    "minPriceInclusive": true,
                    "maxPrice": "5000",
                    "maxPriceInclusive": true
                  },
                  "rankedMenuIds": [1]
                }
                """;
        String stringId = """
                {
                  "constraints": {
                    "minPrice": null,
                    "minPriceInclusive": true,
                    "maxPrice": 5000,
                    "maxPriceInclusive": true
                  },
                  "rankedMenuIds": ["1"]
                }
                """;

        assertThatThrownBy(() -> parser.parse(stringPrice, usage))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(stringId, usage))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 모순된_가격_범위는_허용하지_않는다() {
        String content = """
                {
                  "constraints": {
                    "minPrice": 6000,
                    "minPriceInclusive": true,
                    "maxPrice": 5000,
                    "maxPriceInclusive": true
                  },
                  "rankedMenuIds": [1]
                }
                """;

        assertThatThrownBy(() -> parser.parse(content, usage))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
