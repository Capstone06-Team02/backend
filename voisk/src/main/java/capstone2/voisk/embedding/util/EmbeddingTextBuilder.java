package capstone2.voisk.embedding.util;

public class EmbeddingTextBuilder {

    /** 옵션 없는 시그니처 — 옵션을 포함하지 않는 패시지. */
    public static String buildPassageText(String menuName, String description, String categoryName, int price) {
        return buildPassageText(menuName, description, categoryName, price, null);
    }

    /**
     * 옵션 그룹/항목명을 덧붙인 패시지.
     *
     * <p>optionText 예: {@code "온도:핫·아이스, 사이즈:S·M·L"} (extraPrice·중복 제외, 항목명만).
     * null/blank이면 옵션을 생략한 포맷과 동일하게 동작한다.
     */
    public static String buildPassageText(String menuName, String description, String categoryName, int price,
                                          String optionText) {
        String model = System.getenv().getOrDefault("EMBED_MODEL", "e5-base");
        boolean hasDescription = description != null && !description.isBlank();
        boolean hasOptions = optionText != null && !optionText.isBlank();

        String optionSuffix = hasOptions ? " / 옵션=" + optionText : "";

        if (!hasDescription) {
            return String.format("%s (카테고리=%s / 가격=%d원%s)", menuName, categoryName, price, optionSuffix);
        }

        return switch (model) {
            // e5 계열은 패시지 임베딩 시 "passage: " 프리픽스 필요
            case "e5-base" -> String.format("passage: %s — %s (카테고리=%s / 가격=%d원%s)",
                    menuName, description, categoryName, price, optionSuffix);
            default -> String.format("%s — %s (카테고리=%s / 가격=%d원%s)",
                    menuName, description, categoryName, price, optionSuffix);
        };
    }
}
