package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Popup menu state: open/close rules, selection resets, and cycling. */
class SlashMenuTest {
    @Test
    void syncOpensForSlashTokensAndClosesOtherwise() {
        SlashMenu menu = new SlashMenu();
        assertFalse(menu.active());
        menu.sync(true, "/re");
        assertTrue(menu.active());
        assertEquals("/resume", menu.selectedMatch().token);
        menu.sync(false, "/re");
        assertFalse(menu.active());
        assertNull(menu.selectedMatch());
    }

    @Test
    void queryChangeResetsSelectionButSameQueryKeepsIt() {
        SlashMenu menu = new SlashMenu();
        menu.sync(true, "/");
        menu.move(2);
        assertEquals(2, menu.selectedIndex());
        menu.sync(true, "/");
        assertEquals(2, menu.selectedIndex());
        menu.sync(true, "/c");
        assertEquals(0, menu.selectedIndex());
    }

    @Test
    void moveWrapsAtBothEnds() {
        SlashMenu menu = new SlashMenu();
        menu.sync(true, "/s");
        int count = menu.matches().size();
        assertTrue(count >= 2);
        menu.move(-1);
        assertEquals(count - 1, menu.selectedIndex());
        menu.move(1);
        assertEquals(0, menu.selectedIndex());
        menu.move(1);
        assertEquals(1, menu.selectedIndex());
    }

    @Test
    void noMatchesMeansClosed() {
        SlashMenu menu = new SlashMenu();
        menu.sync(true, "/zzz");
        assertFalse(menu.active());
        assertTrue(menu.matches().isEmpty());
    }

    @Test
    void matchesStayCappedAndRanked() {
        SlashMenu menu = new SlashMenu();
        menu.sync(true, "/");
        List<SlashCommands.Match> matches = menu.matches();
        assertEquals(SlashCommands.MAX_MENU_ROWS, matches.size());
        assertEquals("/help", matches.get(0).token);
    }
}
