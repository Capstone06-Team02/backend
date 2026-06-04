package capstone2.voisk.recommend;

import capstone2.voisk.config.RuleConfig;
import capstone2.voisk.entity.Category;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 룰베이스 추천 서비스 단위 테스트. MenuRepository는 mock으로 고정 메뉴 세트를 주입한다.
 *
 * <p>규칙이 특정 매장에 종속되지 않는지(감각 형용사 + 매장 카테고리명 동적 매칭 + 가격) 검증한다.
 *
 * 실행: ./gradlew test --tests "capstone2.voisk.recommend.RuleRecommendServiceTest"
 */
@ExtendWith(MockitoExtension.class)
class RuleRecommendServiceTest {

    @Mock
    private MenuRepository menuRepository;

    private final RuleConfig ruleConfig = new RuleConfig();

    private RuleRecommendService service;

    private static final Long STORE_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new RuleRecommendService(menuRepository, ruleConfig);
        when(menuRepository.findAvailableByStoreIdWithCategory(STORE_ID)).thenReturn(sampleMenus());
    }

    @Test
    void 단맛_발화는_설명에_달콤이_있는_메뉴만_추천한다() {
        RuleRecommendResponse res = service.recommend("달달한 거 추천해줘", STORE_ID);

        assertThat(res.recommendations()).isNotEmpty();
        assertThat(res.recommendations()).allMatch(r -> r.matchedRules().contains("단맛"));
        // 아메리카노(설명에 '달콤' 없음)는 포함되지 않아야 한다
        assertThat(res.recommendations()).noneMatch(r -> r.name().equals("아메리카노"));
    }

    @Test
    void 시원하고_달달한_발화는_두_규칙_모두_매칭된_메뉴가_최상위다() {
        RuleRecommendResponse res = service.recommend("시원하고 달달한 거", STORE_ID);

        RuleMenuRecommendation top = res.recommendations().get(0);
        assertThat(top.matchedRules()).contains("단맛", "시원함");
        assertThat(top.score()).isEqualTo(2.0);
    }

    @Test
    void 카테고리명_긍정_매칭은_해당_카테고리를_가산한다() {
        // 매장 실제 카테고리명 토큰("음료")으로 매칭 — 하드코딩 아님
        RuleRecommendResponse res = service.recommend("음료 종류로 추천해줘", STORE_ID);

        RuleMenuRecommendation top = res.recommendations().get(0);
        assertThat(top.category()).isEqualTo("티&음료");
        assertThat(top.matchedRules()).contains("카테고리:티&음료");
    }

    @Test
    void 카테고리_부정_발화는_해당_카테고리를_제외한다() {
        RuleRecommendResponse res = service.recommend("에스프레소 말고 추천해줘", STORE_ID);

        assertThat(res.recommendations()).isNotEmpty();
        assertThat(res.recommendations()).noneMatch(r -> r.category().equals("에스프레소"));
    }

    @Test
    void 저렴_발화는_하위_가격대_메뉴를_가산한다() {
        RuleRecommendResponse res = service.recommend("저렴한 거", STORE_ID);

        assertThat(res.recommendations()).isNotEmpty();
        assertThat(res.recommendations().get(0).matchedRules()).contains("저렴함");
        // 가장 비싼 메뉴(프라푸치노 6900)는 저렴 가산을 받지 않는다
        assertThat(res.recommendations()).noneMatch(r -> r.price() == 6900 && r.matchedRules().contains("저렴함"));
    }

    @Test
    void 활성_규칙이_없으면_가격_오름차순_폴백을_반환한다() {
        RuleRecommendResponse res = service.recommend("아무거나요", STORE_ID);

        assertThat(res.recommendations()).hasSize(ruleConfig.getLimit());
        List<Integer> prices = res.recommendations().stream().map(RuleMenuRecommendation::price).toList();
        assertThat(prices).isSorted();
        assertThat(res.recommendations()).allMatch(r -> r.matchedRules().isEmpty());
    }

    private List<Menu> sampleMenus() {
        return List.of(
                menu(1L, "아메리카노", 5500, "깔끔하고 진한 에스프레소에 물을 더한 커피.", "에스프레소"),
                menu(2L, "카페 라떼", 6000, "진한 에스프레소에 스팀 밀크를 더한 부드럽고 고소한 커피.", "에스프레소"),
                menu(3L, "카라멜 마키아토", 6300, "바닐라 시럽과 에스프레소, 카라멜 드리즐의 달콤한 라떼.", "에스프레소"),
                menu(4L, "자바 칩 프라푸치노", 6900, "진한 모카 소스와 초콜릿 칩, 에스프레소의 달콤하고 시원한 블렌디드.", "프라푸치노"),
                menu(5L, "카라멜 프라푸치노", 6500, "달콤한 카라멜 소스와 에스프레소의 시원하고 부드러운 블렌디드.", "프라푸치노"),
                menu(6L, "민트 블렌드 티", 5000, "상쾌하고 청량한 민트향의 허브 블렌드 티.", "티&음료"),
                menu(7L, "유스베리 티 라떼", 6300, "상큼한 유스베리와 부드러운 밀크의 달콤한 티 라떼.", "티&음료")
        );
    }

    private Menu menu(Long id, String name, int price, String desc, String categoryName) {
        Category category = Category.builder().name(categoryName).build();
        return Menu.builder()
                .menuId(id)
                .name(name)
                .price(price)
                .description(desc)
                .isAvailable(true)
                .category(category)
                .build();
    }
}
