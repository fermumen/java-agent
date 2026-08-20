package dev.fxjava;

import java.nio.file.Path;
import java.util.Objects;

public final class AgentConfig {
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Path workspace;
    private final int maxSteps;
    private final PermissionMode permissionMode;

    public AgentConfig(String apiKey, String baseUrl, String model, Path workspace,
                       int maxSteps, PermissionMode permissionMode) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("An API key is required");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("A base URL is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("A model is required");
        }
        workspace = workspace.toAbsolutePath().normalize();
        if (maxSteps < 1 || maxSteps > 100) {
            throw new IllegalArgumentException("maxSteps must be between 1 and 100");
        }
        if (permissionMode == null) throw new IllegalArgumentException("permissionMode is required");
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.workspace = workspace;
        this.maxSteps = maxSteps;
        this.permissionMode = permissionMode;
    }

    public String apiKey() { return apiKey; }
    public String baseUrl() { return baseUrl; }
    public String model() { return model; }
    public Path workspace() { return workspace; }
    public int maxSteps() { return maxSteps; }
    public PermissionMode permissionMode() { return permissionMode; }
    public boolean approveAll() { return permissionMode == PermissionMode.YOLO; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AgentConfig)) return false;
        AgentConfig that = (AgentConfig) other;
        return maxSteps == that.maxSteps
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
        result = 31 * result + Objects.hashCode(workspace);
        result = 31 * result + Integer.hashCode(maxSteps);
        result = 31 * result + Objects.hashCode(permissionMode);
        return result;
    }

    @Override
    public String toString() {
        return "AgentConfig[apiKey=" + apiKey + ", baseUrl=" + baseUrl + ", model=" + model
                + ", workspace=" + workspace + ", maxSteps=" + maxSteps
                + ", permissionMode=" + permissionMode + "]";
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
