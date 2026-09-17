# Android T2 Binder Failure-Injection — Execution Ledger — 2026-09-17

Status: **DIAGNOSTIC EXECUTION / NO T2 PROMOTION YET**

Purpose: preserve the evidence chain for every T2 implementation/test iteration. This ledger records why each change was made and prevents a harness failure from being promoted into a platform or semantic conclusion.

## Scope

The experiment under test is defined in `ANDROID_T2_BINDER_FAILURE_INJECTION_SPEC_2026-09-16.md`.

The current implementation deliberately remains narrow:

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

Initial interpretation was that `IllegalArgumentException: Required value was null.` from `android.os.Parcel.createExceptionOrNull(...)` was a Binder process-death manifestation.

That interpretation is now **RETRACTED** for the failing first test, because the next diagnostic run proved that the exception happened before the injected process death.

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

- every important Binder/test step was wrapped in a `T2_STAGE[...]` diagnostic boundary.

Reason:

- identify the exact operation producing the exception instead of inferring from the top-level stack trace.

Observed first failure:

```text
T2_STAGE[initial.getEffectCount] failed:
java.lang.IllegalArgumentException: Required value was null.
```

This is the decisive diagnostic result.

## Root cause established

**ESTABLISHED:** the failing first test queried the provider for `effectCount` before the provider had ever received the `operation_id`.

`T2ProviderStore.get(operationId)` returns `null` for an unknown operation. `T2ProviderService.getEffectCount(...)` requires an existing operation before returning its count. Therefore the pre-receipt query throws before the fault-injection RPC executes.

Consequences:

- the failing Run #259/#260 first-case exception was not evidence of Binder process-death semantics;
- B3 (effect persisted before provider death) had not yet produced a valid recovery observation in those runs;
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

## Current evidence classification

### Build/runtime

**ESTABLISHED:** the stage-labelled branch in Run #261 compiled and the build job passed, including unit tests and debug APK generation.

### B2 — death after durable receipt, before effect

**UNRESOLVED / NOT PROMOTED.** The second instrumentation test did not appear in the failure list for Runs #259–#261, but no dedicated case-level evidence record has yet been promoted from these runs.

### B3 — effect persisted, then provider dies before reply

**OPEN.** The pre-receipt test defect prevented a valid B3 observation. The next run is the first clean attempt at this case.

### General T2 hypothesis

**OPEN.** No claim of cross-process recoverability, exactly-once behavior, or general Binder reliability is promoted yet.

## Why we do not broaden the architecture now

The failure was localized to the experiment harness, not the domain model or durable provider store. Introducing Room, WorkManager, a new provider package, or additional abstractions would add variables while the current fault boundary is still unresolved.

The correct next move is therefore another focused T2 run against commit `6952b651149e0f0d533403871768e2bdc954e935`.

## Promotion gate after the next run

Only after a clean run:

1. record the exact emulator/API/build context;
2. record B2 and B3 observations separately;
3. map each observation to the applicable T2 invariant;
4. update the T2 specification with case-level evidence;
5. keep unsupported B6/B7/B4/B5 claims explicitly OPEN;
6. only then decide whether T3 is justified.
