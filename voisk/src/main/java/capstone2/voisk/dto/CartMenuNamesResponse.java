package capstone2.voisk.dto;

import java.util.List;

public record CartMenuNamesResponse(
        String cartId,
        List<String> menuNames,
        List<CartMenuItem> items
) {

    public record CartMenuItem(
            String sessionId,
            String menuName
    ) {
    }
}
