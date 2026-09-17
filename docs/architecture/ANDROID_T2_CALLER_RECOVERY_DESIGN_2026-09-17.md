# Android T2 Caller Recovery Design — 2026-09-17

Status: **DESIGN GATE / NOT YET EXECUTED**

Purpose: define the caller-side semantic contract needed to execute T2 B4/B5 and prepare B6/B7 without prematurely changing the production runtime model or introducing Room/WorkManager.

This document incorporates the external reference research recorded in `EXTERNAL_REFERENCE_ARCHITECTURES_2026-09-17.md`.

## 1. Current finding

The provider-side T2 experiment proves B2/B3 at case level, but the current instrumentation topology does not yet exercise caller-process loss. The existing provider is isolated in `:provider`; the instrumentation runner remains alive while the provider process is intentionally killed.

The next experiment therefore needs a second independently killable application process for the caller side.

Current topology:

```text
instrumentation runner
       |
       v
main application process
       |
       | Binder
       v
:provider process
```

Required topology for caller recovery:

```text
instrumentation runner
       |
       +--------------------+
       |                    |
       v                    v
:caller process         :provider process
       |                    |
       +------ Binder ------+
```

The instrumentation runner must remain alive while the caller process self-terminates at a precisely injected semantic boundary.

## 2. Do not kill the whole application

`am force-stop` is not suitable for B4/B5 because it terminates the application package and therefore also kills the provider process.

The caller and provider must therefore be placed in distinct killable processes within the same package for T2:

```xml
android:process=":caller"
android:process=":provider"
```

This preserves the existing T2 goal of isolating process lifetime from package/discovery/authorization variables.

A separate package remains deferred to T3.

## 3. Caller semantic state

Do not add a production `RunStatus.UNKNOWN` solely to make this experiment work. First define and test the smaller caller contract in a T2-specific store.

Candidate caller states:

```text
NEW
DISPATCH_RESERVED
DISPATCHING
UNKNOWN_OUTCOME
RECONCILING
COMPLETED
KNOWN_NOT_EXECUTED
FAILED
```

The important distinction is:

```text
UNKNOWN_OUTCOME
    = the caller cannot establish whether the provider effect happened

KNOWN_NOT_EXECUTED
    = evidence establishes that the provider did not create/execute the operation

COMPLETED
    = durable provider evidence or an equivalent authoritative observation
       establishes completion
```

Transport exceptions must not directly become `FAILED` when a side effect may already have occurred.

## 4. Durable caller record

Before dispatch, the caller must persist enough information to reconstruct the exact semantic operation after its own process disappears.

Minimum record:

```text
operation_id
run_id
capability_id
capability_version
effect_id
request_fingerprint
parameters/reference metadata
caller_state
attempt_id
provider identity/topology used for the experiment
```

The critical property is that recovery must reuse the durable operation/effect identity rather than generating a fresh logical operation.

### Important current-code observation

`AgentRuntime.effectId()` currently derives a stable UUID from `actionId + actionVersion + capabilityId + idempotencyKey` when a non-blank idempotency key exists, but generates a random UUID when no idempotency key is supplied. `RuntimeStore` already persists the resulting `effectId` in an `EffectRecord` when an invocation is reserved.

Therefore caller recovery must reconstruct from the durable reserved `EffectRecord` (or an equivalent durable invocation record) rather than calling the planning/materialization path and silently generating a new identity.

This is an **OPEN design requirement**, not yet a code change.

## 5. B4 — caller dies after provider completion

Fault sequence:

```text
caller process A
    |
    | persist DISPATCH_RESERVED
    |
    | invoke provider with operation_id
    v
provider process B
    |
    | persist RECEIVED
    | apply deterministic effect
    | persist EFFECT_APPLIED / COMPLETED
    | reply
    v
caller receives provider success
    |
    | [fault injection]
    X caller process dies before local COMPLETED persistence
```

The provider effect must already exist before the caller dies.

After caller restart:

```text
caller process C
    |
    | load durable caller record
    v
DISPATCH_RESERVED / DISPATCHING
    |
    v
classify UNKNOWN_OUTCOME
    |
    v
RECONCILING
    |
    | provider lookup(operation_id)
    v
COMPLETED
```

Acceptance:

```text
provider effect_count == 1
caller terminal state == COMPLETED
reconciliation performs no second effect
```

The test must prove that the caller did not merely remember the successful Binder reply in memory.

## 6. B5 — caller dies before dispatch

Fault sequence:

```text
caller process A
    |
    | persist DISPATCH_RESERVED
    X process dies before Binder dispatch

caller process C
    |
    | load operation_id
    |
    | provider lookup(operation_id)
    v
```

The provider must report an authoritative absence for this operation. A generic transport failure or an exception such as "required value was null" is not sufficient evidence of non-execution.

Therefore B5 requires a provider observation with an explicit absence outcome, for example:

```text
ABSENT
RECEIVED
EFFECT_APPLIED
COMPLETED
```

or an equivalent typed lookup response.

Only `ABSENT` can permit:

```text
retry original operation_id
```

Acceptance:

```text
provider effect_count == 1 after exactly one safe dispatch
```

and:

```text
no previous provider record existed before that dispatch
```

This is an important correction to the earlier simplistic idea that "no reply" implies "not executed".

