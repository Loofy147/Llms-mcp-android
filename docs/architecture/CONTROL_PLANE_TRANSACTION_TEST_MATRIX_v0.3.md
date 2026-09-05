# Control-Plane Transaction Test Matrix v0.3

Status: Reviewed implementation gate — Q1 remains OPEN
Date: 2026-09-06

## Objective

Prove that approval resolution, execution admission, and multi-effect reservation behave as one durable control-plane protocol under failure and concurrency.

Tests must validate the durable state after reopening the store. A returned exception is not evidence that a preceding write did not commit.

## Required matrix

| ID | Scenario | Expected result |
|---|---|---|
| T01 | Direct plan throws | persisted `FAILED`; no capability call |
| T02 | Approval denied | approval consumed once; Run `DENIED`; no capability call |
| T03 | Approval approved once | one execution claim; one execution |
| T04 | Same approval resolved twice sequentially | first wins; second cannot execute |
| T05 | Same approval resolved concurrently | exactly one winner |
| T06 | Separate approval-store instances race | exactly one winner or deterministic conflict |
| T07 | Separate runtime instances race | at most one execution admission |
| T08 | Crash before approval commit | approval remains pending/recoverable |
| T09 | Crash after approval commit before execution claim | approved state is recoverable and remains unbound; no false terminal success |
| T10 | Crash after execution claim | recovery exposes `SAFE_TO_RESUME`, `EFFECTS_UNKNOWN`, or explicit reconciliation requirement according to committed effects |
| T11 | Crash before effect reservation commit | no partially committed successful reservation group |
| T12 | Crash after group reservation | every intended effect is durably reserved |
| T13 | Multi-effect reservation failure | no prefix remains represented as a committed reservation group |
| T14 | Crash after first capability effect | affected effect becomes `UNKNOWN`; no blind replay |
| T15 | Crash before terminal Run commit | recovery does not infer success from execution return alone |
| T16 | Crash after terminal success | terminal state remains immutable |
| T17 | Stale callback after success | ignored/rejected; state remains `SUCCEEDED` |
| T18 | Stale recovery after failure | ignored/rejected; state remains terminal |
| T19 | Repeated recovery | idempotent; no state regression |
| T20 | Completed effect replay | `REPLAY_BLOCKED`; no executor call |
| T21 | Confirmed-not-executed effect replay | reservation can be reacquired exactly once |
| T22 | Effect identity conflict | execution blocked; existing state unchanged |
| T23 | Malformed/torn final journal record | valid prefix replays; no invented state |
| T24 | Concurrent unrelated Runs | independent progress; no global serialization beyond required boundaries |
| T25 | Zero-effect Action | normal authorization/terminal semantics; no phantom effect group |
| T26 | Exception after durable commit | reopened state reflects the actual committed event, not an assumed rollback |
| T27 | Claim exists but effect semantics are non-idempotent | recovery refuses blind re-execution and requires reconciliation |
| T28 | Terminal write racing with stale non-terminal write | terminal state wins; stale write rejected/ignored |

## Fault injection API target

Tests should not kill the JVM arbitrarily for every case. Introduce a narrow injectable hook at durable protocol boundaries:

```text
FaultPoint.APPROVAL_VALIDATED
FaultPoint.APPROVAL_COMMIT_ATTEMPTED
FaultPoint.APPROVAL_COMMITTED
FaultPoint.EXECUTION_CLAIM_PREPARED
FaultPoint.EXECUTION_CLAIM_COMMITTED
FaultPoint.EFFECT_GROUP_COMMIT_ATTEMPTED
FaultPoint.EFFECT_GROUP_COMMITTED
FaultPoint.CAPABILITY_STARTED
FaultPoint.CAPABILITY_RETURNED
FaultPoint.RUN_TERMINAL_COMMIT_ATTEMPTED
FaultPoint.RUN_TERMINAL_COMMITTED
```

A deterministic test double may throw `InjectedCrash` at a selected point. The production implementation must not depend on the test hook.

## Assertions

Every test should assert both local result and durable state after reopening the store.

For concurrency, assertions must include:

```text
winner count == 1
executor calls <= 1 per effect identity
no terminal/non-terminal regression
no approval reuse
no partial committed reservation group
```

For recovery, assert the explicit disposition rather than merely checking that some record exists.

## Evidence classification

- T01-T04: deterministic unit evidence (L2).
- T05-T07, T09-T24, T25-T28: concurrency/recovery evidence (L2-L4 when reopening durable stores).
- Android process-death reproduction: L5.
- External effects: L6 only when an actual external system is involved.

Passing JVM tests do not establish Android lifecycle or external exactly-once guarantees.

## Completion rule

Q1 is not closed by a green happy-path suite. Closure requires the durable implementation to satisfy the transaction design and the adversarial matrix, with no unresolved semantic ambiguity in approval binding, claim ownership, effect-group reservation, recovery classification, or terminal transition enforcement.
