package dev.fxjava;

import java.nio.file.Path;

public record AgentConfig(
        String apiKey,
        String baseUrl,
        String model,
        Path workspace,
        int maxSteps,
        PermissionMode permissionMode) {

    public AgentConfig {
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
    }

    public boolean approveAll() { return permissionMode == PermissionMode.YOLO; }
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
