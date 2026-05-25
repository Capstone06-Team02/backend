package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "order_session")
@Getter
@Setter // 추가: 서비스 계층에서 상태 변경을 위해 필요
@NoArgsConstructor // 접근 제어자를 public으로 변경 (혹은 서비스에서 생성 방식을 변경)
@AllArgsConstructor
@Builder
public class OrderSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "order_session_id")
    private String id;

    @Column(name = "total_price")
    private Integer totalPrice;

    @Column(name = "table_number", length = 20)
    private String tableNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private OrderStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @OneToMany(mappedBy = "orderSession")
    private List<OrderMenu> orderMenus;

    // --- 서비스 로직 처리를 위한 Transient(비영속) 필드 및 메서드 추가 ---
    
    @Transient
    private String menu;

    @Transient
    private Integer quantity;

    public void reset() {
        this.menu = null;
        this.quantity = null;
        this.status = OrderStatus.ORDERING;
    }

    public boolean isSlotsComplete() {
        return this.menu != null && this.quantity != null;
    }
}
