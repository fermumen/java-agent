package dev.fxjava;

import java.nio.file.Path;
import java.util.Objects;

public final class AgentConfig {
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String reasoningEffort;
    private final Path workspace;
    private final int maxSteps;
    private final PermissionMode permissionMode;
    private final int contextTokenBudget;
    private final int contextTriggerPercent;
    private final int imageTokenReserve;

    public AgentConfig(String apiKey, String baseUrl, String model, Path workspace,
                       int maxSteps, PermissionMode permissionMode) {
        this(apiKey, baseUrl, model, workspace, maxSteps, permissionMode,
                ContextBudget.DEFAULT_REQUEST_TOKEN_BUDGET, ContextBudget.DEFAULT_TRIGGER_PERCENT,
                ContextBudget.DEFAULT_IMAGE_TOKEN_RESERVE, null);
    }

    public AgentConfig(String apiKey, String baseUrl, String model, Path workspace,
                       int maxSteps, PermissionMode permissionMode, String reasoningEffort) {
        this(apiKey, baseUrl, model, workspace, maxSteps, permissionMode,
                ContextBudget.DEFAULT_REQUEST_TOKEN_BUDGET, ContextBudget.DEFAULT_TRIGGER_PERCENT,
                ContextBudget.DEFAULT_IMAGE_TOKEN_RESERVE, reasoningEffort);
    }

    public AgentConfig(String apiKey, String baseUrl, String model, Path workspace,
                       int maxSteps, PermissionMode permissionMode,
                       int contextTokenBudget, int contextTriggerPercent, int imageTokenReserve) {
        this(apiKey, baseUrl, model, workspace, maxSteps, permissionMode,
                contextTokenBudget, contextTriggerPercent, imageTokenReserve, null);
    }

    public AgentConfig(String apiKey, String baseUrl, String model, Path workspace,
                       int maxSteps, PermissionMode permissionMode,
                       int contextTokenBudget, int contextTriggerPercent, int imageTokenReserve,
                       String reasoningEffort) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("An API key is required");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("A base URL is required");
        }
        ModelSelection selection = new ModelSelection(model, reasoningEffort);
        workspace = workspace.toAbsolutePath().normalize();
        if (maxSteps < 0) {
            throw new IllegalArgumentException("maxSteps must be 0 (unlimited) or positive");
        }
        if (permissionMode == null) throw new IllegalArgumentException("permissionMode is required");
        ContextBudget contextBudget = new ContextBudget(contextTokenBudget, contextTriggerPercent, imageTokenReserve);
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = selection.model();
        this.reasoningEffort = selection.reasoningEffort();
        this.workspace = workspace;
        this.maxSteps = maxSteps;
        this.permissionMode = permissionMode;
        this.contextTokenBudget = contextBudget.requestTokenBudget();
        this.contextTriggerPercent = contextBudget.triggerPercent();
        this.imageTokenReserve = contextBudget.imageTokenReserve();
    }

    public String apiKey() { return apiKey; }
    public String baseUrl() { return baseUrl; }
    public String model() { return model; }
    public String reasoningEffort() { return reasoningEffort; }
    static java.util.List<String> reasoningEffortValues() { return ModelSelection.reasoningEffortValues(); }
    public Path workspace() { return workspace; }
    public int maxSteps() { return maxSteps; }
    public PermissionMode permissionMode() { return permissionMode; }
    public ContextBudget contextBudget() {
        return new ContextBudget(contextTokenBudget, contextTriggerPercent, imageTokenReserve);
    }
    public boolean approveAll() { return permissionMode == PermissionMode.YOLO; }

    public AgentConfig withModel(String replacement) {
        return new AgentConfig(apiKey, baseUrl, replacement, workspace, maxSteps, permissionMode,
                contextTokenBudget, contextTriggerPercent, imageTokenReserve, reasoningEffort);
    }

    public AgentConfig withReasoningEffort(String replacement) {
        return new AgentConfig(apiKey, baseUrl, model, workspace, maxSteps, permissionMode,
                contextTokenBudget, contextTriggerPercent, imageTokenReserve, replacement);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AgentConfig)) return false;
        AgentConfig that = (AgentConfig) other;
        return maxSteps == that.maxSteps
                && contextTokenBudget == that.contextTokenBudget
                && contextTriggerPercent == that.contextTriggerPercent
                && imageTokenReserve == that.imageTokenReserve
                && Objects.equals(reasoningEffort, that.reasoningEffort)
                && Objects.equals(apiKey, that.apiKey)
                && Objects.equals(baseUrl, that.baseUrl)
                && Objects.equals(model, that.model)
                && Objects.equals(workspace, that.workspace)
                && permissionMode == that.permissionMode;
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(apiKey);
        result = 31 * result + Objects.hashCode(baseUrl);
        result = 31 * result + Objects.hashCode(model);
        result = 31 * result + Objects.hashCode(reasoningEffort);
        result = 31 * result + Objects.hashCode(workspace);
        result = 31 * result + Integer.hashCode(maxSteps);
        result = 31 * result + Objects.hashCode(permissionMode);
        result = 31 * result + Integer.hashCode(contextTokenBudget);
        result = 31 * result + Integer.hashCode(contextTriggerPercent);
        result = 31 * result + Integer.hashCode(imageTokenReserve);
        return result;
    }

    @Override
    public String toString() {
        return "AgentConfig[apiKey=" + apiKey + ", baseUrl=" + baseUrl + ", model=" + model
                + ", workspace=" + workspace + ", maxSteps=" + maxSteps
                + ", permissionMode=" + permissionMode + ", contextTokenBudget=" + contextTokenBudget
                + ", contextTriggerPercent=" + contextTriggerPercent
                + ", imageTokenReserve=" + imageTokenReserve
                + ", reasoningEffort=" + reasoningEffort + "]";
    }
}

enum PermissionMode {
    ASK, AUTO, YOLO;

    static PermissionMode parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Permission mode must be ask, auto, or yolo");
        }
    }
}
