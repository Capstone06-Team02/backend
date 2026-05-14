package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "order_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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
    @JoinColumn(name = "store id", nullable = false)
    private Store store;

    @OneToMany(mappedBy = "orderSession")
    private List<OrderMenu> orderMenus;
}
