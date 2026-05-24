package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "menu_option_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MenuOptionGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_option_group_id")
    private Long id;

    @Column(name = "is_required")
    private Boolean isRequired;

    @Column(name = "min_select")
    private Integer minSelect;

    @Column(name = "max_select")
    private Integer maxSelect;

    @Column(name = "is_available")
    private Boolean isAvailable;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "legacy_option_group_id")
    private Long legacyOptionGroupId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_group_template_id", nullable = false)
    private OptionGroupTemplate optionGroupTemplate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_menu_option_item_id")
    private MenuOptionItem parentMenuOptionItem;

    @OneToMany(mappedBy = "menuOptionGroup")
    private List<MenuOptionItem> optionItems = new ArrayList<>();
}
