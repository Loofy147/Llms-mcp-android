# Implementation Reconciliation v0.2

Status: Active engineering baseline
Date: 2026-09-05

This document freezes the distinction between accepted architecture and demonstrated implementation. It is the primary implementation-status matrix after the application-wide runtime convergence merge.

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
- terminal Runs are immutable for the modeled approval/replay paths and replay attempts must not re-execute effects;
- post-approval re-evaluation is terminal: only ALLOW may proceed to execution.

## 2. Reconciliation matrix

| Area | Target architecture | Current implementation | Status | Next proof/fix |
|---|---|---|---|---|
| Action semantics | Reusable Action above CapabilityInvocation | `ActionDefinition` declares an `ActionPlan`; `reduce` is effect-free | PROVISIONALLY ALIGNED | Add richer Action contracts and real adapters |
| Capability execution | Every material effect crosses one controlled executor boundary | `CapabilityExecutor` receives only validated, durably reserved invocations | PROVISIONALLY VERIFIED | Add real Android/device adapters and bypass tests |
| Invocation identity | Explicit invocation/effect identity and attribution | Invocation id, effectId, Action/version, scope, attribution; stable idempotency key supported | PROVISIONALLY VERIFIED | Validate identity against real side effects |
| Effect reservation | No effect executes before a durable duplicate decision | Complete materialized effect set is reserved before executor calls | PROVISIONALLY VERIFIED | Capability-specific reconciliation proof |
| Run state | Durable lifecycle and restart recovery | `JournalRuntimeStore` persists Run snapshots; Android factory uses it by default | PARTIAL | Android process-death integration |
| Evidence | Attributable observations + verification + invocations | `Evidence` is part of Run and is journal-persisted | PARTIAL | Immutable append-only evidence/audit contract |
| Direct execution | Action can run without Model | Native deterministic Actions execute through the runtime without a model | PROVISIONALLY VERIFIED | Real-device validation |
| Policy | Independent authorization boundary | `PolicyEngine` performs local allow/deny/approval admission; post-approval only ALLOW executes | PROVISIONALLY VERIFIED | Persistent policy/profile model |
| Approval | Distinct from denial and bound to operation/context | `JournalApprovalStore` persists exact Run/requester/Action/version/input/plan fingerprint and consumes once | PROVISIONALLY VERIFIED | Android approval UI + restart integration |
| Egress | Explicit local decision before protected data leaves device | Current Anthropic requests cross `EgressPolicy`; HTTPS + host + data-class checks are enforced | PROVISIONALLY VERIFIED | Minimization/redaction and fine-grained policy |
| Model | Optional/provider-neutral | `ModelProvider` boundary with Anthropic adapter | PROVISIONALLY ALIGNED | Alternate provider integration test |
| MCP | Adapter behind internal semantics | Current MCP connector remains inside Anthropic/provider path | PARTIAL | Native internal MCP adapter |
| Secrets | Protected credential boundary | `CredentialStore` uses Android Keystore-backed AES/GCM; legacy plaintext migration exists | PROVISIONALLY ALIGNED | Backup/log/crash/export review |
| Settings | Preferences separate from secrets | `SettingsStore` and `CredentialStore` are separated; removed MCP servers trigger credential cleanup | PROVISIONALLY VERIFIED | Broader persistence/privacy review |
| Activation | Shared entry for UI/automation/model/external surfaces | Runtime accepts `ActivationSource`; current Chat/model path converges through `AssistantRuntime` | PARTIAL | Android-native activation adapters |
| Profiles | Personal/Developer/Product share invariants | Profile-specific runtime policy is not implemented | OPEN | Implement only after runtime core stabilizes |
| Background execution | Android lifecycle-aware | No general scheduler/runtime background adapter is integrated | OPEN | WorkManager adapter after lifecycle proof |

## 3. Active boundaries

### C-01 — Anthropic coupling — CONTROLLED AT APP BOUNDARY

The application exposes a provider-neutral `ModelProvider`; `AnthropicModelProvider` and `AnthropicClient` contain vendor-specific transport/streaming semantics.

Remaining limitation: the current conversation/MCP implementation still lives in the provider adapter, so provider neutrality is not yet a complete runtime property.

### C-02 — Plaintext secret persistence — CONTROLLED AT STORAGE BOUNDARY

Credentials are separated from ordinary settings and protected with Android Keystore-backed AES/GCM storage.

Remaining limitation: device migration behavior and backup/export/crash/log review are still open.

### C-03 — Tool versus Action/Capability — CONTROLLED

`ToolRegistry` is descriptive-only. Local model tool calls are translated by `RuntimeToolGateway` into `ActivationSource.MODEL` requests and run through `AgentRuntime`.

