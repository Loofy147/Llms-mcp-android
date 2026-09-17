# Architecture State Synchronization — 2026-09-17

Status: **CURRENT DOCUMENTATION AUTHORITY / T2 B2-B5 CASE-LEVEL PROOF INTEGRATED**

Purpose: establish one auditable current-state boundary after the T2 Binder failure-injection experiments. Historical audits remain unchanged unless a later correction explicitly updates them; this file defines which conclusions are current and which older statements are historical.

## 1. Authority rule

When documents disagree:

1. repository code at the referenced commit is the implementation fact;
2. executed CI/device evidence is the experiment fact;
3. this synchronization document defines the current cross-document interpretation;
4. older research audits retain historical value but must not be read as current implementation status when they predate later evidence.

This prevents historical notes from silently becoming current architecture requirements.

## 2. Current semantic architecture

```text
DOMAIN
  Action / Capability / Run / Effect / Evidence
        |
        v
DURABILITY
  RuntimeStore / JournalRuntimeStore / ApprovalStore
        |
        v
EXECUTION
  CapabilityExecutor
        |
        v
ANDROID ADAPTERS
  Binder / lifecycle / permissions / future WorkManager / future AppFunctions
        |
        v
EXPERIMENTS
  T0 / T1 / T2 / later T3-T5
```

The domain model does not depend on an Android transport mechanism. Android adapters implement the domain contract; experiments establish the contract at a specific boundary.

## 3. Current T2 result

**EXPERIMENTALLY_SUPPORTED, case-level only:** Run #262 established provider-side B2/B3 recovery, and Run #281 established caller-side B4/B5 recovery on the documented API-35 Google APIs x86_64 emulator with the Pixel 7 Pro profile.

### B2

```text
provider process A
    -> durable RECEIVED record
    -> injected process death before effect
    -> provider process B
    -> RECEIVED / effect_count=0
    -> safe post-recovery execute
    -> COMPLETED / effect_count=1
```

### B3

```text
provider process A
    -> durable EFFECT_APPLIED record / effect_count=1
    -> injected process death before reply
    -> provider process B
    -> EFFECT_APPLIED / effect_count=1
    -> explicit reconcile
    -> COMPLETED / effect count remains 1
```

### B4

```text
caller process A
    -> durable dispatch checkpoint
    -> provider completes effect / effect_count=1
    -> provider reply reaches caller
    -> injected caller-process death before caller COMPLETED persist
    -> caller process C
    -> load durable caller operation
    -> reconcile by operation_id
    -> caller COMPLETED
    -> provider effect_count remains 1
```

### B5

```text
caller process A
    -> durable dispatch-reserved checkpoint
    -> injected caller-process death before provider dispatch
    -> caller process C
    -> provider lookup = ABSENT
    -> safe dispatch of the same operation identity
    -> caller COMPLETED
    -> provider COMPLETED / effect_count=1
```

These cases support provider-side and caller-side durable operation recovery across real process replacement in this tested same-package topology.

They do **not** prove concurrent recovery, stale callbacks, reboot, force-stop, package update, cross-package authorization, power-loss durability, end-to-end `AgentRuntime` recovery, or general exactly-once execution.

Primary evidence:
- `docs/architecture/ANDROID_T2_BINDER_FAILURE_INJECTION_SPEC_2026-09-16.md`
- `docs/architecture/ANDROID_T2_EXECUTION_LEDGER_2026-09-17.md`
- `docs/architecture/ANDROID_T2_CALLER_RECOVERY_DESIGN_2026-09-17.md`
- GitHub Actions Run #262
- GitHub Actions Run #281

## 4. Corrected interpretation of earlier T2 failures

Runs #259 and #260 must not be treated as Binder-death evidence. Run #261 added `T2_STAGE[...]` diagnostics and established that the failure happened at an invalid pre-receipt `getEffectCount(operation_id)` query.

The exception therefore represented an unknown-operation assertion in the provider test path, not the intended process-death fault boundary.

This interpretation is explicitly recorded as **RETRACTED** in the T2 execution ledger.

## 5. Current implementation correction carried into T2

The pre-T2 `AgentRuntime` per-effect truth issue was corrected before the Binder experiment:

```text
successful invocation
    -> complete that effect immediately

failed/ambiguous invocation
    -> mark that effect UNKNOWN

later failure
    -> must not erase established facts for earlier effects
```

The corresponding deterministic test was also corrected to assert per-effect reconciliation semantics.

This is current implementation state, not merely a future recommendation.

## 6. T2 implementation boundary

The provider-side T2 implementation remains intentionally narrow. Caller recovery is also currently implemented as an experiment-only process/store pair so that B4/B5 do not alter production runtime semantics prematurely.

Actual provider transport includes:

```text
execute(operation_id, fault_mode)
reconcile(operation_id)
getState(operation_id)
getEffectCount(operation_id)
getPid()
getProcessInstanceId()
lookupState(operation_id) -> ABSENT / RECEIVED / EFFECT_APPLIED / COMPLETED
```

The experiment-only caller transport includes operation start, recovery, state inspection, PID, and process-instance identity.

