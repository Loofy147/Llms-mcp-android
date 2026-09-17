# Architecture State Synchronization — 2026-09-17

Status: **CURRENT DOCUMENTATION AUTHORITY / T2 CASE-LEVEL PROOF INTEGRATED / EXTERNAL REFERENCE SYNCED**

Purpose: establish one auditable current-state boundary after the T2 Binder failure-injection experiment and synchronize that state with the 2026-09-17 external architecture reviews of Anthropic and Meta Muse. Historical audits remain unchanged unless a later correction explicitly updates them; this file defines which conclusions are current and which older statements are historical.

## 1. Authority rule

When documents disagree:

1. repository code at the referenced commit is the implementation fact;
2. executed CI/device evidence is the experiment fact;
3. this synchronization document defines the current cross-document interpretation;
4. older research audits retain historical value but must not be read as current implementation status when they predate later evidence;
5. external vendor research is reference evidence only and cannot promote a local claim without local implementation/test evidence.

This prevents historical notes or external product claims from silently becoming current architecture requirements.

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

**EXPERIMENTALLY_SUPPORTED, case-level only:** Run #262 (`35216212638`) executed two real instrumentation cases on the API-35 Google APIs x86_64 emulator with the Pixel 7 Pro profile.

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
    -> COMPLETED / effect_count remains 1
```

These cases support provider-side durable operation identity and reconciliation across a real provider-process boundary in this tested topology.

They do **not** prove caller-side durable `UNKNOWN_OUTCOME`, concurrent recovery, stale callbacks, reboot, force-stop, package update, cross-package authorization, power-loss durability, or general exactly-once execution.

Primary evidence:
- `docs/architecture/ANDROID_T2_BINDER_FAILURE_INJECTION_SPEC_2026-09-16.md`
- `docs/architecture/ANDROID_T2_EXECUTION_LEDGER_2026-09-17.md`
- GitHub Actions Run #262

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

The T2 provider is intentionally narrower than the future durable orchestration contract.

Actual test transport:

```text
execute(operation_id, fault_mode)
reconcile(operation_id)
getState(operation_id)
getEffectCount(operation_id)
getPid()
getProcessInstanceId()
```

The conceptual future provider contract may additionally carry capability identity, capability version, artifact references, authorization metadata, result references, and provider package/version. Those fields are not needed to isolate the first Binder failure boundary.

The T2 implementation therefore proves a failure/reconciliation mechanism, not the complete cross-app capability protocol.

## 7. Documentation map

### Current normative/operational references

**T2 specification**
- `ANDROID_T2_BINDER_FAILURE_INJECTION_SPEC_2026-09-16.md`
- status: case-level experimentally supported; B2/B3 supported, remaining cases open.

**T2 execution ledger**
- `ANDROID_T2_EXECUTION_LEDGER_2026-09-17.md`
- status: chronological evidence and claim frontier.

**Recovery state-machine audit**
- `ANDROID_CROSS_APP_RECOVERY_STATE_MACHINE_AUDIT_2026-09-16.md`
- synchronized on 2026-09-17; T2 B2/B3 integrated, T3+ remaining.

**Anthropic external reference review**
- `ANTHROPIC_REFERENCE_REVIEW_2026-09-17.md`
- status: external research record; no local implementation claim is upgraded by it.

**Meta Muse external reference review**
- `MUSE_REFERENCE_REVIEW_2026-09-17.md`
- status: external research record; no local implementation claim is upgraded by it.

**External adoption deltas**
- `ANTHROPIC_ADOPTION_DELTAS_2026-09-17.md`
- `MUSE_ADOPTION_DELTAS_2026-09-17.md`
- status: compact research-to-engineering bridges.

**This document**
- `ARCHITECTURE_STATE_SYNC_2026-09-17.md`
- authority map for resolving document drift and current evidence status.

### Historical research records

`ANDROID_VERTICAL_SLICE_PREIMPLEMENTATION_AUDIT_2026-09-16.md` remains the original pre-implementation research gate. Its pre-T2 statements are historical observations. Do not interpret statements such as “process-death evidence is not yet present” as the current repository status; Run #262 supersedes that statement for the provider-process B2/B3 boundary.

`DURABLE_RUNTIME_GATE_v0.1.md` is an earlier gate dated 2026-09-05. Its original promotion criteria remain useful as historical gate design, while this document and the T2 records define the newer execution evidence.

Other Android platform/data-plane audits are research records and remain authoritative for their cited platform facts, but they do not supersede the T2 execution ledger for experiment status.

## 8. Current evidence frontier

| Area | Current classification | Evidence boundary |
|---|---|---|
| Local durable runtime semantics | ESTABLISHED / EXPERIMENTALLY_SUPPORTED | JVM + Android journal tests |
| Per-effect truth preservation | EXPERIMENTALLY_SUPPORTED | deterministic effect-boundary tests |
| B2 provider receipt survives provider-process loss | EXPERIMENTALLY_SUPPORTED | Run #262, API 35 emulator |
| B3 effect survives provider-process loss and reconciles without reexecution | EXPERIMENTALLY_SUPPORTED | Run #262, API 35 emulator |
| Caller durable UNKNOWN_OUTCOME | OPEN | not yet executed |
| B1 provider unavailable before dispatch | OPEN | not yet executed |
| B4 caller dies after provider completion | OPEN | not yet executed |
| B5 caller dies before dispatch | OPEN | not yet executed |
| B6 concurrent recovery | OPEN | not yet executed |
| B7 stale callback/result ordering | OPEN | not yet executed |
| Cross-package trust/discovery | OPEN | T3 |
| WorkManager scheduling adapter | OPEN | T4 |
| reboot / force-stop / package update | OPEN | T5 |
| power-loss durability | OPEN | separate durability experiment |
| general exactly-once | NOT CLAIMED | provider-specific property only |
| Credential-use isolation | OPEN | current Keystore storage does not prove secret-flow isolation |
| Provenance-aware egress | OPEN | coarse data-class admission exists; source-aware enforcement not proven |
| Durable session/event model | OPEN / HYPOTHESIS | determine need from B4-B7 before adding a new semantic layer |
| Environment containment for high-power capabilities | OPEN / FUTURE GATE | current built-ins are narrow; no general VM/sandbox commitment |
| Browser capability broker | FUTURE ADAPTER | no current browser implementation |

## 8.1 External reference synchronization — Anthropic

The 2026-09-17 Anthropic review is treated as external architecture evidence. It confirms useful patterns already present in our design and identifies concrete next gaps:

```text
Managed agent / harness
    != execution environment
    != durable session state

