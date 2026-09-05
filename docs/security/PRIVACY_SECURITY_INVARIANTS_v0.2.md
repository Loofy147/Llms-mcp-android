# Privacy & Security Invariants v0.2

Status: Foundational security baseline; partially implemented and continuously gated
Date: 2026-09-05

## 1. Local control boundary

The Android application is the authority boundary for agent-mediated local decisions over identity, policy, capability admission, consent/approval, data egress, durable agent control state, and evidence references. Android OS permissions, sandboxing, lifecycle, and external-service authority remain separate trust domains.

The current application composition routes local model tool calls through `AssistantRuntime` -> `AgentRuntime` -> `CapabilityExecutor` rather than allowing the UI or `ToolRegistry` to execute effects directly.

## 2. Model non-authority

```text
model output     != authorization
model output     != policy
model output     != identity
model output     != evidence
preference       != authorization
Tool exposure    != authorization
```

The model can request or select an Action/Capability from an exposed surface. The control plane independently authorizes the requested effect.

## 3. Action and Capability safety

Every effectful Action and Capability must carry enough contract information for policy evaluation:

```text
identity/version
scope
effect class
data access
network/destination requirements
approval requirement
verification requirement
idempotency/replay semantics
provenance
```

The current runtime materializes `CapabilityInvocation` objects from an `ActionPlan`, validates capability membership and scope, reserves effect identities durably, and only then calls `CapabilityExecutor`.

A composite Action must not accidentally inherit broader authority than its component Capabilities.

## 4. Exposure versus authorization

The system distinguishes:

```text
installed capability/action
        -> policy-filtered exposure
        -> model/client discovery
        -> requested operation
        -> independent authorization check
        -> execution
```

A model-visible schema is descriptive, not an implicit grant. `ToolRegistry` contains the native tool descriptions; `RuntimeToolGateway` translates accepted local tool calls into runtime Activations.

## 5. Egress invariant

Protected data may leave the device only when local policy authorizes the destination, purpose, and current data classes. Remote model inference is an egress event.

```text
Data -> classify -> minimize/redact -> destination/purpose check -> policy -> execute
```

The current implementation provides an explicit `EgressPolicy`/`EgressDecision` boundary before the Anthropic HTTP request. `AllowlistEgressPolicy` requires HTTPS, rejects embedded destination credentials, validates the host against an allowlist, and checks declared `EgressDataClass` membership.

Current Android composition allows only `api.anthropic.com` at the destination-policy layer.

Important limitation: the current system classifies broad categories but does not yet implement content-level minimization/redaction or fine-grained per-data-class destination policy. Passing the egress policy therefore means "this declared request is admitted" rather than "the payload has been minimized".

## 6. Secret isolation

API keys, OAuth tokens, MCP credentials, encryption material, and other secrets are not ordinary Mission/Task/Run content. They must not be copied into prompts, evidence, logs, analytics, exports, or crash reports except where a protected low-level protocol path explicitly requires their use.

The current implementation stores API/MCP authorization material through `CredentialStore` using Android Keystore-backed encryption. Ordinary settings are held separately, blank credentials delete stored material, and removal of an MCP server also removes its associated credential through the settings persistence path.

This is a storage-boundary improvement, not proof that every downstream log/export/crash surface is secret-safe. Backup/export review remains open.

## 7. Approval binding

An approval is bound to an operation and context. It must not authorize a different Action, wider scope, different data, different destination, or unrelated future operation. Approval is a control-plane record, not a UI boolean.

The current runtime stores an explicit approval context containing Run identity, requester identity, Action/version, exact input, planned invocations, and a fingerprint. Approval consumption is one-use. On resolution, the runtime revalidates the current Action plan/fingerprint and reruns policy with `approvalSatisfied=true`; only `ALLOW` can proceed to execution.

Pending approvals are represented as `WAITING_APPROVAL`, distinct from `DENIED`.

Remaining limitation: Android UI presentation/resumption and process-death integration for pending approvals are not yet complete.

## 8. Effect classification

At minimum:

```text
READ_ONLY
REVERSIBLE
HIGH_IMPACT
```

The classification is known in the `CapabilityDescriptor` before execution and is available to policy evaluation. The current built-in capabilities are read-only.

## 9. Verification and evidence

Verification tests an expected postcondition or otherwise establishes support for an outcome. Evidence is attributable to an invocation/event and may reference executor results, observations, artifacts, verification output, or hashes.

```text
model: "done"                  -> not evidence
executor result + verification -> candidate evidence
```

