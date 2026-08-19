# MCP parity

The Java runtime reads fx-compatible local MCP profiles from
`<session-root>/mcp.json` or `--mcp-config <path>`. It implements stdio and MCP
2025-06-18 Streamable HTTP and deprecated HTTP+SSE tool paths without routing traffic through Vercel AI
Gateway.

## Ported contracts

The source owners are `fx/src/builtins/mcp.zig`,
`fx/src/core/mcp/stdio_dispatcher.zig`, and
`fx/src/core/mcp/features/tools.zig`. Java tests cover:

- canonical `{"mcp":{"name":{"type":"stdio","command":[...]}}}` config,
  including string commands, args, environment aliases, enablement, required
  policy, and bounded startup/operation timeouts;
- `initialize` followed by `notifications/initialized`;
- Streamable HTTP JSON and SSE response bodies, HTTPS-or-loopback URL policy, safe custom headers, session and protocol-version propagation, optional session IDs, and best-effort DELETE teardown;
- deprecated HTTP+SSE endpoint discovery with same-origin enforcement, exact 2024-11-05 negotiation, POST message routing, custom headers, and stream-only cleanup;
- Streamable HTTP GET listeners and HTTP+SSE reader notifications that refresh changed tool catalogs without reconnecting, including dynamic search, selection, advertisement, and dispatch;
- bounded HTTP bodies plus chunked CR, LF, and CRLF SSE parsing that returns on a matching event without waiting for EOF;
- strict JSON-RPC 2.0 response envelopes and correlated numeric IDs;
- paginated `tools/list`, deterministic sorting, metadata search, exact selection with next-step schema publication, duplicate tool/cursor
  rejection, and catalog/page limits;
- delegated object input schemas with local `$ref` retention and external
  `$ref` rejection;
- read-only annotations mapped to approval policy;
- paginated resources, templates, and prompts; resource reads, prompt gets, and prompt/resource completion;
- capability-gated `resources/subscribe` after the first successful URI read,
  exact once-per-session subscription deduplication, bounded subscription
  cardinality, thread-safe filtering of `notifications/resources/updated`, and
  capability-gated resource/prompt list-change accounting;
- `tools/call` with exact arguments and preservation of text, image, audio,
  resource, resource-link, structured, and tool-error fields;
- bounded structural validation, result size, media base64, inbound queue, and
  line size;
- safe HTTP session-expiry recovery that reinitializes but does not replay an
  ambiguous request;
- bounded SHA-256 config change detection with replacement transports fully
  initialized before atomic swap, selected tool identity preservation,
  superseded transport cleanup, status refresh, and retry after malformed edits;
- optional-server degradation, required-server failure, API-key stripping, and
  deterministic child-process teardown.
- no-auth `mcp list` JSON/human health views plus interactive `/mcp list`, with
  connection, transport, required policy, negotiated protocol, tool count, and
  listener state; diagnostic inspection reports required-server failures
  without weakening normal startup policy.

The main owners are `McpRuntimeTest`, `McpValidationTest`,
`McpHealthPolicyTest`, `McpHttpRuntimeTest`, `McpHttpListenerTest`,
`McpLegacyHttpSseTest`, and
`MainMcpIntegrationTest`, plus `McpStatusCommandTest` for the status surfaces.

## Remaining MCP work

MCP OAuth/credential storage, fx's multiplexed modern
`subscriptions/listen` channel, automatic expired-request replay, and server
elicitation remain unimplemented. These are not counted as parity yet. Standard
MCP resource subscriptions are implemented; a recovered HTTP session subscribes
again after its next successful resource read.
