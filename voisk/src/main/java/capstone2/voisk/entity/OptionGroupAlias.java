package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "option_group_alias")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class OptionGroupAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "option_group_alias_id")
    private Long id;

    @Column(name = "alias", length = 100, nullable = false)
    private String alias;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_group_id", nullable = false)
    private OptionGroup optionGroup;
}
