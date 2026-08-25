package dev.fxjava;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateful byte-to-key-event decoder. Bytes may arrive split across arbitrary
 * read boundaries; incomplete sequences stay buffered until completed. Handles
 * CSI/SS3 escapes, control characters, Alt-prefixed keys, multi-byte UTF-8,
 * and bracketed paste blocks.
 */
final class KeyDecoder {
    private static final String PASTE_END_MARKER = "\u001b[201~";
    private static final int MAX_PARAMS = 32;
    private static final int PASTE_FLUSH_CHARS = 512;

    enum State { GROUND, ESC, CSI, SS3 }

    private State state = State.GROUND;
    private final StringBuilder params = new StringBuilder();
    private final byte[] utf8 = new byte[4];
    private int utf8Have;
    private int utf8Need;
    private final StringBuilder pasteCandidate = new StringBuilder();
    private boolean pasting;
    private final StringBuilder pasteBuffer = new StringBuilder();

    List<KeyEvent> feed(byte[] data, int length) {
        List<KeyEvent> events = new ArrayList<>();
        for (int index = 0; index < length; index++) consume(events, data[index]);
        return events;
    }

    /**
     * Emits Escape when the decoder sits on exactly a lone ESC; longer
     * unfinished sequences stay buffered for the next feed.
     */
    List<KeyEvent> flushPending() {
        List<KeyEvent> events = new ArrayList<>();
        if (state == State.ESC && !pasting) {
            state = State.GROUND;
            events.add(KeyEvent.of(KeyEvent.Kind.ESCAPE));
        }
        return events;
    }

    boolean hasPendingSequence() {
        return state != State.GROUND || utf8Need > 0 || pasteCandidate.length() > 0;
    }

    private void consume(List<KeyEvent> events, byte value) {
        int b = value & 0xff;
        if (pasting) {
            consumePasteByte(events, b);
            return;
        }
        if (utf8Need > 0) {
            consumeUtf8Continuation(events, b);
            return;
        }
        switch (state) {
            case GROUND:
                consumeGround(events, b);
                break;
            case ESC:
                consumeEsc(events, b);
                break;
            case CSI:
                consumeCsi(events, b);
                break;
            case SS3:
                state = State.GROUND;
                mapSs3(events, b);
                break;
            default:
                break;
        }
    }

    private void consumeGround(List<KeyEvent> events, int b) {
        if (b >= 0x80) {
            beginUtf8(b);
            return;
        }
        if (b == 0x1b) {
            state = State.ESC;
            return;
        }
        if (b == '\r' || b == '\n') {
            events.add(KeyEvent.enter());
            return;
        }
        if (b == 0x7f || b == 0x08) {
            events.add(KeyEvent.backspace());
            return;
        }
        if (b == '\t') {
            events.add(KeyEvent.of(KeyEvent.Kind.TAB));
            return;
        }
        if (b < 0x20) {
            events.add(KeyEvent.ctrl((char) ('a' + b - 1)));
            return;
        }
        events.add(KeyEvent.text(String.valueOf((char) b)));
    }

