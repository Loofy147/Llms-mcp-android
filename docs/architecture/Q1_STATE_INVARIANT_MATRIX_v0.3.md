# Q1 State Invariant Matrix v0.3

Status: Proposed review gate
Date: 2026-09-06

## 1. Purpose

Convert the Q1 semantic contract into explicit state invariants and transaction obligations before implementing SQLite/Room.

## 2. Run states

| Current | Allowed next | Required guard | Crash interpretation |
|---|---|---|---|
| CREATED | WAITING_APPROVAL, RUNNING, DENIED, FAILED | creation + policy result | incomplete creation must not authorize execution |
| WAITING_APPROVAL | RUNNING, DENIED, FAILED | approval CAS / policy reevaluation | pending approval remains recoverable |
| RUNNING | SUCCEEDED, FAILED, CANCELLED | execution claim + terminal CAS | ambiguous effects remain UNKNOWN |
| SUCCEEDED | none | terminal write-once | stale work rejected |
| FAILED | none | terminal write-once | stale work rejected |
| DENIED | none | terminal write-once | stale work rejected |
| CANCELLED | none | terminal write-once | stale work rejected |

## 3. Approval invariants

```text
A1: one approval row belongs to one Run.
A2: approval resolution requires status=PENDING.
A3: requester/action/version/fingerprint must match the persisted context.
A4: APPROVED does not imply execution started.
A5: once resolved, approval cannot be reused for another claim.
```

## 4. Execution claim invariants

```text
C1: one Run has at most one active execution claim.
C2: one Approval has at most one execution claim.
C3: claim is bound to approval fingerprint and plan fingerprint.
C4: claim ownership is identity metadata, not authorization.
C5: stale owners cannot acquire a second claim through retry.
```

Recommended initial lifecycle:

```text
CLAIMED -> COMPLETED | ABANDONED
```

`ABANDONED` means the local execution owner is no longer considered the active owner; it must not mean an external effect did not happen.

## 5. Effect-group invariants

```text
G1: one execution claim has at most one reservation group.
G2: RESERVED means every expected effect row exists and is RESERVED.
G3: a partial effect prefix can never make the group RESERVED.
G4: group status cannot claim external completion.
G5: individual effects may become COMPLETED or UNKNOWN independently after reservation commit.
```

For zero-effect Actions:

```text
expected_count = 0
```

The chosen behavior must be explicit; recommended semantics are a claim without an effect group only when no capability invocation exists, followed by execution/reduction/verification.

## 6. Terminal invariants

```text
T1: terminal status cannot transition to any other status.
T2: terminal_at is written in the same transaction as the terminal status.
T3: terminal output/evidence are immutable after terminal commit.
T4: late capability/provider callbacks cannot mutate terminal Run state.
```

## 7. Transactional approval admission

The authoritative transaction is:

```text
BEGIN IMMEDIATE

read Run
read Approval
validate pending + fingerprint
update Approval PENDING -> APPROVED
insert ExecutionClaim
insert EffectGroup when needed
insert all Effect rows
verify effect count
update Run WAITING_APPROVAL -> RUNNING

COMMIT
```

No capability execution occurs before commit.

## 8. Transactional denial

For denial:

```text
BEGIN IMMEDIATE

CAS Approval PENDING -> DENIED
CAS Run WAITING_APPROVAL -> DENIED

COMMIT
```

No effect group or execution claim is created.

## 9. Concurrent approval resolution

Two concurrent resolvers must race on the same database row:

```sql
UPDATE approvals
SET status = 'APPROVED', ...
WHERE approval_id = :id
  AND status = 'PENDING';
```

The caller succeeds only when exactly one row is updated and the subsequent transaction creates the unique claim.

The second resolver must not invoke a capability even if it still holds an in-memory copy of the ApprovalContext.

## 10. Stale write prevention

Every mutable aggregate should have a revision or equivalent conditional-update predicate.

Conceptual form:

```sql
UPDATE runs
SET status = :next,
    revision = revision + 1
WHERE id = :id
  AND revision = :expected_revision
  AND status = :expected_current_state;
```

A zero-row update is a deterministic stale/conflict result, not permission to overwrite state.

## 11. Recovery matrix

| Durable state | Recovery disposition |
|---|---|
| WAITING_APPROVAL + PENDING | keep waiting |
| WAITING_APPROVAL + APPROVED but no claim | invariant violation or migration case; never blindly execute |
| RUNNING + CLAIMED + no effect group | only `SAFE_TO_START` if claim semantics prove execution has not begun |
| RUNNING + RESERVED group | `EXECUTION_STATE_UNKNOWN` until external/local completion is independently established |
| RUNNING + any UNKNOWN effect | `RECONCILIATION_REQUIRED` |
| TERMINAL | `TERMINAL`; no reopening |

Recovery must be idempotent.

## 12. Crash boundary matrix

| Boundary | Local transaction result | Recovery |
|---|---|---|
| before approval CAS commit | rollback | approval remains PENDING |
| after approval admission commit | committed claim/run/group state | continue or recover from durable claim |
| before effect-group commit | rollback | no committed reservation group |
| after effect-group commit | committed reservations | execute or classify uncertain after interruption |
| capability started | DB unchanged by start itself | effect may be UNKNOWN |
| capability returned | observation available | terminal transaction required |
| terminal commit | terminal state durable | stale work rejected |

## 13. Evidence obligations

Each Q1 transaction test must capture:

```text
initial database state
operation/thread identity
expected CAS/transaction outcome
reopened database state
claim count
effect-group completeness
terminal state
```

Tests that only assert a returned Kotlin object are insufficient for Q1.

## 14. Open questions

1. Should `APPROVED` remain persisted after execution claim, or should approval become an immutable decision record while claim state carries execution admission?
2. Should `ExecutionClaim` have only ownership state, or explicit phase values for claim/start/return/finalization?
3. Is `effect_groups` worth storing, or can completeness be derived from effects with lower invariant complexity?
4. Which revision mechanism gives the clearest conflict semantics in Room/SQLite?
5. Should audit events be stored in the same transaction as state changes?
6. What exact migration state represents legacy approvals whose effect/run relationship cannot be proven?
