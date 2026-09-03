# Implementation Reconciliation v0.2

Status: Active engineering baseline
Date: 2026-09-03

This document freezes the distinction between the architecture we have accepted and the implementation state we have actually demonstrated. It prevents architecture claims from being mistaken for completed implementation.

## 1. What is now established

The v0.2 architecture establishes these semantic decisions:

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
- external protocols/providers remain adapters.

These decisions are recorded in the Decision Register and North Star architecture. They are architectural commitments, not claims that every implementation slice is complete.

## 2. Reconciliation matrix

| Area | Target architecture | Current implementation | Status | Next proof/fix |
|---|---|---|---|---|
| Action semantics | Reusable Action above CapabilityInvocation | Minimal ActionDefinition + catalog | PARTIAL | Add explicit invocation/effect identity |
| Direct execution | Action can run without Model | Demonstrated in unit tests | PROVISIONALLY VERIFIED | Run on Android/real integration |
| Policy | Independent authorization boundary | Minimal in-memory PolicyEngine | PARTIAL | Move policy check to persistent control path |
| Approval | Distinct from denial and bound to operation/context | WAITING_APPROVAL state only | PARTIAL | Persist approval request/context and replay protection |
| Egress | Explicit local decision before protected data leaves device | Not implemented | OPEN | Introduce egress decision contract before remote model/MCP work |
| Evidence | Attributable observations + verification | In-memory Evidence record | PARTIAL | Durable evidence storage |
| Recovery | Durable Run/attempt/effect identity | UUID Run only, in-memory | OPEN | Persist lifecycle and effect identity before external effects |
| Model | Optional/provider-neutral | UI still calls AnthropicClient directly | CONFLICT | Introduce provider-neutral ModelProvider adapter |
| MCP | Adapter behind internal semantics | Existing chat path delegates MCP to Anthropic | CONFLICT | Move MCP behind an internal adapter boundary |
| Secrets | Keystore-backed credential boundary | Legacy plaintext SharedPreferences path exists | CONFLICT -> FIXING | Migrate API/MCP credentials to Keystore-backed storage |
| Settings | Ordinary preferences separate from secrets | SharedPreferences contains both | CONFLICT -> FIXING | Keep settings store non-secret |
| Activation | Shared entry for UI/automation/model/external surfaces | Runtime accepts ActivationSource | PARTIAL | Add adapters for additional activation surfaces |
| Profiles | Personal/Developer/Product share invariants | Not implemented | OPEN | Model profiles after core runtime is stable |
| Background execution | Android lifecycle-aware | No dedicated runtime scheduler yet | OPEN | WorkManager adapter only after durable Run semantics |

## 3. Contradictions to actively eliminate

### C-01 — Anthropic coupling

The architecture says providers are replaceable adapters, while the current UI constructs `AnthropicClient` directly. This is a real boundary violation, not a naming issue.

**Rule:** UI/domain code must depend on a provider-neutral model contract. Anthropic belongs behind an adapter.

### C-02 — Plaintext secret persistence

The architecture requires a protected credential boundary, while the prototype stores the API key and MCP authorization token in ordinary `SharedPreferences`.

**Rule:** no new secret may be persisted through `SettingsStore`; legacy values must be migrated into a Keystore-backed credential store.

### C-03 — Tool versus Action/Capability

The old prototype still treats local tools as the main execution abstraction. v0.2 requires Tool to be an exposure mechanism and Action/Capability to represent execution semantics.

**Rule:** new runtime code must not make Tool the authority boundary.

### C-04 — Evidence without durability

The vertical slice creates Evidence, but it is attached to an in-memory Run. This proves the data model shape only.

**Rule:** do not describe evidence as an audit ledger until persistence and recovery tests exist.

### C-05 — Approval without approval context

`WAITING_APPROVAL` is semantically distinct from `DENIED`, but the current slice does not yet persist a cryptographically/non-replayably bound approval context.

**Rule:** no destructive/high-impact approval workflow is considered production-ready until approval binding and replay tests exist.

## 4. Evidence classification

Use these labels in future reviews:

- **ESTABLISHED** — architectural decision explicitly accepted.
- **PROVISIONALLY VERIFIED** — covered by deterministic automated tests but not yet real-device/integration verified.
- **PARTIAL** — implementation exists but omits required production properties.
- **OPEN** — no implementation/evidence yet.
- **CONFLICT** — current implementation contradicts an accepted invariant/decision.
- **REJECTED** — intentionally excluded from the active design.

## 5. Immediate correction order

1. Protect credential storage without widening the dependency surface.
2. Keep the Action runtime small and testable; add explicit invocation identity before real side effects.
3. Establish provider-neutral ModelProvider boundary.
4. Establish explicit EgressDecision boundary before remote model/MCP integrations.
5. Persist Run/Evidence state and test process-death/retry behavior.
6. Only then expand activation surfaces and richer Actions.

## 6. Non-goals of this correction pass

This pass does not introduce a workflow engine, capability graph, multi-agent coordinator, plugin marketplace, server tenancy, or generalized orchestration layer. Those remain rejected until measured workloads demonstrate a missing primitive.

## 7. Gate for the next architectural promotion

The runtime slice may be promoted from a vertical proof to a stable core candidate only when:

- deterministic direct execution is green in CI;
- policy cannot be bypassed by an alternative activation path;
- approval is distinguishable and non-replayable;
- protected data has an explicit egress decision;
- Run/Evidence survive process death/restart;
- model selection uses a provider-neutral boundary;
- no secret is persisted in ordinary settings;
- at least one Android-native activation adapter reaches the same runtime path.
