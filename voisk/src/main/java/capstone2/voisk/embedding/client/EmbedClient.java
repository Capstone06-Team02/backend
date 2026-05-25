package capstone2.voisk.embedding.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

@Component
public class EmbedClient {

    private final RestTemplate restTemplate;
    private final String embedServerUrl;

    public EmbedClient(@Value("${embed.server.url:http://localhost:8000}") String embedServerUrl) {
        this.embedServerUrl = embedServerUrl;

        // 임베딩 서버 응답이 느릴 수 있어 connect/read 각 10초 설정
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(factory);
    }

    public float[] embed(String text, boolean isQuery) {
        try {
            Map<String, Object> body = Map.of("text", text, "is_query", isQuery);
            EmbedResponse response = restTemplate.postForObject(
                    embedServerUrl + "/embed",
                    body,
                    EmbedResponse.class
            );
            if (response == null || response.embedding() == null) {
                throw new RuntimeException("임베딩 서버 호출 실패");
            }
            return response.embedding();
        } catch (RuntimeException e) {
            throw e; // 위에서 직접 던진 RuntimeException은 그대로 전파
        } catch (Exception e) {
            throw new RuntimeException("임베딩 서버 호출 실패", e);
        }
    }

    private record EmbedResponse(float[] embedding) {}
}
