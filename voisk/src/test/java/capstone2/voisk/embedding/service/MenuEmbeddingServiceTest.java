package capstone2.voisk.embedding.service;

import capstone2.voisk.embedding.client.EmbedClient;
import capstone2.voisk.embedding.repository.MenuEmbeddingRepository;
import capstone2.voisk.repository.MenuOptionGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class MenuEmbeddingServiceTest {

    @Mock
    private EmbedClient embedClient;

    @Mock
    private MenuEmbeddingRepository menuEmbeddingRepository;

    @Mock
    private MenuOptionGroupRepository menuOptionGroupRepository;

    private MenuEmbeddingService service;

    @BeforeEach
    void setUp() {
        service = new MenuEmbeddingService(
                embedClient,
                menuEmbeddingRepository,
                menuOptionGroupRepository
        );
    }

    @Test
    void 패시지_형식이_바뀌면_기존_벡터를_재생성하도록_소스_버전을_포함한다() {
        assertThat(service.currentSourceTag()).endsWith("+text-v2");
    }

    @Test
    void 옵션을_포함한_패시지도_형식_버전을_구분한다() {
        ReflectionTestUtils.setField(service, "includeOptions", true);

        assertThat(service.currentSourceTag()).endsWith("+opt+text-v2");
    }
}
