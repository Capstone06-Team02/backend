package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "order_session")
@Getter
@Setter
@NoArgsConstructor
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

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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

    @Transient
    private Long restaurantId;

    @Transient
    private Long menuId;

    @Builder.Default
    @Transient
    private Set<Long> selectedOptionItemIds = new LinkedHashSet<>();

    @Transient
    private String pendingOptionText;

    @Transient
    private Long pendingOptionalGroupId;

    @Builder.Default
    @Transient
    private Integer accumulatedTotalPrice = 0;

    @Transient
    private boolean currentItemFinalized;

    @Builder.Default
    @Transient
    private Deque<PendingMenuItem> pendingMenuItems = new ArrayDeque<>();

    public void reset() {
        resetCurrentItem();
        this.accumulatedTotalPrice = 0;
        this.pendingMenuItems = new ArrayDeque<>();
    }

    public void resetCurrentItem() {
        this.menu = null;
        this.quantity = null;
        this.menuId = null;
        this.selectedOptionItemIds = new LinkedHashSet<>();
        this.pendingOptionText = null;
        this.pendingOptionalGroupId = null;
        this.currentItemFinalized = false;
        this.status = OrderStatus.ORDERING;
    }

    public boolean isSlotsComplete() {
        return this.menu != null && this.quantity != null;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingMenuItem {
        private Long menuId;
        private String menu;
        private Integer quantity;
        private String pendingOptionText;
    }
}
