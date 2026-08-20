package dev.fxjava;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Small strict parser for the portable subset of fx SKILL.md metadata. */
final class SkillMetadata {
    static final int MAX_NAME_BYTES = 256;
    static final int MAX_DESCRIPTION_BYTES = 4 * 1024;

    enum Status { NO_FRONTMATTER, VALID, INVALID }
    enum Cause {
        MISSING_CLOSING_DELIMITER, MISSING_NAME, DUPLICATE_RECOGNIZED_KEY,
        INVALID_NAME, NAME_TOO_LONG, DESCRIPTION_TOO_LONG, MALFORMED_QUOTE,
        UNSUPPORTED_MULTILINE, INVALID_UTF8, CONTROL_BYTE
    }

    static final class Parsed {
        private final String name;
        private final String description;
        private final String body;
        private final Status status;
        private final Cause cause;

        Parsed(String name, String description, String body, Status status, Cause cause) {
            this.name = name;
            this.description = description;
            this.body = body;
            this.status = status;
            this.cause = cause;
        }

        public String name() { return name; }
        public String description() { return description; }
        public String body() { return body; }
        public Status status() { return status; }
        public Cause cause() { return cause; }

        Resolved resolve(String fallbackName) {
            if (status == Status.INVALID) return null;
            String resolvedName = status == Status.NO_FRONTMATTER ? fallbackName : name;
            if (invalidName(resolvedName) != null) return null;
            return new Resolved(resolvedName, status == Status.NO_FRONTMATTER || description == null ? "" : description);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Parsed)) return false;
            Parsed that = (Parsed) other;
            return Objects.equals(name, that.name)
                    && Objects.equals(description, that.description)
                    && Objects.equals(body, that.body)
                    && status == that.status && cause == that.cause;
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(name);
            result = 31 * result + Objects.hashCode(description);
            result = 31 * result + Objects.hashCode(body);
            result = 31 * result + Objects.hashCode(status);
            result = 31 * result + Objects.hashCode(cause);
            return result;
        }

        @Override
        public String toString() {
            return "Parsed[name=" + name + ", description=" + description + ", body=" + body
                    + ", status=" + status + ", cause=" + cause + "]";
        }
    }

    static final class Resolved {
        private final String name;
        private final String description;

        Resolved(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String name() { return name; }
        public String description() { return description; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Resolved)) return false;
            Resolved that = (Resolved) other;
            return Objects.equals(name, that.name) && Objects.equals(description, that.description);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(name);
            result = 31 * result + Objects.hashCode(description);
            return result;
        }

        @Override
        public String toString() {
            return "Resolved[name=" + name + ", description=" + description + "]";
        }
    }

    private SkillMetadata() { }

    static boolean validManagedName(String name) {
        return invalidName(name) == null;
    }

    static Parsed parse(byte[] bytes) {
        String input;
        try {
            input = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            return new Parsed(null, null, "", Status.INVALID, Cause.INVALID_UTF8);
        }
        return parse(input);
    }

    static Parsed parse(String original) {
        String input = original.replace("\r\n", "\n");
        int headerStart;
        if (input.startsWith("---\n")) headerStart = 4;
        else if (input.equals("---")) headerStart = 3;
        else return new Parsed(null, null, original, Status.NO_FRONTMATTER, null);

        int closingStart = -1;
        int bodyStart = -1;
        int lineStart = headerStart;
        while (lineStart <= input.length()) {
            int newline = input.indexOf('\n', lineStart);
            int end = newline < 0 ? input.length() : newline;
            if (input.substring(lineStart, end).equals("---")) {
                closingStart = lineStart;
                bodyStart = newline < 0 ? end : newline + 1;
                break;
            }
            if (newline < 0) break;
            lineStart = newline + 1;
        }
        if (closingStart < 0) {
            return new Parsed(null, null, original, Status.INVALID, Cause.MISSING_CLOSING_DELIMITER);
        }

        String header = input.substring(headerStart, closingStart);
        String body = input.substring(bodyStart).replaceFirst("^[\\r\\n]+", "");
        List<String> lines = new ArrayList<>(List.of(header.split("\n", -1)));
        String name = null;
        String description = null;
        Cause cause = null;
        boolean sawName = false;
        boolean sawDescription = false;
        boolean descriptionBlock = false;
        boolean previousRecognized = false;

        for (int index = 0; index < lines.size();) {
            String line = stripTrailingCarriageReturn(lines.get(index));
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                index++;
                continue;
            }
            if (previousRecognized && startsIndented(line)) {
                cause = first(cause, Cause.UNSUPPORTED_MULTILINE);
                previousRecognized = false;
                index++;
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                previousRecognized = false;
                index++;
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (key.equals("name")) {
                if (sawName) cause = first(cause, Cause.DUPLICATE_RECOGNIZED_KEY);
                sawName = true;
                Value parsed = scalar(value);
                name = parsed.text();
                cause = first(cause, parsed.cause());
                previousRecognized = true;
                index++;
            } else if (key.equals("description")) {
                if (sawDescription) cause = first(cause, Cause.DUPLICATE_RECOGNIZED_KEY);
                sawDescription = true;
                BlockStyle style = BlockStyle.from(value);
                if (style != null) {
                    Block block = block(lines, index + 1, style);
                    description = block.text();
                    descriptionBlock = true;
                    cause = first(cause, block.cause());
                    index = block.nextIndex();
                    previousRecognized = false;
                } else {
                    Value parsed = scalar(value);
                    description = parsed.text();
                    descriptionBlock = false;
                    cause = first(cause, parsed.cause());
                    previousRecognized = true;
                    index++;
                }
            } else {
                previousRecognized = false;
                index++;
            }
        }

        cause = first(cause, invalidName(name));
        if (description != null) {
            if (utf8Length(description) > MAX_DESCRIPTION_BYTES) cause = first(cause, Cause.DESCRIPTION_TOO_LONG);
            if (!descriptionBlock) cause = first(cause, invalidText(description));
        }
        return new Parsed(name, description, body,
                cause == null ? Status.VALID : Status.INVALID, cause);
    }

    private static Block block(List<String> lines, int start, BlockStyle style) {
        int index = start;
        int baseIndent = -1;
        Cause cause = null;
        List<String> values = new ArrayList<>();
        while (index < lines.size()) {
            String line = stripTrailingCarriageReturn(lines.get(index));
            if (line.trim().isEmpty()) {
                values.add("");
                index++;
                continue;
            }
            int indent = leadingSpaces(line);
            if (indent == 0) {
                if (line.charAt(0) == '\t') {
                    cause = first(cause, Cause.UNSUPPORTED_MULTILINE);
                    values.add("");
                    index++;
                    continue;
                }
                break;
            }
            if (indent < line.length() && line.charAt(indent) == '\t') {
                cause = first(cause, Cause.UNSUPPORTED_MULTILINE);
                values.add("");
                index++;
                continue;
            }
            if (baseIndent < 0) baseIndent = indent;
            if (indent < baseIndent) {
                cause = first(cause, Cause.UNSUPPORTED_MULTILINE);
                values.add("");
            } else {
                String content = line.substring(baseIndent);
                cause = first(cause, invalidText(content));
                values.add(content);
            }
            index++;
        }
        while (!values.isEmpty() && values.get(values.size() - 1).trim().isEmpty()) {
            values.remove(values.size() - 1);
        }
        StringBuilder decoded = new StringBuilder();
        for (int value = 0; value < values.size(); value++) {
            if (value > 0) {
                boolean neighbors = !values.get(value - 1).isEmpty() && !values.get(value).isEmpty();
                decoded.append(style == BlockStyle.LITERAL || !neighbors ? '\n' : ' ');
            }
            decoded.append(values.get(value));
        }
        if (!values.isEmpty() && style != BlockStyle.FOLDED_STRIP) decoded.append('\n');
        String text = decoded.toString();
        if (utf8Length(text) > MAX_DESCRIPTION_BYTES) cause = first(cause, Cause.DESCRIPTION_TOO_LONG);
        return new Block(text, index, cause);
    }

    private static Value scalar(String value) {
        if (value.startsWith("|") || value.startsWith(">")) {
            return new Value(value, Cause.UNSUPPORTED_MULTILINE);
        }
        boolean starts = value.startsWith("\"") || value.startsWith("'");
        boolean ends = value.endsWith("\"") || value.endsWith("'");
        if (starts || ends) {
            if (value.length() >= 2 && value.charAt(0) == value.charAt(value.length() - 1)) {
                return new Value(value.substring(1, value.length() - 1), null);
            }
            return new Value(value, Cause.MALFORMED_QUOTE);
        }
        return new Value(value, null);
    }

    private static Cause invalidName(String name) {
        if (name == null || name.isEmpty()) return Cause.MISSING_NAME;
        if (utf8Length(name) > MAX_NAME_BYTES) return Cause.NAME_TOO_LONG;
        Cause text = invalidText(name);
        if (text != null) return text;
        if (name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            return Cause.INVALID_NAME;
        }
        return null;
    }

    private static Cause invalidText(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) return Cause.CONTROL_BYTE;
        }
        return null;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean startsIndented(String value) {
        return !value.isEmpty() && (value.charAt(0) == ' ' || value.charAt(0) == '\t');
    }

    private static int leadingSpaces(String value) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == ' ') count++;
        return count;
    }

    private static String stripTrailingCarriageReturn(String value) {
        return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
    }

    private static Cause first(Cause existing, Cause candidate) {
        return existing == null ? candidate : existing;
    }

    private enum BlockStyle {
        FOLDED_CLIP, FOLDED_STRIP, LITERAL;

        static BlockStyle from(String value) {
            switch (value) {
                case ">": return FOLDED_CLIP;
                case ">-": return FOLDED_STRIP;
                case "|": return LITERAL;
                default: return null;
            }
        }
    }

    private static final class Value {
        private final String text;
        private final Cause cause;

        Value(String text, Cause cause) {
            this.text = text;
            this.cause = cause;
        }

        public String text() { return text; }
        public Cause cause() { return cause; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Value)) return false;
            Value that = (Value) other;
            return Objects.equals(text, that.text) && cause == that.cause;
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(text);
            result = 31 * result + Objects.hashCode(cause);
            return result;
        }

        @Override
        public String toString() { return "Value[text=" + text + ", cause=" + cause + "]"; }
    }

    private static final class Block {
        private final String text;
        private final int nextIndex;
        private final Cause cause;

        Block(String text, int nextIndex, Cause cause) {
            this.text = text;
            this.nextIndex = nextIndex;
            this.cause = cause;
        }

        public String text() { return text; }
        public int nextIndex() { return nextIndex; }
        public Cause cause() { return cause; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Block)) return false;
            Block that = (Block) other;
            return nextIndex == that.nextIndex && Objects.equals(text, that.text) && cause == that.cause;
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(text);
            result = 31 * result + Integer.hashCode(nextIndex);
            result = 31 * result + Objects.hashCode(cause);
            return result;
        }

        @Override
        public String toString() {
            return "Block[text=" + text + ", nextIndex=" + nextIndex + ", cause=" + cause + "]";
        }
    }
}
