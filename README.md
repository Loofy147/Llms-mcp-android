# LLM MCP Android

A native Android Kotlin/Jetpack Compose project evolving into a **user-owned mobile agent runtime and control plane**.

## What the project is

The product is a personal assistant on the phone that can execute bounded operations, not only converse.

The application now has an explicit application-level `AssistantRuntime` facade and a canonical `AgentRuntime` control plane. Chat remains a user-facing reasoning surface; it is not an independent effect authority.

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
- `docs/architecture/WHOLE_APP_UNIFICATION_v0.1.md`
- `docs/architecture/DECISION_REGISTER_v0.2.md`
- `docs/architecture/ASSUMPTION_REGISTER_v0.2.md`
- `docs/architecture/REVIEW_CHECKLIST_v0.2.md`
- `docs/architecture/ACTION_MODEL_v0.2.md`
- `docs/architecture/ECOSYSTEM_RESEARCH_2026-09.md`
- `docs/security/PRIVACY_SECURITY_INVARIANTS_v0.2.md`

## Current implementation

### Unified application boundary

`MainActivity` constructs one `AssistantRuntime`. `ChatViewModel` talks to that facade instead of constructing or calling a provider directly.

### Canonical local tool path

Model-facing tool descriptions live in `ToolRegistry`. They contain no effectful execution method.

A local model tool call is translated by `RuntimeToolGateway` into:

```text
Model tool call
  -> ActivationSource.MODEL
  -> ActionCatalog
  -> PolicyEngine
  -> AgentRuntime
  -> CapabilityInvocation
  -> CapabilityExecutor
```

The built-in local tools are represented as canonical Actions:

- `get_current_time` -> `native.current_time` -> `device.time.read`
- `calculate` -> `native.calculate` -> `device.calculator.evaluate`

Arithmetic is explicitly bounded and does not use `eval` or a scripting engine.

### Durable control state

`AgentRuntime` uses durable runtime and approval journals through `AndroidRuntimeFactory`. Effect identity, duplicate blocking, unknown-effect reconciliation state, and persistent approval context are controlled by the runtime boundary.

Approval decisions are one-use and bound to the exact Run, requester identity, Action/version, input, and planned invocations.

### Local egress boundary

Remote model requests now cross an explicit `EgressPolicy` before the HTTP request is executed. The current policy allows only HTTPS requests to the explicit provider host `api.anthropic.com`; destination validation rejects unlisted hosts, non-HTTPS destinations, and credentials embedded in destination URLs.

This is a boundary implementation, not yet a complete data-classification/redaction system.

### Secrets and settings

Credentials are isolated in a Keystore-backed `CredentialStore`; ordinary settings do not persist plaintext API/MCP credentials. MCP credential material is removed when its server is removed through the settings surface.

## Explicitly not complete

The project is still a vertical proof rather than a production-ready agent runtime.

Open gates include:

- richer local data classification, minimization, and redaction before egress;
- Android process-death/restart integration validation;
- capability-specific reconciliation adapters;
- extraction of MCP into an internal protocol adapter rather than leaving the current connector semantics inside the Anthropic provider;
- persistent conversation history;
- broader Android-native activation surfaces;
- production policy/profile management;
- backup/export/privacy review beyond the current credential boundary.

These are engineering gates, not hidden feature claims.

## Build

`.github/workflows/build-apk.yml` runs JVM unit tests before assembling the debug APK and uploading it as a workflow artifact.
