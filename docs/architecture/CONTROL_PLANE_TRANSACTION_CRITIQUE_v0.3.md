# Control-Plane Transaction Critique v0.3

Status: Pre-implementation review — NOT approved
Date: 2026-09-06

## Purpose

Review the Q1 transaction preparation artifacts before implementation. This document is a gate against treating a plausible protocol description as proof of atomic durable behavior.

## 1. What survives review

The following architectural direction is sound:

```text
Approval resolution
    + execution admission
    + effect reservation
```

must have one durable coordination boundary, and external capability execution must remain outside the local transaction guarantee.

The distinction between:

```text
local control-plane atomicity
external effect exactly-once
```

is retained.

## 2. Correction: Stage A is not atomic

The original migration wording allowed a Coordinator to sit above the existing `ApprovalStore` and `RuntimeStore`. That can provide one *semantic caller*, but it cannot provide a physical atomic commit across two independent journals.

Therefore Stage A must be described as:

```text
single orchestration authority
+
explicit provisional ordering
+
crash-window characterization
```

It must NOT be described as:

```text
transactionally atomic
crash-atomic
single durable transaction
```

Those claims require one persistence boundary or a proven atomic commit protocol.

## 3. Correction: execution claim is not enough

An execution claim alone does not make execution safe to resume.

Recovery must consider:

```text
claim state
reservation state
effect idempotency semantics
capability effect class
external uncertainty
```

A `CLAIMED` Run with a potentially non-idempotent external effect is not automatically `SAFE_TO_START` after process death.

## 4. Correction: recovery needs explicit dispositions

The recovery vocabulary must distinguish at least:

```text
WAITING_APPROVAL
SAFE_TO_START
SAFE_TO_RESUME
EFFECTS_UNKNOWN
TERMINAL
MANUAL_RECONCILIATION_REQUIRED
```

`SAFE_TO_START` means no committed effect or an effect with a proven safe replay contract.

`SAFE_TO_RESUME` means execution can continue under a still-valid claim without re-running already admitted effects.

`EFFECTS_UNKNOWN` means external completion cannot be inferred.

## 5. Correction: group reservation is necessary but insufficient

Atomic reservation establishes that an intended set is admitted together. It does not establish atomic execution of those effects.

Therefore the invariant is:

```text
reservation atomicity != execution atomicity
```

Partial external completion remains possible and must be represented through per-effect state plus reconciliation.

## 6. Correction: terminality must be enforced, not documented

A journal replay model that accepts the newest Run record is not enough. The persistence layer must reject invalid transitions such as:

```text
SUCCEEDED -> RUNNING
FAILED -> WAITING_APPROVAL
CANCELLED -> SUCCEEDED
```

The transition validator must be part of the write path, not only a test expectation.

## 7. Correction: approval lifecycle must be explicit

Approval status alone does not identify whether the decision has been bound to an execution claim.

The durable model should distinguish:

```text
PENDING
APPROVED_UNBOUND
APPROVED_BOUND
DENIED
```

or an equivalent event/state representation.

An `APPROVED` record without a durable binding must remain recoverable.

## 8. Correction: fault injection needs commit semantics

The fault model must distinguish:

```text
operation prepared
operation committed
operation observed after reopen
```

Throwing after `append()` but before the caller receives success is especially important: the durable write may already exist.

Tests must therefore assert both possibilities based on the actual commit boundary rather than assuming that an exception means "not committed".

## 9. Correction: concurrency evidence must include process-level behavior

Separate JVM objects are necessary but not sufficient. A private object lock proves only same-instance mutual exclusion.

For the file journal, tests need OS-visible concurrent access semantics or an explicit storage primitive that provides them.

Until then:

```text
cross-instance correctness = OPEN
cross-process correctness = OPEN
```

## 10. Correction: empty-effect Actions

The protocol must define behavior for Actions with zero CapabilityInvocations. Such Runs still require a terminal transition and evidence semantics, but no effect reservation exists.

This case must not accidentally require a transaction record whose meaning assumes a non-empty effect set.

## 11. Correction: identity hierarchy

The following identifiers serve different purposes and must not be conflated:

```text
runId              execution attempt identity
executionClaimId   ownership/admission identity
reservationGroupId atomic reservation-set identity
effectId           individual effect/replay identity
idempotencyKey     caller/domain replay key
```

No identifier should silently substitute for another.

## 12. Decision

Q1 remains **OPEN**.

Before implementation, the design and migration documents must be updated to reflect these corrections. Afterward, Stage A should produce tests and evidence of orchestration semantics only; Stage B is the first point at which durable atomicity may be claimed.
