package capstone2.voisk.dto;

import java.util.List;

public record CartOrderResponse(
        String cartId,
        boolean confirmed,
        List<String> menuNames
) {
}
