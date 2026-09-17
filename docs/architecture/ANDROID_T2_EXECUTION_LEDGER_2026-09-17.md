# Android T2 Binder Failure-Injection — Execution Ledger — 2026-09-17

Status: **CASE-LEVEL EXPERIMENTALLY SUPPORTED / GENERAL T2 STILL OPEN**

Purpose: preserve the evidence chain for every T2 implementation/test iteration. This ledger records why each change was made and prevents a harness failure from being promoted into a platform or semantic conclusion.

## Scope

The experiment under test is defined in `ANDROID_T2_BINDER_FAILURE_INJECTION_SPEC_2026-09-16.md`.

The implementation deliberately remains narrow:

```text
instrumentation caller
    -> Binder
    -> same-package provider service in :provider
    -> durable provider operation record
```

No Room, WorkManager, ContentProvider, AppFunctions, second-package trust model, or general exactly-once abstraction has been introduced.

## Evidence discipline

A T2 case is promoted only when:

1. the failure is attributable to the intended fault boundary;
2. the assertions test the semantic invariant rather than a harness assumption;
3. the run completes on the real API 35 emulator;
4. the observed provider state and effect count support the claimed transition.

A passing build is not a T2 pass.

## Iteration history

### Run #259 — initial post-recursion-fix execution

Run ID: `35214218631`

Commit: `4b30e87ee5173a0db3d7c48164ff889e9c3d48f6`

Change immediately before run:

- fixed recursive `getProcessInstanceId()` property/method name shadowing in `T2ProviderService`;
- no semantic test change was made at that point.

Observed:

- build job: PASS;
- unit tests: PASS;
- debug APK: PASS;
- instrumentation: FAIL.

Initial interpretation was that `IllegalArgumentException: Required value was null.` was a Binder process-death manifestation.

That interpretation is **RETRACTED** for the failing first test, because the next diagnostic run proved that the exception happened before the injected process death.

### Run #260 — broadened expected Binder-death exception handling

Run ID: `35215028530`

Commit: `cb05db0f3bc673bdca0d1b18764803ba43564330`

Change:

- `expectProviderDeath(...)` was changed to accept either `RemoteException` or the exact observed `IllegalArgumentException("Required value was null.")` signature.

Reason:

- isolate possible API-35 transport representation differences without changing provider semantics.

Observed:

- build job: PASS;
- unit tests: PASS;
- instrumentation: FAIL;
- same first-test exception remained.

Result:

- the broader catch did not fix the failure;
- this was evidence that the exception might not originate inside `expectProviderDeath(...)` at all.

### Run #261 — stage-labelled diagnostic execution

Run ID: `35215646117`

Commit: `534833c6b086f6146148c7d847745909ed4c4c62`

Change:

- important Binder/test steps were wrapped in `T2_STAGE[...]` diagnostic boundaries.

Reason:

- identify the exact operation producing the exception instead of inferring from the top-level stack trace.

Observed first failure:

```text
T2_STAGE[initial.getEffectCount] failed:
java.lang.IllegalArgumentException: Required value was null.
```

This established the actual harness defect.

## Root cause established

**ESTABLISHED:** the failing first test queried the provider for `effectCount` before the provider had ever received the `operation_id`.

`T2ProviderStore.get(operationId)` returns `null` for an unknown operation, and `T2ProviderService.getEffectCount(...)` requires an existing operation. Therefore the pre-receipt query threw before the fault-injection RPC executed.

Consequences:

- the Run #259/#260 exception was not evidence of Binder process-death semantics;
- B3 had not yet produced a valid recovery observation in those runs;
- promoting those runs as T2 evidence would have been incorrect.

## Corrective change

Commit: `6952b651149e0f0d533403871768e2bdc954e935`

Message: `test(t2): remove pre-receipt operation query`

The invalid precondition was removed from the test.

The test now establishes the operation through the injected `execute(...)` call first, then queries durable provider state only after process recovery.

The test comment explicitly records why the pre-receipt query is excluded:

```text
The provider has not received this operation yet. Querying its
state here would test an unknown-operation error, not T2 recovery.
```

No provider implementation change was made.

## Valid execution evidence

