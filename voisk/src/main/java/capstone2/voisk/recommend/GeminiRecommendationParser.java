package capstone2.voisk.recommend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Gemini가 반환한 내부 추천 JSON 계약을 엄격하게 읽는다. */
@Component
public class GeminiRecommendationParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ParsedDecision parse(String content, LlmRecommendResponse.TokenUsage usage) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Gemini recommendation content is empty.");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Gemini recommendation JSON is invalid.", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Gemini recommendation must be a JSON object.");
        }

        JsonNode constraintsNode = requiredObject(root, "constraints");
        Integer minPrice = nullableInteger(constraintsNode, "minPrice");
        Integer maxPrice = nullableInteger(constraintsNode, "maxPrice");
        boolean minInclusive = boundaryInclusive(constraintsNode, "minPriceInclusive", minPrice);
        boolean maxInclusive = boundaryInclusive(constraintsNode, "maxPriceInclusive", maxPrice);

        JsonNode rankedIdsNode = root.get("rankedMenuIds");
        if (rankedIdsNode == null || !rankedIdsNode.isArray()) {
            throw new IllegalArgumentException("rankedMenuIds must be an array.");
        }
        List<Long> rankedMenuIds = new ArrayList<>();
        for (JsonNode idNode : rankedIdsNode) {
            if (!idNode.isIntegralNumber() || !idNode.canConvertToLong()) {
                throw new IllegalArgumentException("rankedMenuIds must contain integers only.");
            }
            rankedMenuIds.add(idNode.longValue());
        }

        RecommendationConstraints constraints = new RecommendationConstraints(
                minPrice,
                minInclusive,
                maxPrice,
                maxInclusive
        );
        return new ParsedDecision(
                constraints,
                List.copyOf(rankedMenuIds),
                usage == null ? LlmRecommendResponse.TokenUsage.zero() : usage
        );
    }

    private JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object.");
        }
        return value;
    }

    private Integer nullableInteger(JsonNode parent, String field) {
        if (!parent.has(field)) {
            throw new IllegalArgumentException(field + " is required.");
        }
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer or null.");
        }
        return value.intValue();
    }

    private boolean requiredBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private boolean boundaryInclusive(JsonNode parent, String field, Integer boundary) {
        JsonNode value = parent.get(field);
        if (boundary == null && (value == null || value.isNull())) {
            return true;
        }
        return requiredBoolean(parent, field);
    }

    public record ParsedDecision(
            RecommendationConstraints constraints,
            List<Long> rankedMenuIds,
            LlmRecommendResponse.TokenUsage usage
    ) {
    }
}
