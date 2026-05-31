package capstone2.voisk.repository;

import capstone2.voisk.entity.MenuOptionGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MenuOptionGroupRepository extends JpaRepository<MenuOptionGroup, Long> {

    @Query("""
            SELECT DISTINCT g
            FROM MenuOptionGroup g
            JOIN FETCH g.optionGroupTemplate
            LEFT JOIN FETCH g.optionItems i
            LEFT JOIN FETCH i.optionItemTemplate
            WHERE g.menu.menuId = :menuId
              AND (g.isRequired IS NULL OR g.isRequired = false)
              AND (g.isAvailable IS NULL OR g.isAvailable = true)
              AND g.parentMenuOptionItem IS NULL
            ORDER BY g.sortOrder ASC, g.id ASC, i.sortOrder ASC, i.id ASC
            """)
    List<MenuOptionGroup> findTopLevelOptionalGroupsByMenuId(@Param("menuId") Long menuId);

    @Query("""
            SELECT DISTINCT g
            FROM MenuOptionGroup g
            JOIN FETCH g.menu
            JOIN FETCH g.optionGroupTemplate
            LEFT JOIN FETCH g.optionItems i
            LEFT JOIN FETCH i.optionItemTemplate
            WHERE g.id = :optionGroupId
            ORDER BY i.sortOrder ASC, i.id ASC
            """)
    Optional<MenuOptionGroup> findByIdWithItems(@Param("optionGroupId") Long optionGroupId);

    @Query("""
            SELECT DISTINCT g
            FROM MenuOptionGroup g
            JOIN FETCH g.optionGroupTemplate
            LEFT JOIN FETCH g.optionItems i
            LEFT JOIN FETCH i.optionItemTemplate
            WHERE g.menu.menuId = :menuId
              AND g.isRequired = true
              AND (g.isAvailable IS NULL OR g.isAvailable = true)
            ORDER BY g.sortOrder ASC, g.id ASC, i.sortOrder ASC, i.id ASC
            """)
    List<MenuOptionGroup> findRequiredGroupsByMenuId(@Param("menuId") Long menuId);
}
