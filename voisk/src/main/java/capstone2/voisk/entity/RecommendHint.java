package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "recommend_hint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RecommendHint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recommend_hint_id")
    private Long id;

    /** 사용자에게 노출되는 추천 힌트 문구 (예: "달달한 거", "커피 아닌 거") */
    @Column(name = "label", length = 100, nullable = false)
    private String label;

    /** 힌트 버튼 표시 순서. 값이 작을수록 앞에 노출되며, null이면 가장 뒤에 위치 */
    @Column(name = "sort_order")
    private Integer sortOrder;

    /** 힌트 노출 여부. false이면 클라이언트에 반환하지 않음 */
    @Column(name = "is_available")
    private Boolean isAvailable;

    /** 이 힌트가 속한 매장 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    /** 이 힌트에 매핑된 메뉴 목록 (rank 오름차순으로 반환) */
    @Builder.Default
    @OneToMany(mappedBy = "recommendHint", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RecommendHintMenu> menus = new ArrayList<>();
}
