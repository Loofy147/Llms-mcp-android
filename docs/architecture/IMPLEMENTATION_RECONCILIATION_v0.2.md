# Implementation Reconciliation v0.2

Status: Active engineering baseline
Date: 2026-09-04

This document freezes the distinction between accepted architecture and demonstrated implementation. It prevents architecture claims from being mistaken for completed implementation.

## 1. Established semantic decisions

- the application owns the local agent-mediated control boundary;
- Model is optional reasoning, never authorization;
- Activation is the common entry contract;
- Capability is the primitive controlled effect;
- Action is the reusable execution contract above Capabilities;
- Tool is an exposure/interface mechanism, not authority;
- direct deterministic Actions are valid without a Model;
- Policy, Approval, and Egress are independent control decisions;
- Run, Verification, and Evidence are execution concepts;
- Mission is optional rather than mandatory for short actions;
- external protocols/providers remain adapters;
- ActionPlan is the canonical declaration of capability effects;
- CapabilityExecutor is the controlled execution boundary for materialized CapabilityInvocations.

## 2. Reconciliation matrix

| Area | Target architecture | Current implementation | Status | Next proof/fix |
|---|---|---|---|---|
| Action semantics | Reusable Action above CapabilityInvocation | ActionDefinition declares ActionPlan and performs effect-free reduction of capability results | PROVISIONALLY ALIGNED | Validate with real capability adapters |
| Capability execution | Every effect crosses one controlled executor boundary | CapabilityExecutor receives only validated, durably reserved invocations; missing executors fail closed | PROVISIONALLY VERIFIED | Add Android/device adapter and negative bypass tests |
| Invocation identity | Explicit invocation/effect identity and attribution | Invocation id, effectId, action/version, scope, attribution; stable idempotency key supported | PROVISIONALLY VERIFIED | Validate identity across real side effects |
| Effect reservation | No effect executes before durable duplicate decision | Runtime materializes the ActionPlan and atomically reserves all effect identities before executor calls | PROVISIONALLY VERIFIED | Add reconciliation/unknown-state recovery |
| Run state | Durable lifecycle and restart recovery | JournalRuntimeStore persists Run snapshots; runtime accepts a durable store | PARTIAL | Wire durable store into application lifecycle and test Android restart |
| Evidence | Attributable observations + verification + invocations | Evidence persisted as part of Run journal snapshots | PARTIAL | Define immutable evidence/append-only audit contract |
| Direct execution | Action can run without Model | Deterministic path covered by unit tests and executor-backed proof | PROVISIONALLY VERIFIED | Android/real-device validation |
| Policy | Independent authorization boundary | Minimal in-memory PolicyEngine | PARTIAL | Persistent policy/control-plane path |
| Approval | Distinct from denial and bound to operation/context | WAITING_APPROVAL state only | PARTIAL | Persist approval context and replay protection |
| Egress | Explicit local decision before protected data leaves device | Not implemented | OPEN | Introduce EgressDecision before remote model/MCP effects |
| Model | Optional/provider-neutral | UI depends on ModelProvider; Anthropic is an adapter | PROVISIONALLY ALIGNED | Alternate provider test/adapter |
| MCP | Adapter behind internal semantics | MCP remains inside current Anthropic adapter | PARTIAL | Introduce internal MCP adapter boundary |
| Secrets | Keystore-backed credential boundary | CredentialStore uses Android Keystore; legacy plaintext migrated/removed | PROVISIONALLY ALIGNED | Android migration + backup/log review |
| Settings | Preferences separate from secrets | SettingsStore and CredentialStore separated | PROVISIONALLY ALIGNED | Verify backup/export/logging surfaces |
| Activation | Shared entry for UI/automation/model/external surfaces | Runtime accepts ActivationSource | PARTIAL | Android-native activation adapters |
| Profiles | Personal/Developer/Product share invariants | Not implemented | OPEN | After runtime core stabilizes |
| Background execution | Android lifecycle-aware | No scheduler integrated into runtime | OPEN | WorkManager adapter after durable lifecycle is proven |

## 3. Active contradictions and boundaries

### C-01 — Anthropic coupling — RESOLVED AT UI BOUNDARY

The UI uses provider-neutral `ModelProvider`; `AnthropicModelProvider` contains the vendor-specific implementation.

