package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_session")
@Getter
@Setter
@NoArgsConstructor
public class OrderSession {

    @Id
    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "menu")
    private String menu;

    @Column(name = "quantity")
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false)
    private Phase phase;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum Phase {
        ORDERING, CONFIRMING, DONE
    }

    public OrderSession(String sessionId) {
        this.sessionId = sessionId;
        this.phase = Phase.ORDERING;
    }

    @Transient
    public boolean isSlotsComplete() {
        return menu != null && quantity != null;
    }

    public void reset() {
        this.menu     = null;
        this.quantity = null;
        this.phase    = Phase.ORDERING;
    }
}
