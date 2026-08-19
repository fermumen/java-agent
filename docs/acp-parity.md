# ACP parity

`java-agent acp` runs a newline-delimited Agent Client Protocol v1 server over
stdin/stdout. It adapts ACP directly to the native OpenAI Responses client and
the same durable local sessions used by interactive mode; no Gateway transport
or event format is involved.

## Ported contracts

- strict JSON-RPC 2.0 requests with integer, string, and null IDs;
- canonical parse, invalid-request, method, parameter, internal, and oversized
  frame errors without poisoning the connection;
- fx's 8 MiB frame boundary, fragmented-frame draining semantics, CR/LF
  handling, and unterminated-EOF behavior;
- initialization gating and ACP v1 agent, prompt, session, and honest MCP
  capability publication;
- `session/new`, `load`, `resume`, `close`, `list`, `prompt`, `cancel`,
  `set_mode`, and `set_config_option`;
- fx-shaped model and `code`/`ask` configuration options and session summaries;
- asynchronous prompt execution, incremental `agent_message_chunk` updates,
  busy-prompt admission, request and notification cancellation, and worker join
  on stdin shutdown;
- text-only prompt validation, with image/audio rejection before conversation
  mutation as advertised during initialization;
- durable model changes, load-time history replay, permission-context rebuilds,
  and model/mode changes staged before live-agent replacement;
- a production entry-point test spanning ACP stdio, native Responses SSE, and
  an atomic session snapshot without stderr protocol contamination.

The primary Java owners are `AcpProtocolParityTest`, `AcpFramingParityTest`,
`AcpCancellationParityTest`, `MainAcpIntegrationTest`, and
`AcpModelReconfigurationIntegrationTest`.

## Remaining ACP work

ACP-supplied MCP servers, direct permission requests and retained grants,
elicitation, client filesystem/terminal delegation, tool-call progress and
structured command-result updates, history updates during load, the full
available slash-command catalog, session removal, provider recovery metadata,
embedded resource prompt blocks, and subagent parent-delivery notifications
remain unimplemented. Initialization therefore does not advertise ACP MCP or
image/audio capabilities that the adapter cannot currently honor.