    private void consumeEsc(List<KeyEvent> events, int b) {
        if (b == '[') {
            state = State.CSI;
            params.setLength(0);
        } else if (b == 'O') {
            state = State.SS3;
        } else if (b == 0x1b) {
            // restarted escape: remain waiting
        } else {
            state = State.GROUND;
            if (b == 0x7f || b == 0x08) events.add(KeyEvent.altBackspace());
            else if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')) {
                events.add(KeyEvent.alt((char) b));
            }
        }
    }

    private void consumeCsi(List<KeyEvent> events, int b) {
        if (b == 0x1b) {
            state = State.ESC;
            params.setLength(0);
            return;
        }
        if (b >= 0x30 && b <= 0x3f) {
            if (params.length() >= MAX_PARAMS) resetToGround();
            else params.append((char) b);
            return;
        }
        if ((b < 0x40 || b > 0x7e)) {
            resetToGround();
            return;
        }
        state = State.GROUND;
        String sequenceParams = params.toString();
        params.setLength(0);
        if (b == '~') mapCsiTilde(events, firstParam(sequenceParams));
        else mapCsiFinal(events, b);
    }

    private void mapCsiFinal(List<KeyEvent> events, int b) {
        KeyEvent.Kind kind;
        switch (b) {
            case 'A': kind = KeyEvent.Kind.UP; break;
            case 'B': kind = KeyEvent.Kind.DOWN; break;
            case 'C': kind = KeyEvent.Kind.RIGHT; break;
            case 'D': kind = KeyEvent.Kind.LEFT; break;
            case 'H': kind = KeyEvent.Kind.HOME; break;
            case 'F': kind = KeyEvent.Kind.END; break;
            case 'Z': kind = KeyEvent.Kind.SHIFT_TAB; break;
            default: kind = KeyEvent.Kind.UNKNOWN; break;
        }
        events.add(KeyEvent.of(kind));
    }

    private void mapCsiTilde(List<KeyEvent> events, int value) {
        switch (value) {
            case 1:
            case 7:
                events.add(KeyEvent.of(KeyEvent.Kind.HOME));
                break;
            case 3:
                events.add(KeyEvent.delete());
                break;
            case 4:
            case 8:
                events.add(KeyEvent.of(KeyEvent.Kind.END));
                break;
            case 5:
                events.add(KeyEvent.of(KeyEvent.Kind.PAGE_UP));
                break;
            case 6:
                events.add(KeyEvent.of(KeyEvent.Kind.PAGE_DOWN));
                break;
            case 200:
                pasting = true;
                events.add(KeyEvent.of(KeyEvent.Kind.PASTE_START));
                break;
            case 201:
                endPaste(events);
                break;
            default:
                events.add(KeyEvent.of(KeyEvent.Kind.UNKNOWN));
                break;
        }
    }

    private void mapSs3(List<KeyEvent> events, int b) {
        KeyEvent.Kind kind;
        switch (b) {
            case 'A': kind = KeyEvent.Kind.UP; break;
            case 'B': kind = KeyEvent.Kind.DOWN; break;
            case 'C': kind = KeyEvent.Kind.RIGHT; break;
            case 'D': kind = KeyEvent.Kind.LEFT; break;
            case 'H': kind = KeyEvent.Kind.HOME; break;
            case 'F': kind = KeyEvent.Kind.END; break;
            default: kind = KeyEvent.Kind.UNKNOWN; break;
        }
        events.add(KeyEvent.of(kind));
    }

    private void consumePasteByte(List<KeyEvent> events, int b) {
        if (utf8Need > 0) {
            consumeUtf8Continuation(events, b);
            return;
        }
        if (pasteCandidate.length() > 0) {
            char expected = PASTE_END_MARKER.charAt(pasteCandidate.length());
            if (b == expected) {
                pasteCandidate.appendCodePoint(b);
                if (pasteCandidate.length() == PASTE_END_MARKER.length()) endPaste(events);
                return;
            }
            flushPasteCandidate(events);
        }
        if (b == 0x1b) {
            pasteCandidate.appendCodePoint(b);
            return;
        }
        if (b >= 0x80) {
            beginUtf8(b);
            return;
        }
        appendPasteLiteral(events, b);
    }

    private void endPaste(List<KeyEvent> events) {
        boolean wasPasting = pasting;
        pasting = false;
        pasteCandidate.setLength(0);
        if (!wasPasting) return;
        addTextIfPresent(events, takePasteText());
        events.add(KeyEvent.of(KeyEvent.Kind.PASTE_END));
    }

    /** Releases marker-prefix bytes held while disambiguating ESC from the paste-end marker. */
    private void flushPasteCandidate(List<KeyEvent> events) {
        for (int index = 0; index < pasteCandidate.length(); index++) {
            appendPasteLiteral(events, pasteCandidate.charAt(index));
        }
        pasteCandidate.setLength(0);
    }

    private void appendPasteLiteral(List<KeyEvent> events, int b) {
        if (b == '\t' || b == '\r' || b == '\n' || b >= 0x20) {
            pasteBuffer.appendCodePoint(b);
            if (pasteBuffer.length() >= PASTE_FLUSH_CHARS) {
                addTextIfPresent(events, takePasteText());
            }
        }
    }

    private void beginUtf8(int lead) {
        int need = utf8Length(lead);
        if (need == 0) return;
        utf8[0] = (byte) lead;
        utf8Have = 1;
        utf8Need = need;
    }

    private void consumeUtf8Continuation(List<KeyEvent> events, int b) {
        if ((b & 0xc0) != 0x80) {
            utf8Need = 0;
            utf8Have = 0;
            return;
        }
        utf8[utf8Have++] = (byte) b;
        if (utf8Have != utf8Need) return;
        utf8Need = 0;
        String decoded = new String(utf8, 0, utf8Have, StandardCharsets.UTF_8);
        utf8Have = 0;
        if (pasting) pasteBuffer.append(decoded);
        else events.add(KeyEvent.text(decoded));
    }

    private String takePasteText() {
        String text = normalizeNewlines(pasteBuffer.toString());
        pasteBuffer.setLength(0);
        return text;
    }

    static String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void addTextIfPresent(List<KeyEvent> events, String value) {
        if (!value.isEmpty()) events.add(KeyEvent.text(value));
    }

    private static int firstParam(String sequenceParams) {
        int semicolon = sequenceParams.indexOf(';');
        String first = semicolon < 0 ? sequenceParams : sequenceParams.substring(0, semicolon);
        try {
            return Integer.parseInt(first);
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }

    private void resetToGround() {
        state = State.GROUND;
        params.setLength(0);
    }

    private static int utf8Length(int lead) {
        if (lead >= 0xc2 && lead <= 0xdf) return 2;
        if (lead >= 0xe0 && lead <= 0xef) return 3;
        if (lead >= 0xf0 && lead <= 0xf4) return 4;
        return 0;
    }
}