Credential reference
    != credential value

Policy
    != human approval
    != risk classifier

Session/event history
    != sandbox lifetime
    != memory
    != evidence
```

Highest-value local implications:

1. **Credential-use isolation:** a future `CredentialRef` path should keep raw secret values out of model/tool/action/evidence/logging boundaries and resolve them only at a protected transport boundary.
2. **Provenance-aware egress:** extend the current destination/data-class gate only after a discriminating local invariant is defined.
3. **Caller-side durability:** use the external session/event pattern as reference while preserving our own Run/Effect semantics; B4/B5 remain the next proof gate.
4. **Conditional event log:** add an explicit execution-event model only if B4-B7 expose information that Run/Effect state cannot represent safely.
5. **Containment:** high-power capabilities eventually require execution-environment restrictions in addition to authorization; this does not justify adding a generic VM/sandbox to the Android core now.
6. **MCP:** keep authentication, authorization, server declaration, and execution lifecycle as separate concerns; re-audit against current MCP authorization/Tasks semantics when native MCP extraction begins.

## 8.2 External reference synchronization — Meta Muse

Muse adds a second independent reference for the same frontier. Meta documents Secure VM isolation, Sentinel enforcement at connector/network boundaries, scoped approval, just-in-time credential insertion, layer-4/7 egress enforcement, tainted egress, brokered browser access, durable audit/replay concepts, and explicit acknowledgement that prompt injection remains an open problem.

The useful local translation is:

```text
execution environment
    != durable semantic state

Sentinel-like enforcement
    = future separation of authorization from model/runtime when needed

Credential surrogate/reference
    != real secret

data provenance
    -> egress authorization

browser power
    -> narrow brokered capability
```

Muse does **not** justify adding Secure VM/eBPF/browser infrastructure now. The current next proof remains caller-side recovery. If B4-B7 require a new durable event primitive, add the smallest one that closes the demonstrated semantic gap.

Primary external sources:
- https://ai.meta.com/muse/
- https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/
- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse
- https://research.meta.ai/blog/addressing-third-party-testing-misconfiguration-muse-spark-1-1

Vendor-reported architecture or evaluation results do not upgrade local claims.

## 8.3 Combined cross-vendor synthesis

The two independent external reviews converge on the same design boundary:

```text
Reasoning
   -> Activation
   -> Policy / Approval / Risk signal
   -> Authorization
   -> CapabilityRef + CredentialRef
   -> Execution environment
   -> Effect
   -> Observation / Verification
   -> Evidence
   -> Durable Run / Event history
   -> Session / Memory as separate semantic layers
```

This is a local synthesis, not a claim that either vendor exposes this exact graph.

## 8.4 Current synchronized decisions

Preserve:

```text
Model != Authority
Tool != Authority
Preference != Authority
Policy != Approval
Approval != Risk Classifier
CredentialRef != CredentialValue
MCP != Canonical Domain Model
Process != Durable Semantic Identity
Sandbox != Durable Semantic Identity
```

Do not add yet:

```text
Generic event bus
Generic VM/sandbox in the APK
Kernel-level eBPF taint replication
Browser infrastructure before a real browser capability
Multi-agent coordinator
Cloud tenancy layer
ML risk classifier as authority
WorkManager as domain truth
```

These positions are recorded in `DECISION_REGISTER_v0.2.md` and the detailed external research records.

## 9. Current next gate

Do not add Room or WorkManager as a response to T2. The next discriminating work is the caller-side boundary:

```text
caller durable dispatch/checkpoint
    -> provider process loss / completed provider effect
    -> caller process loss
    -> restart
    -> classify UNKNOWN_OUTCOME
    -> reconcile by operation_id
    -> commit without duplicate effect
```

Then isolate concurrent recovery (B6) and stale-result ordering (B7).

After those recovery proofs:

```text
credential-use isolation
    -> provenance-aware egress
    -> event-log sufficiency test
    -> native MCP security/lifecycle re-audit
    -> high-power capability containment experiments
```

Only after these boundaries are explicitly scoped should T3 introduce a distinct provider package/application and Android discovery/authorization variables.

## 10. Merge gate

The branch may be fast-forwarded into `main` only when:

1. the final branch head contains the synchronized documentation;
2. the final head's unit/build/instrumentation workflow is green;
3. Run #262 remains preserved as the case-level execution proof for B2/B3;
4. no document claims unsupported T2 generality;
5. external reference notes are clearly marked as external evidence rather than local proof;
6. `main` has no divergence that would require a merge conflict.

The merge itself does not upgrade any OPEN claim to ESTABLISHED.
