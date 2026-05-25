package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "option_default_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class OptionDefaultRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "condition_item_id", nullable = false)
    private OptionItem conditionItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_item_id", nullable = false)
    private OptionItem targetItem;

    @Column(name = "default_quantity", nullable = false)
    private Integer defaultQuantity;
}