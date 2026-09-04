package capstone2.voisk.dto;

import java.util.List;

public record CartMenuNamesResponse(
        String cartId,
        List<String> menuNames
) {
}
