package capstone2.voisk.repository;

import capstone2.voisk.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    // category를 JOIN FETCH → 세션 밖에서 LazyInitializationException 없이 category.name 접근 가능
    @Query("SELECT m FROM Menu m JOIN FETCH m.category")
    List<Menu> findAllWithCategory();

    @Query("""
            SELECT m
            FROM Menu m
            JOIN FETCH m.category
            WHERE m.menuId IN :menuIds
              AND m.storeId = :storeId
              AND m.isAvailable = true
              AND m.price IS NOT NULL
            """)
    List<Menu> findAvailableByMenuIdsAndStoreId(
            @Param("menuIds") List<Long> menuIds,
            @Param("storeId") Long storeId
    );

    // 최종 검증: 후보·매장·판매 상태·기본가
    @Query("""
            SELECT m
            FROM Menu m
            JOIN FETCH m.category
            WHERE m.menuId IN :menuIds
              AND m.storeId = :storeId
              AND m.isAvailable = true
              AND m.price IS NOT NULL
              AND (
                   :minPrice IS NULL
                   OR (:minInclusive = true AND m.price >= :minPrice)
                   OR (:minInclusive = false AND m.price > :minPrice)
              )
              AND (
                   :maxPrice IS NULL
                   OR (:maxInclusive = true AND m.price <= :maxPrice)
                   OR (:maxInclusive = false AND m.price < :maxPrice)
              )
            """)
    List<Menu> findValidRecommendationMenus(
            @Param("menuIds") List<Long> menuIds,
            @Param("storeId") Long storeId,
            @Param("minPrice") Integer minPrice,
            @Param("minInclusive") boolean minInclusive,
            @Param("maxPrice") Integer maxPrice,
            @Param("maxInclusive") boolean maxInclusive
    );

    List<Menu> findByStoreIdOrderByMenuIdAsc(Long storeId);

    @Query("SELECT m FROM Menu m JOIN FETCH m.category WHERE m.storeId = :storeId AND m.isSignature = true ORDER BY m.menuId ASC")
    List<Menu> findSignatureMenusByStoreIdWithCategory(@Param("storeId") Long storeId);

    // 룰베이스 추천: 판매중 메뉴를 category와 함께 로드 (이름/설명/카테고리 텍스트 매칭 + 응답용)
    @Query("SELECT m FROM Menu m JOIN FETCH m.category "
            + "WHERE m.storeId = :storeId AND m.isAvailable = true AND m.price IS NOT NULL")
    List<Menu> findAvailableByStoreIdWithCategory(@Param("storeId") Long storeId);
}
