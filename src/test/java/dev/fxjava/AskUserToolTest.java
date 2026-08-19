package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskUserToolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void noninteractiveSentinelPrecedesArgumentValidation() throws Exception {
        AskUserTool tool = tool("", false, new ByteArrayOutputStream());
        assertEquals(AskUserTool.NOT_AVAILABLE, tool.execute(json.createObjectNode()));
        assertEquals(1, tool.parameters().path("properties").path("questions").path("minItems").asInt());
        assertEquals(4, tool.parameters().path("properties").path("questions").path("maxItems").asInt());
    }

    @Test
    void interactiveChoiceReturnsFxAnswerShape() throws Exception {
        ByteArrayOutputStream presentation = new ByteArrayOutputStream();
        AskUserTool tool = tool("2\n", true, presentation);
        ObjectNode arguments = question("Proceed?", "Yes", "No");

        assertEquals("[{\"question\":\"Proceed?\",\"answer\":\"No\"}]", tool.execute(arguments));
        String shown = presentation.toString(StandardCharsets.UTF_8);
        assertTrue(shown.contains("Proceed?"));
        assertTrue(shown.contains("1. Yes"));
        assertTrue(shown.contains("2. No"));
    }

    @Test
    void rejectsDuplicateLabelsIgnoringCase() {
        AskUserTool tool = tool("1\n", true, new ByteArrayOutputStream());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tool.execute(question("Choose?", "Same", "same")));
        assertTrue(error.getMessage().contains("unique"));
    }

    private AskUserTool tool(String input, boolean available, ByteArrayOutputStream output) {
        return new AskUserTool(new BufferedReader(new StringReader(input)),
                new PrintStream(output, true, StandardCharsets.UTF_8), available);
    }

    private ObjectNode question(String text, String first, String second) {
        ObjectNode arguments = json.createObjectNode();
        var question = arguments.putArray("questions").addObject().put("question", text);
        question.putArray("options").addObject().put("label", first);
        question.withArray("options").addObject().put("label", second);
        return arguments;
    }
}