The conceptual future provider contract may additionally carry capability identity, capability version, artifact references, authorization metadata, result references, and provider package/version. Those fields are not needed to isolate the tested T2 failure boundaries.

The T2 implementation therefore proves a failure/reconciliation mechanism at case level, not the complete cross-app capability protocol.

## 7. Documentation map

### Current normative/operational references

**T2 specification**
- `ANDROID_T2_BINDER_FAILURE_INJECTION_SPEC_2026-09-16.md`
- status: case-level experimentally supported; B2/B3/B4/B5 supported, B1/B6/B7 remain open.

**T2 execution ledger**
- `ANDROID_T2_EXECUTION_LEDGER_2026-09-17.md`
- status: chronological evidence and claim frontier through Run #281.

**Caller recovery design**
- `ANDROID_T2_CALLER_RECOVERY_DESIGN_2026-09-17.md`
- status: executed for B4/B5; B6/B7 remain design gates.

**Recovery state-machine audit**
- `ANDROID_CROSS_APP_RECOVERY_STATE_MACHINE_AUDIT_2026-09-16.md`
- synchronized on 2026-09-17; T2 B2/B3 integrated, T3+ remaining.

**External reference research**
- `EXTERNAL_REFERENCE_ARCHITECTURES_2026-09-17.md`
- status: current research input to B6/B7 and later architecture choices.

**This document**
- `ARCHITECTURE_STATE_SYNC_2026-09-17.md`
- authority map for resolving document drift.

### Historical research records

`ANDROID_VERTICAL_SLICE_PREIMPLEMENTATION_AUDIT_2026-09-16.md` remains the original pre-implementation research gate. Its pre-T2 statements are historical observations. Do not interpret statements such as “process-death evidence is not yet present” as the current repository status; later runs supersede that statement for the tested provider and caller process boundaries.

`DURABLE_RUNTIME_GATE_v0.1.md` is an earlier gate dated 2026-09-05. Its original promotion criteria remain useful as historical gate design, while this document and the T2 records define the newer execution evidence.

Other Android platform/data-plane audits are research records and remain authoritative for their cited platform facts, but they do not supersede the T2 execution ledger for experiment status.

## 8. Current evidence frontier

| Area | Current classification | Evidence boundary |
|---|---|---|
| Local durable runtime semantics | ESTABLISHED / EXPERIMENTALLY_SUPPORTED | JVM + Android journal tests |
| Per-effect truth preservation | EXPERIMENTALLY_SUPPORTED | deterministic effect-boundary tests |
| B2 provider receipt survives provider-process loss | EXPERIMENTALLY_SUPPORTED | Run #262, API 35 emulator |
| B3 effect survives provider-process loss and reconciles without reexecution | EXPERIMENTALLY_SUPPORTED | Run #262, API 35 emulator |
| B4 caller dies after provider completion and recovers without duplicate effect | EXPERIMENTALLY_SUPPORTED | Run #281, API 35 emulator |
| B5 caller dies before dispatch and safely recovers only after explicit ABSENT | EXPERIMENTALLY_SUPPORTED | Run #281, API 35 emulator |
| Caller durable UNKNOWN_OUTCOME boundary | EXPERIMENTALLY_SUPPORTED, case-level | B4/B5 experiment-only caller store/process topology |
| B1 provider unavailable before dispatch | OPEN | not yet executed |
| B6 concurrent recovery | OPEN | not yet executed |
| B7 stale callback/result ordering | OPEN | not yet executed |
| Cross-package trust/discovery | OPEN | T3 |
| WorkManager scheduling adapter | OPEN | T4 |
| reboot / force-stop / package update | OPEN | T5 |
| power-loss durability | OPEN | separate durability experiment |
| end-to-end AgentRuntime caller recovery | OPEN | not yet exercised |
| general exactly-once | NOT CLAIMED | provider/case-specific property only |

## 9. Current next gate

Do not add Room or WorkManager as a response to B4/B5. The next discriminating work is the concurrency boundary:

```text
same operation_id
    -> two recovery actors/processes
    -> concurrent reconciliation / retry race
    -> one authoritative effect
    -> no duplicate execution
```

The leading external reference pattern is operation-keyed coordination: the implementation should define an ownership/serialization boundary for one semantic operation without relying on a process-local mutex.

After B6, isolate stale-result ordering (B7):

```text
attempt N
attempt N+1 becomes authoritative
late result from N
    -> must not regress durable state
```

Only after these boundaries are explicitly scoped should T3 introduce a distinct provider package/application and Android discovery/authorization variables.

## 10. Merge gate

The branch may be fast-forwarded into `main` only when:

1. the final branch head contains the synchronized documentation;
2. the final head's unit/build/instrumentation workflow is green;
3. Run #262 remains preserved as the provider-side B2/B3 proof;
4. Run #281 remains preserved as the caller-side B4/B5 proof;
5. no document claims unsupported T2 generality;
6. `main` has no divergence that would require a merge conflict.

The merge itself does not upgrade any OPEN claim to ESTABLISHED.