Rule: no model-facing tool may become a second runtime authority model.

### C-04 — Evidence durability — IMPROVED, NOT CLOSED

Evidence is included in durable Run journal snapshots through `JournalRuntimeStore` and can be restored across store instances.

Rule: do not call this an immutable audit ledger until append-only evidence semantics, corruption handling, retention, and Android restart validation are proven.

### C-05 — Approval context — PROVISIONALLY VERIFIED

Approval context is durable, exact-bound, and one-use. The stored authorization binds Run identity, requester identity, Action/version, input, planned invocations, and a fingerprint. Approval replay on a terminal Run returns the stored terminal result; post-approval policy re-evaluation permits execution only for `ALLOW`.

Remaining limitation: approval presentation/resumption is not yet integrated into Android UI and process-death validation is still open.

### C-06 — Effect identity and reconciliation — PROVISIONALLY VERIFIED LOCALLY, NOT CLOSED EXTERNALLY

Stable effect identity is backed by a durable reservation journal. A repeated effect is blocked before executor invocation across store instances using the same journal. Process-start recovery converts stale `RESERVED` effects to explicit `UNKNOWN`. Reconciliation can confirm an unknown effect as completed or not executed; only the latter permits a new reservation.

Remaining limitation: this does not establish exactly-once external execution, and capability-specific reconciliation is not integrated.

### C-07 — Direct Action side effects — RESOLVED FOR CURRENT CORE CONTRACT

The runtime no longer calls an effect-capable Action closure. `ActionDefinition.reduce` is the effect-free reduction stage; capability effects are routed through `CapabilityExecutor`.

Remaining limitation: this protects the core API boundary, not the behavior of arbitrary future executor adapters.

### C-08 — Remote egress — PROVISIONALLY VERIFIED

Current remote provider requests cross an injected `EgressPolicy` before HTTP execution. The Android composition explicitly allowlists `api.anthropic.com`; policy also rejects non-HTTPS destinations, embedded destination credentials, and disallowed declared data classes.

Remaining limitation: the current gate is admission/classification, not content minimization/redaction or per-data-class destination policy.

## 4. Evidence classification

- **ESTABLISHED** — architectural decision explicitly accepted.
- **PROVISIONALLY VERIFIED** — covered by deterministic automated tests but not yet real-device/integration verified.
- **PROVISIONALLY ALIGNED** — implementation matches the semantic boundary, but proof depth is limited.
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
7. Convert stale reserved effects to `UNKNOWN` on process-start recovery and provide explicit reconciliation outcomes.
8. Move capability execution behind a dedicated CapabilityExecutor boundary.
9. Persist approval context bound to exact Action/version/input/plan and consume decisions once.
10. Route model tool calls through the canonical runtime path.
11. Remove direct execution responsibility from ToolRegistry.
12. Introduce the first explicit local EgressPolicy boundary before remote provider requests.
13. Enforce post-approval policy terminality: only `ALLOW` may transition into execution.
14. Make MCP credential cleanup part of the `SettingsStore.save()` invariant rather than relying only on a UI deletion path.

### Next correction gates

15. Add Android approval presentation/resumption and process-death validation.
16. Add capability-specific reconciliation for `UNKNOWN` effects.
17. Define immutable evidence/append-only audit semantics.
18. Add richer egress classification, minimization, and redaction.
19. Add real Android/device CapabilityExecutor adapters.
20. Move MCP from the vendor adapter to an internal protocol adapter boundary.
21. Add broader Android-native activation adapters.
22. Only then expand richer Actions, profiles, and deferred/background execution.

## 6. Non-goals

This pass does not introduce a workflow engine, capability graph, multi-agent coordinator, plugin marketplace, server tenancy, or generalized orchestration layer. These remain rejected until measured workloads demonstrate a missing primitive.

## 7. Promotion gate

The runtime slice may be promoted from a vertical proof to a stable core candidate only when:

- deterministic direct execution is green in CI;
- policy cannot be bypassed by an alternative activation path;
- approval is distinguishable and non-replayable;
- protected data has an explicit egress decision;
- Run/Evidence survive Android process death/restart with documented recovery semantics;
- effect identity has durable duplicate and reconciliation semantics against at least one real external effect;
- capability effects execute only through the controlled executor boundary;
- model selection uses a provider-neutral boundary;
- no secret is persisted in ordinary plaintext settings;
- at least one Android-native activation adapter reaches the same runtime path.

## 8. Current CI evidence

As of 2026-09-05, merge commit `0fccaea041c9dc39a1183f44418897e43433f73a` on `main` completed workflow run `147` successfully. The workflow is configured to run JVM unit tests, assemble the debug APK, and upload the APK artifact. This is CI evidence for the merged source; it is not device-level evidence.
