# Subagent parity

The Java harness exposes fx's six public `subagent` command branches:
`create`, `inspect`, `message`, `relationship`, `configure`, and `lifecycle`.
Commands use strict branch-specific decoding and return stable structured
receipts containing an operation ID, child ID, status, error code, retryability,
requested projections, and pagination cursor.

## Ported contracts

- asynchronous one-off and persistent children with a bounded worker pool;
- child-specific Responses conversations, models, effort settings, result
  stores, and permission modes clamped to the parent's authority;
- condition-based inspection waits, generation-bound message pagination,
  stale-cursor rejection, events, configuration, relationship, and settled
  tool-activity projections;
- queued messages, configuration changes, attach/detach/reparent operations
  with cycle rejection, and cancel/resume/close/reopen lifecycle transitions;
- explicit child-scoped tool capabilities for milestone authorship, active-work
  and declared-name enforcement, per-work name deduplication, actor-scoped
  operation replay, durable notification policy, and nonblocking inspect waits;
- atomic bounded child snapshots containing messages, queued and in-flight
  work, events, tool activity, configuration, relationship, and conversation;
- restart reconciliation of active work to `interrupted`, with execution held
  until explicit resume;
- executable integration coverage proving that a parent Responses agent can
  create a child Responses agent and inspect its completed response;
- bounded canonical operation fingerprints with exact receipt replay,
  changed-request conflict rejection, atomic persistence, restart recovery,
  eviction, and corrupt-ledger isolation.

The primary Java owners are `SubagentCommandParityTest`,
`SubagentManagerParityTest`, `SubagentPaginationParityTest`,
`SubagentPersistenceParityTest`, `SubagentOperationReplayTest`,
`SubagentToolActivityParityTest`, `SubagentMilestoneParityTest`, and
`MainSubagentIntegrationTest`.

## Remaining subagent work

Notification policy is validated and persisted but is not yet projected into a
parent-session asynchronous notification stream. Relationship state does not
have fx's separate durable index. These limits are not counted as parity yet.
