package capstone2.voisk.embedding;

import capstone2.voisk.embedding.repository.MenuEmbeddingRepository;
import capstone2.voisk.embedding.service.MenuEmbeddingService;
import capstone2.voisk.embedding.util.EmbeddingTextBuilder;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingInitializer implements ApplicationRunner {

    private final MenuRepository menuRepository;
    private final MenuEmbeddingRepository menuEmbeddingRepository;
    private final MenuEmbeddingService menuEmbeddingService;

    @Override
    public void run(ApplicationArguments args) {
        // JOIN FETCH로 미리 로드되어 있어 LazyInitializationException 없음
        List<Menu> menus = menuRepository.findAllWithCategory();
        Set<Long> existingIds = menuEmbeddingRepository.findAllMenuIds();

        log.info("임베딩 초기화 시작: 총 {}개 메뉴", menus.size());

        int created = 0, skipped = 0;
        for (Menu menu : menus) {
            if (existingIds.contains(menu.getMenuId())) {
                skipped++;
                continue;
            }

            menuEmbeddingService.saveEmbedding(menu);

            String passageText = EmbeddingTextBuilder.buildPassageText(
                    menu.getName(), menu.getDescription(), menu.getCategory().getName(), menu.getPrice()
            );
            log.info("임베딩 생성 완료: {} → {}",
                    menu.getName(),
                    passageText.length() > 50 ? passageText.substring(0, 50) : passageText);
            created++;
        }

        log.info("임베딩 초기화 완료: {}개 생성, {}개 스킵", created, skipped);
    }
}
