# Terminal UI notes

Why the interactive shell's terminal layer looks the way it does, how to
diagnose it on a user's machine, and what is still open. `ui-parity.md` lists
the shipped contracts; this file keeps the reasoning behind them.

## Raw mode on Windows

Windows has no `stty`, so `RawTerminal.open()` delegates to `WindowsConsole`,
which needs the Win32 console API (`Get/SetConsoleMode`,
`GetConsoleScreenBufferInfo`). Java 11 cannot call it directly, and each route
to it failed somewhere before the current chain held:

| Route | Where it breaks |
| --- | --- |
| JNA (`jnidispatch.dll` unpacked to `%TEMP%`) | IBM Semeru 11.0.14 (OpenJ9): the DLL loads, then the first native call fails with `UnsatisfiedLinkError: com/sun/jna/Native.getNativeVersion()`. `jnidispatch.preserve` and `jna.tmpdir` do not help. Root cause unknown (OpenJ9 or endpoint security). |
| `jdk.internal.le` (`JdkConsoleApi`, JLine 3's `Kernel32Impl` over `le.dll`, as jshell uses) | Exists in JDK 11.0.14 through 21, not in mainline JDK 22+. Only `jdk.jshell` requires the module, so a **JRE without jshell ships it but never resolves it**, which looks exactly like a missing class. A jar manifest can `Add-Opens` (silently ignored when the module or package is absent) but cannot add modules. |

So the chain is: `JdkConsoleApi` first, opened by the manifest and resolved by
`launch-java-agent.ps1`, which passes `--add-modules jdk.internal.le` only when
the runtime's `release` file lists it (naming a missing module aborts JVM
startup); JNA second; a clear startup error if neither binds. There is no
line-mode prompt fallback.

Other constraints the backend relies on:

- The JDK's `System.in.available()` on a Windows console counts key events
  only up to the last Enter, so it stays 0 while typing. Keys come from a
  daemon reader thread into `QueuedInputStream`, whose `available()` the
  generation loop polls.
- Console modes belong to the console, not the process, so a crash that skips
  the restore leaves the tab raw; the shutdown hook covers normal exits.
- The JDK path reads raw stdin bytes and relies on the launcher's UTF-8 input
  code page; JNA reads UTF-16 with `ReadConsoleW`. JLine's own event-record
  reader was avoided because its native code writes `int`s into `char` fields.

## Diagnosing a raw-mode startup failure

- The interactive shell requires raw mode. If it cannot start, it prints the
  reason and points to `java-agent doctor` or a one-shot `java-agent ask` request;
  it never switches silently to a different UI.
- `java-agent doctor` adds a `terminal` check: it enters and restores raw mode
  and names the binding (`console API: JDK jdk.internal.le` or `JNA (… skipped:
  why)`), even when no console is attached.
- For JNA, `JAVA_TOOL_OPTIONS=-Djna.debug_load=true -Djna.debug_load.jna=true`
  logs which `jnidispatch` file was loaded.
- Windows CI checks the shaded jar binds the JDK console API, and jlinks a
  runtime without `jdk.jshell` to check the launcher's `--add-modules` path.
  CI has no interactive console, so raw input itself is only verified by hand.

## Rendering lessons

- Erase routines must leave the cursor at the top of what they erased; leaving
  it on the last row stranded blank gaps above every submitted prompt and
  duplicated the running tool line after approvals. Unit tests asserted the
  bytes and missed it; `tools/ui-screenshots` caught it.
- The composer box stops one column short of the margin so its right border
  never enters the terminal's pending-wrap state.
- Muted chrome uses 256-color grays (`Ansi.gray`) rather than SGR dim, which
  renders inconsistently across terminals.
- Idle Windows reads have a timeout so a resize can trigger a frame without
  keyboard input. Size probes remain throttled and unchanged sizes do not paint.
  The terminal reflows the old frame before the next render; account for its
  extra physical rows before erasing, and strip SGR before counting cell widths.
  Counting colored bytes as cells over-erased history and misplaced the cursor.
  Shrink/grow and session-picker frames are checked with the xterm replay harness.
- The shimmer advances one cell per 200 ms independently of the braille
  spinner's 80 ms frames. Sharing the faster frame step made the label sweep
  feel rushed in Windows Terminal. Both animations use elapsed time, not ticks.

## Open work

- Linux and macOS: `stty raw` turns off OPOST, so every `println` (a bare LF)
  staircases. Windows is unaffected because `println` emits CRLF. Fix by
  translating LF to CRLF on the raw-mode output stream.
- JDK 22+ on Windows lacks `Kernel32Impl`; such runtimes rely on JNA. A
  `java.lang.foreign` binding (reflective, since the build targets 11) would
  remove the native dependency there.
- Approval previews collapse after six rows, so long BeanShell scripts are
  approved partly unseen; an expand key would close that gap.
- Restore failures from `SetConsoleMode` are ignored, and a blocked console
  read is not cancelled on close (harmless today because the process exits).
- Not yet checked by hand on Windows: accented input on the JDK path, resize
  redraw, and exact mode restoration after Ctrl+C and errors.
