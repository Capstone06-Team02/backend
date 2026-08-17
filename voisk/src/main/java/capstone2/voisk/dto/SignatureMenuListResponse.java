package capstone2.voisk.dto;

import java.util.List;

public record SignatureMenuListResponse(
        Long storeId,
        String storeName,
        int menuCount,
        List<SignatureMenuInfo> menus
) {

    public record SignatureMenuInfo(
            Long menuId,
            String name,
            Integer price,
            String description,
            Boolean isAvailable,
            Boolean isSignature,
            CategoryInfo category
    ) {
    }

    public record CategoryInfo(
            Long categoryId,
            String name,
            Integer depth
    ) {
    }
}
