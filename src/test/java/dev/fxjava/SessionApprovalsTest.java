package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Always-grant matching plus session-scope expiry on restart. */
class SessionApprovalsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void grantedPairsAllowOnlyIdenticalCanonicalArguments() throws Exception {
        SessionApprovals approvals = new SessionApprovals();
        String command = canonical("{\"command\":\"echo Exact\",\"working_directory\":\"src\"}");
        String sameCommand = canonical("{ \"working_directory\": \"src\", \"command\": \"echo Exact\" }");
        String changedSuffix = canonical("{\"command\":\"echo Exact && rm x\",\"working_directory\":\"src\"}");
        String changedDirectory = canonical("{\"command\":\"echo Exact\",\"working_directory\":\"test\"}");
        assertFalse(approvals.allows("run_command", command));
        approvals.grant("run_command", command);
        assertTrue(approvals.allows("run_command", sameCommand),
                "canonical object order and formatting produce the same identity");
        assertFalse(approvals.allows("run_command", changedSuffix),
                "a command suffix cannot inherit a grant");
        assertFalse(approvals.allows("run_command", changedDirectory),
                "a different working directory cannot inherit a grant");
        assertFalse(approvals.allows("Run_Command", command), "tool matching preserves case");
        assertEquals(1, approvals.count());
    }

    @Test
    void grantsExpireWhenTheSessionRestarts() {
        SessionApprovals live = new SessionApprovals();
        live.grant("write_file", "{\"path\":\"smoke.txt\"}");
        assertTrue(live.allows("write_file", "{\"path\":\"smoke.txt\"}"));
        SessionApprovals restarted = new SessionApprovals();
        assertEquals(0, restarted.count());
        assertFalse(restarted.allows("write_file", "{\"path\":\"smoke.txt\"}"));
    }

    @Test
    void clearExpiresAllCurrentSessionGrants() {
        SessionApprovals approvals = new SessionApprovals();
        approvals.grant("write_file", "{\"path\":\"smoke.txt\"}");
        approvals.grant("run_command", "{\"command\":\"mvn test\"}");
        approvals.clear();
        assertEquals(0, approvals.count());
        assertFalse(approvals.allows("write_file", "{\"path\":\"smoke.txt\"}"));
    }

    private String canonical(String raw) throws Exception {
        JsonNode parsed = json.readTree(raw);
        return SessionRules.normalizeArguments(parsed);
    }
}
