package capstone2.voisk.dto;

public record SlotExtractionResult(String intent, String menu, Integer quantity, String option) {

    public static SlotExtractionResult fallback() {
        return new SlotExtractionResult("UNKNOWN", null, null, null);
    }
}
