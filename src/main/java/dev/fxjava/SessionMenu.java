package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Recent-session picker: keyboard selection and safe, one-line conversation previews. */
final class SessionMenu {
    private List<SessionStore.Snapshot> sessions = List.of();
    private int selected;
    private List<String> descriptions = List.of();

    void open(List<SessionStore.Snapshot> sessions) {
        this.sessions = List.copyOf(sessions);
        selected = 0;
        List<String> descriptions = new ArrayList<>();
        for (SessionStore.Snapshot saved : sessions) {
            String preview = preview(saved);
            descriptions.add(saved.title().isBlank() ? preview : safeLine(saved.title()) + " · " + preview);
        }
        this.descriptions = List.copyOf(descriptions);
    }

    void close() {
        sessions = List.of();
        descriptions = List.of();
        selected = 0;
    }

    boolean active() {
        return !sessions.isEmpty();
    }

    void move(int delta) {
        if (active()) selected = Math.floorMod(selected + delta, sessions.size());
    }

    SessionStore.Snapshot selected() {
        return active() ? sessions.get(selected) : null;
    }

    List<String> rows(int columns, int maxRows, String currentId, Ansi ansi) {
        if (!active()) return List.of();
        int count = Math.min(sessions.size(), Math.max(1, maxRows));
        int start = Math.min(Math.max(0, selected - count + 1), sessions.size() - count);
        List<String> rows = new ArrayList<>();
        rows.add(ansi.muted() + ToolGroupLines.truncate("  Resume " + (selected + 1) + "/"
                + sessions.size() + " · ↑/↓ select · Enter resume · Esc cancel", Math.max(1, columns - 1))
                + ansi.reset());
        for (int index = start; index < start + count; index++) {
            SessionStore.Snapshot saved = sessions.get(index);
            String id = saved.id();
            if (id.length() > 12) id = id.substring(id.length() - 12);
            String label = "  " + (index == selected ? "› " : "  ")
                    + (saved.id().equals(currentId) ? "* " : "  ") + id + " ";
            String row = ToolGroupLines.truncate(label + descriptions.get(index), Math.max(1, columns - 1));
            rows.add((index == selected ? ansi.bold() : ansi.muted()) + row + ansi.reset());
        }
        return rows;
    }

    static String preview(SessionStore.Snapshot saved) {
        for (JsonNode item : saved.input()) {
            if (!"user".equals(item.path("role").asText())) continue;
            String text = contentText(item.path("content"));
            if (!text.isBlank()) return safeLine(text);
        }
        for (JsonNode item : saved.input()) {
            if (!"message".equals(item.path("type").asText())
                    && !"compacted_summary".equals(item.path("type").asText())) continue;
            String text = contentText(item.path("content"));
            if (!text.isBlank()) return safeLine(text);
        }
        return "(empty conversation)";
    }

    private static String contentText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                if (part.path("text").isTextual()) text.append(part.path("text").asText()).append(' ');
            }
            return text.toString();
        }
        return "";
    }

    private static String safeLine(String text) {
        return ToolPreview.safeText(text.strip().replaceAll("\\s+", " "));
    }
}
