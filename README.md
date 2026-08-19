# java-agent

`java-agent` is a small Java 17 coding-agent harness inspired by the
[Vercel Labs `fx` project](https://github.com/vercel-labs/fx). It uses the OpenAI
Responses API directly. It does not contain a Vercel AI Gateway or Chat
Completions transport.

The only runtime dependency is Jackson for JSON. HTTP uses the JDK client, so
standard corporate JVM proxy and trust-store settings continue to apply.

## Features

- Direct `POST /v1/responses` integration
- Interactive sessions and one-shot `ask` mode
- Stateless API requests with `store=false`
- Replay of response output items, including encrypted reasoning content
- Workspace-scoped `list_files`, `read_file`, `grep_files`, `write_file`, and
  `edit_file` tools
- Captured `run_command` execution with a timeout and bounded output
- Confirmation before writes, edits, and commands; `--yes` for unattended runs
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

The client sends `store=false` and manages conversation state locally. It asks
the API to return `reasoning.encrypted_content`, then preserves all response
output items when continuing a conversation or returning tool results. This
supports stateless operation and avoids relying on server-stored response IDs.

## Safety boundary

File paths are normalized and checked against the real workspace root. Existing
symlinks cannot be used to escape that root. Reads are limited to 1 MB, tool
output is bounded, and shell commands time out. OpenAI API keys are removed from
spawned command environments. Commands still have the authority of the Java
process, so keep confirmation enabled and use your corporate sandbox where
appropriate.

## Deliberately out of scope

The reference `fx` project also includes a full-screen terminal UI, persisted
sessions, ACP, MCP, skills, subagents, web tools, OAuth login, automatic permission
review, streaming, and extensive recovery logic. This basis concentrates on a
readable Java Responses agent loop suitable for extension inside a JVM-only
environment.
