# LLM MCP Android

A native Android Kotlin/Jetpack Compose project evolving into a **user-owned mobile agent runtime and control plane**.

## What the project is

The product is a personal assistant on the phone that can execute bounded operations, not only converse.

The application has one explicit runtime authority: `AgentRuntime`, composed for the Android app through `AndroidRuntimeFactory` and exposed to the active UI flow through `AssistantRuntime`. Chat is a reasoning/user surface; it is not an independent effect authority.

## Current status

The application-wide runtime convergence described by PR #7 is merged into `main`.

Reference commit: `0fccaea041c9dc39a1183f44418897e43433f73a`

CI on the merged commit completed successfully on 2026-09-05. This confirms the repository's JVM tests, debug APK build, and artifact upload pipeline; it does not constitute real-device or production evidence.

For the detailed post-merge state, see `docs/architecture/CURRENT_STATE_AUDIT_2026-09-05.md`.

## Canonical execution model

```text
Surface
  -> Activation / reasoning request
  -> Action
  -> Policy
  -> Approval (when required)
  -> Egress (when remote/protected data is involved)
  -> Run
  -> CapabilityInvocation
  -> CapabilityExecutor
  -> Observation
  -> Verification
  -> Evidence
```

The model may reason and request a tool, but a model response never authorizes an effect.

## Current architecture baseline

- `docs/architecture/NORTH_STAR_ARCHITECTURE_v0.2.md`
- `docs/architecture/IMPLEMENTATION_RECONCILIATION_v0.2.md`
- `docs/architecture/CURRENT_STATE_AUDIT_2026-09-05.md`
- `docs/architecture/WHOLE_APP_UNIFICATION_v0.1.md`
- `docs/architecture/DECISION_REGISTER_v0.2.md`
- `docs/architecture/ASSUMPTION_REGISTER_v0.2.md`
- `docs/architecture/REVIEW_CHECKLIST_v0.2.md`
- `docs/architecture/ACTION_MODEL_v0.2.md`
- `docs/architecture/CAPABILITY_EXECUTOR_BOUNDARY_v0.1.md`
- `docs/architecture/DURABLE_RUNTIME_GATE_v0.1.md`
- `docs/architecture/ECOSYSTEM_RESEARCH_2026-09.md`
- `docs/security/PRIVACY_SECURITY_INVARIANTS_v0.2.md`

## Current implementation

### Unified application boundary

`MainActivity` creates `AssistantRuntime`. The current Chat/Model path talks to that facade rather than constructing a provider as a separate local execution authority.

### Canonical local tool path

Model-facing tool descriptions live in `ToolRegistry`. They contain no effectful execution method.

A local model tool call is translated by `RuntimeToolGateway` into:

```text
Model tool call
  -> ActivationSource.MODEL
  -> ActionCatalog
  -> PolicyEngine
  -> AgentRuntime
  -> ActionPlan
  -> durable effect reservation
  -> CapabilityExecutor
  -> Observation / Verification / Evidence
```

The built-in local tools are represented as canonical Actions:

- `get_current_time` -> `native.current_time` -> `device.time.read`
- `calculate` -> `native.calculate` -> `device.calculator.evaluate`

Arithmetic is explicitly bounded and does not use `eval` or a scripting engine.

### Durable control state

The Android runtime composition uses `JournalRuntimeStore` and `JournalApprovalStore` by default. Effect identity, duplicate blocking, `UNKNOWN` effect state, reconciliation primitives, persistent approval context, and durable Run/Evidence snapshots are controlled by the runtime boundary.

Approval decisions are one-use and bound to the exact Run, requester identity, Action/version, input, planned invocations, and fingerprint.

### Local egress boundary

Remote model requests cross an explicit `EgressPolicy` before the HTTP request is executed. The current policy requires HTTPS, uses an explicit host allowlist, rejects credentials embedded in destination URLs, and checks the declared data classes. The Android composition currently allows only `api.anthropic.com`.

This is an admission/classification boundary, not yet a complete data-minimization or redaction system.

### Secrets and settings

Credentials are isolated behind a Keystore-backed `CredentialStore`; ordinary settings do not persist plaintext API/MCP credentials. Legacy API-key migration and MCP credential cleanup are implemented.

## Important boundaries that remain open

The project is still a vertical proof rather than a production-ready autonomous runtime.

Open engineering gates include:

- Android process-death/restart integration evidence;
- Android approval presentation/resumption;
- capability-specific reconciliation against real external systems;
- complete content minimization/redaction and finer-grained egress policy;
- immutable/tamper-evident audit semantics;
- native internal MCP adapter extraction;
- real Android/device CapabilityExecutor adapters beyond the current deterministic built-ins;
- broader Android-native activation surfaces;
- production profile/policy management;
- backup/export/logging/telemetry privacy review;
- real-device performance and correctness evidence.

The repository must not be described as providing exactly-once external execution or unrestricted autonomous background execution.

## Build

`.github/workflows/build-apk.yml` runs JVM unit tests before assembling the debug APK and uploading it as a workflow artifact.
