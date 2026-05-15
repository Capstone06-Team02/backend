package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "order_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_session_id")
    private Long id;

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
    @JoinColumn(name = "store id") // 임시로 nullable 허용 또는 서비스 로직 수정 필요
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
