# Terminal parity

The Java `terminal` tool now advertises the same twelve public actions as fx:
`exec`, `start`, `read`, `screen`, `write`, `wait`, `monitor`, `inspect`,
`list`, `resize`, `signal`, and `close`. Its action-specific field ownership,
required fields, null-placeholder handling, 64 KiB command/write bounds,
session IDs, byte cursors, and structured success/failure envelopes are ported
from `fx/src/tools/terminal/terminal.zig` and
`fx/src/core/terminal/contracts.zig`.

Pure Java 17 provides portable child-process pipes but no native PTY. The
implemented actions are:

- `exec`, which retains the bounded captured-command behavior;
- `start`, with native shell processes and `started`, `exit`, `match`, and
  `quiet` return conditions;
- cursor-based `read` and bounded output retention;
- bounded `screen` snapshots for the faithfully renderable plain ASCII subset,
  including cursor position, wrapping, scrolling, tabs, and backspace;
- text, paste, named-key, and control `write` payloads;
- background monitors with fx's 13 condition kinds, polling/event-driven
  schedule distinction, add/update/pause/resume/remove operations, stable IDs,
  bounded event replay/acknowledgement, duration expiry, workspace-bounded path
  probes, and approval-visible TCP/HTTP/custom probes;
- `wait`, `inspect`, `list`, logical `resize`, `signal`, and `close`.

`screen` returns `screen_unavailable` when output was truncated or contains
terminal state that a pipe cannot faithfully reconstruct (ANSI escapes,
Unicode-width behavior, or an oversized grid). Screen-pattern monitors therefore
cover only the same plain-output subset. The `tmux` backend returns
`unsupported_host`. Background sessions, monitor definitions, and monitor
events currently survive only for the lifetime of the Java process; crash/restart
recovery and reconnectable stdin remain parity work. This is
reported explicitly because a pipe-backed process is not a PTY and must not be
presented as one.
