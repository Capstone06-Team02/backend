package capstone2.voisk.entity;

import jakarta.persistence.*;
import lombok.*;

/** RecommendHint ↔ Menu 다대다 관계의 중간 테이블. rank 속성을 저장하기 위해 엔티티로 분리 */
@Entity
@Table(name = "recommend_hint_menu")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RecommendHintMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recommend_hint_menu_id")
    private Long id;

    /** 힌트 선택 시 반환되는 메뉴 순서. 값이 작을수록 먼저 노출 */
    @Column(name = "menu_rank", nullable = false)
    private Integer rank;

    /** 이 매핑이 속한 추천 힌트 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommend_hint_id", nullable = false)
    private RecommendHint recommendHint;

    /** 힌트에 매핑된 메뉴 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;
}
