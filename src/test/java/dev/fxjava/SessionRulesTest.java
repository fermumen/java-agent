package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-match rule identity, deny-first precedence, stable ids, and codec
 * round-trips for fx-shaped saved-session permission rules.
 */
class SessionRulesTest {
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode args(String raw) {
        try {
            return json.readTree(raw);
        } catch (IOException malformedFixture) {
            throw new AssertionError(malformedFixture);
        }
    }

    @Test
    void normalizationMatchesSameArgumentsDespiteKeyOrderAndSpacing() {
        String spaced = "{\n \"content\" : \"hello\\tworld\",  \"nested\" : {\"b\": 2, \"a\": [1, 2]},\n"
                + " \"path\": \"notes.md\" }";
        String compact = "{\"path\":\"notes.md\",\"content\":\"hello\\tworld\",\"nested\":{\"a\":[1,2],\"b\":2}}";
        assertEquals(SessionRules.normalizeArguments(args(compact)),
                SessionRules.normalizeArguments(args(spaced)),
                "key order and insignificant spacing must not change identity");
        assertEquals("{\"path\":\"notes.md\"}", SessionRules.normalizeArguments(args("{ \"path\": \"notes.md\" }")));
    }

    @Test
    void differentArgumentsNormalizeDifferently() {
        String base = SessionRules.normalizeArguments(args("{\"path\":\"notes.md\",\"content\":\"one\"}"));
        assertNotEquals(base, SessionRules.normalizeArguments(
                args("{\"path\":\"notes.md\",\"content\":\"two\"}")), "different values differ");
        assertNotEquals(base, SessionRules.normalizeArguments(
                args("{\"path\":\"other.md\",\"content\":\"one\"}")), "different paths differ");
        assertNotEquals(base, SessionRules.normalizeArguments(
                args("{\"path\":\"notes.md\",\"content\":\"one\",\"extra\":true}")), "extra fields differ");
        assertNotEquals("", SessionRules.normalizeArguments(args("{}")), "empty object still has identity");
    }

    @Test
    void whitespaceInsideValuesRemainsExactData() {
        assertNotEquals(SessionRules.normalizeArguments(args("{\"a\":\"x y\"}")),
                SessionRules.normalizeArguments(args("{\"a\":\"x\\ty\"}")),
                "different string values must never share a rule identity");
    }

    @Test
    void decideMatchesOnlyTheExactToolAndKeyPair() {
        SessionRules rules = new SessionRules();
        String key = SessionRules.normalizeArguments(args("{\"path\":\"notes.md\"}"));
        assertEquals("1", rules.remember(SessionRules.Kind.DENY, "write_file", key));
        assertEquals(SessionRules.Decision.DENY, rules.decide("write_file", key));
        assertEquals(SessionRules.Decision.UNRESOLVED,
                rules.decide("write_file", SessionRules.normalizeArguments(args("{\"path\":\"other.md\"}"))));
        assertEquals(SessionRules.Decision.UNRESOLVED, rules.decide("edit_file", key));
    }

    @Test
    void replacementLeavesOnlyOneActiveDecisionForAnIdentity() {
        SessionRules rules = new SessionRules();
        String key = SessionRules.normalizeArguments(args("{\"path\":\"notes.md\"}"));
        String allowId = rules.remember(SessionRules.Kind.ALLOW, "write_file", key);
        String denyId = rules.remember(SessionRules.Kind.DENY, "write_file", key);
        assertEquals(allowId, denyId, "fx replacement preserves the existing stable id");
        assertEquals(1, rules.count(), "only one rule may be active for an exact identity");
        assertEquals(SessionRules.Decision.DENY, rules.decide("write_file", key));
        assertEquals(SessionRules.Decision.UNRESOLVED, rules.decide("WRITE_FILE", key),
                "tool names are part of the case-sensitive exact identity");
    }

