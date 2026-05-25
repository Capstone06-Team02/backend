package capstone2.voisk.embedding.repository;

import capstone2.voisk.embedding.domain.MenuEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface MenuEmbeddingRepository extends JpaRepository<MenuEmbedding, Long> {

    // 초기화 시 중복 스킵용 — 전체 행 대신 ID만 조회
    @Query("SELECT m.menuId FROM MenuEmbedding m")
    Set<Long> findAllMenuIds();

    // <=> : pgvector 코사인 거리. 1 - 거리 = 유사도(높을수록 가까움).
    // queryVec: "[0.023,-0.041,...]" 형태 문자열 → CAST로 vector 변환
    // 반환: [menu_id, similarity] 쌍, 상위 k개
    @Query(value =
            "SELECT menu_id, 1 - (embedding <=> CAST(:queryVec AS vector)) AS similarity " +
            "FROM menu_embedding " +
            "ORDER BY embedding <=> CAST(:queryVec AS vector) " +
            "LIMIT :k",
            nativeQuery = true)
    List<Object[]> findTopKBySimilarity(
            @Param("queryVec") String queryVec,
            @Param("k") int k
    );
}
