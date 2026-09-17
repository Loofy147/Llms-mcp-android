# Android Cross-App Recovery State Machine Audit — 2026-09-16

Status: **RESEARCH RECORD / ARCHITECTURE INPUT**

Purpose: refine the durable cross-app workflow model into an explicit recovery state machine, while keeping Android scheduler state, transport state, provider state, and domain state separate.

This document follows the repository evidence discipline:

- **ESTABLISHED** — directly supported by Android/platform documentation.
- **EXPERIMENTALLY_SUPPORTED** — confirmed by a controlled device test.
- **INFERENCE** — architectural consequence of established behavior.
- **HYPOTHESIS** — design proposition requiring validation.
- **UNKNOWN** — not yet established.
- **CONTRADICTED** — prior assumption rejected by stronger evidence.
- **OPEN** — requires a discriminating experiment.

## 1. The boundary we must preserve

A durable cross-app operation contains at least five distinct state machines:

```text
DOMAIN STATE
    semantic progress and outcome

SCHEDULER STATE
    WorkManager / JobScheduler / FGS / alarm state

TRANSPORT STATE
    Binder / Intent / AppFunction / provider-call state

PROVIDER STATE
    selected package, capability version, availability, authorization

ARTIFACT STATE
    semantic artifact identity, locator, accessibility, integrity
```

**INFERENCE:** none of these machines should be used as a substitute for the domain state machine.

The durable source of truth remains the domain operation record.

## 2. WorkManager retry is not semantic retry

**ESTABLISHED:** WorkManager workers can return `Result.retry()`, after which WorkManager reschedules the work using the configured backoff policy. Execution timing is inexact and affected by constraints/system optimization.

**INFERENCE:**

```text
Result.retry()
    means:
    "ask Android to schedule another execution attempt"

It does NOT mean:
    "the external side effect did not happen"
    "the operation is safe to repeat"
    "the provider rolled back"
```

Therefore:

```text
WorkManager retry
    !=
operation retry
```

A Worker may only return `Result.retry()` after the domain recovery policy has classified the current operation state as retryable.

## 3. Required idempotency boundary

For every side-effecting capability invocation:

```text
operation_id
    ↓
provider invocation
    ↓
possible result:

SUCCESS
FAILURE_BEFORE_EFFECT
EFFECT_APPLIED
UNKNOWN
```

The dangerous case is:

```text
provider performs effect
    ↓
reply lost / process killed / binder failure
    ↓
caller sees UNKNOWN
```

**INFERENCE:** retrying the transport without first reconciling the semantic operation can duplicate the side effect.

The provider contract should therefore expose one of these mechanisms:

```text
1. idempotent operation_id accepted by provider
2. query/reconcile by operation_id
3. deterministic result artifact that can be checked
4. domain-specific compensation/rollback
5. explicit declaration that the operation is non-retryable
```

This is a contract requirement, not an Android guarantee.

## 4. Recovery transition rule

The recovery engine should implement:

```text
LOAD durable Operation
        ↓
READ checkpoint
        ↓
RESOLVE provider again
        ↓
REVALIDATE authorization/capability
        ↓
REVALIDATE artifacts
        ↓
CLASSIFY previous attempt
        ↓
┌───────────────────────────────────────────┐
│ KNOWN SUCCESS  → commit/verify            │
│ KNOWN FAILURE  → retry only if policy says│
│ UNKNOWN        → reconcile first          │
│ USER STOP      → await valid reactivation │
│ PROVIDER LOST  → re-resolve / await       │
│ PACKAGE UPDATE → compatibility check      │
└───────────────────────────────────────────┘
        ↓
CREATE NEXT ATTEMPT
        ↓
SCHEDULE EXECUTION
```

The ordering is deliberate: **classification precedes execution**.

## 5. Checkpoint semantics

A checkpoint must describe what the system knows, not what it hopes happened.

Minimum conceptual fields:

```text
operation_id
workflow_id
checkpoint_id
semantic_state
attempt_id
provider_package
provider_capability_version
started_at
last_observed_at
input_artifact_versions
output_artifact_refs
outcome_class
reconciliation_status
```

**INFERENCE:** timestamps and attempt counters are evidence metadata; they are not proof of successful external effects.

## 6. Provider version is part of the capability identity

Android package updates can terminate the old runtime, and provider implementations can change between package versions.

**INFERENCE:** a durable capability reference should not be only:

```text
package = com.example.provider
```

It should retain at least:

