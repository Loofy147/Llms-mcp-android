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
- CapabilityExecutor is the controlled execution boundary for materialized CapabilityInvocations;
- terminal Runs are immutable and replay attempts must not re-execute effects.

## 2. Reconciliation matrix

| Area | Target architecture | Current implementation | Status | Next proof/fix |
|---|---|---|---|---|
| Action semantics | Reusable Action above CapabilityInvocation | ActionDefinition declares ActionPlan and performs effect-free reduction of capability results | PROVISIONALLY ALIGNED | Validate with real capability adapters |
| Capability execution | Every effect crosses one controlled executor boundary | CapabilityExecutor receives only validated, durably reserved invocations; missing executors fail closed | PROVISIONALLY VERIFIED | Add Android/device adapter and negative bypass tests |
| Invocation identity | Explicit invocation/effect identity and attribution | Invocation id, effectId, action/version, scope, attribution; stable idempotency key supported | PROVISIONALLY VERIFIED | Validate identity across real side effects |
| Effect reservation | No effect executes before durable duplicate decision | Runtime materializes the ActionPlan and reserves all effect identities before executor calls | PROVISIONALLY VERIFIED | Expand reconciliation/unknown-state recovery |
| Run state | Durable lifecycle and restart recovery | JournalRuntimeStore persists Run snapshots; runtime accepts durable store | PARTIAL | Android restart integration validation |
| Evidence | Attributable observations + verification + invocations | Evidence persisted as part of Run journal snapshots | PARTIAL | Define immutable evidence/append-only audit contract |
| Direct execution | Action can run without Model | Deterministic path covered by unit tests and executor-backed proof | PROVISIONALLY VERIFIED | Android/real-device validation |
| Policy | Independent authorization boundary | Minimal in-memory PolicyEngine | PARTIAL | Persistent policy/control-plane path |
| Approval | Distinct from denial and bound to operation/context | Durable ApprovalStore persists exact Run/action/version/input/plan fingerprint and consumes decisions once | PROVISIONALLY VERIFIED | Android approval UI + restart integration |
| Egress | Explicit local decision before protected data leaves device | Provider requests cross EgressPolicy; current Android policy allows only HTTPS to explicit `api.anthropic.com` and rejects unlisted/non-HTTPS destinations | PROVISIONALLY VERIFIED | Richer classification/minimization/redaction and Android integration |
| Model | Optional/provider-neutral | UI depends on ModelProvider; Anthropic is an adapter | PROVISIONALLY ALIGNED | Alternate provider test/adapter |
| MCP | Adapter behind internal semantics | MCP remains inside current Anthropic adapter and provider-side execution path | PARTIAL | Introduce internal MCP adapter boundary |
| Secrets | Keystore-backed credential boundary | CredentialStore uses Android Keystore; legacy plaintext migrated/removed | PROVISIONALLY ALIGNED | Android migration + backup/log review |
| Settings | Preferences separate from secrets | SettingsStore and CredentialStore separated; removal explicitly deletes MCP credential | PROVISIONALLY ALIGNED | Verify backup/export/logging surfaces |
| Activation | Shared entry for UI/automation/model/external surfaces | Runtime accepts ActivationSource; UI/model paths converge on AssistantRuntime/AgentRuntime | PARTIAL | Android-native activation adapters |
| Profiles | Personal/Developer/Product share invariants | Not implemented | OPEN | After runtime core stabilizes |
| Background execution | Android lifecycle-aware | No scheduler integrated into runtime | OPEN | WorkManager adapter after durable lifecycle is proven |

## 3. Active boundaries

### C-01 — Anthropic coupling — CONTROLLED AT APP BOUNDARY

The application exposes a provider-neutral `ModelProvider`; `AnthropicModelProvider` and `AnthropicClient` contain vendor-specific transport/streaming semantics.

Remaining limitation: the adapter still owns the current conversation/MCP implementation, so provider neutrality is not yet a complete runtime property.

### C-02 — Plaintext secret persistence — RESOLVED AT STORAGE BOUNDARY

