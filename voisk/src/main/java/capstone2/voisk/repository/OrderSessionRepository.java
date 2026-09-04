package capstone2.voisk.repository;

import capstone2.voisk.entity.OrderSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderSessionRepository extends JpaRepository<OrderSession, Long> {

    void deleteByUpdatedAtBefore(LocalDateTime threshold);

    @Query("""
            SELECT m.name
            FROM OrderSession s
            JOIN s.orderMenus om
            JOIN om.menu m
            WHERE s.cartId = :cartId
            ORDER BY s.createdAt ASC, om.id ASC
            """)
    List<String> findMenuNamesByCartId(@Param("cartId") String cartId);
}
