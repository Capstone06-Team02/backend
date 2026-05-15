package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "order_menu")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class OrderMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_menu_id")
    private Long id;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "price_with_option")
    private Integer priceWithOption;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_session id", nullable = false)
    private OrderSession orderSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu id", nullable = false)
    private Menu menu;

    @OneToMany(mappedBy = "orderMenu")
    private List<OrderMenuOption> orderMenuOptions;
}