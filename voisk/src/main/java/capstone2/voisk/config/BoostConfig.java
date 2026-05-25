package capstone2.voisk.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// V3 부스트 가중치 — V2에서는 구조만 예약, 실제 적용은 RecommendService 주석 참고
@Component
@ConfigurationProperties(prefix = "boost")
@Getter
@Setter
public class BoostConfig {
    private double wCat = 0.10;
    private double wPrice = 0.05;
}
