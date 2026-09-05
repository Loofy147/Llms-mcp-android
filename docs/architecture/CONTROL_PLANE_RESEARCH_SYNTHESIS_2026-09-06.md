# Control-Plane Research Synthesis — 2026-09-06

Status: Research-backed design input; NOT an accepted architectural decision
Scope: Q1 durable control plane, persistence, crash consistency, concurrency, Android lifecycle, and external-effect boundaries.

## 1. Purpose

This document records external research used to challenge and refine the Q1 design before implementation is frozen. External evidence informs the design; it does not prove behavior in this repository.

## 2. Core finding

The strongest conclusion is that the local control-plane transaction should have one authoritative durable transactional backend. A coordinator over multiple independent journal files can provide ordering and orchestration, but cannot by itself make cross-file writes crash-atomic.

The preferred implementation direction is therefore:

```text
ControlPlaneCoordinator
        |
        v
one authoritative transactional database
        |
        +-- Run
        +-- Approval
        +-- ExecutionClaim
        +-- EffectGroup
        +-- Effect
        +-- Recovery state / transaction metadata
```

The current file journals should be treated as migration/compatibility material unless later evidence demonstrates a genuinely equivalent cross-file atomic protocol.

## 3. SQLite / Room evidence

SQLite documents ACID transactions and atomic commit behavior across process crashes and power failures. Its crash-test infrastructure explicitly varies the point of simulated power loss and reopens the database to verify complete-or-not-at-all transaction outcomes.

Android's current Room guidance recommends Room over direct SQLite APIs for non-trivial structured persistence. Room provides transactional APIs; current documentation states that `withTransaction` commits when its block completes successfully and rolls back when an exception or cancellation occurs. Current Room documentation also provides explicit writer-transaction APIs.

Implication for this project:

```text
Approval + claim + group reservation
should be rows in the same transactional database
when they form one local admission decision.
```

This does not mean Room automatically solves application semantics. We still need explicit state transitions, uniqueness constraints, idempotency keys, recovery rules, and tests.

Sources:
- https://www.sqlite.org/transactional.html
- https://www.sqlite.org/atomiccommit.html
- https://developer.android.com/training/data-storage/room
- https://developer.android.com/reference/androidx/room/RoomDatabaseKt
- https://developer.android.com/reference/androidx/room/Transactor

## 4. Concurrency evidence

SQLite serializes writes and provides transaction isolation. Android's SQLite API documents WAL as a write-ahead-log mode that supports concurrent reads/writes with transactional semantics, while write transactions remain serialized.

Room documentation states that database transactions are queued/serialized at the transaction layer and supports multiple database instances/processes with multi-instance invalidation for cache coherency.

Implication:

```text
Do not build correctness around JVM object locks.
Let the durable backend own the cross-instance race.
```

An application-level mutex may still be used as a performance optimization, but it cannot be the correctness primitive.

Sources:
- https://www.sqlite.org/isolation.html
- https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase
- https://developer.android.com/training/data-storage/room

## 5. Crash consistency

SQLite's documented crash testing is stronger than injecting ordinary exceptions into business code: it models incomplete/partial writes and reopens the database to verify consistency.

Implication for E-13:

```text
JVM fault injection
    = protocol-level evidence

Database reopen after interrupted transaction
    = durable-storage evidence

Real Android process death
    = Android lifecycle evidence
```

These must remain separate evidence levels.

A test that throws after an in-memory mutation is not sufficient to claim durable crash atomicity.

Source:
- https://www.sqlite.org/atomiccommit.html

## 6. WorkManager boundary

Android documents WorkManager as the recommended API for persistent background work. Unique work can prevent duplicate scheduling, and persisted work can be recovered by the system.

Implication:

```text
Control-plane database = authority / source of truth
WorkManager            = scheduler / recovery launcher
```

WorkManager should not become the authorization source or effect-identity authority. If introduced, it should consume durable work/admission state rather than define it.

Source:
- https://developer.android.com/develop/background-work/background-tasks/persistent

## 7. Durable execution comparison

Temporal is an external reference for a stronger durable-execution model. Its documentation describes workflows that resume after crashes and infrastructure failures. This is relevant as a conceptual reference for recovery semantics, but the project's local Android runtime does not need Temporal's distributed architecture.

Implication:

```text
Borrow the semantic distinction:
workflow state is durable and recoverable

Do NOT import:
distributed service architecture
multi-node orchestration
remote control plane
```

Source:
- https://docs.temporal.io/

## 8. External effect boundary

AWS guidance on the transactional outbox pattern reinforces the distinction between atomic local state and external communication. The database mutation and outbox record can be committed atomically, but delivery can still duplicate and downstream consumers should be idempotent.

Implication for this project:

```text
Local transaction:
    authorization + claim + effect admission = atomic target

External side effect:
    separate system boundary

Recovery:
    uncertainty must remain UNKNOWN until reconciled

Exactly-once external execution:
    NOT claimed by Q1
```

Source:
- https://docs.aws.amazon.com/en_en/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html

## 9. Android backup lifecycle

Current Android documentation states that `android:allowBackup` defaults to true and recommends using data-extraction rules to exclude sensitive keys or temporary caches when backup/restore is enabled.

Implication:

```text
Control-plane durable state
and
credential/data backup policy
must be analyzed separately.
```

Q1 should define which state is reconstructible, which state is user continuity data, and which state must not be transferred. This is adjacent to Q1 rather than part of the transaction primitive itself.

Source:
- https://developer.android.com/guide/topics/manifest/application-element

## 10. Architectural consequences

### Accepted as research-supported direction (not yet project decisions)

1. Prefer one authoritative transactional database for local control-plane atomicity.
2. Keep Approval, ExecutionClaim, EffectGroup, Effect, and Run state within one transaction boundary when they form one admission decision.
3. Treat database uniqueness/CAS/transaction rules as correctness mechanisms, not in-memory locks.
4. Keep external effects outside the database transaction.
5. Represent unresolved external completion explicitly rather than guessing.
6. Use WorkManager only as a durable scheduler/recovery mechanism if background execution becomes necessary.
7. Separate protocol evidence, durable-storage evidence, and Android process-death evidence.

### Still OPEN

- Room version and exact persistence API selection.
- Whether the project needs one database file or multiple databases with an explicit cross-database protocol.
- Exact execution-claim lease/ownership semantics.
- Exact effect-group schema and uniqueness constraints.
- Backup/restore policy for Run/Approval/Effect state.
- Whether WorkManager is needed in the first implementation.
- Android multi-process requirements beyond current application architecture.

## 11. Design corrections before implementation

The earlier Q1 migration plan is corrected as follows:

```text
OLD
Stage A: coordinator over separate stores
Stage B: transactional backend

NEW
Stage A: semantic protocol + state machine tests
Stage B: authoritative transactional backend
Stage C: recovery/process-death evidence
Stage D: external-effect reconciliation evidence
```

A coordinator may still exist as an application-layer facade, but it is not considered an atomicity mechanism.

## 12. Decision status

This research does NOT close Q1.

It changes the implementation bias toward:

```text
SQLite/Room-backed ControlPlaneStore
```

while preserving a technology-neutral contract until the concrete schema, migration path, and adversarial tests are reviewed.
