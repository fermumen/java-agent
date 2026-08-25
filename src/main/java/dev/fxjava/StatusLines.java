package dev.fxjava;

/**
 * Single dim hint line above the prompt: model, permission mode, and a short
 * session id, composed as a plain string so it redraws inside the normal
 * frame without flicker.
 */
final class StatusLines {
    private StatusLines() {
    }

    static String hint(String model, String mode, String sessionId, int columns, Ansi ansi) {
        String id = sessionId == null || sessionId.isBlank() ? "unsaved" : sessionId;
        if (id.length() > 8) id = id.substring(0, 8);
        String body = ToolGroupLines.truncate(model + " · " + mode + " · " + id, Math.max(1, columns));
        return ansi.dim() + body + ansi.reset();
    }
}
