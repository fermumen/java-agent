package dev.fxjava;

import java.util.List;

/**
 * Slash popup state: the filtered match list, the selected row, and the rule
 * that a query change resets selection. Pure; the shell feeds it composer
 * tokens and reads back rows to render.
 */
final class SlashMenu {
    private String query = "";
    private boolean open;
    private List<SlashCommands.Match> matches = List.of();
    private int selected;

    boolean active() {
        return open;
    }

    /** Re-filters for the given token; opening or changing the query resets selection. */
    void sync(boolean desired, String newQuery) {
        if (!desired) {
            open = false;
            matches = List.of();
            selected = 0;
            query = "";
            return;
        }
        boolean refiltered = !open || !newQuery.equals(query);
        query = newQuery;
        matches = SlashCommands.filter(newQuery);
        open = !matches.isEmpty();
        if (refiltered) selected = 0;
        if (selected >= matches.size()) selected = Math.max(0, matches.size() - 1);
    }

    /** Cycles the selection by delta, wrapping at both ends. */
    void move(int delta) {
        if (matches.isEmpty()) return;
        selected = Math.floorMod(selected + delta, matches.size());
    }

    int selectedIndex() {
        return selected;
    }

    List<SlashCommands.Match> matches() {
        return matches;
    }

    SlashCommands.Match selectedMatch() {
        return open && !matches.isEmpty() ? matches.get(selected) : null;
    }
}
