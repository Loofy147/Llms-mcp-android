# Q1 Backend Comparison — 2026-09-06

Status: Decision preparation; no backend frozen

## Criteria

Score qualitatively against the actual Q1 requirements:

```text
C1 atomic multi-record transaction
C2 cross-instance/process concurrency
C3 crash recovery
C4 deterministic uniqueness/CAS
C5 migration from current journal
C6 testability/fault injection
C7 Android lifecycle fit
C8 operational complexity
C9 inspectability/debuggability
C10 future background scheduling fit
```

## Option A — Hardened append-only journal

Strengths:
- minimal dependencies;
- easy human inspection;
- preserves current format direction;
- deterministic replay is straightforward for simple state.

Weaknesses:
- atomicity across logical record groups must be implemented manually;
- cross-instance/process locking requires filesystem locking protocol;
- partial writes and torn records are application responsibilities;
- CAS semantics and uniqueness are harder to make obviously correct;
- multi-record commit/recovery is significantly more custom code.

Assessment: useful as migration/legacy compatibility and possibly as an append-only audit/event layer, but weak candidate for the authoritative Q1 transactional state store.

## Option B — SQLite via Android/Room

Strengths:
- built-in transactional commit/rollback;
- serialized write transactions and database isolation;
- uniqueness constraints and conditional updates map naturally to claims/idempotency;
- WAL support is appropriate for local read/write concurrency;
- Room provides typed DAO/schema/migration tooling and transaction APIs;
- crash recovery is a property of the database engine rather than a custom record protocol.

Weaknesses:
- introduces schema/migration code;
- requires deliberate transaction-boundary design;
- database transactions cannot include arbitrary external side effects;
- Room usage must remain disciplined so business logic does not accidentally leak into transaction blocks.

Assessment: strongest current candidate for authoritative Q1 control-plane persistence.

## Option C — Custom state machine + current journal

Strengths:
- excellent semantic clarity;
- small application-level state model;
- easy to unit test transitions.

Weaknesses:
- state machine does not itself provide physical durability or atomic multi-record commit;
- still inherits journal concurrency and crash-consistency complexity;
- easy to confuse semantic correctness with storage atomicity.

Assessment: required as a semantic layer, insufficient as the physical durability solution by itself.

## Option D — State machine + SQLite/Room + WorkManager

Strengths:
- combines explicit state semantics with transactional persistence;
- WorkManager can relaunch durable work when background continuation is actually required;
- clean separation between authority (database/runtime) and scheduling (WorkManager).

Weaknesses:
- unnecessary complexity if background execution is not yet required;
- more lifecycle cases and integration tests;
- WorkManager must not become a second source of truth.

Assessment: likely end-state if background/recovery execution becomes a real requirement; not necessary for initial Q1 persistence work.

## Provisional conclusion

```text
Semantic layer:
    explicit Run state machine

Authoritative persistence:
    SQLite/Room candidate

Scheduler:
    optional WorkManager later

Legacy journal:
    compatibility/migration reader
```

This is a provisional recommendation, not an accepted architectural decision.

## Decision gate

Freeze the backend only after:

1. mapping every Q1 invariant to concrete DB constraints/transactions;
2. writing the schema migration for existing journal state;
3. proving single-winner approval resolution;
4. proving single execution claim;
5. proving all-or-none effect-group reservation;
6. proving terminal immutability;
7. fault-injection/reopen tests;
8. measuring migration and test complexity.
