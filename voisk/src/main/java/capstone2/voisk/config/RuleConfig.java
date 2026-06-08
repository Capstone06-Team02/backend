package capstone2.voisk.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 룰베이스 추천 가중치 설정. application.yaml 의 {@code rule.*} 로 조정 가능
 * (발표/실험 시 재배포 없이 튜닝하기 위함). 감각 형용사 규칙별 weight 는 {@link capstone2.voisk.recommend.RuleSet} 에 고정.
 */
@Component
@ConfigurationProperties(prefix = "rule")
@Getter
@Setter
public class RuleConfig {

    /** 최종 반환 개수 (임베딩 추천과 동일하게 5). */
    private int limit = 5;

    /** 발화에 매장 카테고리명이 명시적으로 등장했을 때(긍정 매칭) 부여하는 가산점. */
    private double categoryMatchWeight = 1.0;

    /** 가격 의도(저렴/프리미엄)가 가격대와 맞을 때 부여하는 가산점. */
    private double priceMatchWeight = 1.0;

    /** '저렴함' 판단: 매장 가격 분포에서 하위 비율 지점(0.4 = 하위 40%) 이하를 저렴으로 본다. */
    private double cheapThresholdRatio = 0.4;

    /** '프리미엄' 판단: 매장 가격 분포에서 상위 비율 지점(0.7 = 상위 30%) 이상을 프리미엄으로 본다. */
    private double premiumThresholdRatio = 0.7;
}
