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

    // pgvector 검색 결과 ID 목록을 storeId로 필터링 후 category 한 번에 로드
    @Query("SELECT m FROM Menu m JOIN FETCH m.category WHERE m.menuId IN :menuIds AND m.storeId = :storeId")
    List<Menu> findByMenuIdsAndStoreId(@Param("menuIds") List<Long> menuIds, @Param("storeId") Long storeId);

	List<Menu> findByStoreIdOrderByMenuIdAsc(Long storeId);

    // 룰베이스 추천: 판매중 메뉴를 category와 함께 로드 (이름/설명/카테고리 텍스트 매칭 + 응답용)
    @Query("SELECT m FROM Menu m JOIN FETCH m.category WHERE m.storeId = :storeId AND m.isAvailable = true")
    List<Menu> findAvailableByStoreIdWithCategory(@Param("storeId") Long storeId);
}
