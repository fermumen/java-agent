package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentCommandParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsEveryCanonicalFxCommandBranch() throws Exception {
        assertBranch("{\"command\":{\"create\":{\"name\":\"worker\",\"mode\":\"one_off\",\"prompt\":\"do it\"}}}", "create");
        assertBranch("{\"command\":{\"inspect\":{\"id\":\"child-1\",\"sections\":[\"status\"]}}}", "inspect");
        assertBranch("{\"command\":{\"message\":{\"send\":{\"id\":\"child-1\",\"content\":\"hello\"}}}}", "message");
        assertBranch("{\"command\":{\"message\":{\"milestone\":{\"name\":\"compiled\"}}}}", "message");
        assertBranch("{\"command\":{\"relationship\":{\"action\":\"detach\",\"id\":\"child-1\"}}}", "relationship");
        assertBranch("{\"command\":{\"configure\":{\"id\":\"child-1\",\"name\":\"renamed\"}}}", "configure");
        assertBranch("{\"command\":{\"lifecycle\":{\"id\":\"child-1\",\"action\":\"cancel\"}}}", "lifecycle");
    }

    @Test
    void rejectsAmbiguityUnknownFieldsAndMissingRequiredValuesWithFxCodes() throws Exception {
        rejected("{\"command\":{}}", "invalid_branch_selection");
        rejected("{\"command\":{\"inspect\":{\"id\":\"child-1\",\"sections\":[\"status\"]},\"lifecycle\":{\"id\":\"child-1\",\"action\":\"cancel\"}}}", "invalid_branch_selection");
        rejected("{\"command\":{\"message\":{\"send\":{\"id\":\"child-1\",\"content\":\"hello\"},\"milestone\":{\"name\":\"compiled\"}}}}", "invalid_nested_branch_selection");
        rejected("{\"command\":{\"inspect\":{\"sections\":[\"status\"]}}}", "missing_inspect_id");
        rejected("{\"command\":{\"inspect\":{\"id\":\"child-1\",\"sections\":[\"status\"],\"extra\":true}}}", "unknown_field");
        rejected("{\"command\":{\"create\":{\"mode\":\"persistent\"}}}", "missing_name");
        rejected("{\"command\":{\"create\":{\"name\":\"worker\"}}}", "missing_mode");
        rejected("{\"command\":{\"create\":{\"name\":\"worker\",\"mode\":\"one_off\"}}}", "missing_one_off_prompt");
    }

    @Test
    void validatesWaitRelationshipsConfigurationNotificationsAndBounds() throws Exception {
        assertBranch("{\"command\":{\"inspect\":{\"id\":\"child-1\",\"sections\":[\"status\",\"messages\"],\"wait\":{\"until\":\"settled\",\"after_generation\":3,\"timeout_ms\":30000}}}}", "inspect");
        rejected("{\"command\":{\"inspect\":{\"id\":\"child-1\",\"sections\":[\"messages\"],\"wait\":{\"until\":\"settled\",\"timeout_ms\":30000}}}}", "invalid_inspect_wait");
        rejected("{\"command\":{\"relationship\":{\"action\":\"reparent\",\"id\":\"child-1\"}}}", "invalid_relationship");
        rejected("{\"command\":{\"configure\":{\"id\":\"child-1\"}}}", "empty_configuration");
        rejected("{\"command\":{\"create\":{\"name\":\"worker\",\"mode\":\"persistent\",\"permission_mode\":\"unsafe\"}}}", "invalid_enum");

        String oversized = "x".repeat(SubagentCommand.MAX_MESSAGE_BYTES + 1);
        rejected("{\"command\":{\"message\":{\"send\":{\"id\":\"child-1\",\"content\":\""
                + oversized + "\"}}}}", "invalid_message");
    }

    private void assertBranch(String input, String expected) throws Exception {
        assertEquals(expected, SubagentCommand.parse(json.readTree(input)).branch());
    }

    private void rejected(String input, String code) throws Exception {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SubagentCommand.parse(json.readTree(input)));
        assertTrue(failure.getMessage().contains(code));
    }
}
