package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "order_menu_option")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class OrderMenuOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_menu_option_id")
    private Long id;

    @Column(name = "quantity")
    private Integer quantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_menu id", nullable = false)
    private OrderMenu orderMenu;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item id", nullable = false)
    private OptionItem optionItem;
}