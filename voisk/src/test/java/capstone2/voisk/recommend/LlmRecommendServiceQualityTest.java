package capstone2.voisk.recommend;

import capstone2.voisk.config.GeminiProperties;
import capstone2.voisk.entity.Category;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class LlmRecommendServiceQualityTest {

    private static final Long STORE_ID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private MenuRepository menuRepository;

    private MockRestServiceServer server;
    private LlmRecommendService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        server = MockRestServiceServer.bindTo(builder).build();

        GeminiProperties properties = new GeminiProperties();
        properties.setModel("test-model");

        RecommendationValidationService validationService = new RecommendationValidationService(menuRepository);
        service = new LlmRecommendService(
                builder.build(),
                properties,
                menuRepository,
                new GeminiRecommendationParser(),
                validationService,
                0L
        );
    }

    @Test
    void 가격_초과_상위_후보를_제거하고_후순위로_3개를_보충한다() throws Exception {
        List<Menu> candidates = List.of(
                menu(1L, "비싼 메뉴", 6000),
                menu(2L, "후보 2", 4500),
                menu(3L, "후보 3", 4800),
                menu(4L, "후보 4", 5000)
        );
        expectGeminiSuccess(decisionJson(5000, true, List.of(1L, 2L, 3L, 4L)));
        when(menuRepository.findValidRecommendationMenus(
                List.of(1L, 2L, 3L, 4L), STORE_ID, null, true, 5000, true
        )).thenReturn(List.of(candidates.get(3), candidates.get(2), candidates.get(1)));

        LlmRecommendResponse response = service.recommendFromCandidates(
                "오천 원 이하로 추천해줘", STORE_ID, candidates
        );

        assertThat(response.recommendations()).extracting(LlmMenuRecommendation::menuId)
                .containsExactly(2L, 3L, 4L);
        assertThat(response.recommendations()).allMatch(menu -> menu.price() <= 5000);
        server.verify();
    }

    @Test
    void 유효한_빈_추천은_오류가_아니며_실제_토큰_사용량을_유지한다() throws Exception {
        expectGeminiSuccess(decisionJson(3000, true, List.of()));

        LlmRecommendResponse response = service.recommendFromCandidates(
                "삼천 원 이하로 추천해줘",
                STORE_ID,
                List.of(menu(1L, "메뉴", 5000))
        );

        assertThat(response.recommendations()).isEmpty();
        assertThat(response.usage()).isEqualTo(new LlmRecommendResponse.TokenUsage(100, 20, 120));
        server.verify();
    }

    @Test
    void constraints가_없는_LLM_응답은_503으로_구분한다() throws Exception {
        expectGeminiSuccess("{\"rankedMenuIds\":[1]}");

        assertThatThrownBy(() -> service.recommendFromCandidates(
                "추천해줘", STORE_ID, List.of(menu(1L, "메뉴", 4500))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(503));
        server.verify();
    }

    @Test
    void 첫_503은_한_번만_재시도하고_성공_응답을_사용한다() throws Exception {
        server.expect(requestTo("http://localhost/v1beta/models/test-model:generateContent"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectGeminiSuccess(decisionJson(null, true, List.of(1L)));
        Menu menu = menu(1L, "메뉴", 4500);
        when(menuRepository.findValidRecommendationMenus(
                List.of(1L), STORE_ID, null, true, null, true
        )).thenReturn(List.of(menu));

        LlmRecommendResponse response = service.recommendFromCandidates(
                "추천해줘", STORE_ID, List.of(menu)
        );

        assertThat(response.recommendations()).extracting(LlmMenuRecommendation::menuId)
                .containsExactly(1L);
        server.verify();
    }

    @Test
    void 두_번째_503도_실패하면_503을_반환한다() {
        server.expect(requestTo("http://localhost/v1beta/models/test-model:generateContent"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("http://localhost/v1beta/models/test-model:generateContent"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> service.recommendFromCandidates(
                "추천해줘", STORE_ID, List.of(menu(1L, "메뉴", 4500))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(503));
        server.verify();
    }

    @Test
    void 연결_예외는_재시도하지_않고_바로_503으로_구분한다() {
        server.expect(requestTo("http://localhost/v1beta/models/test-model:generateContent"))
                .andRespond(withException(new java.io.IOException("read timeout")));

        assertThatThrownBy(() -> service.recommendFromCandidates(
                "추천해줘", STORE_ID, List.of(menu(1L, "메뉴", 4500))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(503));
        server.verify();
    }

    @Test
    void 추천_요청은_추론_예산을_512로_제한한다() throws Exception {
        server.expect(requestTo("http://localhost/v1beta/models/test-model:generateContent"))
                .andExpect(content().json("""
                        {
                          "generationConfig": {
                            "thinkingConfig": {
                              "thinkingBudget": 512
                            }
                          }
                        }
                        """, false))
                .andRespond(withSuccess(
                        geminiEnvelope(decisionJson(null, true, List.of())),
                        org.springframework.http.MediaType.APPLICATION_JSON
                ));

        service.recommendFromCandidates(
                "추천해줘", STORE_ID, List.of(menu(1L, "메뉴", 4500))
        );

        server.verify();
    }

    @Test
    void 추천_추론_예산을_설정값으로_전달한다() throws Exception {
        GeminiProperties properties = new GeminiProperties();
        properties.setModel("test-model");
        properties.setRecommendationThinkingBudget(256);
        RecommendationValidationService validationService = new RecommendationValidationService(menuRepository);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new LlmRecommendService(
                builder.build(),
                properties,
                menuRepository,
                new GeminiRecommendationParser(),
                validationService,
                0L
        );
        server.expect(requestTo("http://localhost/v1beta/models/test-model:generateContent"))
                .andExpect(content().json("""
                        {
                          "generationConfig": {
                            "thinkingConfig": {
                              "thinkingBudget": 256
                            }
                          }
                        }
                        """, false))
                .andRespond(withSuccess(
                        geminiEnvelope(decisionJson(null, true, List.of())),
                        org.springframework.http.MediaType.APPLICATION_JSON
                ));

        service.recommendFromCandidates(
                "추천해줘", STORE_ID, List.of(menu(1L, "메뉴", 4500))
        );

        server.verify();
    }

    private void expectGeminiSuccess(String decisionJson) throws Exception {
        server.expect(requestTo("http://localhost/v1beta/models/test-model:generateContent"))
                .andRespond(withSuccess(geminiEnvelope(decisionJson), org.springframework.http.MediaType.APPLICATION_JSON));
    }

    private String geminiEnvelope(String decisionJson) throws Exception {
        return """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{"text": %s}]
                    }
                  }],
                  "usageMetadata": {
                    "promptTokenCount": 100,
                    "candidatesTokenCount": 20,
                    "totalTokenCount": 120
                  }
                }
                """.formatted(MAPPER.writeValueAsString(decisionJson));
    }

    private String decisionJson(Integer maxPrice, boolean inclusive, List<Long> ids) throws Exception {
        return MAPPER.writeValueAsString(java.util.Map.of(
                "constraints", java.util.Map.of(
                        "minPrice", com.fasterxml.jackson.databind.node.NullNode.getInstance(),
                        "minPriceInclusive", true,
                        "maxPrice", maxPrice == null
                                ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                                : maxPrice,
                        "maxPriceInclusive", inclusive
                ),
                "rankedMenuIds", ids
        ));
    }

    private Menu menu(Long id, String name, int price) {
        return Menu.builder()
                .menuId(id)
                .name(name)
                .price(price)
                .description(name + " 설명")
                .isAvailable(true)
                .category(Category.builder().name("음료").build())
                .build();
    }
}
