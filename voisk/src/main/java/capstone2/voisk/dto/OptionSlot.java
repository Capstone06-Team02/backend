package capstone2.voisk.dto;

import java.util.List;

public record OptionSlot(
        String name,
        Boolean required,
        String selectedOption,
        Boolean defaultSelected,
        List<OptionCandidate> candidates
) {

    public record OptionCandidate(
            String name,
            Integer extraPrice,
            Boolean defaultSelected,
            boolean selected
    ) {
    }
}
