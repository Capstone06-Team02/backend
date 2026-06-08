package capstone2.voisk.embedding.service;

import capstone2.voisk.embedding.client.EmbedClient;
import capstone2.voisk.embedding.domain.MenuEmbedding;
import capstone2.voisk.embedding.repository.MenuEmbeddingRepository;
import capstone2.voisk.embedding.util.EmbeddingTextBuilder;
import capstone2.voisk.entity.Menu;
import capstone2.voisk.entity.MenuOptionGroup;
import capstone2.voisk.repository.MenuOptionGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MenuEmbeddingService {

    private final EmbedClient embedClient;
    private final MenuEmbeddingRepository menuEmbeddingRepository;
    private final MenuOptionGroupRepository menuOptionGroupRepository;

    // 패시지에 옵션(온도/사이즈 등) 텍스트 포함 여부. true면 재임베딩 트리거(source 태그 변경)
    @Value("${embedding.include-options:false}")
    private boolean includeOptions;

    /**
     * 메뉴 패시지를 임베딩해 저장하고, 저장에 사용한 패시지 텍스트를 반환한다(로깅용).
     * 같은 menuId가 이미 있으면 save가 merge로 덮어쓴다 → 재임베딩에 그대로 사용 가능.
     */
    public String saveEmbedding(Menu menu) {
        String optionText = includeOptions ? buildOptionText(menu.getMenuId()) : null;
        String passageText = EmbeddingTextBuilder.buildPassageText(
                menu.getName(),
                menu.getDescription(),
                menu.getCategory().getName(),
                menu.getPrice(),
                optionText
        );
        float[] vector = embedClient.embed(passageText, false);

        MenuEmbedding embedding = MenuEmbedding.builder()
                .menuId(menu.getMenuId())
                .embedding(vector)
                .embeddingSource(currentSourceTag()) // 모델 + 옵션포함 여부 기록 → 재임베딩 판단 키
                .build();
        menuEmbeddingRepository.save(embedding);
        return passageText;
    }

    public void deleteEmbedding(Long menuId) {
        menuEmbeddingRepository.deleteById(menuId);
    }

    /**
     * 현재 패시지 구성을 식별하는 태그. 모델명에 옵션 포함 시 {@code +opt}를 붙인다.
     * 이 값이 저장된 {@code embedding_source}와 다르면 재임베딩 대상이다.
     */
    public String currentSourceTag() {
        String model = System.getenv().getOrDefault("EMBED_MODEL", "e5-base");
        return includeOptions ? model + "+opt" : model;
    }

    // 옵션 그룹/항목명을 "온도:핫·아이스, 사이즈:S·M·L" 형태로 직렬화. extraPrice·중복은 제외(잡음 최소화).
    private String buildOptionText(Long menuId) {
        List<MenuOptionGroup> groups = menuOptionGroupRepository.findTopLevelGroupsByMenuId(menuId);
        return groups.stream()
                .map(g -> {
                    String groupName = g.getOptionGroupTemplate().getName();
                    String items = g.getOptionItems().stream()
                            .filter(i -> i.getIsAvailable() == null || i.getIsAvailable())
                            .map(i -> i.getOptionItemTemplate().getName())
                            .filter(n -> n != null && !n.isBlank())
                            .distinct()
                            .collect(Collectors.joining("·"));
                    return items.isBlank() ? groupName : groupName + ":" + items;
                })
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(", "));
    }

    // Arrays.toString() 사용 금지 → 공백 포함으로 pgvector 파싱 실패
    // findTopKBySimilarity 의 queryVec 인자로 사용
    public String toVectorString(float[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
