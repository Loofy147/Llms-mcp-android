# Q1 Control-Plane Schema v0.3

Status: Proposed / review gate
Date: 2026-09-06

## 1. Purpose

Define a concrete relational model for the control plane before implementing the durable backend.
The schema is a semantic proposal, not yet an implementation commitment.

The authoritative local transaction must cover the state required to decide whether one approved operation is admitted to execution:

```text
Run
Approval
ExecutionClaim
EffectReservationGroup
Effect
```

The external side effect is outside this transaction boundary.

## 2. Database boundary

Use one SQLite database file for all control-plane tables.
Do not place Run, Approval, and Effect state in separate database files when Q1 requires atomicity across them.

The database should be the authoritative source for:

```text
approval state
execution admission
run transition state
effect reservation state
recovery state
```

A legacy journal may remain as a read-only migration source during transition, but it is not the authoritative transaction backend after cutover.

SQLite transactions provide atomic commit and serialized writes; WAL can provide snapshot reads concurrent with a writer. These properties support the required local control-plane boundary, but do not make external effects transactional. The relevant semantics must still be enforced by the application schema and state machine.

## 3. Core tables

### 3.1 runs

```text
runs
-----
id                       TEXT PRIMARY KEY
status                   TEXT NOT NULL
activation_source        TEXT NOT NULL
requester_principal_id   TEXT NOT NULL
action_id                TEXT NOT NULL
action_version           INTEGER NOT NULL
action_definition_hash   TEXT NOT NULL
input_json               TEXT NOT NULL
output_json              TEXT NOT NULL
denial_reason            TEXT NULL
approval_id              TEXT NULL
execution_claim_id       TEXT NULL
created_at               INTEGER NOT NULL
updated_at               INTEGER NOT NULL
terminal_at              INTEGER NULL
```

Important invariant:

```text
terminal_at IS NOT NULL <=> status is terminal
```

Historical semantic identity is pinned by `action_definition_hash`, not reconstructed solely from `(action_id, action_version)`.

### 3.2 approvals

```text
approvals
---------
approval_id              TEXT PRIMARY KEY
run_id                   TEXT NOT NULL UNIQUE
requester_principal_id   TEXT NOT NULL
action_id                TEXT NOT NULL
action_version           INTEGER NOT NULL
action_definition_hash   TEXT NOT NULL
plan_fingerprint         TEXT NOT NULL
planned_invocations_json TEXT NOT NULL
status                   TEXT NOT NULL
resolved_by              TEXT NULL
resolved_at              INTEGER NULL
created_at               INTEGER NOT NULL
updated_at               INTEGER NOT NULL
```

Required status set:

```text
PENDING
APPROVED
DENIED
```

Exactly one approval row belongs to a Run. Approval resolution is compare-and-set on `status = PENDING`.

### 3.3 execution_claims

```text
execution_claims
----------------
claim_id                 TEXT PRIMARY KEY
run_id                   TEXT NOT NULL UNIQUE
approval_id              TEXT NOT NULL UNIQUE
plan_fingerprint         TEXT NOT NULL
group_id                 TEXT NOT NULL
owner_id                 TEXT NOT NULL
status                   TEXT NOT NULL
claimed_at               INTEGER NOT NULL
released_at              INTEGER NULL
```

Required status set:

```text
CLAIMED
RELEASED
```

For Q1, a Run can have at most one execution claim.

### 3.4 effect_groups

```text
effect_groups
-------------
group_id                 TEXT PRIMARY KEY
run_id                   TEXT NOT NULL UNIQUE
claim_id                 TEXT NOT NULL UNIQUE
plan_fingerprint        TEXT NOT NULL
expected_count           INTEGER NOT NULL
state                    TEXT NOT NULL
created_at               INTEGER NOT NULL
committed_at             INTEGER NULL
```

Required group states:

```text
PREPARED
RESERVED
UNKNOWN
RECONCILED
```

A group can be marked `RESERVED` only when all expected effect rows are present and reserved inside the same SQLite transaction.

### 3.5 effects

```text
effects
-------
effect_id               TEXT PRIMARY KEY
run_id                  TEXT NOT NULL
claim_id                TEXT NOT NULL
group_id                TEXT NOT NULL
invocation_id           TEXT NOT NULL UNIQUE
capability_id           TEXT NOT NULL
capability_version      INTEGER NOT NULL
action_id               TEXT NOT NULL
action_version          INTEGER NOT NULL
action_definition_hash  TEXT NOT NULL
attributed_principal_id TEXT NOT NULL
scope_json              TEXT NOT NULL
parameters_json         TEXT NOT NULL
idempotency_key         TEXT NULL
status                  TEXT NOT NULL
created_at              INTEGER NOT NULL
updated_at              INTEGER NOT NULL
completed_at            INTEGER NULL
```

Required effect states:

```text
RESERVED
COMPLETED
UNKNOWN
CONFIRMED_NOT_EXECUTED
```

`effect_id` is the stable local/external replay identity. `invocation_id` identifies the concrete invocation instance.

## 4. Foreign keys and constraints

The database must enforce relationships where possible:

