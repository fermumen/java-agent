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

## Permission rules

The shell remembers persistent, exact-match permission rules bound to the
active saved session, porting fx's `/permissions remember` contract:
`/permissions remember allow|deny <tool-name> <arguments-json>` stores one
rule and confirms its stable numeric id, bare `/permissions` prints the current
mode plus a table of active rules (id, kind, tool, arguments) sorted stably by
id, and `/permissions revoke <id>` removes a rule by exact id with a friendly
message for unknown ids. A rule's identity is the tool name plus the
arguments JSON canonicalized with recursively sorted object keys and compact
serialization, so the same arguments match despite source key order or
insignificant spacing while different values, including whitespace inside
strings, do not. Tool names are matched exactly and case-sensitively. Rules
persist inside the authoritative atomic `session.json` schema-v3 snapshot,
survive restart and resume, belong to exactly one saved session (`/new` starts
with explicit empty state, `/resume` loads that session's state, and `/recover`
copies the source rules to its new session id), and are refused under
`--no-save`. Schema-v1/v2 snapshots migrate missing permission state to
explicit empty state when next saved; schema v3 fails closed when permission
state is missing or corrupt. Recovery stages and validates the complete
snapshot and referenced artifacts before promoting the recovered directory or
changing `latest`. Persistence uses ordinary atomic file replacement and does
not depend on `SecureDirectoryStream`, so sessions remain provider-independent
and portable to Windows. Permission state allows at most 1024 rules, a
256-byte tool name, and a 4096-byte combined exact identity. Stable rule ids
and mutation generation use separate checked counters; exhaustion is rejected
before publication. Only one rule is active for an
exact identity; remembering it again replaces the decision while preserving
the id. JSON command arguments and snapshots reject duplicate keys, trailing
values, and oversized input before tree parsing. Concurrent mutations on one
runtime are serialized, while stale store writers are rejected by persisted
generation. Consultation order wherever approvals are checked is: exact deny
(blocked without prompting), exact allow (approved without prompting),
non-persistent session `always` grants, then the normal prompt flow; yolo
still bypasses everything. Session `always` grants are cleared only after a
successful `/new`, `/resume`, or `/recover`; failed transitions retain them.
Non-yolo subagents capture the owning root session's deny-only projection, so
they preserve exact denies without inheriting remembered allows or `always`
grants and do not follow a later root-session switch. Yolo children bypass the
projection.

Deliberate limits: there are no configured global rules, no
auto-classifier/reviewer, no wildcards or patterns — matching is exact only —
and no command-prefix admission. The fx-only `/permissions ask|auto|yolo|reset`
modes are not implemented; mode stays fixed by CLI flag. The current slash
popup only completes command tokens before the first space, so the nested
remember/revoke entries appear in `/help` but not in a second-stage popup.
Typed nested commands still dispatch through `/permissions` in both raw and
legacy shells. The standalone `permissions` info command has no active saved
session and therefore reports no rule scope; it does not load global rules.
ACP rule enforcement is deliberately out of scope, so ACP remains a known
transport parity gap rather than part of this TUI permission contract.
