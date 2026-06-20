package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "menu")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Menu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_id")
    private Long menuId;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "price")
    private Integer price;

    @Column(name = "description")
    private String description;

    @Column(name = "is_available")
    private Boolean isAvailable;

    // read-only 스칼라 — store @ManyToOne이 column 소유
    @Column(name = "store_id", insertable = false, updatable = false)
    private Long storeId;

    // read-only 스칼라 — category @ManyToOne이 column 소유
    @Column(name = "category_id", insertable = false, updatable = false)
    private Long categoryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Builder.Default
    @OneToMany(mappedBy = "menu")
    private List<MenuAlias> aliases = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "menu")
    private List<OptionGroup> optionGroups = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "menu")
    private List<MenuOptionGroup> menuOptionGroups = new ArrayList<>();
}