```text
capability_id
capability_version
provider_package
provider_package_version
```

Then recovery can distinguish:

```text
same provider / compatible version
same provider / changed version
provider replaced
provider missing
```

## 7. Force-stop and stopped state

**ESTABLISHED:** Android 15 keeps a package in the stopped state until a direct or indirect user action removes it. Android 15 also cancels pending intents when the package enters the stopped state; when the stopped state is removed, `ACTION_BOOT_COMPLETED` is delivered to provide an opportunity to re-register pending intents. `ApplicationStartInfo.wasForceStopped()` can report whether the app was force-stopped.

**INFERENCE:**

```text
FORCE_STOP
    → USER_STOPPED / AWAITING_REACTIVATION
```

not:

```text
FORCE_STOP
    → Result.retry()
```

The recovery subsystem must respect the user's explicit stop boundary.

## 8. ContentProvider boundary

**ESTABLISHED:** remote ContentProvider calls execute through Binder/provider threads; slow provider queries can trigger provider ANRs, and concurrent blocking Binder calls can exhaust provider Binder threads.

**INFERENCE:** a provider query should be treated as a bounded request/response boundary.

Long-running work should instead follow:

```text
request
  ↓
create operation_id
  ↓
return quickly
  ↓
execute asynchronously
  ↓
persist result/checkpoint
  ↓
query status/result later
```

This pattern also gives the orchestrator a place to reconcile UNKNOWN outcomes.

## 9. URI grants are capability state, not artifact truth

**ESTABLISHED:** AppFunction URI grants can carry read/write and optional persistable flags; ordinary grants have explicit lifetime semantics, while persistent access requires the persistable mechanism and receiver action. The owning provider must allow URI grants.

**INFERENCE:**

```text
ArtifactRef
    = semantic identity + provenance + locator

URI grant
    = current access capability
```

Loss of a URI grant must therefore transition artifact availability, not silently redefine artifact identity.

## 10. Revised domain state machine

```text
PLANNED
  ↓
RESOLVING_PROVIDER
  ↓
PROVIDER_SELECTED
  ↓
AUTHORIZED
  ↓
INPUT_READY
  ↓
DISPATCHED
  ↓
EXECUTING
  ↓
CHECKPOINTED
  ↓
VERIFYING
  ↓
COMMITTED

Exceptional transitions:

EXECUTING → UNKNOWN_OUTCOME
EXECUTING → PROCESS_LOST
EXECUTING → PROVIDER_LOST
EXECUTING → USER_STOPPED
EXECUTING → PACKAGE_UPDATED
EXECUTING → PERMISSION_CHANGED
EXECUTING → FAILED

Recovery transitions:

UNKNOWN_OUTCOME → RECONCILING
PROCESS_LOST    → RECONCILING / RESUME
PROVIDER_LOST   → RESOLVING_PROVIDER
PACKAGE_UPDATED → COMPATIBILITY_CHECK
PERMISSION_CHANGED → AUTHORIZATION_REQUIRED
USER_STOPPED    → AWAITING_REACTIVATION

Only after classification:

RECONCILED_SAFE_TO_RETRY → NEXT_ATTEMPT
```

## 11. State invariants

The following should become testable invariants:

### I1 — No live-handle recovery

A recovered operation must not require an old Binder proxy, FD, coroutine Job, component instance, or process-local object.

### I2 — No transport-implies-failure

Transport failure alone cannot transition an operation directly to semantic `FAILED` when a side effect may already have occurred.

### I3 — No blind side-effect retry

`UNKNOWN_OUTCOME` must pass through reconciliation before a repeated side effect.

### I4 — User stop is not ordinary preemption

A force-stop/user stop must not silently become an autonomous retry loop.

### I5 — Scheduler state is subordinate

WorkManager state can trigger execution, but the Worker reads/writes domain state rather than becoming the domain state.

### I6 — Artifact identity survives locator replacement

Changing URI, FD, BlobStore handle, or storage location must not silently create a new semantic artifact version.

### I7 — Provider resolution is renewable

The provider selected during planning is not assumed valid forever.

## 12. Failure matrix to implement/test

