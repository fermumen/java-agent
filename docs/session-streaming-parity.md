# Session and streaming parity

The Java port keeps the fx durability invariants that matter to a compact,
single-process Responses client while deliberately avoiding fx's Gateway event
format and multi-process UI architecture.

## Ported contracts

The primary fx owners are `src/core/session/session_store.zig`,
`src/core/session/session_discovery.zig`, `src/core/session/result_store.zig`, and
`src/core/agent/runtime/tests/interruption_flow.zig`. The Java suite ports these
observable contracts:

- safe session identities and managed-path symlink rejection;
- atomic snapshot replacement with no temporary-file residue;
- serialized same-process and cross-process writers through a managed lock;
- workspace-scoped latest pointers, repair, and cross-workspace isolation;
- newest-first bounded catalogs with corrupt-record isolation;
- independent recovery copies that leave their source unchanged;
- persisted instructions and complete stateless Responses input replay;
- content-addressed image sidecars with MIME, digest, size, symlink, recovery,
  deletion, and deduplication checks;
- stable session-scoped sidecar handles for tool output over 16 KiB, 4 KiB
  UTF-8-safe previews, 64 KiB bounded reads, literal line queries, digest
  verification, recovery copying, and deletion;
- provider-token, credentialed-URL, sensitive-assignment, and structured JSON
  argument masking before model replay, previews, snapshots, and sidecar writes;
- defensive deep copies at persistence and agent boundaries;
- repair of a function call interrupted before its matching output;
- streamed text deltas followed by the canonical `response.completed` object;
- explicit failure for failed, incomplete, or truncated SSE streams.

The relevant Java owners are `SessionStoreTest`, `AgentSessionStateTest`,
`AgentInterruptedRecoveryTest`, `ResponsesStreamingTest`, and
`MainSessionIntegrationTest`, `ToolResultStoreParityTest`,
`AgentToolResultIntegrationTest`, `ToolResultSessionLifecycleTest`, and
`SecretRedactorTest`.

## Storage shape

Snapshots live below `JAVA_AGENT_HOME` (default `~/.java-agent`):

```text
sessions/<session-id>/session.json
sessions/<session-id>/images/<sha256>.<type>
sessions/<session-id>/tool-results/result-<tool>-<call-hash>-<content-hash>.txt
latest/<workspace-hash>.txt
```

The schema is intentionally one bounded JSON snapshot rather than fx's
multi-file event log. It is capped at 8 MiB and is written by atomic rename.
Inline image data URLs are externalized to verified sidecars before that limit is
measured and hydrated when a snapshot is loaded. Large tool outputs are likewise
kept out of session JSON; only their bounded preview and exact retrieval handle
enter Responses history.

## Deliberate limits

This phase does not claim fx's schema migration, artifact manifests, Responses
compaction, or background-process recovery. Those remain separate parity work.
The network transport is native
OpenAI Responses SSE (`stream=true`), not Vercel AI Gateway streaming.