    @Test
    void rememberingAnExistingIdentityFlipsKindAndKeepsId() {
        SessionRules rules = new SessionRules();
        String key = SessionRules.normalizeArguments(args("{\"path\":\"notes.md\"}"));
        String allowId = rules.remember(SessionRules.Kind.ALLOW, "write_file", key);
        String flipped = rules.remember(SessionRules.Kind.DENY, "write_file", key);
        assertEquals(allowId, flipped, "replacement preserves the stable id");
        assertEquals(1, rules.count());
        assertEquals(SessionRules.Decision.DENY, rules.decide("write_file", key));
        assertEquals(1, rules.all().size());
        assertEquals(SessionRules.Kind.DENY, rules.all().get(0).kind);
        assertEquals("2", rules.remember(SessionRules.Kind.ALLOW, "open_file", "{}"),
                "replacement advances mutation generation without consuming a stable id");
    }

    @Test
    void revokeRemovesOnlyTheTargetedRule() {
        SessionRules rules = new SessionRules();
        String first = rules.remember(SessionRules.Kind.ALLOW, "write_file",
                SessionRules.normalizeArguments(args("{\"path\":\"a.md\"}")));
        String second = rules.remember(SessionRules.Kind.DENY, "run_command",
                SessionRules.normalizeArguments(args("{\"command\":\"rm -rf /\"}")));
        assertTrue(rules.revoke(first));
        assertFalse(rules.revoke(first), "double revoke reports absence");
        assertFalse(rules.revoke("999"));
        assertEquals(1, rules.count());
        assertEquals(SessionRules.Decision.DENY, rules.decide("run_command",
                SessionRules.normalizeArguments(args("{\"command\":\"rm -rf /\"}"))));
        assertEquals(second, rules.all().get(0).id);
    }

    @Test
    void idsStayStableAcrossEncodeDecodeRoundTrip() throws IOException {
        SessionRules rules = new SessionRules();
        String first = rules.remember(SessionRules.Kind.DENY, "write_file",
                SessionRules.normalizeArguments(args("{\"path\":\"a.md\"}")));
        String second = rules.remember(SessionRules.Kind.ALLOW, "run_command",
                SessionRules.normalizeArguments(args("{\"command\":\"ls\"}")));
        SessionRules reloaded = SessionRules.decode(rules.encode(json));
        assertEquals(rules.all(), reloaded.all(), "ids, kinds, tools, and keys survive reload");
        String third = reloaded.remember(SessionRules.Kind.ALLOW, "open_file",
                SessionRules.normalizeArguments(args("{\"path\":\"b.md\"}")));
        assertNotEquals(first, third);
        assertNotEquals(second, third);
        assertEquals("3", third, "the persisted counter continues after reload");
    }

    @Test
    void decodeRejectsCorruptStateInsteadOfSilentlyDroppingDenies() throws IOException {
        SessionRules rules = new SessionRules();
        rules.remember(SessionRules.Kind.DENY, "write_file",
                SessionRules.normalizeArguments(args("{\"path\":\"a.md\"}")));
        ObjectNode encoded = rules.encode(json);

        encoded.put("schema_version", 99);
        assertThrowsIo(() -> SessionRules.decode(encoded));

        encoded.put("schema_version", SessionRules.SCHEMA_VERSION);
        ((com.fasterxml.jackson.databind.node.ObjectNode) encoded.path("rules").get(0)).put("kind", "maybe");
        assertThrowsIo(() -> SessionRules.decode(encoded));

        ((com.fasterxml.jackson.databind.node.ObjectNode) encoded.path("rules").get(0)).put("kind", "deny");
        encoded.put("next_id", 1);
        assertThrowsIo(() -> SessionRules.decode(encoded), "next_id must stay above every id");

        assertThrowsIo(() -> SessionRules.decode(json.readTree("[]")));
        assertThrowsIo(() -> SessionRules.decode(null));
    }

    @Test
    void ruleCapacityMirrorsFxLimit() {
        SessionRules rules = new SessionRules();
        for (int index = 1; index <= SessionRules.MAX_RULES; index++) {
            assertEquals(String.valueOf(index),
                    rules.remember(SessionRules.Kind.ALLOW, "tool_" + index, "{}"));
        }
        assertEquals(SessionRules.MAX_RULES, rules.count());
        assertNull(rules.remember(SessionRules.Kind.ALLOW, "overflow", "{}"), "full store refuses new rules");
        assertEquals("1", rules.remember(SessionRules.Kind.DENY, "tool_1", "{}"),
                "fx permits replacement while the store is full");
        assertEquals(SessionRules.MAX_RULES, rules.count());
    }

