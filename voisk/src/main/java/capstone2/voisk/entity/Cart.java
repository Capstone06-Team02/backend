package capstone2.voisk.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "cart")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cart {

    @Id
    @Column(name = "cart_id", length = 36)
    private String id;

    @Builder.Default
    @Column(name = "is_confirmed", nullable = false)
    private Boolean confirmed = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (confirmed == null) {
            confirmed = false;
        }
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        if (confirmed == null) {
            confirmed = false;
        }
        updatedAt = LocalDateTime.now();
    }

    public boolean isConfirmed() {
        return Boolean.TRUE.equals(confirmed);
    }

    public void confirm() {
        confirmed = true;
        updatedAt = LocalDateTime.now();
    }
}
