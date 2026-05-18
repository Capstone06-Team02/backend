package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "option_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class OptionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "option_item_id")
    private Long id;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "extra_price")
    private Integer extraPrice;

    @Column(name = "is_available")
    private Boolean isAvailable;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_option_group_id", nullable = false)
    private OptionGroup optionGroup;

    @OneToMany(mappedBy = "parentOptionItem")
    private List<OptionGroup> childOptionGroups = new ArrayList<>();
}