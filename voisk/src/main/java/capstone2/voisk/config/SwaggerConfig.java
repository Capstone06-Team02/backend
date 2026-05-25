package capstone2.voisk.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Voisk API")
                        .description("Voisk 백엔드 API 문서")
                        .version("v1.0.0"))
                .servers(List.of(
                        new Server().url("https://api.voisk.cloud").description("Production Server"),
                        new Server().url("http://localhost:8080").description("Local Server")
                ));
    }
}
