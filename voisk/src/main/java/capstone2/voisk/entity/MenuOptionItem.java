package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "menu_option_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MenuOptionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_option_item_id")
    private Long id;

    @Column(name = "extra_price")
    private Integer extraPrice;

    @Column(name = "is_available")
    private Boolean isAvailable;

    @Column(name = "default_quantity")
    private Integer defaultQuantity;

    @Column(name = "max_quantity")
    private Integer maxQuantity;

    @Column(name = "is_default")
    private Boolean isDefault;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "legacy_option_item_id")
    private Long legacyOptionItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_option_group_id", nullable = false)
    private MenuOptionGroup menuOptionGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_item_template_id", nullable = false)
    private OptionItemTemplate optionItemTemplate;

    @OneToMany(mappedBy = "parentMenuOptionItem")
    private List<MenuOptionGroup> childOptionGroups = new ArrayList<>();
}
