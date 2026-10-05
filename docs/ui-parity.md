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
  workspace, session id over a dim key-hint row), a rounded muted border
  around the composer (cyan `›` chevron, hanging indent on wrapped rows, a
  muted placeholder while empty, one column short of the margin), and a dim
  status hint beneath it showing model, permission mode, and a truncated
  session id; the slash menu opens between the box and the hint.
- Transcript rendering: a braille spinner plus a `Thinking…` label (or
  `Compacting…`) whose letters shimmer under a bright band sweeping across
  muted 256-color grays, with elapsed seconds; live tool-group lines rewritten
  in place from a muted running dot to a check or cross, keeping the muted
  preview and adding the tool's duration; assistant text rendered
  block-by-block as complete lines arrive; and a compact muted usage line
  after each turn (`↑ 1.2k ↓ 340 · 12s`). The line-mode loop keeps its plain
  `tokens:` line for scripts.
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
  bordered inline box with `y/n/a` keys, Esc or Enter denying; previews
  longer than six wrapped rows show five and a muted `… N more rows` line; `always`
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
Subagents consult the owning root session's live deny-only rules, so they
preserve exact denies without inheriting remembered allows or `always` grants
and do not follow a later root-session switch. The current parent mode clamps
child approvals dynamically; yolo children bypass the projection only while
that mode is active.

The interactive shell supports `/permissions ask|auto|yolo` for the current
run. `/permissions yolo` bypasses approval checks and remembered rules;
`auto` approves only tools that pass their safe-action check and denies other
approval-required actions. Switching back to `ask` or `auto` restores checks
against the active root session's rules. Child approvals follow the current
parent mode and owning session's denies, so lowering from yolo does not leave
inherited full privilege in effect. The slash popup completes command tokens before the first space;
`/permissions ask`, `/permissions auto`, and `/permissions yolo` are listed in
`/help` and appear as completions. Nested remember/revoke arguments remain
documented in `/help` and dispatch through `/permissions` in both raw and
legacy shells. The standalone `permissions` info command has no active saved
session and therefore reports no rule scope; it does not load global rules.
ACP permission behavior remains controlled by its own client authorization
boundary and is not changed by this interactive command.

Deliberate limits: there are no configured global rules, no
auto-classifier/reviewer, no wildcards or patterns — matching is exact only —
and no command-prefix admission.

## Usage tracking

Every completed turn reports token usage, porting fx's Responses usage parse
(`responses_protocol.zig`): `input_tokens` and `output_tokens` are read from
the `response.completed` payload's final `usage` object; absent, non-numeric,
or negative fields count as zero, so providers without usage stay silent-safe.
The per-turn totals reach the UI through a default `TurnListener.onUsage`
method (existing listeners are unaffected) and accumulate across tool steps
within one turn. After each completed generation the raw shell prints one dim
line (`tokens: 1234 in · 567 out · 1801 total`) once the spinner is fully
erased so frames never tear; the legacy loop prints the same line plain, and
`ask --json` omits it to stay machine-readable. Cancelled or failed turns
print nothing.

Totals are cumulative per saved session and durable: they ride inside the
authoritative atomic `session.json` snapshot as an embedded `usage_state`
object beside permission state, written under the store lock in the same
write as the conversation. Usage deltas fold into the on-disk totals at save
time, so rule mutations and usage updates in one process never lose either
change. Failed turns still count: the agent finalizes its per-turn
accumulator even when a turn dies mid-flight (step-limit exhaustion, provider
errors, Ctrl+C), so tokens already consumed by completed steps fold into the
persisted totals through the same failure path, while the UI tokens line
stays suppressed for anything but successful turns. `/compact` meters its own
summarization round-trip into the same totals. Snapshots written before usage
tracking have no `usage_state` field; they load as zeroed totals and gain
explicit state when next saved, while a present but invalid field — including
an explicit JSON null — fails closed instead of dropping history. Totals
survive restart, resume, and recovery (recovered sessions copy the source's
usage). Under `--no-save` the active session still tracks in-memory totals.

Subagent child sessions run their own model loops, and their token spend does
not yet contribute to the parent session's totals; child usage is visible
only to the child (and in provider-side accounting) until forwarding is wired.

`/stats` (General category, raw and legacy shells) prints the active session
line, an all-time line summed across saved sessions on disk — when more than
200 sessions exist it says "most recent 200 of N" rather than implying
completeness — and a per-session breakdown of the ten most recent saved
sessions for this workspace with the active session starred; a breakdown that
cannot be loaded reports itself unavailable (it stays best-effort) instead of
claiming no breakdown exists. Anything other than bare `/stats` earns a usage
line.

## Conversation compaction

`/compact` (Session category, raw and legacy shells) ports fx's
compacted_summary history turns: conversations below six items — or without
more exchanges than the three kept verbatim — are refused with a friendly
count. Otherwise exactly one extra model round-trip runs through the
non-streaming client seam with no tools and nothing appended to durable
conversation state: the transcript rendering folds any prior summary text
ahead of a `[user]`/`[assistant]`/`[tool call]`/`[tool result]` rendering,
bounded to 100 KiB by dropping oldest conversation lines first — the prior
summary block itself is exempt from the budget (only conversation lines are
trimmed against the remaining room), and a summary too large for even its
reserved space is truncated with an explicit marker instead of being dropped.
The summarization round-trip's own token usage folds into the session totals.
On success the agent input history is rebuilt as one leading
`{"type":"compacted_summary","summary_text":...,"compaction_count":N,
"removed_item_count":M}` item plus the three most recent user/assistant/tool
exchanges kept verbatim (tool call/output pairs never split), persisted
before the live conversation is swapped so a persist failure leaves the
session untouched, and confirmed with `Compacted: 24 → 6 items · compaction
#1`. `removed_item_count` and `compaction_count` accumulate across folds, and
the persisted write is store-level authoritative: it re-reads current rules
and usage under the lock. The summary item stays on the
history layer only (like fx): every Responses request projects it into a plain
`{"role":"user","content":"[Summary of earlier conversation]\n..."}` message on
the request copy, so the wire never carries the custom type. Re-compacting folds the old
summary text into the summarizer prompt and increments the count inside the
new item. Failures print `java-agent: compaction failed: ...` and leave the
conversation byte-identical; Ctrl+C during the raw shell's compaction
interrupts the worker exactly like generation and changes nothing. Under
`--no-save` compaction works in memory only and says persistence is disabled.
