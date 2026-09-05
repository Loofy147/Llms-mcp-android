# Unified Control-Plane Transaction Design v0.3

Status: Reviewed proposal — NOT approved
Date: 2026-09-06

## 1. Purpose

Define the minimum durable protocol required before changing Approval, Run, and Effect persistence.

The design deliberately separates semantic correctness from storage technology. The current file journal is an implementation detail; the protocol is the contract.

## 2. Transaction boundary

The desired logical transaction is:

```text
Approve pending Run
  + bind approval decision
  + claim execution
  + reserve all planned effects
  = one durable admission to execution
```

This statement is a **target property**, not a claim about the current implementation.

The transaction does NOT include the external capability side effect itself. External exactly-once execution is not claimed.

## 3. State model

For an approval-required Run:

```text
WAITING_APPROVAL
      |
      | resolve(APPROVED)
      v
APPROVED_UNBOUND
      |
      | bind execution claim
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

`EXECUTION_CLAIMED` is a durable control-plane state, even if the public Run enum keeps `RUNNING` as the externally visible execution state. The implementation may represent the claim as a separate record/event rather than expanding the enum immediately.

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
executionClaimId
effect identities
```

The approval cannot be transferred to a different Run or materially different plan.

## 5. Single-winner rule

For a given `approvalId`, exactly one resolution may transition `PENDING` to a terminal approval decision.

For a given `runId`, at most one approval resolution may successfully acquire the execution claim.

A second concurrent resolver must receive a deterministic non-success result and must not execute capabilities.

This requires compare-and-set semantics in the durable persistence boundary; an in-memory lock is insufficient for multiple store instances/processes.

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

A partial prefix must never represent a successful group reservation.

This guarantee concerns control-plane admission only; it does not make the external effects themselves atomic.

## 7. Recovery classification

After process restart, durable state must classify an interrupted operation as one of:

```text
WAITING_APPROVAL
SAFE_TO_START
SAFE_TO_RESUME
EFFECTS_UNKNOWN
TERMINAL
MANUAL_RECONCILIATION_REQUIRED
```

`SAFE_TO_START` is permitted only when no committed effect exists or the effect's replay semantics explicitly permit another admission.

`SAFE_TO_RESUME` means an existing execution owner can continue without re-admitting already-reserved effects.

Recovery must not infer external completion from approval, a claim, or a local `RUNNING` record alone.

## 8. Terminality

Terminal Run states are write-once semantic states:

```text
SUCCEEDED
FAILED
DENIED
CANCELLED
```

Any stale callback, retry, or recovery operation attempting to write a different terminal or non-terminal state must be rejected or ignored according to an explicit transition result.

Terminality must be enforced by the durable write path, not merely asserted by tests.

## 9. Journal protocol

An append-only implementation should record logical transaction events, not rely on a partially written sequence as if it were atomic.

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

The replay reducer reconstructs the state machine. A transaction is considered committed only when its required event set is durably present according to the persistence protocol.

Fault injection must distinguish:

```text
prepared
committed
visible after reopen
```

because an exception does not prove that a durable write did not happen.

## 10. Storage requirements

The target store implementation must provide:

```text
atomic logical transaction / equivalent CAS protocol
compare-and-set approval state
compare-and-set execution claim
atomic group effect reservation
append/force durability
replay with torn-final-record tolerance
terminal transition validation
```

A future SQLite implementation is acceptable, but not required by this contract. The storage technology must be chosen only after proving that it can supply the protocol properties under Android-relevant concurrency.

## 11. Crash points for E-13

Inject failure after each of:

```text
A approval decision validated
B approval decision committed
C execution claim prepared
D execution claim committed
E effect reservation committed
F capability execution started
G capability execution returned
H terminal Run committed
```

Expected outcome is deterministic classification without lost authorization or silent duplicate admission.

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
no terminal-state regression
```

## 13. Edge cases

The protocol must explicitly define Actions with zero CapabilityInvocations. Such Runs require normal authorization, terminal transition, and evidence semantics but do not require an effect reservation group.

The identity hierarchy must remain distinct:

```text
runId              execution attempt
executionClaimId   execution ownership
reservationGroupId atomic reservation-set identity
effectId           individual effect/replay identity
idempotencyKey     caller/domain replay key
```

## 14. Non-goals

This protocol does not provide:

- distributed consensus;
- exactly-once external side effects;
- rollback of arbitrary external effects;
- durable authentication of the Android user;
- tamper-proof audit storage.

Those require separate contracts.
