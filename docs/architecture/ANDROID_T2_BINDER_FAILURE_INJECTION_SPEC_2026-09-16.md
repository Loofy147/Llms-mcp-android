# Android T2 Binder Failure-Injection Experiment — 2026-09-16

Status: **CASE-LEVEL EXPERIMENTALLY SUPPORTED / GENERAL T2 STILL OPEN**

Purpose: isolate cross-process ambiguity and reconciliation without introducing Room, WorkManager, AppFunctions, ContentProvider, or a second application architecture.

The experiment is deliberately designed to falsify the durable-effect model.

## 1. Hypothesis under test

**HYPOTHESIS:** a side-effecting Android capability can be made recoverable across provider-process loss when the provider and caller exchange a durable `operation_id`, the provider can reconcile that identity after restart, and the caller treats ambiguous transport loss as `UNKNOWN` rather than immediate failure/retry.

**Case-level result:** provider-side B2/B3 and caller-side B4/B5 are experimentally supported on the documented API-35 emulator topology. Caller recovery remains experiment-specific and the general T2 hypothesis remains partial because B1/B6/B7 and end-to-end production-runtime recovery remain open.

This experiment does **not** test exactly-once as a general platform property.

## 2. Minimal topology

```text
Instrumentation runner
      |             |
      v             v
:caller process   :provider process
      |             |
      +---- Binder --+
                       |
                       +--> durable provider-side operation record
                       +--> deterministic side effect
```

Both caller and provider are Android services running in separate processes of the same installed application. This keeps package/signing/discovery variables out of the T2 failure boundary while exercising independent process lifetimes. A later T3 experiment may move the provider to a distinct package/application.

## 3. Provider contract

Minimal request:

```text
operation_id
capability_id
capability_version
input/reference metadata
fault_mode
```

Provider-side durable record:

```text
operation_id
capability_id
capability_version
state
side_effect_count
result_reference/result_digest when available
last_updated
```

Provider semantic states:

```text
RECEIVED
EFFECT_APPLIED
COMPLETED
```

Caller-side experiment states:

```text
DISPATCH_RESERVED
DISPATCHING
UNKNOWN_OUTCOME
RECONCILING
COMPLETED
KNOWN_NOT_EXECUTED
```

## 4. Fault injection points

Each case changes exactly one fault boundary.

### B1 — provider unavailable before dispatch

Expected:

```text
caller -> provider unavailable / no dispatch
provider side effect count = 0
caller may classify as known-not-executed only if absence is established
```

**Status: OPEN.**

### B2 — provider accepts request, dies before durable effect

Expected:

```text
caller = UNKNOWN until reconciliation
provider after restart = RECEIVED/effect_count=0
```

**Execution result: EXPERIMENTALLY_SUPPORTED at provider level.** The tested provider recovered the durable state as `RECEIVED` with `effect_count = 0`, then a post-recovery execution transitioned it to `COMPLETED` with `effect_count = 1`.

### B3 — provider persists acceptance/effect, dies before reply

Expected:

```text
caller = UNKNOWN_OUTCOME
provider after restart = EFFECT_APPLIED
reconciliation = COMPLETED
no repeated side effect
```

**Execution result: EXPERIMENTALLY_SUPPORTED at provider/reconciliation level.** The tested provider recovered as `EFFECT_APPLIED` with `effect_count = 1`; reconciliation transitioned it to `COMPLETED` while the effect count remained `1`.

### B4 — provider completes effect and reply is delivered, caller dies before local completion persist

Expected:

```text
provider = COMPLETED
caller after restart = UNKNOWN until provider reconciliation
reconciliation = COMPLETED
no repeated effect
```

**Execution result: EXPERIMENTALLY_SUPPORTED, case-level.** Run #281 killed the caller process after the provider completed and replied but before caller `COMPLETED` persistence. A new caller process recovered the durable caller record, reconciled the same `operation_id`, and reached `COMPLETED` while provider `effect_count` remained `1`.

