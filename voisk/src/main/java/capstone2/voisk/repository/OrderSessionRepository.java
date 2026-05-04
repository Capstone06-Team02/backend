package capstone2.voisk.repository;

import capstone2.voisk.entity.OrderSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface OrderSessionRepository extends JpaRepository<OrderSession, String> {

    void deleteByUpdatedAtBefore(LocalDateTime threshold);
}
