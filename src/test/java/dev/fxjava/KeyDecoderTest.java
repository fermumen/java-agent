package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Table-driven coverage of terminal byte decoding, including split reads. */
class KeyDecoderTest {
    private final KeyDecoder decoder = new KeyDecoder();

    private static List<KeyEvent> decoded(KeyDecoder target, String hex) {
        return target.feed(bytes(hex), bytes(hex).length);
    }

    private static byte[] bytes(String hex) {
        byte[] raw = new byte[hex.length() / 2];
        for (int index = 0; index < raw.length; index++) {
            raw[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        }
        return raw;
    }

    private void assertSingle(String hex, KeyEvent expected) {
        assertEquals(List.of(expected), decoded(decoder, hex), "sequence " + hex);
    }

    @Test
    void printableAsciiDecodesAsText() {
        assertSingle("41", KeyEvent.text("A"));
        assertSingle("7e", KeyEvent.text("~"));
        assertSingle("20", KeyEvent.text(" "));
    }

    @Test
    void enterBackspaceTabDecode() {
        assertSingle("0d", KeyEvent.enter());
        assertSingle("0a", KeyEvent.enter());
        assertSingle("7f", KeyEvent.backspace());
        assertSingle("08", KeyEvent.backspace());
        assertSingle("09", KeyEvent.of(KeyEvent.Kind.TAB));
    }

    @Test
    void arrowKeysViaCsiAndSs3() {
        assertSingle("1b5b41", KeyEvent.of(KeyEvent.Kind.UP));
        assertSingle("1b5b42", KeyEvent.of(KeyEvent.Kind.DOWN));
        assertSingle("1b5b43", KeyEvent.of(KeyEvent.Kind.RIGHT));
        assertSingle("1b5b44", KeyEvent.of(KeyEvent.Kind.LEFT));
        assertSingle("1b4f41", KeyEvent.of(KeyEvent.Kind.UP));
        assertSingle("1b4f44", KeyEvent.of(KeyEvent.Kind.LEFT));
    }

    @Test
    void homeEndPageAndDeleteMappings() {
        assertSingle("1b5b48", KeyEvent.of(KeyEvent.Kind.HOME));
        assertSingle("1b5b46", KeyEvent.of(KeyEvent.Kind.END));
        assertSingle("1b4f48", KeyEvent.of(KeyEvent.Kind.HOME));
        assertSingle("1b4f46", KeyEvent.of(KeyEvent.Kind.END));
        assertSingle("1b5b317e", KeyEvent.of(KeyEvent.Kind.HOME));
        assertSingle("1b5b377e", KeyEvent.of(KeyEvent.Kind.HOME));
        assertSingle("1b5b347e", KeyEvent.of(KeyEvent.Kind.END));
        assertSingle("1b5b387e", KeyEvent.of(KeyEvent.Kind.END));
        assertSingle("1b5b337e", KeyEvent.delete());
        assertSingle("1b5b357e", KeyEvent.of(KeyEvent.Kind.PAGE_UP));
        assertSingle("1b5b367e", KeyEvent.of(KeyEvent.Kind.PAGE_DOWN));
        assertSingle("1b5b327e", KeyEvent.of(KeyEvent.Kind.UNKNOWN));
    }

    @Test
    void shiftTabAndEscapeAlone() {
        assertSingle("1b5b5a", KeyEvent.of(KeyEvent.Kind.SHIFT_TAB));
        assertEquals(List.of(), decoded(decoder, "1b"), "lone ESC waits for the flush");
        assertEquals(List.of(KeyEvent.of(KeyEvent.Kind.ESCAPE)), decoder.flushPending());
        assertEquals(List.of(), decoder.flushPending(), "escape already flushed");
    }

    @Test
    void controlLettersMapToNamedEvents() {
        assertSingle("01", KeyEvent.ctrl('a'));
        assertSingle("03", KeyEvent.ctrl('c'));
        assertSingle("04", KeyEvent.ctrl('d'));
        assertSingle("0c", KeyEvent.ctrl('l'));
        assertSingle("0f", KeyEvent.ctrl('o'));
        assertSingle("12", KeyEvent.ctrl('r'));
        assertSingle("14", KeyEvent.ctrl('t'));
        assertSingle("15", KeyEvent.ctrl('u'));
        assertSingle("17", KeyEvent.ctrl('w'));
    }

    @Test
    void altPrefixedLetterDecodes() {
        assertSingle("1b62", KeyEvent.alt('b'));
        assertSingle("1b66", KeyEvent.alt('f'));
        assertSingle("1b42", KeyEvent.alt('B'));
        assertSingle("1b37", KeyEvent.alt('7'));
    }

    @Test
    void altBackspaceDecodes() {
        assertSingle("1b7f", KeyEvent.altBackspace());
    }

    @Test
    void utf8MultiByteTextDecodes() {
        String expected = "héllo 界 👍";
        byte[] encoded = expected.getBytes(StandardCharsets.UTF_8);
        List<KeyEvent> events = decoder.feed(encoded, encoded.length);
        StringBuilder text = new StringBuilder();
        for (KeyEvent event : events) text.append(event.text());
        assertEquals(expected, text.toString());
    }

    @Test
    void utf8SequenceSplitAcrossFeedsReassembles() {
        byte[] encoded = "界".getBytes(StandardCharsets.UTF_8);
        byte[] first = java.util.Arrays.copyOfRange(encoded, 0, 1);
        byte[] second = java.util.Arrays.copyOfRange(encoded, 1, 2);
        byte[] third = java.util.Arrays.copyOfRange(encoded, 2, 3);
        assertEquals(List.of(), decoder.feed(first, first.length));
        assertTrue(decoder.hasPendingSequence());
        assertEquals(List.of(), decoder.feed(second, second.length));
        assertEquals(List.of(KeyEvent.text("界")), decoder.feed(third, third.length));
        assertFalse(decoder.hasPendingSequence());
    }

    @Test
    void arrowKeySplitAcrossReadBoundariesStaysSilentUntilComplete() {
        assertEquals(List.of(), decoder.feed(bytes("1b"), 1));
        assertEquals(List.of(), decoder.feed(bytes("5b"), 1));
        assertTrue(decoder.hasPendingSequence());
        assertEquals(List.of(KeyEvent.of(KeyEvent.Kind.UP)), decoder.feed(bytes("41"), 1));
        assertFalse(decoder.hasPendingSequence());
    }

    @Test
    void flushPendingEmitsEscapeOnlyForLoneEsc() {
        assertEquals(List.of(), decoder.feed(bytes("1b5b"), 2));
        assertEquals(List.of(), decoder.flushPending(), "mid-CSI must stay buffered");
        assertEquals(List.of(KeyEvent.of(KeyEvent.Kind.RIGHT)),
                decoder.feed(bytes("43"), 1));
        KeyDecoder lone = new KeyDecoder();
        assertEquals(List.of(), lone.feed(bytes("1b"), 1));
        assertEquals(List.of(KeyEvent.of(KeyEvent.Kind.ESCAPE)), lone.flushPending());
    }

    @Test
    void doubleEscapeIsDistinctAndDoesNotConsumeFollowingText() {
        assertEquals(List.of(KeyEvent.of(KeyEvent.Kind.DOUBLE_ESCAPE), KeyEvent.text("a")),
                decoder.feed(bytes("1b1b61"), 3));
        assertEquals(List.of(), decoder.flushPending());
        assertFalse(decoder.hasPendingSequence());
    }

    @Test
    void doubleEscapeSplitAcrossReadsStillDecodes() {
        assertEquals(List.of(), decoder.feed(bytes("1b"), 1));
        assertEquals(List.of(KeyEvent.of(KeyEvent.Kind.DOUBLE_ESCAPE)),
                decoder.feed(bytes("1b"), 1));
        assertFalse(decoder.hasPendingSequence());
    }

    @Test
    void unknownCsiFinalIsDroppedNotGarbage() {
        assertSingle("1b5b39397e", KeyEvent.of(KeyEvent.Kind.UNKNOWN));
        assertEquals(List.of(KeyEvent.of(KeyEvent.Kind.UNKNOWN)),
                decoded(decoder, "1b5b3f317e"),
                "private-mode CSI decodes to a dropped event, never text");
    }

    @Test
    void bracketedPasteWrapsTextBlocks() {
        List<KeyEvent> events = new ArrayList<>();
        events.addAll(decoded(decoder, "1b5b3230307e"));
        events.addAll(decoded(decoder, "6869"));
        events.addAll(decoded(decoder, "0d"));
        events.addAll(decoded(decoder, "1b5b323031"));
        events.addAll(decoded(decoder, "7e"));
        assertEquals(List.of(
                KeyEvent.of(KeyEvent.Kind.PASTE_START),
                KeyEvent.text("hi\n"),
                KeyEvent.of(KeyEvent.Kind.PASTE_END)), events);
    }

    @Test
    void bracketedPasteNormalizesCrLfAndKeepsMultibyte() {
        List<KeyEvent> events = new ArrayList<>();
        events.addAll(decoded(decoder, "1b5b3230307e"));
        byte[] body = "a\r\nbé\r".getBytes(StandardCharsets.UTF_8);
        events.addAll(decoder.feed(body, body.length));
        events.addAll(decoded(decoder, "1b5b3230317e"));
        assertEquals(List.of(KeyEvent.of(KeyEvent.Kind.PASTE_START),
                KeyEvent.text("a\nbé\n"),
                KeyEvent.of(KeyEvent.Kind.PASTE_END)), events);
    }

    @Test
    void strayEscInsidePasteBecomesLiteralText() {
        List<KeyEvent> events = new ArrayList<>();
        events.addAll(decoded(decoder, "1b5b3230307e"));
        events.addAll(decoded(decoder, "781b79"));
        events.addAll(decoded(decoder, "1b5b3230317e"));
        assertEquals(List.of(KeyEvent.of(KeyEvent.Kind.PASTE_START),
                KeyEvent.text("xy"),
                KeyEvent.of(KeyEvent.Kind.PASTE_END)), events,
                "stray escape bytes are dropped rather than injected into text");
    }

    @Test
    void incompletePasteMarkerWaitsForMoreBytes() {
        List<KeyEvent> events = new ArrayList<>();
        events.addAll(decoded(decoder, "1b5b3230307e"));
        events.addAll(decoded(decoder, "61"));
        events.addAll(decoded(decoder, "1b5b32"));
        assertEquals(List.of(KeyEvent.of(KeyEvent.Kind.PASTE_START)), events,
                "paste text flushes only when the block ends");
        events.addAll(decoded(decoder, "30317e"));
        assertEquals(List.of(KeyEvent.of(KeyEvent.Kind.PASTE_START),
                KeyEvent.text("a"),
                KeyEvent.of(KeyEvent.Kind.PASTE_END)), events);
    }

    @Test
    void modifiedArrowParamsStillMapBaseKey() {
        assertSingle("1b5b313b3541", KeyEvent.of(KeyEvent.Kind.UP));
        assertSingle("1b5b367e", KeyEvent.of(KeyEvent.Kind.PAGE_DOWN));
    }
}
