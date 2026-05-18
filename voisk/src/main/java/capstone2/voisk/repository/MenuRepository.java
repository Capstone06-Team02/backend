package capstone2.voisk.repository;

import capstone2.voisk.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    // category를 JOIN FETCH → 세션 밖에서 LazyInitializationException 없이 category.name 접근 가능
    @Query("SELECT m FROM Menu m JOIN FETCH m.category")
    List<Menu> findAllWithCategory();
}
