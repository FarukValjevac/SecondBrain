package dev.secondbrain.documents;

public enum ExtractionState {
    QUEUED("Queued"),
    PROCESSING("Reading text"),
    EXTRACTED("Text ready"),
    FAILED("Failed");

    private final String label;

    ExtractionState(String label) {
        this.label = label;
    }

    /** Short wording for pages; the enum name stays the stored and API value. */
    public String getLabel() {
        return label;
    }

    ExtractionState displayedWith(String deliveryState) {
        if (this != EXTRACTED && deliveryState.equals("FAILED")) return FAILED;
        boolean active = deliveryState.equals("PENDING") || deliveryState.equals("RUNNING");
        if (active && (this == FAILED || deliveryState.equals("RUNNING"))) return PROCESSING;
        return this;
    }
}
