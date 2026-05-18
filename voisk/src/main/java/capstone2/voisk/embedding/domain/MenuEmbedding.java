package capstone2.voisk.embedding.domain;

import capstone2.voisk.embedding.type.FloatArrayType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * PostgreSQL menu_embedding 테이블 엔티티.
 * menuId는 MySQL menu.menu_id와 동일한 값 → 두 DB 연결 키.
 */
@Entity
@Table(name = "menu_embedding")
@Getter
@NoArgsConstructor
public class MenuEmbedding {

    // MySQL menu PK와 동기화. auto-increment 없이 직접 지정.
    @Id
    @Column(name = "menu_id")
    private Long menuId;

    // Hibernate 미지원 타입 → FloatArrayType에 직렬화/역직렬화 위임
    @Type(FloatArrayType.class)
    @Column(columnDefinition = "vector(768)")
    private float[] embedding;

    @Column(name = "embedding_source")
    private String embeddingSource;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