```text
approvals.run_id -> runs.id
execution_claims.run_id -> runs.id
execution_claims.approval_id -> approvals.approval_id
effect_groups.run_id -> runs.id
effect_groups.claim_id -> execution_claims.claim_id
effects.run_id -> runs.id
effects.claim_id -> execution_claims.claim_id
effects.group_id -> effect_groups.group_id
```

Enable foreign-key enforcement explicitly.

Recommended uniqueness constraints:

```text
approvals.run_id UNIQUE
execution_claims.run_id UNIQUE
execution_claims.approval_id UNIQUE
effect_groups.run_id UNIQUE
effect_groups.claim_id UNIQUE
effects.invocation_id UNIQUE
```

## 5. Authoritative approval transaction

Approval must be resolved and execution admitted in one database transaction:

```sql
BEGIN IMMEDIATE;

-- 1. Read current Run + Approval.
-- 2. Verify non-terminal Run and PENDING approval.
-- 3. Verify requester, action identity and plan fingerprint.
-- 4. CAS approval PENDING -> APPROVED.
-- 5. Insert one ExecutionClaim.
-- 6. Insert EffectGroup.
-- 7. Insert every Effect row.
-- 8. Verify count == expected_count.
-- 9. Transition Run WAITING_APPROVAL -> RUNNING.
-- 10. COMMIT.
```

If any step fails, the transaction rolls back and the operation remains retryable according to the persisted state.

The external capability execution begins only after this transaction commits.

## 6. Concurrency semantics

Two resolvers for the same approval execute against the same database.
The first transaction that changes `PENDING` to `APPROVED` and acquires the unique claim wins.
The second must observe a non-pending approval or a conflicting unique constraint and must not invoke capabilities.

The application must not depend on an in-memory monitor for correctness.

## 7. Group reservation semantics

All effect rows are inserted in one transaction.

The invariant is:

```text
COMMITTED RESERVED group
    => expected_count effects exist
    => every effect is RESERVED
    => no missing suffix/prefix
```

A failed transaction must leave no committed `RESERVED` group.

After commit, individual effects may independently transition to `COMPLETED` or `UNKNOWN` because the external operations are not themselves atomic.

## 8. Recovery semantics

Recovery queries the joined durable state:

```text
Run
+ Approval
+ ExecutionClaim
+ EffectGroup
+ Effects
```

Examples:

```text
WAITING_APPROVAL + PENDING approval
    => WAITING_APPROVAL

RUNNING + no claim
    => CORRUPT / invariant violation; do not execute blindly

RUNNING + claim + no committed effect group
    => SAFE_TO_START only if the claim protocol says execution has not started

RUNNING + RESERVED effects
    => EXECUTION_STATE_UNKNOWN until external completion is established

TERMINAL Run
    => TERMINAL; never reopen
```

Recovery must not derive external completion from a local transaction alone.

## 9. Terminal-state enforcement

Application writes should use a transition function rather than unrestricted status updates.

Conceptually:

```text
transition(current, next)
```

must reject all writes from terminal states.

The database should additionally make terminal timestamps and status changes consistent through update predicates and tests.

## 10. Idempotency scope

The current project should not assume that a user-supplied idempotency key is globally unique.

The semantic identity should include a deliberate namespace, at minimum:

```text
capability_id/version
operation semantics
principal or tenancy context where appropriate
destination context where appropriate
idempotency_key
```

The canonical effect identity algorithm must be documented separately and tested against cross-principal/cross-destination collisions before external effects are promoted.

## 11. Journal compatibility

Migration must read legacy journal records and materialize them into the new schema without reopening terminal Runs.

Legacy state that cannot be translated with confidence must be marked for explicit reconciliation rather than guessed.

No destructive journal deletion is part of the first migration step.

## 12. Backend settings to validate

The implementation must explicitly test:

```text
WAL mode
foreign_keys=ON
transaction failure/rollback
multiple connections
busy/lock behavior
crash/reopen behavior
checkpoint/restart behavior
backup/restore semantics
```

`PRAGMA synchronous` must be chosen deliberately for the durability contract; performance-oriented settings must not silently weaken the required evidence level.

## 13. Non-goals

This schema does not provide:

- rollback of external side effects;
- distributed transactions;
- exactly-once external execution;
- authentication of the Android user;
- independent verification;
- tamper-evident evidence;
- long-running background scheduling.

Those remain separate contracts.

## 14. Review gates before implementation

The schema is not accepted until review answers these questions:

1. Is `RUNNING` semantically sufficient, or is an internal execution-admission state required?
2. Is `APPROVED` a stable state or should approval resolution directly produce an execution claim without an intermediate approval state?
3. Should `effect_groups` have stronger aggregate-state invariants?
4. What exact meaning does `RELEASED` claim have after process death?
5. Should `owner_id` be a process-instance lease, invocation identity, or scheduler identity?
6. What is the canonical idempotency namespace?
7. Which legacy journal states can be migrated deterministically?
8. What recovery classifications must be persisted versus derived?
9. Do we need an event/outbox table for audit/provenance even if state tables remain authoritative?
10. What is the minimum Android lifecycle path needed to establish L5 evidence?
