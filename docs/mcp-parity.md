# MCP parity

The Java runtime reads fx-compatible local MCP profiles from
`<session-root>/mcp.json` or `--mcp-config <path>`. It implements stdio and MCP
2025-06-18 Streamable HTTP tool paths without routing traffic through Vercel AI
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
- bounded HTTP bodies plus chunked mixed-CR/LF SSE parsing that returns on a matching event without waiting for EOF;
- strict JSON-RPC 2.0 response envelopes and correlated numeric IDs;
- paginated `tools/list`, deterministic sorting, metadata search, exact selection with next-step schema publication, duplicate tool/cursor
  rejection, and catalog/page limits;
- delegated object input schemas with local `$ref` retention and external
  `$ref` rejection;
- read-only annotations mapped to approval policy;
- paginated resources, templates, and prompts; resource reads, prompt gets, and prompt/resource completion;
- `tools/call` with exact arguments and preservation of text, image, audio,
  resource, resource-link, structured, and tool-error fields;
- bounded structural validation, result size, media base64, inbound queue, and
  line size;
- safe HTTP session-expiry recovery that reinitializes but does not replay an
  ambiguous request;
- optional-server degradation, required-server failure, API-key stripping, and
  deterministic child-process teardown.

The main owners are `McpRuntimeTest`, `McpValidationTest`,
`McpHealthPolicyTest`, and `MainMcpIntegrationTest`.

## Remaining MCP work

MCP OAuth/credential storage, GET listeners and subscriptions, deprecated
HTTP+SSE, automatic expired-request replay, server elicitation, live reload,
and health/status commands remain unimplemented. These are not counted as
parity yet.
