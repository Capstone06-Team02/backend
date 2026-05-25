package capstone2.voisk.embedding.util;

public class EmbeddingTextBuilder {

    public static String buildPassageText(String menuName, String description, String categoryName, int price) {
        String model = System.getenv().getOrDefault("EMBED_MODEL", "e5-base");
        boolean hasDescription = description != null && !description.isBlank();

        if (!hasDescription) {
            return String.format("%s (카테고리=%s / 가격=%d원)", menuName, categoryName, price);
        }

        return switch (model) {
            // e5 계열은 패시지 임베딩 시 "passage: " 프리픽스 필요
            case "e5-base" -> String.format("passage: %s — %s (카테고리=%s / 가격=%d원)",
                    menuName, description, categoryName, price);
            default -> String.format("%s — %s (카테고리=%s / 가격=%d원)",
                    menuName, description, categoryName, price);
        };
    }
}
