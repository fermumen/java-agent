package dev.fxjava;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Always-grant matching plus session-scope expiry on restart. */
class SessionApprovalsTest {
    @Test
    void grantedPairsAllowIdenticalRequestsOnly() {
        SessionApprovals approvals = new SessionApprovals();
        assertFalse(approvals.allows("write_file", "create smoke.txt"));
        approvals.grant("write_file", "create smoke.txt");
        assertTrue(approvals.allows("write_file", " create\tsmoke.txt\n"),
                "whitespace variants of the same preview match");
        assertFalse(approvals.allows("write_file", "create other.txt"));
        assertFalse(approvals.allows("run_command", "create smoke.txt"));
        assertEquals(1, approvals.count());
    }

    @Test
    void grantsExpireWhenTheSessionRestarts() {
        SessionApprovals live = new SessionApprovals();
        live.grant("write_file", "create smoke.txt");
        assertTrue(live.allows("write_file", "create smoke.txt"));
        SessionApprovals restarted = new SessionApprovals();
        assertEquals(0, restarted.count());
        assertFalse(restarted.allows("write_file", "create smoke.txt"));
    }

    @Test
    void clearExpiresAllCurrentSessionGrants() {
        SessionApprovals approvals = new SessionApprovals();
        approvals.grant("write_file", "create smoke.txt");
        approvals.grant("run_command", "mvn test");
        approvals.clear();
        assertEquals(0, approvals.count());
        assertFalse(approvals.allows("write_file", "create smoke.txt"));
    }
}
