package dev.fxjava;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Composer editing semantics: cursor motion, word ops, kills, and UTF-16 safety. */
class ComposerTest {
    private final Composer composer = new Composer();

    @Test
    void insertPlacesCursorAfterText() {
        composer.insert("hello");
        assertEquals("hello", composer.text());
        assertEquals(5, composer.cursor());
        composer.insert(" world");
        assertEquals("hello world", composer.text());
        assertEquals(11, composer.cursor());
    }

    @Test
    void insertNormalizesCarriageReturns() {
        composer.insert("a\r\nb\rc");
        assertEquals("a\nb\nc", composer.text());
    }

    @Test
    void backspaceDeletesCodePointsNotSurrogateHalves() {
        composer.insert("a👍b");
        composer.backspace();
        assertEquals("a👍", composer.text());
        assertEquals(3, composer.cursor());
        composer.backspace();
        assertEquals("a", composer.text());
        assertEquals(1, composer.cursor());
        composer.backspace();
        assertEquals("", composer.text());
        assertEquals(0, composer.cursor());
        composer.backspace();
        assertEquals("", composer.text());
    }

    @Test
    void deleteForwardRemovesAtCursor() {
        composer.setText("abc");
        composer.moveTo(1);
        composer.deleteForward();
        assertEquals("ac", composer.text());
        assertEquals(1, composer.cursor());
    }

    @Test
    void leftRightRespectSurrogatePairs() {
        composer.setText("a👍");
        assertEquals(3, composer.cursor());
        composer.left();
        assertEquals(1, composer.cursor());
        composer.left();
        assertEquals(0, composer.cursor());
        composer.right();
        assertEquals(1, composer.cursor());
        composer.right();
        assertEquals(3, composer.cursor());
        composer.right();
        assertEquals(3, composer.cursor());
    }

    @Test
    void homeEndAndLineWiseMotion() {
        composer.setText("one\ntwo");
        composer.moveTo(5);
        composer.moveToLineEnd();
        assertEquals(7, composer.cursor());
        composer.moveToLineStart();
        assertEquals(4, composer.cursor());
        composer.end();
        assertEquals(7, composer.cursor());
        composer.home();
        assertEquals(0, composer.cursor());
    }

    @Test
    void wordMotionMatchesReadline() {
        composer.setText("alpha beta  gamma");
        composer.end();
        composer.moveWordLeft();
        assertEquals(12, composer.cursor(), "start of gamma");
        composer.moveWordLeft();
        assertEquals(6, composer.cursor(), "start of beta");
        composer.moveWordLeft();
        assertEquals(0, composer.cursor());
        composer.moveWordLeft();
        assertEquals(0, composer.cursor());
        composer.moveTo(0);
        composer.moveWordRight();
        assertEquals(5, composer.cursor(), "end of alpha");
        composer.moveWordRight();
        assertEquals(10, composer.cursor(), "end of beta");
        composer.moveWordRight();
        assertEquals(17, composer.cursor());
    }

    @Test
    void wordStartBeforeHandlesWhitespaceRuns() {
        Composer other = new Composer();
        other.setText("foo   bar");
        other.end();
        assertEquals(6, other.wordStartBefore(other.cursor()));
        assertEquals(0, other.wordStartBefore(3));
    }

    @Test
    void killToEndOfLineStopsAtNewline() {
        composer.setText("keep\nkill me");
        composer.moveTo(2);
        composer.moveToLineEnd();
        assertEquals(4, composer.cursor());
        assertEquals("", composer.killToEndOfLine(), "at line end there is nothing to kill");
        assertEquals("keep\nkill me", composer.text());
        composer.moveTo(0);
        assertEquals("keep", composer.killToEndOfLine());
        assertEquals("\nkill me", composer.text());
        assertEquals(0, composer.cursor());
    }

    @Test
    void killToLineStartClearsBeforeCursor() {
        composer.setText("abc\ndef");
        composer.moveTo(5);
        assertEquals("d", composer.killToLineStart());
        assertEquals("abc\nef", composer.text());
        assertEquals(4, composer.cursor());
    }

    @Test
    void killWordBackRemovesPrecedingWord() {
        composer.setText("alpha beta ");
        String killed = composer.killWordBack();
        assertEquals("beta ", killed);
        assertEquals("alpha ", composer.text());
        assertEquals(6, composer.cursor());
        assertEquals("alpha ", composer.killWordBack());
        assertEquals("", composer.killWordBack());
    }

    @Test
    void killWordForwardRemovesFollowingWord() {
        composer.setText("kill me later");
        composer.moveTo(5);
        assertEquals("me", composer.killWordForward());
        assertEquals("kill  later", composer.text());
    }

    @Test
    void moveToClampsInsideSurrogatePairs() {
        composer.setText("a👍b");
        composer.moveTo(2);
        assertEquals(1, composer.cursor());
        composer.moveTo(99);
        assertEquals(4, composer.cursor());
        composer.moveTo(-4);
        assertEquals(0, composer.cursor());
    }
}
