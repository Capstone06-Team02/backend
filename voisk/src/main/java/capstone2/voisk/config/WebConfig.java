package capstone2.voisk.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
				.allowedOrigins(
					"https://voisk-frontend.vercel.app",
					"https://voisk.cloud",
					"https://www.voisk.cloud",
					"https://api.voisk.cloud",
					"http://localhost:3000"
				)
				.allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
				.maxAge(3600);
        registry.addMapping("/v3/api-docs/**").allowedOrigins("*");
        registry.addMapping("/swagger-ui/**").allowedOrigins("*");
    }
}