    @Test
    void decodeAcceptsCounterBoundariesAndMutationsRejectOverflowExplicitly() throws IOException {
        ObjectNode idExhausted = json.createObjectNode().put("schema_version", SessionRules.SCHEMA_VERSION)
                .put("next_id", Long.MAX_VALUE).put("mutation_generation", Long.MAX_VALUE - 1);
        idExhausted.putArray("rules");
        SessionRules noIds = SessionRules.decode(idExhausted);
        IllegalStateException idFailure = assertThrows(IllegalStateException.class,
                () -> noIds.remember(SessionRules.Kind.ALLOW, "tool", "{}"));
        assertTrue(idFailure.getMessage().contains("ID counter exhausted"));
        assertEquals(0, noIds.count());

        ObjectNode generationExhausted = json.createObjectNode().put("schema_version", SessionRules.SCHEMA_VERSION)
                .put("next_id", 2).put("mutation_generation", Long.MAX_VALUE);
        generationExhausted.putArray("rules").addObject().put("id", "1").put("kind", "allow")
                .put("tool", "tool").put("arguments", "{}");
        SessionRules noMutations = SessionRules.decode(generationExhausted);
        IllegalStateException generationFailure = assertThrows(IllegalStateException.class,
                () -> noMutations.revoke("1"));
        assertTrue(generationFailure.getMessage().contains("generation exhausted"));
        assertEquals(1, noMutations.count());
    }

    @Test
    void parseArgumentsAcceptsOnlyJsonObjects() {
        assertNull(SessionRules.parseArguments(json, ""));
        assertNull(SessionRules.parseArguments(json, "   "));
        assertNull(SessionRules.parseArguments(json, "[1,2]"));
        assertNull(SessionRules.parseArguments(json, "\"text\""));
        assertNull(SessionRules.parseArguments(json, "{\"broken\":"));
        assertTrue(SessionRules.parseArguments(json, "  {\"a\": \"b c\"}  ") != null);
    }

    @Test
    void parseArgumentsRejectsTrailingValuesDuplicateKeysAndOversizedRawUtf8() {
        assertNull(SessionRules.parseArguments(json, "{} {}"));
        assertNull(SessionRules.parseArguments(json, "{\"a\":1,\"a\":2}"));
        assertNull(SessionRules.parseArguments(json,
                "{\"value\":\"" + "x".repeat(SessionRules.MAX_RAW_ARGUMENT_BYTES) + "\"}"));
    }

    @Test
    void ruleIdentityBoundsToolAndCombinedCanonicalUtf8() {
        SessionRules rules = new SessionRules();
        assertNull(rules.remember(SessionRules.Kind.ALLOW,
                "t".repeat(SessionRules.MAX_TOOL_BYTES + 1), "{}"));
        assertNull(rules.remember(SessionRules.Kind.ALLOW, "tool",
                "{\"value\":\"" + "x".repeat(SessionRules.MAX_IDENTITY_BYTES) + "\"}"));
        assertEquals(0, rules.count());
    }

    @Test
    void listedRulesAreSortedStablyByNumericId() {
        SessionRules rules = new SessionRules();
        rules.remember(SessionRules.Kind.ALLOW, "t3", "{}");
        rules.remember(SessionRules.Kind.DENY, "t1", "{}");
        rules.remember(SessionRules.Kind.ALLOW, "t2", "{}");
        List<SessionRules.Rule> listed = rules.all();
        assertEquals(3, listed.size());
        int previous = 0;
        for (SessionRules.Rule rule : listed) {
            int current = Integer.parseInt(rule.id);
            assertTrue(current > previous);
            previous = current;
        }
    }

    private static void assertThrowsIo(ThrowingCall call) {
        try {
            call.run();
        } catch (IOException expected) {
            return;
        } catch (Exception unexpected) {
            throw new AssertionError("threw " + unexpected, unexpected);
        }
        throw new AssertionError("expected an IOException");
    }

    private static void assertThrowsIo(ThrowingCall call, String message) {
        try {
            call.run();
        } catch (IOException expected) {
            return;
        } catch (Exception unexpected) {
            throw new AssertionError(message + ": threw " + unexpected, unexpected);
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
