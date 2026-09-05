# Unified Control-Plane Transaction Design v0.3

Status: Implementation preparation baseline
Date: 2026-09-06

## 1. Purpose

Define the minimum durable protocol required before changing Approval, Run, and Effect persistence.

The design deliberately separates semantic correctness from storage technology. The current file journal is an implementation detail; the protocol is the contract.

## 2. Transaction boundary

The logical transaction is:

```text
Approve pending Run
  + bind approval decision
  + claim execution
  + reserve all planned effects
  = one durable admission to execution
```

The transaction does NOT include the external capability side effect itself. External exactly-once execution is not claimed.

## 3. State model

For an approval-required Run:

```text
WAITING_APPROVAL
      |
      | resolve(APPROVED)
      v
EXECUTION_CLAIMED
      |
      | reserve all effects
      v
RUNNING
      |
      +--> capability execution
      +--> observation
      +--> verification
      v
TERMINAL
```

`EXECUTION_CLAIMED` is a durable control-plane state, even if the public Run enum keeps `RUNNING` as the externally visible execution state. The implementation may represent the claim as a separate record/lease rather than expanding the enum immediately.

## 4. Required identities

Every transaction must be bound to:

```text
runId
approvalId
requester identity
approver identity
approval fingerprint
action id + version + immutable semantic identity
planned invocation set identity
effect identities
```

The approval cannot be transferred to a different Run or materially different plan.

## 5. Single-winner rule

For a given `approvalId`, exactly one resolution may transition `PENDING` to a terminal approval decision.

For a given `runId`, at most one approval resolution may successfully acquire the execution claim.

A second concurrent resolver must receive a deterministic non-success result and must not execute capabilities.

This requires compare-and-set semantics in the durable store; an in-memory lock is insufficient for multiple store instances/processes.

## 6. Atomic reservation rule

For a multi-invocation Action plan:

```text
reserve(all effects)
```

is one logical operation.

Either:

```text
all effects are RESERVED
```

or:

```text
none are RESERVED
```

A partial prefix must never represent a successful reservation result.

## 7. Recovery classification

After process restart, durable state must classify an approved operation as one of:

```text
SAFE_TO_START
RUNNING / EFFECTS_UNKNOWN
TERMINAL
```

Recovery must not infer external completion from the existence of an approval or a local `RUNNING` record.

Reserved effects without terminal evidence become `UNKNOWN` and require explicit reconciliation before replay.

## 8. Terminality

Terminal Run states are write-once semantic states:

```text
SUCCEEDED
FAILED
DENIED
CANCELLED
```

Any stale callback, retry, or recovery operation attempting to write a different terminal or non-terminal state must be rejected or ignored according to an explicit transition result.

## 9. Journal protocol

An append-only implementation should record logical transaction events, not rely on reading a partially written sequence as if it were atomic.

Preferred event vocabulary:

```text
RUN_CREATED
APPROVAL_PENDING
APPROVAL_RESOLVED
EXECUTION_CLAIMED
EFFECTS_RESERVED
EFFECT_COMPLETED
EFFECT_UNKNOWN
RUN_TERMINAL
```

The replay reducer reconstructs the state machine. A transaction is considered committed only when its required event set is durably present according to the protocol.

## 10. Storage requirements

The store implementation must provide:

```text
begin/commit logical transaction
compare-and-set approval state
compare-and-set run execution claim
atomic group effect reservation
append/force durability
replay with torn-final-record tolerance
```

A future SQLite implementation is acceptable, but not required by this contract. The current file journal should first be strengthened enough to prove the semantics in tests.

## 11. Crash points for E-13

Inject failure after each of:

```text
A approval decision validated
B execution claim prepared
C execution claim committed
D effect reservation committed
E capability execution started
F capability execution returned
G terminal Run committed
```

Expected outcome is deterministic classification without lost approval or silent duplicate admission.

## 12. Concurrency cases for E-14

At minimum test:

1. two threads resolving the same approval;
2. two independent `JournalApprovalStore` instances resolving the same approval;
3. two independent runtime instances resolving the same Run;
4. two reservations of the same effect set;
5. different effects in unrelated Runs progressing concurrently.

Success criteria:

```text
single winner
no duplicate execution admission
no mixed approval states
no partial successful reservation
```

## 13. Non-goals

This protocol does not provide:

- distributed consensus;
- exactly-once external side effects;
- rollback of arbitrary external effects;
- durable authentication of the Android user;
- tamper-proof audit storage.

Those require separate contracts.
