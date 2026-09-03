package capstone2.voisk.recommend;

import capstone2.voisk.entity.Category;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunnelRecommendServiceTest {

    private static final Long STORE_ID = 1L;

    @Mock
    private RecommendService recommendService;
    @Mock
    private LlmRecommendService llmRecommendService;
    @Mock
    private MenuRepository menuRepository;

    private FunnelRecommendService service;

    @BeforeEach
    void setUp() {
        service = new FunnelRecommendService(recommendService, llmRecommendService, menuRepository);
    }

    @Test
    void 기본_K는_20이고_DB_조회_후에도_임베딩_순서를_보존한다() {
        String text = "오천 원 이하로 추천해줘";
        when(recommendService.recommend(text, STORE_ID, 20)).thenReturn(new RecommendResponse(
                List.of(
                        recommendation(3L, 0.9),
                        recommendation(1L, 0.8),
                        recommendation(2L, 0.7)
                ),
                ""
        ));
        when(menuRepository.findAvailableByMenuIdsAndStoreId(List.of(3L, 1L, 2L), STORE_ID))
                .thenReturn(List.of(menu(1L), menu(2L), menu(3L)));
        when(llmRecommendService.recommendFromCandidates(
                org.mockito.ArgumentMatchers.eq(text),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                org.mockito.ArgumentMatchers.anyList()
        )).thenReturn(new LlmRecommendResponse(List.of(), "", LlmRecommendResponse.TokenUsage.zero()));

        service.recommend(text, STORE_ID, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Menu>> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmRecommendService).recommendFromCandidates(
                org.mockito.ArgumentMatchers.eq(text),
                org.mockito.ArgumentMatchers.eq(STORE_ID),
                candidatesCaptor.capture()
        );
        assertThat(candidatesCaptor.getValue()).extracting(Menu::getMenuId)
                .containsExactly(3L, 1L, 2L);
    }

    @Test
    void 임베딩_후보가_없으면_DB와_LLM을_호출하지_않는다() {
        String text = "추천해줘";
        when(recommendService.recommend(text, STORE_ID, 20))
                .thenReturn(new RecommendResponse(List.of(), ""));

        LlmRecommendResponse result = service.recommend(text, STORE_ID, null);

        assertThat(result.recommendations()).isEmpty();
        verify(menuRepository, never()).findAvailableByMenuIdsAndStoreId(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyLong()
        );
        verify(llmRecommendService, never()).recommendFromCandidates(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    private MenuRecommendation recommendation(Long id, double score) {
        return new MenuRecommendation(id, "메뉴 " + id, 4500, "음료", score);
    }

    private Menu menu(Long id) {
        return Menu.builder()
                .menuId(id)
                .name("메뉴 " + id)
                .price(4500)
                .description("설명")
                .isAvailable(true)
                .category(Category.builder().name("음료").build())
                .build();
    }
}