Credentials are separated from ordinary settings and protected with an Android Keystore-backed boundary.

Remaining limitation: device-level migration and backup/export/crash/log review remain open.

### C-03 — Tool versus Action/Capability — CONTROLLED

`ToolRegistry` is descriptive-only. Model tool calls are translated by `RuntimeToolGateway` into `ActivationSource.MODEL` requests and run through `AgentRuntime`.

Rule: no model-facing tool may become a second runtime authority model.

### C-04 — Evidence durability — IMPROVED, NOT CLOSED

Evidence is included in durable Run journal snapshots through `JournalRuntimeStore` and can be restored across store instances.

Rule: do not call this an immutable audit ledger until append-only evidence semantics, corruption handling, and Android restart validation are proven.

### C-05 — Approval context — PROVISIONALLY VERIFIED

Approval context is durable, exact-bound, and one-use. The stored authorization binds Run identity, requester identity, Action/version, input, planned invocations, and a fingerprint. Terminal approval replays return the immutable terminal Run and do not execute again.

Remaining limitation: approval presentation/resumption is not yet integrated into Android UI and process-death integration is still open.

### C-06 — Effect identity and reconciliation — PARTIAL

Stable effect identity is backed by a durable reservation journal. A repeated effect is blocked before executor invocation, including after a new store instance reads the journal.

Remaining limitation: a reserved effect can become `UNKNOWN` after a crash or failed execution, and capability-specific reconciliation is not yet integrated.

### C-07 — Direct Action side effects — RESOLVED FOR CURRENT CORE CONTRACT

The runtime no longer calls an effect-capable Action closure. `ActionDefinition.reduce` is the effect-free reduction stage; capability effects are routed through `CapabilityExecutor`.

Remaining limitation: this is an API/architecture boundary, not a proof that every future executor implementation is safe. Executor adapters must preserve invocation authority and must not self-grant permissions.

### C-08 — Remote egress — PROVISIONALLY VERIFIED

All current remote provider requests cross an injected `EgressPolicy` before HTTP execution. The current Android composition explicitly allowlists `api.anthropic.com` and the policy rejects non-HTTPS, unlisted hosts, and credentials embedded in destination URLs.

Remaining limitation: the policy currently classifies broad data categories but does not yet implement minimization/redaction or per-data-class destination policy.

## 4. Evidence classification

- **ESTABLISHED** — architectural decision explicitly accepted.
- **PROVISIONALLY VERIFIED** — covered by deterministic automated tests but not yet real-device/integration verified.
- **PARTIAL** — implementation exists but omits required production properties.
- **OPEN** — no implementation/evidence yet.
- **CONFLICT** — current implementation contradicts an accepted invariant/decision.
- **REJECTED** — intentionally excluded from the active design.

## 5. Correction order

### Completed convergence gates

1. Introduce canonical ActionPlan effect declarations.
2. Make effect identity deterministic when an idempotency key is supplied.
3. Validate declared capabilities and scope before execution.
4. Reserve the complete effect set before execution.
5. Persist Run state and Evidence snapshots through an append-only journal.
6. Block replay across independent store instances using the same durable journal.
7. Mark reserved effects `UNKNOWN` when execution cannot establish a verified completion.
8. Move capability execution behind a dedicated CapabilityExecutor boundary.
9. Persist approval context bound to exact Action/version/input/plan and consume decisions once.
10. Route model tool calls through the canonical runtime path.
11. Remove direct execution responsibility from ToolRegistry.
12. Introduce the first explicit local EgressPolicy boundary before remote provider requests.

### Next correction gates

13. Add Android approval presentation/resumption and process-death validation.
14. Add capability-specific reconciliation for `UNKNOWN` effects.
15. Define immutable evidence/append-only audit semantics.
16. Add richer egress classification, minimization, and redaction.
17. Add real Android/device CapabilityExecutor adapters.
18. Move MCP from the vendor adapter to an internal protocol adapter boundary.
19. Only then expand activation surfaces and richer Actions.

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
