package capstone2.voisk.recommend;

import capstone2.voisk.entity.Category;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.entity.Store;
import capstone2.voisk.repository.MenuRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class MenuRepositoryRecommendationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private MenuRepository menuRepository;

    private Long storeId;
    private Long otherStoreMenuId;
    private Long soldOutMenuId;
    private Long price4000Id;
    private Long price5000Id;
    private Long price6000Id;

    @BeforeEach
    void setUp() {
        Store store = entityManager.persist(Store.builder().name("테스트 매장").build());
        Category category = entityManager.persist(Category.builder()
                .name("음료")
                .depth(1)
                .store(store)
                .build());

        storeId = store.getId();
        price4000Id = persistMenu(store, category, "4천원 메뉴", 4000, true).getMenuId();
        price5000Id = persistMenu(store, category, "5천원 메뉴", 5000, true).getMenuId();
        price6000Id = persistMenu(store, category, "6천원 메뉴", 6000, true).getMenuId();
        soldOutMenuId = persistMenu(store, category, "품절 메뉴", 4500, false).getMenuId();

        Store otherStore = entityManager.persist(Store.builder().name("다른 매장").build());
        Category otherCategory = entityManager.persist(Category.builder()
                .name("다른 음료")
                .depth(1)
                .store(otherStore)
                .build());
        otherStoreMenuId = persistMenu(otherStore, otherCategory, "다른 매장 메뉴", 4500, true).getMenuId();

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 판매_가능_후보_조회에서_품절과_다른_매장을_제외한다() {
        List<Menu> result = menuRepository.findAvailableByMenuIdsAndStoreId(
                allMenuIds(), storeId
        );

        assertThat(result).extracting(Menu::getMenuId)
                .containsExactlyInAnyOrder(price4000Id, price5000Id, price6000Id)
                .doesNotContain(soldOutMenuId, otherStoreMenuId);
    }

    @Test
    void 이하에는_경계_가격을_포함하고_미만에는_제외한다() {
        List<Menu> inclusive = menuRepository.findValidRecommendationMenus(
                allMenuIds(), storeId, null, true, 5000, true
        );
        List<Menu> exclusive = menuRepository.findValidRecommendationMenus(
                allMenuIds(), storeId, null, true, 5000, false
        );

        assertThat(inclusive).extracting(Menu::getMenuId)
                .containsExactlyInAnyOrder(price4000Id, price5000Id);
        assertThat(exclusive).extracting(Menu::getMenuId)
                .containsExactly(price4000Id);
    }

    @Test
    void 이상에는_경계_가격을_포함하고_초과에는_제외한다() {
        List<Menu> inclusive = menuRepository.findValidRecommendationMenus(
                allMenuIds(), storeId, 5000, true, null, true
        );
        List<Menu> exclusive = menuRepository.findValidRecommendationMenus(
                allMenuIds(), storeId, 5000, false, null, true
        );

        assertThat(inclusive).extracting(Menu::getMenuId)
                .containsExactlyInAnyOrder(price5000Id, price6000Id);
        assertThat(exclusive).extracting(Menu::getMenuId)
                .containsExactly(price6000Id);
    }

    @Test
    void 최소와_최대_가격을_동시에_검증한다() {
        List<Menu> result = menuRepository.findValidRecommendationMenus(
                allMenuIds(), storeId, 4000, false, 6000, false
        );

        assertThat(result).extracting(Menu::getMenuId).containsExactly(price5000Id);
    }

    private Menu persistMenu(Store store, Category category, String name, int price, boolean available) {
        return entityManager.persist(Menu.builder()
                .name(name)
                .price(price)
                .description(name + " 설명")
                .isAvailable(available)
                .isSignature(false)
                .store(store)
                .category(category)
                .build());
    }

    private List<Long> allMenuIds() {
        return List.of(price4000Id, price5000Id, price6000Id, soldOutMenuId, otherStoreMenuId, 999999L);
    }
}
