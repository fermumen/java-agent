package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Objects;

/** Shared bounds for untrusted MCP schemas and tool results. */
final class McpValidation {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 20_000;
    private static final int MAX_CONTENT_ITEMS = 256;

    private static final class Entry {
        private final JsonNode node;
        private final int depth;

        private Entry(JsonNode node, int depth) {
            this.node = node;
            this.depth = depth;
        }

        public JsonNode node() { return node; }
        public int depth() { return depth; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            Entry that = (Entry) other;
            return depth == that.depth && Objects.equals(node, that.node);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(node) + Integer.hashCode(depth);
        }

        @Override
        public String toString() {
            return "Entry[node=" + node + ", depth=" + depth + "]";
        }
    }

    private McpValidation() { }

    static void schema(JsonNode schema) throws IOException {
        walk(schema, true);
    }

    static void json(JsonNode value) throws IOException {
        walk(value, false);
    }

    static void toolResult(JsonNode result) throws IOException {
        if (!result.isObject()) throw new IOException("MCP tool result must be an object");
        JsonNode content = result.get("content");
        if (content == null || !content.isArray() || content.size() > MAX_CONTENT_ITEMS) {
            throw new IOException("MCP tool result has invalid content");
        }
        JsonNode isError = result.get("isError");
        if (isError != null && !isError.isBoolean()) throw new IOException("MCP isError must be boolean");
        for (JsonNode item : content) validateContent(item);
        if (result.has("structuredContent")) walk(result.get("structuredContent"), false);
    }

    private static void validateContent(JsonNode item) throws IOException {
        if (!item.isObject()) throw new IOException("MCP content item must be an object");
        String type = item.path("type").asText();
        switch (type) {
            case "text":
                requireText(item, "text");
                break;
            case "image":
            case "audio": {
                String data = requireText(item, "data");
                requireText(item, "mimeType");
                try {
                    Base64.getDecoder().decode(data);
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("MCP media content has invalid base64", invalid);
                }
                break;
            }
            case "resource_link":
                requireText(item, "uri");
                requireText(item, "name");
                break;
            case "resource":
                validateResource(item.path("resource"));
                break;
            default:
                throw new IOException("Unsupported MCP content type: " + type);
        }
        walk(item, false);
    }

    private static void validateResource(JsonNode resource) throws IOException {
        if (!resource.isObject()) throw new IOException("MCP embedded resource must be an object");
        requireText(resource, "uri");
        boolean text = resource.path("text").isTextual();
        boolean blob = resource.path("blob").isTextual();
        if (text == blob) throw new IOException("MCP resource must contain exactly one of text or blob");
        if (blob) {
            try {
                Base64.getDecoder().decode(resource.path("blob").asText());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("MCP resource blob has invalid base64", invalid);
            }
        }
    }

    private static String requireText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IOException("MCP content omitted " + field);
        return value.asText();
    }

    private static void walk(JsonNode root, boolean rejectExternalRefs) throws IOException {
        ArrayDeque<Entry> pending = new ArrayDeque<>();
        pending.push(new Entry(root, 0));
        int nodes = 0;
        while (!pending.isEmpty()) {
            Entry entry = pending.pop();
            if (entry.depth() > MAX_DEPTH || ++nodes > MAX_NODES) {
                throw new IOException("MCP JSON exceeds structural limits");
            }
            JsonNode node = entry.node();
            if (rejectExternalRefs && node.isObject() && node.has("$ref")) {
                JsonNode reference = node.get("$ref");
                if (!reference.isTextual() || !reference.asText().startsWith("#")) {
                    throw new IOException("MCP schemas may not use external references");
                }
            }
            if (node.isContainerNode()) {
                node.elements().forEachRemaining(child -> pending.push(new Entry(child, entry.depth() + 1)));
            }
        }
    }
}