Remaining limitation: the adapter still owns the current conversation/MCP implementation, so provider neutrality is not yet a complete runtime property.

### C-02 — Plaintext secret persistence — RESOLVED AT STORAGE BOUNDARY

Credentials are separated from ordinary settings and protected with an Android Keystore-backed boundary.

Remaining limitation: device-level migration and backup/export/crash/log review remain open.

### C-03 — Tool versus Action/Capability — CONTROLLED FOR NEW RUNTIME CODE

Legacy ToolRegistry remains where the existing provider adapter needs it. New runtime semantics do not use Tool as authority.

Rule: legacy tool operations must map into Action/Capability semantics rather than creating a second runtime authority model.

### C-04 — Evidence durability — IMPROVED, NOT CLOSED

Evidence is included in durable Run journal snapshots through `JournalRuntimeStore` and can be restored across store instances.

Rule: do not call this an immutable audit ledger until append-only evidence semantics, corruption handling, and Android restart validation are proven.

### C-05 — Approval without approval context — OPEN BY DESIGN

`WAITING_APPROVAL` remains semantically distinct from `DENIED`, but approval is not yet a durable, non-replayable authorization artifact.

### C-06 — Effect identity without full reconciliation — PARTIAL

Stable effect identity is backed by an atomic durable reservation journal. A repeated effect is blocked before executor invocation, including after a new store instance reads the journal.

Remaining limitation: a reserved effect can become `UNKNOWN` after a crash or failed execution, and no reconciliation protocol yet decides whether it may safely resume.

### C-07 — Direct Action side effects — RESOLVED FOR NEW CORE CONTRACT

The runtime no longer calls an effect-capable Action closure. `ActionDefinition.reduce` is the effect-free reduction stage; capability effects are routed through `CapabilityExecutor`.

Remaining limitation: this is an API/architecture boundary, not a proof that every future executor implementation is safe. Executor adapters must preserve invocation authority and must not self-grant permissions.

## 4. Evidence classification

- **ESTABLISHED** — architectural decision explicitly accepted.
- **PROVISIONALLY VERIFIED** — covered by deterministic automated tests but not yet real-device/integration verified.
- **PARTIAL** — implementation exists but omits required production properties.
- **OPEN** — no implementation/evidence yet.
- **CONFLICT** — current implementation contradicts an accepted invariant/decision.
- **REJECTED** — intentionally excluded from the active design.

## 5. Correction order

### Completed in the durable-effects slice

1. Introduce canonical ActionPlan effect declarations.
2. Make effect identity deterministic when an idempotency key is supplied.
3. Validate declared capabilities and scope before execution.
4. Atomically reserve the complete effect set before execution.
5. Persist Run state and Evidence snapshots through an append-only journal.
6. Block replay across independent store instances using the same durable journal.
7. Mark reserved effects `UNKNOWN` when execution cannot establish a verified completion.
8. Move capability execution behind a dedicated CapabilityExecutor boundary.

### Next correction gates

9. Add persistent approval context bound to exact Action/version/scope/input and a one-use decision.
10. Introduce an explicit EgressDecision contract before protected remote model/MCP flows.
11. Add effect reconciliation for `UNKNOWN` state and capability-specific recovery semantics.
12. Add real Android/device CapabilityExecutor adapters and Android process-death validation.
13. Move MCP from the vendor adapter to an internal protocol adapter boundary.
14. Only then expand activation surfaces and richer Actions.

## 6. Non-goals

This pass does not introduce a workflow engine, capability graph, multi-agent coordinator, plugin marketplace, server tenancy, or generalized orchestration layer. These remain rejected until measured workloads demonstrate a missing primitive.

## 7. Promotion gate

The runtime slice may be promoted from a vertical proof to a stable core candidate only when:

- deterministic direct execution is green in CI;
- policy cannot be bypassed by an alternative activation path;
- approval is distinguishable and non-replayable;
- protected data has an explicit egress decision;
- Run/Evidence survive process death/restart on Android;
- effect identity has durable duplicate and reconciliation semantics;
- capability effects execute only through the controlled executor boundary;
- model selection uses a provider-neutral boundary;
- no secret is persisted in ordinary settings;
- at least one Android-native activation adapter reaches the same runtime path.