### B5 — caller dies before dispatch

Expected:

```text
no provider effect
recovery may safely re-dispatch only after durable state proves dispatch never occurred
```

**Execution result: EXPERIMENTALLY_SUPPORTED, case-level.** Run #281 persisted the caller dispatch checkpoint, killed the caller before provider dispatch, observed explicit provider `ABSENT`, then safely dispatched the same operation identity exactly once and reached `COMPLETED` with provider `effect_count = 1`.

### B6 — duplicate recovery attempts

Two caller instances/processes attempt the same `operation_id` concurrently.

Expected:

```text
provider effect count <= 1
or
provider explicitly reports an already-known operation
```

The caller must not rely on process-local locking for this result.

**Status: OPEN.** No concurrent recovery test was executed.

### B7 — stale callback

Attempt N completes after attempt N+1 has already become authoritative.

Expected:

```text
late result from N cannot regress terminal state of N+1
```

Every reply/callback must carry sufficient identity to establish:

```text
operation_id
attempt_id
provider/capability version
```

**Status: OPEN.** No stale-result ordering test was executed.

## 5. Measurement

Every case records:

```text
experiment_id
case_id
hardware
Android release
API level
build fingerprint
application version
provider process/version
caller process/version
operation_id
attempt_id
initial caller state
fault injection point
caller observation
provider durable observation
side_effect_count
reconciliation request/result
final domain state
final artifact/result reference
journal/state record
limits
```

For B2/B3 the decisive provider measurements were process-instance identity, recovered operation state, and durable effect count. For B4/B5 the decisive caller measurements additionally include caller process-instance identity and recovered caller state.

## 6. Acceptance invariants

### I-T2-1 — Ambiguity is preserved

Transport/process loss cannot by itself become semantic success or definitive failure when the external effect may have occurred.

**Case-level support:** B2/B3/B4 required recovery from the relevant process-loss boundary before terminal completion could be established.

### I-T2-2 — Reconciliation precedes repeated side effect

No second-side-effect dispatch for the same semantic effect may occur while its first attempt remains `UNKNOWN`.

**Case-level support:** B3 and B4 reconciliation completed the operation while `effect_count` remained `1`.

### I-T2-3 — Provider identity is durable

The provider can answer reconciliation for a known `operation_id` after its process has restarted.

**Case-level support:** B2/B3 observed changed provider process instance and recovered the pre-existing operation record.

### I-T2-4 — Per-effect truth is retained

A completed effect cannot later be rewritten to `UNKNOWN` merely because another effect in the same Action failed.

**Case-level support:** provider-side tests retain the distinction between `RECEIVED`/`effect_count=0` and `EFFECT_APPLIED`/`effect_count=1` across process replacement.

### I-T2-5 — Stale results are rejected

An older attempt cannot overwrite a newer authoritative state.

**Status: OPEN.** Requires B7.

### I-T2-6 — Local duplicate prevention is not the proof

Passing because of an in-process mutex is insufficient. The decisive evidence must survive an actual process boundary.

**Case-level support:** B2/B3/B4 crossed actual process boundaries and validated durable state afterward.

### I-T2-7 — Caller durable intent precedes external dispatch

A caller operation that may need recovery has a durable checkpoint before the provider invocation starts.

**Case-level support:** B4/B5 used the experiment-only caller store; the recovered caller process loaded the persisted operation record after process replacement.

### I-T2-8 — Known absence is explicit

Non-execution cannot be inferred solely from a transport exception; the provider must expose explicit absence for B5.

**Case-level support:** B5 used `lookupState(operation_id) = ABSENT` before permitting redispatch.

### I-T2-9 — Caller recovery is monotonic in tested paths

Recovery may add knowledge and reach a terminal state without erasing the provider-side effect fact.

**Case-level support:** B4/B5 recovered to caller `COMPLETED` while the provider effect count remained consistent with the established execution history.

