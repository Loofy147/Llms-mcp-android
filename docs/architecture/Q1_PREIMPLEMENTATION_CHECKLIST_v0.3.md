# Q1 Pre-Implementation Checklist v0.3

Status: Reviewed — implementation not yet approved
Date: 2026-09-06

## Scope lock

Q1 is limited to the control-plane correctness boundary:

```text
Approval
Run transition
Execution claim
Effect group reservation
Recovery
Concurrency
Terminal immutability
```

Do not combine Q1 with:

```text
independent verification
MCP redesign
developer capabilities
Android UI redesign
provider abstraction changes
```

## Required design objects

Before production code changes, identify one owner for each:

```text
ControlPlaneCoordinator
ExecutionClaim
EffectReservationGroup
RunTransition
RecoveryDisposition
FaultInjector (test-only)
```

Names may differ; ownership must not.

## Invariants to encode

### I-Q1-01 — single approval winner

One pending approval can transition exactly once.

### I-Q1-02 — single execution admission

One Run can acquire at most one successful execution claim.

### I-Q1-03 — plan equivalence

The execution claim is bound to the exact approval fingerprint and planned invocation set.

### I-Q1-04 — group reservation

A multi-effect plan is either fully reserved or not committed as a reservation group.

### I-Q1-05 — no terminal regression

A terminal Run cannot transition back to any non-terminal state or to a different terminal state.

### I-Q1-06 — explicit uncertainty

Any effect whose external completion cannot be established is `UNKNOWN`; recovery never upgrades uncertainty to success.

### I-Q1-07 — recovery idempotence

Running recovery repeatedly does not create a second execution claim or change terminal outcomes.

### I-Q1-08 — commit ambiguity is explicit

An exception around a durable write does not by itself establish whether that write committed. The reopened store is the authority for post-fault state.

### I-Q1-09 — transaction boundary is physical or proven equivalent

A coordinator over multiple independent persistence files is not considered a transaction. Q1 atomicity requires one durable atomic boundary or an equivalently proven protocol.

## API migration rules

The first implementation may introduce a coordinator around the current stores, but that stage is orchestration only. `AgentRuntime` must no longer independently coordinate approval consumption and Run/effect persistence for the target boundary.

A facade/coordinator must not be documented as crash-atomic until its persistence layer satisfies I-Q1-09.

Temporary compatibility methods are acceptable only when clearly marked transitional and covered by tests.

## Test gate before backend replacement

All deterministic tests below must exist before replacing or consolidating the physical journal backend:

```text
approval single-winner
execution claim single-winner
group reservation all-or-none
terminal write rejection
recovery classification
fault injection at every durable boundary
repeated recovery
malformed/torn journal handling
commit-versus-exception ambiguity
```

## Recovery gate

Recovery must return an explicit disposition:

```text
WAITING_APPROVAL
SAFE_TO_START
SAFE_TO_RESUME
EFFECTS_UNKNOWN
TERMINAL
MANUAL_RECONCILIATION_REQUIRED
```

`SAFE_TO_START` requires no committed effect or a proven safe replay contract. A committed claim alone does not justify re-execution.

## Backend decision gate

Do not select SQLite solely because it is familiar. Compare at minimum:

```text
transaction support
WAL/concurrency behavior
crash durability
migration complexity
testability
Android lifecycle compatibility
operational footprint
```

The backend is selected after protocol tests, not before.

## Evidence gate

Q1 cannot be marked closed from compilation or happy-path tests alone.

Required evidence levels:

```text
L2 deterministic protocol tests
L3 CI green
L4 durable restart/replay evidence
L5 Android process-death evidence
```

External-effect exactly-once remains outside Q1.

## Current decision

Q1 remains **OPEN**.

The reviewed artifacts are preparation material only. Production implementation starts only after their terminology and invariants remain consistent with the eventual persistence mechanism.
