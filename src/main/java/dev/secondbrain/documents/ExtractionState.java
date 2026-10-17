package dev.secondbrain.documents;

public enum ExtractionState {
    QUEUED,
    PROCESSING,
    EXTRACTED,
    FAILED;

    ExtractionState displayedWith(String deliveryState) {
        if (this != EXTRACTED && deliveryState.equals("FAILED")) return FAILED;
        boolean active = deliveryState.equals("PENDING") || deliveryState.equals("RUNNING");
        if (active && (this == FAILED || deliveryState.equals("RUNNING"))) return PROCESSING;
        return this;
    }
}