## 7. Failure outcomes

A case is **PASS** only when all applicable invariants hold.

A case is **FAIL** if any of the following occurs:

```text
side effect duplicated
UNKNOWN silently converted to retry
stale callback regresses terminal state
provider cannot reconcile after restart
caller cannot distinguish known completion from ambiguity
caller process did not actually terminate and restart
known absence is inferred from transport error alone
```

A case is **INCONCLUSIVE** when the failure cannot be attributed to the tested semantic boundary, such as device instability, instrumentation failure, or an unrecorded platform event.

## 8. What this experiment does not establish

Even with B2/B3/B4/B5 case-level results, T2 does not establish:

- exactly-once execution for arbitrary applications;
- power-loss durability of every storage configuration;
- WorkManager correctness;
- reboot behavior;
- force-stop semantics;
- ContentProvider/AppFunction semantics;
- cross-package trust/authorization;
- URI/BlobStore artifact durability;
- production reliability;
- end-to-end `AgentRuntime` caller recovery;
- concurrent duplicate recovery;
- stale callback rejection.

Those remain separate experiments/cases.

## 9. Promotion sequence

```text
T0 — pure JVM effect/reconciliation semantics
  ↓
T1 — local Android persistent-store/restart proof
  ↓
T2 — real Binder cross-process failure injection + caller recovery
  ↓
T3 — distinct provider package + Android authorization/discovery
  ↓
T4 — WorkManager scheduler adapter
  ↓
T5 — reboot / force-stop / package-update matrix
```

The project must not skip directly to a generalized workflow engine.

## 10. Execution record

### Run #262 — first clean provider-side T2 execution

Run ID: `35216212638`

Commit: `6952b651149e0f0d533403871768e2bdc954e935`

Environment:

- GitHub Actions Ubuntu 24.04
- JDK 17
- Gradle 8.9
- Android API 35
- Google APIs x86_64 image
- Pixel 7 Pro hardware profile

Results:

```text
build job                 PASS
unit tests                PASS
debug APK                 PASS
instrumentation tests     PASS
instrumentation cases    2/2
```

Case evidence:

```text
B2:
  process instance changed
  recovered state = RECEIVED
  recovered effect_count = 0
  post-recovery execute -> COMPLETED / effect_count = 1

B3:
  process instance changed
  recovered state = EFFECT_APPLIED
  recovered effect_count = 1
  reconcile -> COMPLETED / effect_count remains 1
```

### Run #281 — caller-side B4/B5 execution

Run ID: `35220752022`

Commit: `912fe77914b69ca12b84e01d4428693a366184f8`

Environment:

- GitHub Actions Ubuntu 24.04
- JDK 17
- Gradle 8.9
- Android API 35
- Google APIs x86_64 image
- Pixel 7 Pro hardware profile

Results:

```text
build job                 PASS
unit tests                PASS
debug APK                 PASS
instrumentation tests     PASS
instrumentation cases    4/4
failed                    0
skipped                   0
```

B4 evidence:

```text
provider state before caller recovery = COMPLETED
provider effect_count = 1
caller process instance changed
caller recovery -> COMPLETED
provider effect_count after recovery = 1
```

B5 evidence:

```text
provider lookup before dispatch = ABSENT
caller process instance changed
caller recovery -> ABSENT observed -> safe dispatch
caller = COMPLETED
provider = COMPLETED
provider effect_count = 1
```

A detailed chronological evidence ledger is maintained in `ANDROID_T2_EXECUTION_LEDGER_2026-09-17.md`.

## 11. Current status

**T2 now has case-level experimental support for B2, B3, B4, and B5** on the documented API-35 emulator topology.

The general T2 hypothesis remains **OPEN/PARTIAL** because B1, concurrent recovery B6, stale-result ordering B7, and end-to-end production-runtime caller recovery have not been exercised.
