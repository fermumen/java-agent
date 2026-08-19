package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Interactive-only FX-compatible multiple-choice clarification tool. */
final class AskUserTool implements Tool {
    static final String NOT_AVAILABLE =
            "(ask_user_question is only available in the interactive shell; ask the user freeform instead)";
    static final String CANCELLED = "(user cancelled the question)";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BufferedReader input;
    private final PrintStream output;
    private final boolean available;
    private final ObjectNode parameters;

    AskUserTool(BufferedReader input, PrintStream output, boolean available) {
        this.input = input;
        this.output = output;
        this.available = available;
        ObjectNode option = JSON.createObjectNode().put("type", "object");
        option.putObject("properties").putObject("label").put("type", "string");
        option.withObject("properties").putObject("description").put("type", "string");
        option.putArray("required").add("label");
        ObjectNode question = JSON.createObjectNode().put("type", "object");
        question.putObject("properties").putObject("question").put("type", "string");
        question.withObject("properties").putObject("options").put("type", "array")
                .put("minItems", 2).put("maxItems", 6).set("items", option);
        question.putArray("required").add("question").add("options");
        parameters = JSON.createObjectNode().put("type", "object");
        parameters.putObject("properties").putObject("questions").put("type", "array")
                .put("minItems", 1).put("maxItems", 4).set("items", question);
        parameters.putArray("required").add("questions");
    }

    @Override public String name() { return "ask_user_question"; }
    @Override public String description() {
        return "Ask the user 1-4 multiple-choice questions in interactive runs when a concrete decision blocks progress.";
    }
    @Override public ObjectNode parameters() { return parameters; }
    @Override public boolean requiresApproval() { return false; }
    @Override public String preview(JsonNode arguments) { return "ask user"; }

    @Override
    public String execute(JsonNode arguments) throws IOException {
        if (!available) return NOT_AVAILABLE;
        List<Question> questions = parse(arguments);
        ArrayNode answers = JSON.createArrayNode();
        for (Question question : questions) {
            output.println(question.text());
            for (int index = 0; index < question.options().size(); index++) {
                Option option = question.options().get(index);
                output.println("  " + (index + 1) + ". " + option.label()
                        + (option.description().isBlank() ? "" : " — " + option.description()));
            }
            output.print("Choose 1-" + question.options().size() + ": ");
            output.flush();
            String raw = input.readLine();
            if (raw == null) return CANCELLED;
            String answer = select(raw.trim(), question.options());
            if (answer == null) return CANCELLED;
            answers.addObject().put("question", question.text()).put("answer", answer);
        }
        return JSON.writeValueAsString(answers);
    }

    private static List<Question> parse(JsonNode arguments) {
        JsonNode values = arguments.path("questions");
        if (!values.isArray()) throw new IllegalArgumentException("missing required array \"questions\"");
        if (values.size() < 1 || values.size() > 4) throw new IllegalArgumentException("provide 1 to 4 questions");
        List<Question> result = new ArrayList<>();
        for (JsonNode value : values) {
            String text = required(value, "question", "question text");
            JsonNode options = value.path("options");
            if (!options.isArray() || options.size() < 2 || options.size() > 6) {
                throw new IllegalArgumentException("provide 2 to 6 options per question");
            }
            List<Option> parsed = new ArrayList<>();
            Set<String> labels = new HashSet<>();
            for (JsonNode option : options) {
                String label = required(option, "label", "option label");
                if (!labels.add(label.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("option labels must be unique within a question");
                }
                String description = option.path("description").isTextual()
                        ? safe(option.path("description").asText().trim()) : "";
                parsed.add(new Option(label, description));
            }
            result.add(new Question(text, List.copyOf(parsed)));
        }
        return List.copyOf(result);
    }

    private static String required(JsonNode value, String field, String label) {
        if (!value.isObject() || !value.path(field).isTextual() || value.path(field).asText().trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return safe(value.path(field).asText().trim());
    }

    private static String select(String raw, List<Option> options) {
        try {
            int index = Integer.parseInt(raw);
            if (index >= 1 && index <= options.size()) return options.get(index - 1).label();
        } catch (NumberFormatException ignored) { }
        for (Option option : options) if (option.label().equalsIgnoreCase(raw)) return option.label();
        return null;
    }

    private static String safe(String text) {
        StringBuilder value = new StringBuilder(text.length());
        text.codePoints().forEach(code -> value.appendCodePoint(Character.isISOControl(code) ? '?' : code));
        return value.toString();
    }

    private record Question(String text, List<Option> options) { }
    private record Option(String label, String description) { }
}
