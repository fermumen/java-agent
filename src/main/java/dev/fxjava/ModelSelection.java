package dev.fxjava;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Active Responses model and optional reasoning effort for one agent. */
final class ModelSelection {
    private static final List<String> EFFORT_VALUES = List.of(
            "none", "minimal", "low", "medium", "high", "xhigh", "max");

    private final String model;
    private final String reasoningEffort;

    ModelSelection(String model, String reasoningEffort) {
        this.model = normalizeModel(model);
        this.reasoningEffort = normalizeReasoningEffort(reasoningEffort);
    }

    String model() { return model; }
    String reasoningEffort() { return reasoningEffort; }

    ModelSelection withModel(String replacement) {
        return new ModelSelection(replacement, reasoningEffort);
    }

    ModelSelection withReasoningEffort(String replacement) {
        return new ModelSelection(model, replacement);
    }

    static List<String> reasoningEffortValues() { return EFFORT_VALUES; }

    static String normalizeModel(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > 200) {
            throw new IllegalArgumentException("Model must contain between 1 and 200 characters");
        }
        return normalized;
    }

    static String normalizeReasoningEffort(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!EFFORT_VALUES.contains(normalized)) {
            throw new IllegalArgumentException("Reasoning effort must be one of: "
                    + String.join(", ", EFFORT_VALUES) + " (or default to omit it)");
        }
        return normalized;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ModelSelection)) return false;
        ModelSelection that = (ModelSelection) other;
        return Objects.equals(model, that.model)
                && Objects.equals(reasoningEffort, that.reasoningEffort);
    }

    @Override
    public int hashCode() { return Objects.hash(model, reasoningEffort); }

    @Override
    public String toString() {
        return "ModelSelection[model=" + model + ", reasoningEffort=" + reasoningEffort + "]";
    }
}
