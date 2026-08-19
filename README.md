# java-agent

`java-agent` is a small Java 17 coding-agent harness inspired by the
[Vercel Labs `fx` project](https://github.com/vercel-labs/fx). It uses the OpenAI
Responses API directly. It does not contain a Vercel AI Gateway or Chat
Completions transport.

The only runtime dependency is Jackson for JSON. HTTP uses the JDK client, so
standard corporate JVM proxy and trust-store settings continue to apply.

## Features

- Direct `POST /v1/responses` integration
- Structured `ask --json` output with per-turn tool outcomes
- Interactive sessions and one-shot `ask` mode
- Native Responses SSE streaming with incremental text output
- Stateless API requests with `store=false`
- Atomic local sessions with latest/resume/recover and corrupt-record isolation
- Replay of response output items, including encrypted reasoning content
- All 13 fx filesystem tools: list, glob, grep, read, write, edit, delete,
  rename, copy, create-folder, metadata, lexical semantic search, and Windows open
- Installed-skill discovery plus safe, bounded skill resource reads
- Strict fx-compatible skill metadata and no-auth `skills` list/show/create/remove/local-install commands
- Content-addressed session image sidecars with MIME, digest, size, and symlink verification
- fx-compatible persistent memory plus session-scoped large tool-result previews, bounded paging, and literal search
- fx-compatible masking of provider tokens, credential URLs, and sensitive assignments before model replay or sidecar persistence
- Interactive FX-shaped multiple-choice clarification with a noninteractive sentinel
- Direct bounded public `web_fetch` with redirect revalidation, HTML text conversion, caching, and credential redaction
- MCP stdio, Streamable HTTP, and deprecated HTTP+SSE tools, live catalog refresh,
  metadata search/selection, resources, prompts, completion, strict validation,
  health policy, and no-auth status reporting
- Captured `run_command` execution with a timeout and bounded output
- FX-shaped `terminal` actions for captured exec, bounded background-process
  lifecycles, plain-output screen snapshots, and process-lifetime monitors
- Optional OpenAI-hosted Responses web search (`--web-search`)
- Bounded local `install_skill` with immediate catalog refresh
- Bounded asynchronous subagents with six fx-shaped command branches,
  durable conversations, explicit restart resume, and authority clamping
- ACP v1 stdio mode with durable sessions, incremental Responses output,
  cancellation, model/mode configuration, and bounded JSON-RPC framing
- Read-only `status`, `permissions`, `doctor`, `mcp list`, and paginated `sessions` commands
- `ask`, conservative `auto`, and unrestricted `yolo` permission modes (`--yes` remains an alias)
- A bounded agent loop and exact `function_call`/`function_call_output` pairing

## Build

Requirements: JDK 17 or newer and Maven 3.8 or newer.

```sh
mvn clean verify
```

The shaded executable is written to `target/java-agent.jar`.

## Configure and run

```sh
export OPENAI_API_KEY="..."
java -jar target/java-agent.jar
```

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

## Safety boundary

Paths are normalized and canonicalized. Workspace reads run directly; external
paths and symlink escapes require per-call approval, as do mutations, opening
files, and commands. Read views are capped at 50 KiB, prepared mutations at 4
MiB, and command output at 200 KiB. OpenAI API keys are removed from spawned
command environments. Commands still have the authority of the Java process, so
keep confirmation enabled and use your corporate sandbox where appropriate.

## Parity roadmap

Gateway support and further ACP parity are intentionally excluded. The existing compact ACP mode remains available for compatibility. Responses compaction,
crash-recoverable terminal sessions and full PTY/ANSI screen behavior, richer
permission rules, MCP OAuth/filtered subscriptions, remote skill sources
and full-screen skill management, full subagent notification/identity/relationship-index
parity, media tools, and the full-screen UI remain fx parity work. The implemented terminal boundary is documented in
[`docs/terminal-parity.md`](docs/terminal-parity.md). The session/streaming
subset and its explicit limits are in
[`docs/session-streaming-parity.md`](docs/session-streaming-parity.md), and skills
in
[`docs/skills-parity.md`](docs/skills-parity.md). Subagent coverage is in
[`docs/subagent-parity.md`](docs/subagent-parity.md). ACP coverage is in
[`docs/acp-parity.md`](docs/acp-parity.md). Filesystem
behavior targets Windows and is covered by the Windows
CI workflow.