The current runtime constructs attributable `Evidence` as part of the `Run` and persists it with the durable journal when the Android `JournalRuntimeStore` is used.

This is not yet an immutable, tamper-evident audit ledger. Evidence durability exists; audit-ledger semantics remain open.

## 10. Attribution and lifecycle

An auditable execution should retain:

```text
activation source
local identity
Action identity/version
CapabilityInvocation identity
effect identity
Run identity
authorization decision
actual executor/provider
observations
verification
result
```

Terminal state is treated as monotonic in the runtime contract. Stored terminal Run snapshots are not intended to be rewritten by stale callbacks, and approval replay returns the existing terminal Run rather than executing again.

Current limitation: Android lifecycle/process-death validation is not yet demonstrated end-to-end on a device. The durable store and startup recovery primitives exist.

## 11. Recovery and duplicate effects

Important runtime state is durable when `JournalRuntimeStore` is used. An effect is reserved before executor invocation. A process-start recovery pass converts stale `RESERVED` effects to `UNKNOWN`. Unknown effects cannot be silently retried; reconciliation must explicitly classify them as completed or confirmed-not-executed before a new reservation can proceed.

This does **not** establish exactly-once external execution. External systems may perform a side effect while the client remains unaware of the final outcome. Capability-specific query/idempotency adapters are required for stronger guarantees.

## 12. Preferences

Preferences may rank model providers, Actions, confirmation UX, locality, or latency/quality. They are never authority grants.

```text
Preference -> selection bias
Policy     -> authority constraint
Approval   -> explicit authorization event
```

## 13. External adapters

MCP, HTTP, App Functions, A2A, local processes, and other integrations are adapter boundaries until their identity, scope, credential, destination, data, side effects, verification, and failure semantics are mapped into the local contract.

Current state: the Anthropic adapter performs provider transport and provider-owned MCP connector handling; native local tools are no longer executed by the provider adapter and instead converge through `RuntimeToolGateway`.

The architecture intentionally keeps external protocol state outside the application authority model. The native MCP adapter is a future gate, not a current claim.

## 14. Logging/backup/export

Logs, diagnostics, backups, device transfer, share/export actions, and telemetry are privacy surfaces. Sensitive content must be minimized/redacted. Private profiles should default to local-only traces; exporting telemetry or artifacts is itself an explicit activation/egress event.

Current gap: `AndroidManifest.xml` still permits Android backup, and a full backup/export/telemetry threat review has not been completed. This must not be treated as solved by Keystore storage alone.

## 15. Product profiles

Personal, Developer, and Product/Public profiles may eventually expose different Actions and scopes. They must share the same foundational security boundary. UI visibility is not enforcement.

Current status: profile-specific policy management is not implemented in the active runtime.

## 16. New capability security gate

Before general exposure of an Action/Capability, answer:

1. Who/what can activate it?
2. What exact capability/effect occurs?
3. What scope is granted?
4. What data is read/written?
5. What network/destination is used?
6. What policy decides admission?
7. Is approval required and how is it bound?
8. How is success verified?
9. What evidence is produced?
10. What happens on timeout/failure/retry/process death?
11. How are secrets and logs handled?
12. What changes in each distribution profile?

A capability that cannot answer these questions is not ready for broad exposure.

## 17. Current implementation status

Implemented / provisionally verified:

- first-class runtime vocabulary for Activation/Action/Capability/Policy/Run/Observation/Verification/Evidence;
- deterministic Action execution without a Model;
- distinct `DENIED` versus `WAITING_APPROVAL` semantics;
- one-use, exact-bound persistent approval context;
- durable Run/effect journal and process-start recovery primitive;
- durable effect identity, replay blocking, and explicit `UNKNOWN` reconciliation state;
- capability execution through `CapabilityExecutor` for the current local capabilities;
- provider-neutral `ModelProvider` boundary with Anthropic behind an adapter;
- explicit `EgressPolicy`/`EgressDecision` before current remote provider requests;
- Keystore-backed credential storage with legacy plaintext migration and MCP credential cleanup.

Open gates:

- Android process-death/restart integration evidence;
- Android approval UI presentation/resumption;
- capability-specific reconciliation against real external systems;
- immutable/tamper-evident audit/evidence semantics;
- content minimization/redaction and richer per-data-class egress policy;
- real Android/device CapabilityExecutor adapters beyond current deterministic built-ins;
- native internal MCP adapter boundary;
- broader Android-native activation adapters;
- production policy/profile management;
- backup/export/logging/telemetry privacy review;
- real-device performance/correctness comparison of deterministic versus model-mediated execution.
