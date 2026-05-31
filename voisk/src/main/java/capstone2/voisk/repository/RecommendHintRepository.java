package capstone2.voisk.repository;

import capstone2.voisk.entity.RecommendHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RecommendHintRepository extends JpaRepository<RecommendHint, Long> {

    @Query("""
            SELECT h FROM RecommendHint h
            JOIN FETCH h.menus hm
            JOIN FETCH hm.menu m
            WHERE h.store.id = :storeId
              AND (h.isAvailable IS NULL OR h.isAvailable = true)
            ORDER BY h.sortOrder ASC, h.id ASC, hm.rank ASC
            """)
    List<RecommendHint> findByStoreIdWithMenus(@Param("storeId") Long storeId);

    @Query("""
            SELECT h FROM RecommendHint h
            JOIN FETCH h.menus hm
            JOIN FETCH hm.menu m
            WHERE h.id = :hintId
            """)
    Optional<RecommendHint> findByIdWithMenus(@Param("hintId") Long hintId);
}
