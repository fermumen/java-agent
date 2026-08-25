package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The raw-shell flow owns presentation; the tool's JSON contract stays fixed. */
class AskUserFlowTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void flowAnswersMapIntoTheExistingJsonContract() throws Exception {
        ByteArrayOutputStream presentation = new ByteArrayOutputStream();
        RecordingFlow flow = new RecordingFlow("No");
        AskUserTool tool = new AskUserTool(new BufferedReader(new StringReader("")),
                stream(presentation), true, flow);

        assertEquals("[{\"question\":\"Proceed?\",\"answer\":\"No\"}]", tool.execute(question("Proceed?", "Yes", "No")));
        assertEquals(1, flow.asked.size());
        assertEquals("Proceed?", flow.asked.get(0).text());
        assertTrue(presentation.size() == 0, "the flow owns rendering; the tool prints nothing");
    }

    @Test
    void multiQuestionBatchesAskInOrderAndKeepLabels() throws Exception {
        ObjectNode arguments = json.createObjectNode();
        var questions = arguments.putArray("questions");
        var first = questions.addObject().put("question", "First?").putArray("options");
        first.addObject().put("label", "Alpha");
        first.addObject().put("label", "Beta");
        var second = questions.addObject().put("question", "Second?").putArray("options");
        second.addObject().put("label", "Gamma");
        second.addObject().put("label", "Delta");
        RecordingFlow flow = new RecordingFlow("Beta", "Delta");
        AskUserTool tool = new AskUserTool(new BufferedReader(new StringReader("")),
                stream(new ByteArrayOutputStream()), true, flow);

        assertEquals("[{\"question\":\"First?\",\"answer\":\"Beta\"},"
                        + "{\"question\":\"Second?\",\"answer\":\"Delta\"}]",
                tool.execute(arguments));
        assertEquals(2, flow.asked.size());
    }

    @Test
    void cancelledQuestionKeepsTheCancelledSentinel() throws Exception {
        AskUserTool tool = new AskUserTool(new BufferedReader(new StringReader("")),
                stream(new ByteArrayOutputStream()), true, new RecordingFlow((String) null));
        assertEquals(AskUserTool.CANCELLED, tool.execute(question("Proceed?", "Yes", "No")));
    }

    @Test
    void unavailableSentinelWinsEvenWithAFlow() throws Exception {
        AskUserTool tool = new AskUserTool(new BufferedReader(new StringReader("")),
                stream(new ByteArrayOutputStream()), false, new RecordingFlow("Yes"));
        assertEquals(AskUserTool.NOT_AVAILABLE, tool.execute(json.createObjectNode()));
    }

    private static final class RecordingFlow implements QuestionFlow {
        private final String[] labels;
        private final List<AskUserTool.Question> asked = new ArrayList<>();

        RecordingFlow(String... labels) {
            this.labels = labels;
        }

        @Override
        public String ask(AskUserTool.Question question) {
            asked.add(question);
            return labels[asked.size() - 1];
        }
    }

    private static PrintStream stream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private ObjectNode question(String text, String first, String second) {
        ObjectNode arguments = json.createObjectNode();
        var question = arguments.putArray("questions").addObject().put("question", text);
        question.putArray("options").addObject().put("label", first);
        question.withArray("options").addObject().put("label", second);
        return arguments;
    }
}
