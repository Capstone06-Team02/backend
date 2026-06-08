package capstone2.voisk.embedding;

import capstone2.voisk.embedding.repository.MenuEmbeddingRepository;
import capstone2.voisk.embedding.repository.MenuEmbeddingRepository.MenuIdSource;
import capstone2.voisk.embedding.service.MenuEmbeddingService;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingInitializer implements ApplicationRunner {

    private final MenuRepository menuRepository;
    private final MenuEmbeddingRepository menuEmbeddingRepository;
    private final MenuEmbeddingService menuEmbeddingService;

    @Override
    @Transactional(readOnly = true) // buildOptionText 의 옵션 조회가 같은 영속성 컨텍스트에서 동작
    public void run(ApplicationArguments args) {
        // JOIN FETCH로 category를 미리 로드. 옵션은 saveEmbedding 내부에서 menuId로 별도 조회한다.
        List<Menu> menus = menuRepository.findAllWithCategory();

        // menuId → 저장된 source. 현재 패시지 구성 태그와 다르면(모델/옵션포함 변경) 재임베딩한다.
        String currentTag = menuEmbeddingService.currentSourceTag();
        Map<Long, String> existingSource = menuEmbeddingRepository.findAllIdAndSource().stream()
                .collect(Collectors.toMap(MenuIdSource::getMenuId,
                        s -> s.getSource() == null ? "" : s.getSource(), (a, b) -> a));

        log.info("임베딩 초기화 시작: 총 {}개 메뉴 (현재 패시지 태그={})", menus.size(), currentTag);

        int created = 0, reembedded = 0, skipped = 0;
        for (Menu menu : menus) {
            String prev = existingSource.get(menu.getMenuId());
            if (currentTag.equals(prev)) {
                skipped++;
                continue;
            }

            try {
                String passageText = menuEmbeddingService.saveEmbedding(menu);
                String shortText = passageText.length() > 60 ? passageText.substring(0, 60) + "…" : passageText;
                if (prev == null) {
                    log.info("임베딩 생성: {} → {}", menu.getName(), shortText);
                    created++;
                } else {
                    log.info("재임베딩({}→{}): {} → {}", prev, currentTag, menu.getName(), shortText);
                    reembedded++;
                }
            } catch (RuntimeException e) {
                log.warn("임베딩 생성 실패 (menuId={}): {}", menu.getMenuId(), e.getMessage());
                skipped++;
            }
        }

        log.info("임베딩 초기화 완료: {}개 생성, {}개 재임베딩, {}개 스킵", created, reembedded, skipped);
    }
}