### Run #262 — first clean T2 execution

Run ID: `35216212638`

Commit under test: `6952b651149e0f0d533403871768e2bdc954e935`

Environment:

- GitHub Actions Ubuntu 24.04 runner
- JDK 17
- Gradle 8.9
- Android API 35
- Google APIs system image
- x86_64 emulator
- Pixel 7 Pro hardware profile

Build result:

- build job: PASS
- unit tests: PASS
- debug APK: PASS
- instrumentation: PASS
- 2 instrumentation tests executed and finished successfully

### B2 — provider receives durable operation, dies before effect

Test: `providerReceiptSurvivesDeathBeforeEffectAndCanBeExecutedAfterRecovery`

Observed assertions:

```text
initial provider process instance
    !=
recovered provider process instance

recovered state = RECEIVED
recovered effect_count = 0

retry after recovery
    -> state = COMPLETED
    -> effect_count = 1
```

Classification: **EXPERIMENTALLY_SUPPORTED**, limited to this modeled topology and API-35 emulator run.

Meaning supported by the evidence:

- the operation receipt survived provider-process loss;
- recovery reached a new provider process instance;
- no side effect was recorded before the injected death;
- execution could safely continue after recovery.

### B3 — effect persists, provider dies before reply

Test: `effectPersistedBeforeProviderDeathIsReconciledWithoutReexecution`

Observed assertions:

```text
initial provider process instance
    !=
recovered provider process instance

recovered state = EFFECT_APPLIED
recovered effect_count = 1

reconcile
    -> state = COMPLETED
    -> effect_count remains 1
```

Classification: **EXPERIMENTALLY_SUPPORTED**, limited to this modeled topology and API-35 emulator run.

Meaning supported by the evidence:

- the durable operation record survived provider-process loss;
- the effect was known to have been applied after recovery;
- reconciliation completed the operation without invoking the side effect again;
- the observed effect count remained exactly one in this experiment.

## Current claim frontier

### ESTABLISHED

The current provider-side T2 model survives the two tested provider-process failure boundaries on the API-35 emulator:

1. durable receipt before process death, with no effect yet;
2. durable effect before process death, with the reply unavailable.

The evidence is case-level, not a general Android reliability guarantee.

### EXPERIMENTALLY_SUPPORTED

I-T2-1 is supported for B2/B3 insofar as transport/process loss did not produce a semantic completion by itself and recovery required reading durable provider state.

I-T2-2 is supported for B3 in the tested path: reconciliation preceded completion and the effect count remained one.

I-T2-3 is supported for B2/B3: a known operation could be recovered after the provider process instance changed.

I-T2-4 is supported for these cases because the provider retained the effect state/count needed to distinguish RECEIVED from EFFECT_APPLIED.

I-T2-6 is supported narrowly by the cross-process observation: the tested recovery path did not depend on an in-process lock to prevent duplicate effect application.

### OPEN / NOT TESTED

The following remain open:

- B1 provider unavailable before dispatch
- B4 caller dies after provider completion but before caller-side durable completion
- B5 caller dies before dispatch
- B6 concurrent duplicate recovery
- B7 stale callback/result ordering
- I-T2-5 stale-result rejection
- caller-side durable `UNKNOWN_OUTCOME` persistence and recovery
- caller/provider end-to-end reconciliation through `AgentRuntime`
- cross-package authorization/discovery
- reboot/force-stop/update behavior
- power-loss durability
- exactly-once semantics as a general guarantee

## Why we do not broaden the architecture now

The clean B2/B3 result validates only the narrow provider-process failure boundary. The unresolved cases concern different ownership or lifecycle boundaries and should be isolated in separate experiments.

Therefore:

- do not add WorkManager as domain truth;
- do not add Room merely to “make it durable”;
- do not generalize the provider contract yet;
- do not promote exactly-once;
- do not move to T3 until the remaining caller-side and concurrency questions are explicitly scoped.

## Next gate

The next engineering step is a targeted review of B4/B5/B6/B7 and the caller-side `UNKNOWN_OUTCOME` boundary. T3 should only start after that review establishes which remaining invariants require a new experiment rather than a change to the existing T2 model.
