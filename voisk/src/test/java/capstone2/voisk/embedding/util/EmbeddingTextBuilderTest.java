package capstone2.voisk.embedding.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingTextBuilderTest {

    @Test
    void 모델_접두사는_임베딩_서버가_붙이므로_패시지_본문에는_포함하지_않는다() {
        String text = EmbeddingTextBuilder.buildPassageText(
                "페퍼민트 티",
                "민트 향이 상쾌한 허브티",
                "티·에이드",
                4500
        );

        assertThat(text)
                .doesNotStartWith("passage:")
                .isEqualTo("페퍼민트 티 — 민트 향이 상쾌한 허브티 (카테고리=티·에이드 / 가격=4500원)");
    }

    @Test
    void 옵션_텍스트를_포함해도_모델_접두사는_추가하지_않는다() {
        String text = EmbeddingTextBuilder.buildPassageText(
                "카페 라떼",
                "에스프레소와 우유가 어우러진 음료",
                "라떼",
                5200,
                "온도:핫·아이스, 사이즈:톨·그란데"
        );

        assertThat(text)
                .doesNotStartWith("passage:")
                .contains("옵션=온도:핫·아이스, 사이즈:톨·그란데");
    }
}
