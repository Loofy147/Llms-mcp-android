# Q1 Control-Plane Schema Critique v0.3

Status: Review required / not accepted
Date: 2026-09-06

## 1. Scope

This document critiques `Q1_CONTROL_PLANE_SCHEMA_v0.3.md` before implementation. The schema is a candidate mapping of the semantic contract to SQLite; it is not evidence of correctness.

## 2. Finding C-01 — one database file is necessary but not sufficient

Putting Run, Approval, Claim, Group, and Effect rows in one SQLite database gives the backend a real atomic transaction boundary. It does not automatically make the state machine correct.

The application still needs:

- explicit transition predicates;
- invariant-preserving SQL transactions;
- recovery rules;
- tests that reopen the database after injected failure.

## 3. Finding C-02 — `RUNNING` still conflates admission and active execution

The schema contains a durable execution claim, but the public Run state remains `RUNNING` immediately after admission.

This can be acceptable only if the claim itself is the authoritative admission record and recovery can distinguish:

```text
claimed but not started
started
returned/awaiting terminalization
unknown external effect
```

Therefore an implementation should prefer an explicit execution-phase field or derived claim phase rather than assuming `RUNNING` means the capability has started.

## 4. Finding C-03 — APPROVED is not itself an execution state

`APPROVED` may safely mean that the approver accepted the exact bound plan. It must not mean that execution happened or even that a capability started.

The execution claim is the admission primitive. The approval record and claim must be linked by the same transaction.

## 5. Finding C-04 — released claims need precise semantics

`RELEASED` is underspecified.

Possible meanings include:

```text
normal terminal completion
explicit cancellation
expired lease
recovery handoff
claim superseded by reconciliation
```

These cannot be collapsed if recovery behavior differs. Prefer a small explicit lifecycle with semantics proven by tests.

## 6. Finding C-05 — owner identity should not imply trust

`owner_id` identifies an execution owner or worker instance. It is not authentication and must not be treated as proof that the owner is authorized to perform the action.

Authorization remains a separate control-plane decision.

## 7. Finding C-06 — group state should be derived carefully

`effect_groups.state` creates a second representation of information also present in effect rows.

This is useful for efficient recovery, but it creates a denormalized aggregate whose invariants must be enforced. The implementation should either:

1. derive group state from effect rows, or
2. store it and enforce every state transition transactionally.

Do not allow the two representations to drift.

## 8. Finding C-07 — zero-effect Actions need explicit semantics

An Action with zero capabilities has no effect reservation group.

The state machine must define whether such an Action can go directly:

```text
WAITING_APPROVAL -> RUNNING -> TERMINAL
```

without claim/group rows, or whether every Run receives a claim even with zero effects. The choice must be deterministic and tested.

## 9. Finding C-08 — idempotency identity must remain capability-specific

The schema stores both `effect_id` and optional `idempotency_key`. The implementation must define the canonical digest separately from the database schema and bind it to the exact operation semantics.

Do not use a raw user-provided key as a global primary identity.

## 10. Finding C-09 — constraints must encode as much safety as practical

Required database-level enforcement should include:

```text
foreign_keys = ON
unique claim per Run
unique claim per Approval
unique invocation identity
effect belongs to exactly one group/run/claim
terminal timestamp/status consistency where expressible
```

Where SQLite CHECK constraints cannot express the complete invariant, the transaction protocol remains responsible.

## 11. Finding C-10 — transaction boundary should be one database transaction

The approval resolution transaction should update all local control-plane state together:

```text
approval
execution claim
run execution admission
reservation group
effects
```

No second file, network call, UI callback, or external executor call may be required for the local commit to become valid.

## 12. Finding C-11 — recovery must never synthesize success

A durable row saying:

```text
RUNNING + CLAIMED
```

does not prove an external capability completed.

The safe default for an externally effectful operation after ambiguous execution is:

```text
UNKNOWN / RECONCILIATION_REQUIRED
```

unless the capability has an independently verifiable local completion record or an external idempotency/status query proves completion.

## 13. Finding C-12 — schema migration requires provenance

The migration layer must preserve the historical identity of old records. Converting an old Run into a new row without enough evidence should produce an explicit migration/reconciliation state rather than silently inventing action hashes or claim semantics.

## 14. Finding C-13 — event history may still be needed

State tables answer `what is true now`.

They do not necessarily answer `how did we get here` with sufficient forensic detail.

An optional append-only `control_plane_events` table may therefore be useful for durable provenance:

```text
event_id
run_id
correlation_id
causation_id
event_type
aggregate_version
payload_hash
created_at
```

This should be considered separately from the authoritative transactional state model, not introduced merely for logging.

## 15. Finding C-14 — schema should support optimistic stale-write rejection

Every mutable aggregate should have a monotonic revision/version:

```text
runs.revision
approvals.revision
claims.revision
```

or equivalent compare-and-set predicates.

A stale worker must not be able to update a row simply because it still knows the primary key.

## 16. Finding C-15 — leases are premature for Q1

A time-based lease can create an ambiguous case where the old worker is still executing while a new worker believes the lease expired.

Therefore Q1 should use non-overlapping execution claims rather than automatic lease expiration unless a concrete background-worker requirement proves that a lease is necessary.

## 17. Revised decision

The candidate backend direction is strengthened:

```text
SQLite / Room = preferred authoritative local transaction backend
```

but the schema itself remains provisional until the following are resolved:

- execution-phase semantics;
- claim lifecycle;
- aggregate-state consistency;
- zero-effect semantics;
- stale-write/versioning strategy;
- event/provenance requirement;
- legacy migration behavior.

## 18. Required next artifact

Before implementation, produce:

```text
Q1_STATE_INVARIANT_MATRIX_v0.3
```

mapping every state, transition, SQL predicate, allowed caller, crash point, and recovery disposition.

Only after that artifact is reviewed should the first SQLite/Room implementation be written.
