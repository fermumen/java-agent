package dev.fxjava;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Persistent exact-match permission rules bound to one saved session, ported
 * from fx's session permission state. Each rule carries a stable numeric id, an
 * allow/deny decision, a tool name, and a canonical key derived from the tool's
 * arguments JSON. Matching is exact on tool plus key; re-remembering an
 * existing identity replaces its decision while keeping the stable id,
 * mirroring fx's rule replacement.
 */
final class SessionRules {
    /** fx caps saved-session rule state at this many entries. */
    static final int MAX_RULES = 1024;
    /** fx bounds a rule identity at this many canonical bytes. */
    static final int MAX_IDENTITY_BYTES = 4096;
    static final int MAX_TOOL_BYTES = 256;
    static final int MAX_RAW_ARGUMENT_BYTES = 4096;
    static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    enum Kind {
        ALLOW, DENY;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind parse(String value) {
            if ("allow".equalsIgnoreCase(value)) return ALLOW;
            if ("deny".equalsIgnoreCase(value)) return DENY;
            return null;
        }
    }

    enum Decision {
        ALLOW, DENY, UNRESOLVED
    }

    static final class Rule {
        final String id;
        final Kind kind;
        final String tool;
        final String arguments;

        Rule(String id, Kind kind, String tool, String arguments) {
            this.id = id;
            this.kind = kind;
            this.tool = tool;
            this.arguments = arguments;
        }

