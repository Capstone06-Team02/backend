package capstone2.voisk.service;

import capstone2.voisk.dto.SlotExtractionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class LlmSlotFillerService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            너는 키오스크 주문 분석기야. 사용자의 한국어 음성 입력에서 아래 정보를 추출해서 JSON만 반환해. 설명 없이 JSON만.

            메뉴 종류: "일반 메뉴"(8,000원), "특식 메뉴"(12,000원) 두 가지만 존재해.

            반환 형식:
            {"intent": "...", "menu": "..." 또는 null, "quantity": 숫자 또는 null}

            intent 분류 기준:
            - ORDER: 메뉴 선택, 수량 언급, 주문 의사 표현 (예: 주세요, 줘, 먹을게, 드릴게요, 하나 주세요)
            - CONFIRM: 확인/동의 (예: 네, 응, 맞아요, 맞아, 확인, 그래요)
            - CANCEL: 취소/거부/재시작 (예: 아니요, 아니, 취소, 다시, 틀려요)
            - UNKNOWN: 위 세 가지 모두 해당 없음

            수량 추출 예시 (단위어 없어도 숫자 판단):
            "두 개" → 2, "둘" → 2, "2개" → 2, "하나" → 1, "한 개" → 1, "세 개" → 3, "열 개" → 10

            없는 정보는 null로 반환. 메뉴 이름은 반드시 "일반 메뉴" 또는 "특식 메뉴" 중 하나만 사용.
            """;

    private final RestClient openAiRestClient;

    @Value("${openai.model:gpt-4o-mini}")
    private String model;

    public LlmSlotFillerService(RestClient openAiRestClient) {
        this.openAiRestClient = openAiRestClient;
    }

    public SlotExtractionResult extract(String userInput) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", userInput)
                    )
            );

            String raw = openAiRestClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root    = MAPPER.readTree(raw);
            String content   = root.path("choices").get(0).path("message").path("content").asText();
            JsonNode parsed  = MAPPER.readTree(content);

            String  intent   = parsed.path("intent").asText("UNKNOWN");
            String  menu     = parsed.path("menu").isNull() ? null : parsed.path("menu").asText(null);
            Integer quantity = parsed.path("quantity").isNull() ? null : parsed.path("quantity").asInt();

            return new SlotExtractionResult(intent, menu, quantity);
        } catch (Exception e) {
            return SlotExtractionResult.fallback();
        }
    }
}
