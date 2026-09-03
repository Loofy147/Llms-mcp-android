# Privacy & Security Invariants v0.2

Status: Foundational security baseline
Date: 2026-09-03

## 1. Local control boundary

The Android application is the authority boundary for agent-mediated local decisions over identity, policy, capability admission, consent/approval, data egress, durable agent control state, and evidence references. Android OS permissions/sandbox/lifecycle and external-service authority remain separate trust domains.

## 2. Model non-authority

```text
model output     != authorization
model output     != policy
model output     != identity
model output     != evidence
preference       != authorization
Tool exposure    != authorization
```

The model can request or select an Action/Capability from an allowed exposure set. The control plane independently authorizes the requested effect.

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

A model-visible schema is descriptive, not an implicit grant.

## 5. Egress invariant

Protected data may leave the device only when local policy authorizes the destination, purpose, and execution context. Remote model inference is an egress event.

```text
Data -> classify -> minimize/redact -> destination/purpose check -> policy -> execute
```

## 6. Secret isolation

API keys, OAuth tokens, MCP credentials, encryption material, and other secrets are not ordinary Mission/Task/Run content. They must not be copied into prompts, evidence, logs, analytics, exports, or crash reports except where a protected low-level protocol path explicitly requires their use.

The current implementation has moved API/MCP authorization material behind `CredentialStore`, using non-exportable Android Keystore key material. Legacy plaintext settings are migrated once and removed. This is a storage-boundary improvement, not proof that every downstream log/export path is secret-safe.

## 7. Approval binding

An approval is bound to an operation and context. It must not authorize a different Action, wider scope, different data, different destination, or unrelated future operation. Approval is a control-plane record, not a UI boolean.

The runtime slice represents an approval-required state as `WAITING_APPROVAL`, distinct from `DENIED`. Persistent approval context and replay protection remain open.

## 8. Effect classification

At minimum:

```text
READ_ONLY
REVERSIBLE
DESTRUCTIVE / HIGH_IMPACT
```

The classification is known before execution and drives policy/approval/verification requirements.

## 9. Verification and evidence

Verification tests an expected postcondition or otherwise establishes support for an outcome. Evidence is attributable to an invocation/event and may reference executor results, observations, artifacts, verification output, or hashes.

```text
model: "done"                 -> not evidence
executor result + verification -> candidate evidence
```

The vertical slice now creates attributable in-memory Evidence records. It is not yet a durable evidence ledger.

## 10. Attribution and lifecycle

An auditable execution should retain:

```text
activation source
local identity
Action identity/version
CapabilityInvocation identity
Run identity/attempt
authorization decision
actual executor/provider
observations
verification
result
```

Terminal state is monotonic. Stale callbacks cannot turn denied/failed/cancelled work into success.

The current vertical slice has Run identity and explicit terminal outcomes, but durable lifecycle persistence is still open.

## 11. Recovery and duplicate effects

Important state is durable. Recovery after process death must use explicit effect/attempt identity and capability-appropriate idempotency or reconciliation. Retry is not assumed safe merely because an executor call returned an error.

This invariant is not yet proven by the current in-memory slice and is a mandatory gate before adding real external side effects.

## 12. Preferences

Preferences may rank model providers, Actions, confirmation UX, locality, or latency/quality. They are never authority grants.

```text
Preference -> selection bias
Policy     -> authority constraint
Approval   -> explicit authorization event
```

## 13. External adapters

MCP, HTTP, App Functions, A2A, local processes, and other integrations are untrusted adapter boundaries until their identity, scope, credential, destination, data, side effects, verification, and failure semantics are mapped into the local contract.

The current MCP 2026-07-28 release has a stateless protocol core, Tasks as an extension, authorization hardening, and deprecation of legacy HTTP+SSE. We therefore keep protocol state/semantics outside the application authority model. Reference: https://blog.modelcontextprotocol.io/posts/2026-07-28/

Android App Functions provides cross-app functions for trusted/system-privileged orchestration; the Jetpack artifact is `1.0.0-alpha11` as of 2026-08-26 and is treated as an adapter candidate, not a core authority source. References: https://developer.android.com/jetpack/androidx/releases/appfunctions and https://developer.android.com/reference/android/app/appfunctions/package-summary

## 14. Logging/backup/export

Logs, diagnostics, backups, device transfer, share/export actions, and telemetry are privacy surfaces. Sensitive content must be minimized/redacted. Private profiles should default to local-only traces; exporting telemetry or artifacts is itself an explicit activation/egress event.

## 15. Product profiles

Personal, Developer, and Product/Public profiles may expose different Actions and scopes. They must share the same foundational security boundary. UI visibility is not enforcement.

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

Implemented/provisionally verified:

- first-class runtime vocabulary for Activation/Action/Capability/Policy/Run/Observation/Verification/Evidence;
- deterministic Action execution without a Model;
- explicit `DENIED` versus `WAITING_APPROVAL` semantics;
- attributable in-memory evidence records;
- provider-neutral `ModelProvider` boundary with Anthropic behind an adapter;
- Keystore-backed credential storage with legacy plaintext migration.

Open gates:

- durable Mission/Task/Run/Evidence state;
- persistent approval object with replay/scope binding;
- explicit data classification and egress policy;
- Action -> CapabilityInvocation identity/budget model for real effects;
- process-death/retry and duplicate-effect tests;
- native MCP adapter boundary;
- Android-native activation adapters beyond the current UI path;
- real-device performance/correctness evidence for deterministic versus model-mediated execution.
