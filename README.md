# java-agent

`java-agent` is a small Java 11 coding-agent harness inspired by the
[Vercel Labs `fx` project](https://github.com/vercel-labs/fx). It uses the OpenAI
Responses API directly. It does not contain a Vercel AI Gateway or Chat
Completions transport.

The executable agent's only runtime dependency is Jackson for JSON. HTTP uses
the JDK client, so standard corporate JVM proxy and trust-store settings
continue to apply. A separately built productivity JAR provides document and
data-processing libraries to JShell without coupling them to the agent runtime.

## Features

- Direct `POST /v1/responses` integration
- Structured `ask --json` output with per-turn tool outcomes
- Interactive sessions and one-shot `ask` mode
- Native Responses SSE streaming with incremental text output
- Bounded `Retry-After`/exponential recovery for pre-output 429 and transient 5xx Responses statuses
- Stateless API requests with `store=false`
- Atomic schema-v2 local sessions with v1 compatibility, exact artifact
  manifests, latest/resume/recover, and corrupt-record isolation
- Replay of response output items, including encrypted reasoning content
- All 13 fx filesystem tools: list, glob, grep, read, write, edit, delete,
  rename, copy, create-folder, metadata, lexical semantic search, and Windows open
- Installed-skill discovery plus safe, bounded skill resource reads
- Strict fx-compatible skill metadata and no-auth `skills` list/show/create/remove/local-install commands
- Content-addressed session image sidecars with MIME, digest, size, and symlink verification
- fx-compatible persistent memory plus session-scoped large tool-result previews, bounded paging, and literal search
- fx-compatible masking of provider tokens, credential URLs, and sensitive assignments before model replay or sidecar persistence
- Interactive FX-shaped multiple-choice clarification with a noninteractive sentinel
- FX-shaped interactive terminal UI: raw-mode composer with history, bracketed
  paste, and Ctrl+C semantics; markdown streaming with spinner and live tool
  groups; slash popup menu and `/help` catalog; inline approval boxes with
  session `always` grants; type-ahead replay; and inline ask_user question
  panels answered by digits or option prefixes
- Direct bounded public `web_fetch` with redirect revalidation, HTML text conversion, caching, and credential redaction
- MCP stdio, Streamable HTTP, and deprecated HTTP+SSE tools, live catalog refresh,
  metadata search/selection, resources, negotiated subscribe-on-read with
  filtered updates, prompts, completion, strict validation, health policy,
  atomic local config reload, and no-auth status reporting
- Captured `run_command` execution with a timeout and bounded output
- FX-shaped `terminal` actions for captured exec, bounded background-process
  lifecycles, plain-output screen snapshots, and process-lifetime monitors
- Optional OpenAI-hosted Responses web search (`--web-search`)
- Bounded local `install_skill` with immediate catalog refresh
- Bounded asynchronous subagents with six fx-shaped command branches,
  durable conversations, explicit restart resume, authority clamping, and
  child-authenticated milestones, coalesced interval reports, notification
  stop policies, and durable parent-turn delivery
- ACP v1 stdio mode with durable sessions, incremental Responses output,
  cancellation, model/mode configuration, and bounded JSON-RPC framing
- Read-only `status`, `permissions`, `doctor`, `mcp list`, and paginated `sessions` commands
- `ask`, conservative `auto`, and unrestricted `yolo` permission modes (`--yes` remains an alias)
- Interactive `/permissions ask|auto|yolo` switches the active mode for this run; bare `/permissions` shows the mode and remembered rules
- A bounded agent loop and exact `function_call`/`function_call_output` pairing

## Build

Requirements: JDK 11 or newer and Maven 3.8 or newer.

```sh
mvn clean verify
```

The shaded executable is written to `target/java-agent.jar`.

Build the optional, pure-Java productivity library bundle:

```sh
mvn -f productivity/pom.xml clean verify
```

This writes `productivity/target/productivity.jar` and stages a copy at
`target/productivity.jar` beside the executable agent:

```text
java-agent.jar
productivity.jar
```

The agent's system prompt supplies that absolute path and directs artifact
creation through reusable Java source-file programs, with JShell available for
exploration. Override the location with
`JAVA_AGENT_PRODUCTIVITY_JAR` when the files cannot be colocated. For example:

```sh
jshell --class-path "target/productivity.jar" script.jsh
java --class-path "target/productivity.jar" Script.java
```

The bundle contains Apache POI, PDFBox, Tika Core, Commons CSV/IO/Compress/Lang/
Text/Codec/Math, Jackson JSON and YAML, jsoup, commonmark with GFM tables,
selected TwelveMonkeys ImageIO plugins, XZ, and XChart. It intentionally omits
native/JNI dependencies and Tika's full parser package.

## Configure and run

On Windows, build with Maven and run `java-agent.cmd` from the checkout, or
optionally install a per-user `java-agent` command:

```powershell
mvn package
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\install-user-path.ps1
```

The installer copies the launcher and jar to `%LOCALAPPDATA%\java-agent\bin`
and adds that directory to your user PATH. Open a new terminal afterward.
The `.cmd` launcher uses PowerShell's process-only execution-policy bypass; it
does not change a saved policy. Enforced Group Policy or AppLocker rules can
still block execution. The command keeps the calling directory as the
workspace, so `java-agent ask "Inspect this folder"` works from any directory;
use `--workspace` to select another one.

```sh
export OPENAI_API_KEY="..."
java -jar target/java-agent.jar
```

When started in a terminal without an API key, `java-agent` asks for it with
input hidden, then asks whether to save it as unencrypted text. Press Enter at
the save prompt to keep the key in memory for this run only. Saying yes stores
it in `user-settings.json` under `JAVA_AGENT_HOME` (or `~/.java-agent` by
default); `--session-root` can select another settings root. The file is
unencrypted and uses owner-only permissions on supported filesystems; saving
is refused if private permissions cannot be enforced. The prompt does not
place the key in shell history, conversation history, or logs. Headless runs
do not prompt and fail with an environment-variable hint. Environment keys
take precedence over the saved key.

Select a model or reasoning effort at startup, or change the active session
with `/model <id>` and `/effort <level>`. CLI options override environment
variables, which override explicitly saved preferences:

```sh
java-agent --model gpt-5.6 --effort high ask "Review this project"
export OPENAI_MODEL="gpt-5.6"
export OPENAI_REASONING_EFFORT="medium"
```

`JAVA_AGENT_MODEL` and `JAVA_AGENT_REASONING_EFFORT` are fallback environment
names. The model precedence is `--model`, `OPENAI_MODEL`, `JAVA_AGENT_MODEL`,
saved preference, then `gpt-5.6`. Bare `/model` and `/effort` show the active
values. Add `--save` to a slash command to keep that preference in
`user-settings.json`; environment and CLI options still take precedence on the
next run. `/effort default` omits the
effort field and leaves the provider/model default in effect. Supported effort
values are `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, and `max`; the
provider may reject a value for a particular model.

Run one request:

```sh
java -jar target/java-agent.jar ask "Explain this repository"
```

Manage local skills without an API key:

```sh
java -jar target/java-agent.jar skills list
java -jar target/java-agent.jar skills create review
java -jar target/java-agent.jar skills install C:\path\to\skill-pack --skill=review
```

Inspect configured MCP servers without an OpenAI API key:

```sh
java -jar target/java-agent.jar mcp list
java -jar target/java-agent.jar mcp list --json
```

Permit file mutations and shell commands without interactive confirmation:

```sh
java -jar target/java-agent.jar --yes "Fix the failing tests"
```

Choose a model or an approved corporate OpenAI API proxy:

```sh
export OPENAI_MODEL="gpt-5.6"
export OPENAI_BASE_URL="https://api.openai.com/v1"
java -jar target/java-agent.jar
```

The base URL may be the API base or the complete `/responses` endpoint. Run
`java -jar target/java-agent.jar --help` for all options.

## Data handling

The client sends `store=false` and manages conversation state locally in atomic, workspace-scoped snapshots. Use `--resume last` (or a session ID), `--no-save`, and `JAVA_AGENT_HOME` to
control persistence. It asks
the API to return `reasoning.encrypted_content`, then preserves all response
output items when continuing a conversation or returning tool results. Sensitive
tool arguments and results are masked before durable snapshots, model replay,
previews, and tool-result sidecars. This
supports stateless operation and avoids relying on server-stored response IDs.

Saved sessions checkpoint each completed Responses item set before running its
tool calls, then checkpoint every tool result before starting the next call. If
a turn stops with an unanswered call, recovery records that its outcome may be
uncertain and never runs it again. A failed intent checkpoint prevents the tool
from starting; a failed result checkpoint stops the remaining calls. This
per-tool recovery boundary applies to the root saved session. Child sessions
keep their existing turn-level persistence boundary.

The agent estimates request size before each Responses request and summarizes
older history when it reaches the configured trigger. Defaults are a 96,000
token request budget, an 80% trigger, and a 4,096-token reserve per attached
image. The estimate includes instructions, tool definitions, and injected child
context; it is a conservative estimate rather than provider tokenization.
Override the values with `JAVA_AGENT_CONTEXT_BUDGET_TOKENS`,
`JAVA_AGENT_CONTEXT_TRIGGER_PERCENT`, and `JAVA_AGENT_IMAGE_TOKEN_RESERVE`.
If the current user request and fixed request overhead cannot fit, the agent
fails before issuing a summary or ordinary request.

## Safety boundary

Paths are normalized and canonicalized. Workspace reads run directly; external
paths and symlink escapes require per-call approval, as do mutations, opening
files, and commands. Read views are capped at 50 KiB, prepared mutations at 4
MiB, and command output at 200 KiB. OpenAI API keys are removed from spawned
command environments. Commands still have the authority of the Java process, so
keep confirmation enabled and use your corporate sandbox where appropriate.

## Parity roadmap

Gateway support and further ACP parity are intentionally excluded. The existing
compact ACP mode remains available for compatibility. Crash-recoverable terminal
sessions, full PTY/ANSI screen behavior, richer permission rules, MCP OAuth/fx
multiplexed subscription streams, remote skill sources and full-screen skill
management, richer subagent identity isolation, media tools, and the full-screen
UI remain fx parity work. The implemented terminal boundary is documented in
[`docs/terminal-parity.md`](docs/terminal-parity.md). The interactive
composer, transcript, slash menu, approvals, and ask_user surfaces are covered by
[`docs/ui-parity.md`](docs/ui-parity.md), which also lists their explicit limits; the full-screen
transcript manager stays future work. The session/streaming
subset and its explicit limits are in
[`docs/session-streaming-parity.md`](docs/session-streaming-parity.md), and skills
in
[`docs/skills-parity.md`](docs/skills-parity.md). Subagent coverage is in
[`docs/subagent-parity.md`](docs/subagent-parity.md). ACP coverage is in
[`docs/acp-parity.md`](docs/acp-parity.md). Filesystem
behavior targets Windows and is covered by the Windows
CI workflow.
