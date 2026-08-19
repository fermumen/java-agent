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
  with parent-state preconditions, active-work rejection, durable snapshots,
  and cycle rejection, plus cancel/resume/close/reopen lifecycle transitions;
- explicit child-scoped tool capabilities for milestone authorship, active-work
  and declared-name enforcement, per-work name deduplication, actor-scoped
  operation replay, durable notification policy, and nonblocking inspect waits;
- atomic bounded child snapshots containing messages, queued and in-flight
  work, events, tool activity, configuration, relationship, and conversation;
- restart reconciliation of active work to `interrupted`, with execution held
  until explicit resume;
- executable integration coverage proving that a parent Responses agent can
  create a child Responses agent and inspect its completed response;
- bounded trusted parent-turn envelopes for declared milestones and configured
  completed/failed/cancelled events, with deterministic direct-child ordering,
  nested delivery, detach/reparent authorization checks, exact replay until
  acknowledgement, durable cursors, and ephemeral model injection;
- bounded canonical operation fingerprints with exact receipt replay,
  changed-request conflict rejection, atomic persistence, restart recovery,
  eviction, and corrupt-ledger isolation.

The primary Java owners are `SubagentCommandParityTest`,
`SubagentManagerParityTest`, `SubagentPaginationParityTest`,
`SubagentPersistenceParityTest`, `SubagentOperationReplayTest`,
`SubagentToolActivityParityTest`, `SubagentMilestoneParityTest`,
`SubagentParentDeliveryParityTest`, and `MainSubagentIntegrationTest`.

## Remaining subagent work

Interval reports, report-duration stopping, and notification stop conditions
are validated and persisted but are not yet scheduled. Java deliberately uses
the already bounded 32-child snapshot set for relationship discovery instead
of fx's separate paged binary index; attach/detach/reparent, restart recovery,
cycle rejection, and direct-parent delivery are observable parity contracts.
