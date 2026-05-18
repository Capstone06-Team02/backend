package capstone2.voisk.repository;

import capstone2.voisk.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    List<Menu> findByStoreIdOrderByIdAsc(Long storeId);
}