| Failure | Domain classification | Automatic action |
|---|---|---|
| Worker stopped before side effect | PROCESS_LOST / PREEMPTED | resume from checkpoint |
| Binder error before provider accepts request | UNKNOWN until proven otherwise | reconcile when ambiguous |
| Provider completed effect, reply lost | UNKNOWN_OUTCOME | reconcile by operation_id |
| Provider returned explicit failure | FAILED | retry only if policy allows |
| Provider package disappeared | PROVIDER_LOST | re-resolve capability |
| Provider package updated | PACKAGE_UPDATED | compatibility check |
| Permission revoked | PERMISSION_CHANGED | require authorization / user action |
| Force-stop | USER_STOPPED | await reactivation |
| Artifact URI inaccessible | ARTIFACT_UNAVAILABLE | re-resolve/reacquire if reconstructable |
| WorkManager retry requested | SCHEDULER_RETRY | no domain conclusion by itself |

## 13. Minimum provider contract for durable orchestration

A provider that wants to participate in a durable workflow should ideally expose:

```text
capability_id
capability_version
operation_id
request schema
response schema
idempotency/reconciliation semantics
artifact input/output contract
authorization requirements
failure categories
compatibility policy
```

**HYPOTHESIS:** this contract is sufficient to let our orchestrator treat third-party Android apps as replaceable capability providers without importing their internal architecture.

The hypothesis requires later device experiments and at least one real provider implementation.

## 14. Test ladder

### T0 — pure state-machine tests

No Android dependency.

### T1 — local Android durable-state/restart proof

Use the existing durable runtime store and process-restart harness. Do not introduce Room merely to satisfy this ladder; SQLite/Room remains a separate implementation choice unless required by evidence.

### T2 — Binder cross-process failure injection

**CASE-LEVEL EXPERIMENTALLY_SUPPORTED:** B2 and B3 have now been exercised on an API-35 Google APIs x86_64 emulator using a Pixel 7 Pro profile. The test provider runs in a separate process of the same installed application.

Supported cases:

```text
B2 — durable receipt, provider process loss before effect
B3 — durable effect, provider process loss before reply, explicit reconciliation
```

Still open at T2:

```text
B1, B4, B5, B6, B7
caller-side durable UNKNOWN_OUTCOME
stale callback rejection
concurrent recovery ownership
```

### T3 — distinct provider package/application

Test package discovery, authorization, provider identity/version changes, and cross-package reconciliation.

### T4 — WorkManager scheduling adapter

Add only after the semantic recovery boundary is sufficiently established. WorkManager schedules attempts; it does not become domain truth.

### T5 — reboot / force-stop / package-update matrix

Run across the API/device matrix used by the product.

## 15. Evidence record format

Each experiment should produce:

```text
experiment_id
hardware
Android release
API level
build fingerprint
app version
provider package/version
capability version
initial state
fault injection
observed platform state
observed domain state
artifact state
result
limits
reproduction data
```

No device result should be generalized beyond the tested matrix without an explicit inference.

## 16. Current conclusions

**ESTABLISHED:** Android gives us strong scheduling, IPC, package, permission, URI, and lifecycle mechanisms, but those mechanisms expose different scopes and failure semantics.

**INFERENCE:** the correct architecture is not a large custom Android runtime manager. It is a small durable semantic state machine whose execution adapters delegate to Android-native mechanisms.

**INFERENCE:** the most important primitive is not "background execution" but **reconciliation after ambiguity**.

**HYPOTHESIS:** if `operation_id`, capability versioning, durable checkpoints, artifact identity, and provider reconciliation are enforced, multi-app Android workflows can become resumable without treating apps as microservices or processes as durable actors.

## 17. Post-T2 synchronization note — 2026-09-17

Run #262 established provider-side B2/B3 behavior on the documented API-35 emulator topology. That result supersedes the pre-implementation assumption that real Android process-death evidence was entirely absent, but it does not close the caller-side recovery, concurrency, stale-result, reboot, force-stop, or package-update boundaries.

The pre-T2 portions of this document remain as the historical architecture audit. Current implementation direction is now:

```text
existing durable runtime semantics
        ↓
real Binder/provider failure evidence (B2/B3)
        ↓
caller-side UNKNOWN/recovery + concurrency/stale-result experiments
        ↓
distinct provider package / authorization (T3)
        ↓
WorkManager scheduling adapter
        ↓
reboot / force-stop / update matrix
```

The project must not promote the B2/B3 provider result into a general exactly-once or Android reliability guarantee.

## 18. Current next gate

The next discriminating work is B4/B5/B6/B7 and caller-side `UNKNOWN_OUTCOME` handling. T3 is deferred until those cases are explicitly scoped and the project can identify which remaining invariants require new experiments versus domain/runtime changes.
