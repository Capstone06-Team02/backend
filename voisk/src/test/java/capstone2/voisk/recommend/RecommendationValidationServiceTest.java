package capstone2.voisk.recommend;

import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationValidationServiceTest {

    private static final Long STORE_ID = 1L;

    @Mock
    private MenuRepository menuRepository;

    private RecommendationValidationService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationValidationService(menuRepository);
    }

    @Test
    void 후보_밖_ID와_중복을_제거한_뒤_DB_검증한다() {
        RecommendationConstraints constraints = new RecommendationConstraints(null, true, 5000, true);
        when(menuRepository.findValidRecommendationMenus(
                List.of(3L, 1L, 2L), STORE_ID, null, true, 5000, true
        )).thenReturn(List.of(menu(2L), menu(1L), menu(3L)));

        List<Menu> result = service.validate(
                STORE_ID,
                List.of(1L, 2L, 3L),
                constraints,
                List.of(99L, 3L, 1L, 3L, 2L)
        );

        assertThat(result).extracting(Menu::getMenuId).containsExactly(3L, 1L, 2L);
    }

    @Test
    void 상위_후보가_DB에서_탈락하면_다음_순위로_최대_3개를_보충한다() {
        RecommendationConstraints constraints = new RecommendationConstraints(null, true, 5000, false);
        when(menuRepository.findValidRecommendationMenus(
                List.of(1L, 2L, 3L, 4L), STORE_ID, null, true, 5000, false
        )).thenReturn(List.of(menu(4L), menu(3L), menu(2L)));

        List<Menu> result = service.validate(
                STORE_ID,
                List.of(1L, 2L, 3L, 4L),
                constraints,
                List.of(1L, 2L, 3L, 4L)
        );

        assertThat(result).extracting(Menu::getMenuId).containsExactly(2L, 3L, 4L);
    }

    @Test
    void LLM_ID는_최대_10개까지만_검증한다() {
        List<Long> eleven = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);
        List<Long> firstTen = eleven.subList(0, 10);
        when(menuRepository.findValidRecommendationMenus(
                firstTen, STORE_ID, null, true, null, true
        )).thenReturn(List.of(menu(10L), menu(11L)));

        List<Menu> result = service.validate(
                STORE_ID,
                eleven,
                RecommendationConstraints.none(),
                eleven
        );

        assertThat(result).extracting(Menu::getMenuId).containsExactly(10L);
    }

    @Test
    void 검증할_ID가_없으면_DB를_호출하지_않는다() {
        List<Menu> result = service.validate(
                STORE_ID,
                List.of(1L, 2L),
                RecommendationConstraints.none(),
                List.of(99L)
        );

        assertThat(result).isEmpty();
        verify(menuRepository, never()).findValidRecommendationMenus(
                anyList(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
    }

    @Test
    void 가격_제약이_누락되면_조건_없음으로_우회하지_않는다() {
        assertThatThrownBy(() -> service.validate(
                STORE_ID,
                List.of(1L),
                null,
                List.of(1L)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private Menu menu(Long id) {
        return Menu.builder().menuId(id).name("메뉴 " + id).price(4500).isAvailable(true).build();
    }
}