        boolean matches(String toolName, String key) {
            return tool.equals(toolName) && arguments.equals(key);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Rule)) return false;
            Rule that = (Rule) other;
            return id.equals(that.id) && kind == that.kind
                    && tool.equals(that.tool) && arguments.equals(that.arguments);
        }

        @Override
        public int hashCode() {
            int result = id.hashCode();
            result = 31 * result + kind.hashCode();
            result = 31 * result + tool.hashCode();
            return 31 * result + arguments.hashCode();
        }

        @Override
        public String toString() {
            return "Rule[id=" + id + ", kind=" + kind.label() + ", tool=" + tool
                    + ", arguments=" + arguments + "]";
        }
    }

    private final List<Rule> rules = new ArrayList<>();
    private long nextId = 1;
    private long mutationGeneration;

    synchronized Decision decide(String tool, String key) {
        for (Rule rule : rules) {
            if (!rule.matches(tool, key)) continue;
            return rule.kind == Kind.DENY ? Decision.DENY : Decision.ALLOW;
        }
        return Decision.UNRESOLVED;
    }

    /**
     * Stores one exact rule and returns its stable id, or null when the store
     * is full. Remembering an identity that already has a rule flips that
     * rule's decision and keeps its original id.
     */
    synchronized String remember(Kind kind, String tool, String key) {
        if (kind == null || !validIdentity(tool, key)) return null;
        for (int index = 0; index < rules.size(); index++) {
            Rule existing = rules.get(index);
            if (existing.tool.equals(tool) && existing.arguments.equals(key)) {
                requireMutationGeneration();
                rules.set(index, new Rule(existing.id, kind, existing.tool, existing.arguments));
                mutationGeneration++;
                return existing.id;
            }
        }
        if (rules.size() >= MAX_RULES) return null;
        if (nextId == Long.MAX_VALUE) {
            throw new IllegalStateException("Permission rule ID counter exhausted");
        }
        requireMutationGeneration();
        String id = Long.toString(nextId++);
        rules.add(new Rule(id, kind, tool, key));
        mutationGeneration++;
        return id;
    }

    synchronized boolean revoke(String id) {
        for (Iterator<Rule> iterator = rules.iterator(); iterator.hasNext(); ) {
            if (iterator.next().id.equals(id)) {
                requireMutationGeneration();
                iterator.remove();
                mutationGeneration++;
                return true;
            }
        }
        return false;
    }

    /** Active rules sorted stably by numeric id ascending. */
    synchronized List<Rule> all() {
        List<Rule> copy = new ArrayList<>(rules);
        copy.sort((left, right) -> {
            long leftId = parseId(left.id);
            long rightId = parseId(right.id);
            if (leftId != rightId) return Long.compare(leftId, rightId);
            return left.id.compareTo(right.id);
        });
        return List.copyOf(copy);
    }

    synchronized int count() {
        return rules.size();
    }

    synchronized SessionRules copy() {
        SessionRules copy = new SessionRules();
        copy.rules.addAll(rules);
        copy.nextId = nextId;
        copy.mutationGeneration = mutationGeneration;
        return copy;
    }

    synchronized SessionRules denyOnlyCopy() {
        SessionRules copy = new SessionRules();
        for (Rule rule : rules) {
            if (rule.kind == Kind.DENY) copy.rules.add(rule);
        }
        copy.nextId = nextId;
        copy.mutationGeneration = mutationGeneration;
        return copy;
    }

    synchronized long generation() {
        return mutationGeneration;
    }

    synchronized long nextId() {
        return nextId;
    }

    synchronized ObjectNode encode(ObjectMapper mapper) {
        ObjectNode encoded = mapper.createObjectNode();
        encoded.put("schema_version", SCHEMA_VERSION);
        encoded.put("next_id", nextId);
        encoded.put("mutation_generation", mutationGeneration);
        ArrayNode entries = encoded.putArray("rules");
        for (Rule rule : all()) {
            ObjectNode entry = entries.addObject();
            entry.put("id", rule.id).put("kind", rule.kind.label())
                    .put("tool", rule.tool).put("arguments", rule.arguments);
        }
        return encoded;
    }

    /** Decodes persisted rule state; unreadable or invalid shapes are errors. */
    static SessionRules decode(JsonNode node) throws IOException {
        if (node == null || !node.isObject()) throw new IOException("Invalid permission rule state");
        JsonNode schema = node.get("schema_version");
        if (schema == null || !schema.isIntegralNumber() || !schema.canConvertToInt()
                || schema.intValue() != SCHEMA_VERSION) {
            throw new IOException("Unsupported permission rule schema");
        }
        JsonNode entries = node.path("rules");
        if (!entries.isArray()) throw new IOException("Invalid permission rule list");
        if (entries.size() > MAX_RULES) throw new IOException("Too many permission rules");
        SessionRules restored = new SessionRules();
        Set<String> ids = new HashSet<>();
        Set<String> identities = new HashSet<>();
        long highestId = 0;
        for (JsonNode entry : entries) {
            String id = text(entry, "id");
            String kindText = text(entry, "kind");
            Kind kind = Kind.parse(kindText);
            String tool = text(entry, "tool");
            String arguments = text(entry, "arguments");
            if (kind == null || !kindText.equals(kind.label()) || !validIdentity(tool, arguments)
                    || !isNumericId(id)) {
                throw new IOException("Invalid permission rule entry");
            }
            ObjectNode parsed = parseArguments(JSON, arguments);
            if (parsed == null || !arguments.equals(normalizeArguments(parsed))) {
                throw new IOException("Non-canonical permission rule arguments");
            }
            if (!ids.add(id) || !identities.add(tool + "\u0000" + arguments)) {
                throw new IOException("Duplicate permission rule identity");
            }
            long numericId;
            try {
                numericId = Long.parseLong(id);
            } catch (NumberFormatException invalidId) {
                throw new IOException("Invalid permission rule id", invalidId);
            }
            if (numericId == 0) throw new IOException("Invalid permission rule id");
            highestId = Math.max(highestId, numericId);
            restored.rules.add(new Rule(id, kind, tool, arguments));
        }
        JsonNode next = node.get("next_id");
        if (next == null || !next.isIntegralNumber() || !next.canConvertToLong()) {
            throw new IOException("Invalid permission rule counter");
        }
        long storedNext = next.longValue();
        if (storedNext < 1 || storedNext <= highestId) throw new IOException("Invalid permission rule counter");
        restored.nextId = storedNext;
        JsonNode generation = node.get("mutation_generation");
        if (generation == null || !generation.isIntegralNumber() || !generation.canConvertToLong()
                || generation.longValue() < 0) {
            throw new IOException("Invalid permission rule mutation generation");
        }
        if (generation.longValue() < storedNext - 1) {
            throw new IOException("Inconsistent permission rule mutation generation");
        }
        restored.mutationGeneration = generation.longValue();
        return restored;
    }

    private void requireMutationGeneration() {
        if (mutationGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("Permission rule mutation generation exhausted");
        }
    }

    /**
     * Canonical exact-match key for tool arguments: recursively sorted object
     * keys and compact serialization. Insignificant source whitespace and key
     * order do not matter, while whitespace inside string values remains data.
     */
    static String normalizeArguments(JsonNode arguments) {
        try {
            return JSON.writeValueAsString(sortedCopy(arguments));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Unable to serialize canonical arguments", impossible);
        }
    }

    /** Parses typed arguments JSON; null when the input is not a JSON object. */
    static ObjectNode parseArguments(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank() || utf8Length(raw) > MAX_RAW_ARGUMENT_BYTES) return null;
        JsonNode parsed;
        try (JsonParser parser = mapper.getFactory().createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            parsed = mapper.readTree(parser);
            if (parser.nextToken() != null) return null;
        } catch (IOException malformed) {
            return null;
        }
        return parsed != null && parsed.isObject() ? (ObjectNode) parsed : null;
    }

    private static JsonNode sortedCopy(JsonNode node) {
        if (node == null) return JSON.nullNode();
        if (node instanceof ObjectNode) {
            Map<String, JsonNode> ordered = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = ((ObjectNode) node).fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                ordered.put(field.getKey(), sortedCopy(field.getValue()));
            }
            ObjectNode out = JSON.createObjectNode();
            ordered.forEach(out::set);
            return out;
        }
        if (node instanceof ArrayNode) {
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode child : node) out.add(sortedCopy(child));
            return out;
        }
        return node.deepCopy();
    }

    private static String text(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IOException("Invalid permission rule field: " + field);
        return value.asText();
    }

    private static boolean isNumericId(String id) {
        if (id == null || id.isEmpty() || id.length() > 19) return false;
        if (id.length() > 1 && id.charAt(0) == '0') return false;
        for (int index = 0; index < id.length(); index++) {
            char character = id.charAt(index);
            if (character < '0' || character > '9') return false;
        }
        return true;
    }

    static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    static boolean validTool(String tool) {
        return tool != null && utf8Length(tool) <= MAX_TOOL_BYTES && tool.matches("[A-Za-z0-9_-]+");
    }

    static boolean validIdentity(String tool, String arguments) {
        return validTool(tool) && arguments != null && !arguments.isBlank()
                && utf8Length(arguments) <= MAX_IDENTITY_BYTES
                && utf8Length(tool) + 1 + utf8Length(arguments) <= MAX_IDENTITY_BYTES;
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException unparsable) {
            return Long.MAX_VALUE;
        }
    }
}
