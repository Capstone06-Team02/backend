package capstone2.voisk.embedding.service;

import capstone2.voisk.embedding.client.EmbedClient;
import capstone2.voisk.embedding.domain.MenuEmbedding;
import capstone2.voisk.embedding.repository.MenuEmbeddingRepository;
import capstone2.voisk.embedding.util.EmbeddingTextBuilder;
import capstone2.voisk.entity.Menu;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MenuEmbeddingService {

    private final EmbedClient embedClient;
    private final MenuEmbeddingRepository menuEmbeddingRepository;

    public void saveEmbedding(Menu menu) {
        String passageText = EmbeddingTextBuilder.buildPassageText(
                menu.getName(),
                menu.getDescription(),
                menu.getCategory().getName(),
                menu.getPrice()
        );
        float[] vector = embedClient.embed(passageText, false);

        MenuEmbedding embedding = MenuEmbedding.builder()
                .menuId(menu.getMenuId())
                .embedding(vector)
                .embeddingSource(System.getenv().getOrDefault("EMBED_MODEL", "e5-base")) // 어떤 모델로 생성했는지 기록
                .build();
        menuEmbeddingRepository.save(embedding);
    }

    public void deleteEmbedding(Long menuId) {
        menuEmbeddingRepository.deleteById(menuId);
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
