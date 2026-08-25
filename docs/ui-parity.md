# Interactive UI parity

The raw-mode shell ports the inline, composer-first subset of fx's terminal
experience (`src/ui/`): the transcript, prompts, approvals, and question
panels stay in the normal buffer and scrollback, styled with fx's
deliberately small ANSI subset through a single `Ansi` helper. This document
records exactly what the interactive UX covers today and where it stops short
of the full-screen manager.

## Ported contracts

- Raw mode entry: `System.console()` present and `TERM` not `dumb`; `stty`
  must save the exact prior state (`stty -g`) before `raw -echo`, and any
  failure falls back cleanly to the line-mode shell banner and prompt.
- Composer editing: printable insert, Backspace/Delete, Left/Right, Home/End,
  Ctrl+A/E/B/F/D/K/U/W word-and-line kills, Alt+B/F/D/Backspace word motion,
  multi-row visual wrapping with exact cursor placement.
- History recall: Up/Down/Ctrl+P/N walk persisted entries at the row edges,
  preserve an in-progress draft, and deduplicate consecutive repeats; history
  lives one-entry-per-line at `<session-root>/prompt_history.txt`, bounded to
  the most recent 500 entries, saved best-effort after each submitted prompt.
- Bracketed paste: `?2004` enabled for the session and disabled on exit; pasted
  text lands as one insert instead of keystrokes.
- Ctrl+C semantics: first press during a generation cancels it and prints
  `^C cancelled`; otherwise it clears the draft once, and a second press
  within 1.5 s exits the shell.
- Welcome and status chrome: a bold two-line welcome (version, model,
  workspace, session id over a dim key-hint row) and a dim status hint above
  the prompt showing model, permission mode, and a truncated session id.
- Transcript rendering: braille spinner with elapsed seconds while nothing new
  shows, live tool-group lines rewritten in place from a running dot to a
  check or cross plus dim preview, and assistant text rendered block-by-block
  as complete lines arrive.
- Markdown subset: bold ATX headings, fenced code between dim rules with a
  right-aligned language label, colored inline code, bold/italic spans that
  compose so emphasis nests inside list items and table cells, hanging-indent
  bullet and ordered lists, aligned pipe tables whose columns shrink to fit
  the terminal width with graceful ellipsis truncation and a dim separator
  row, dim full-width horizontal rules, blockquotes behind a dim bar, and
  greedy word wrap to the terminal width.
- Slash registry: ranked completion matches (exact, prefix, fuzzy) capped at
  eight popup rows with a bold selected row and dim descriptions; Tab and
  Shift+Tab cycle, Enter completes without submitting, Esc dismisses until
  the next edit; `/help [query]` prints the wrapped command catalog.
- Approval flow: external reads, mutations, opens, and commands render as a
  bordered inline box with `y/n/a` keys, Esc or Enter denying; `always`
  grants are session-scoped memory keyed by tool plus normalized preview and
  never persist beyond the process.
- ask_user_question panels: bold question text, numbered options with dim
  descriptions hanging beneath, a `Choose 1-N:` prompt answered by digits or
  case-insensitive label prefixes; an invalid answer earns one dim warning
  and re-prompt, then the batch cancels into the tool's existing cancelled
  result. Answers route through the shell's main loop, so only the shell ever
  reads stdin while generating.
- Type-ahead: printable keys typed during a generation queue up to 4096
  characters and replay into the composer when the turn ends.
- Color-off behavior: `NO_COLOR` (or a non-TTY sink) drops every SGR sequence;
  layout, wrapping, table alignment, and panel geometry are identical.

## Deliberate limits

This phase does not claim fx's alternate-screen owners: there is no
full-transcript screen manager, permission review screen, catalog menu,
subagent manager, or hosted terminal takeover — everything renders inline.
Resize handling polls `stty size` behind a throttle instead of receiving
SIGWINCH. The composer has no image previews or paste-token spans; pastes
insert as plain text. The slash popup caps at eight rows with no scrolling
window. The markdown parser is deliberately shallow (no setext headings,
nested lists, alignment colons, or reference links), and during generation
every decoded key except Ctrl+C and printable text is dropped rather than
queued. Those remain parity work alongside the full-screen UI.