## 7. Required provider API extension for B5

The current minimal T2 provider has `getState(operationId)`, but B5 needs an explicit negative result for an operation that has never been received.

Do not encode this as an exception.

Candidate experiment-only API:

```text
String lookupState(String operationId)
```

with:

```text
ABSENT
RECEIVED
EFFECT_APPLIED
COMPLETED
```

This is preferable to using a nullable/exceptional transport path because it keeps:

```text
known absence
    !=
unknown transport outcome
```

The provider contract can remain experiment-specific until T2 is complete.

## 8. Explicit caller self-termination fault injection

The caller process should terminate itself only after the semantic checkpoint required by the case has been persisted.

The test process remains outside the caller process and controls the experiment via a Binder/test-only interface.

### B4 injection

```text
caller persists DISPATCH_RESERVED
caller dispatches provider operation
provider completes effect and reply reaches caller
caller invokes local fault point
caller terminates itself before COMPLETED append
```

### B5 injection

```text
caller persists DISPATCH_RESERVED
caller reaches pre-dispatch fault point
caller terminates itself
```

The test runner records the caller PID/process-instance token before and after recovery, just as T2 already records provider process identity.

## 9. Process instance identity

The caller needs a durable/process-local instance identifier similar to the provider:

```text
callerProcessInstanceId = random UUID per process start
```

The experiment must observe:

```text
caller process instance A
        !=
caller process instance C
```

This proves that the test actually crossed a process boundary rather than merely recreated an in-memory service object.

## 10. B6 preparation — concurrent recovery

B6 should not begin until B4/B5 establish the caller recovery record and lookup contract.

Candidate race:

```text
caller process C1           caller process C2
       |                           |
       | load operation X          | load operation X
       |                           |
       +------------+--------------+
                    |
                    v
             reconcile(X)
```

The acceptance invariant is:

```text
non-repeatable effect count <= 1
```

A process-local JVM mutex is explicitly insufficient.

The external reference research suggests a keyed coordination model:

```text
operation_id -> serialization/ownership boundary
```

The precise implementation remains OPEN.

## 11. B7 preparation — stale results

B7 requires an ordering identity beyond `operation_id`.

Candidate metadata:

```text
operation_id
attempt_id
state_version
```

Example:

```text
attempt A: version 1
attempt B: version 2

B completes first
A reply arrives later

A must not regress durable state established by B.
```

A timestamp alone is not an acceptable semantic ordering primitive because clock ordering is not the same as causal/authority ordering.

The exact rule should be selected before implementation and then tested with delayed callbacks.

## 12. Required invariants

### I-CALLER-1 — durable intent precedes dispatch

If a caller can restart and recover an operation, the durable operation record exists before the external dispatch begins.

### I-CALLER-2 — ambiguity is preserved

Caller/process/transport loss cannot by itself produce semantic success or definitive failure when the provider effect may have happened.

### I-CALLER-3 — reconciliation is operation-identity based

Recovery targets the exact durable `operation_id` and stored effect identity.

### I-CALLER-4 — known absence is explicit

B5 can only classify an operation as not executed when the provider returns explicit authoritative absence.

### I-CALLER-5 — reconciliation does not reapply an established effect

If provider state is `EFFECT_APPLIED` or `COMPLETED`, reconciliation must preserve `effect_count`.

### I-CALLER-6 — process identity changes across injected death

Caller process instance A and recovered process instance C must differ.

### I-CALLER-7 — recovery is monotonic

Recovery may add knowledge but must not erase established facts.

## 13. Failure interpretation

The experiment is **FAIL** when:

```text
caller creates a second operation identity during recovery
provider effect count becomes > 1 for a non-repeatable effect
UNKNOWN_OUTCOME is silently converted to retry without reconciliation
absence is inferred from transport error alone
stale response regresses a newer terminal state
caller process did not actually terminate and restart
```

The experiment is **INCONCLUSIVE** when instrumentation or device instability prevents attribution of the observed failure to the intended boundary.

## 14. External references used for this design

See `EXTERNAL_REFERENCE_ARCHITECTURES_2026-09-17.md` for the full research record.

The most directly relevant external ideas are:

- Temporal: durable history + workflow/activity separation.
- DBOS: checkpoint/effect atomicity as a distinct question.
- Cloudflare Durable Objects: keyed coordination and explicit concurrency boundaries.
- Stripe: operation identity plus request-parameter matching.
- Transactional Outbox / Idempotent Consumer: delivery is not semantic completion; duplicate-safe receivers are necessary.
- Android Binder: death notification is a transport/process signal, not a semantic outcome.

## 15. Execution order

```text
1. Add experiment-only caller process.
2. Add durable caller record/state machine.
3. Add explicit provider ABSENT lookup result.
4. Execute B5 first.
5. Execute B4 second.
6. Verify caller process-instance change.
7. Verify provider effect count and recovered caller state.
8. Only then design/execute B6 concurrency.
9. Then design/execute B7 stale-result ordering.
```

No Room, WorkManager, cross-package provider, or general workflow engine is required for this gate.

## 16. Status

**OPEN / READY FOR IMPLEMENTATION**

This document defines the next discriminating experiment. It does not promote any B4/B5/B6/B7 claim until real instrumentation evidence exists.
