package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "option_item_template")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class OptionItemTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "option_item_template_id")
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Builder.Default
    @OneToMany(mappedBy = "optionItemTemplate")
    private List<OptionItemTemplateAlias> aliases = new ArrayList<>();
}
