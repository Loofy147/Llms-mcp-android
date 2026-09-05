# Llms-mcp-android — Whole-App Current State Audit

Status: Post-merge engineering audit
Date: 2026-09-05
Reference commit: `0fccaea041c9dc39a1183f44418897e43433f73a`
Reference merge: PR #7 (`refactor(runtime): unify app execution through one control plane`)

## 1. Executive finding

The application has crossed an important architectural boundary: local native tool execution and model-mediated local tool execution now converge on one application-owned runtime authority.

The merged state is **not production-complete**. The strongest current evidence is deterministic JVM/CI proof of the control-plane primitives plus a successful merged-source Android debug build. The remaining gaps are primarily integration, external-effect, privacy-data handling, lifecycle, and product-surface gaps rather than a second competing execution architecture.

A second architectural principle is now established for capability selection: **prefer capabilities whose outputs are observable and independently verifiable**. This does not mean only deterministic work is useful; it means verifiability is a first-class factor in deciding what to automate first.

## 2. Canonical control path

```text
Chat / future activation surface
        |
        v
AssistantRuntime
        |
        v
AgentRuntime
  |      |      |
  |      |      +--> JournalRuntimeStore / ApprovalStore
  |      |
  |      +--> PolicyEngine
  |
  +--> ActionCatalog
          |
          v
       ActionPlan
          |
          v
 CapabilityInvocation(s)
          |
          v
 durable effect reservation
          |
          v
 CapabilityExecutor
          |
          v
 CapabilityExecution
          |
          +--> Observation
          +--> Verification
          +--> Evidence
```

Remote model transport follows a separate adapter path but crosses the local egress boundary first:

```text
AssistantRuntime
   -> AnthropicModelProvider / AnthropicClient
   -> EgressPolicy
   -> HTTPS transport
   -> Anthropic
```

This egress control is an application->Anthropic boundary. It does **not** mean the application currently controls every downstream network interaction made by the Anthropic provider on behalf of a configured MCP server. Provider-side MCP therefore remains a separate trust boundary and is an explicit open gate.

## 3. Component truth table

| Component | Role | Current truth |
|---|---|---|
| `AssistantRuntime` | application facade | Creates/owns the runtime-facing model/tool surface for the active UI flow |
| `AgentRuntime` | execution authority | Resolves Actions, evaluates policy, manages approval, reservation, execution, verification, evidence, terminal outcomes |
| `ActionCatalog` | semantic catalog | Resolves trusted Action definitions |
| `ActionDefinition.plan` | effect declaration | Produces the complete declared capability invocation set |
| `ActionDefinition.reduce` | effect-free reduction | Combines capability results into Action output/observations/postcondition |
| `CapabilityExecutor` | effect boundary | Sole runtime-owned local capability execution boundary |
| `RuntimeToolGateway` | model-tool adapter | Maps local model tool calls into `ActivationSource.MODEL` runtime requests |
| `ToolRegistry` | model exposure | Tool descriptions only; no direct effect authority |
| `PolicyEngine` | authorization gate | Minimal allow/deny/approval policy; post-approval only `ALLOW` executes |
| `ApprovalStore` | approval persistence | Exact-bound, one-use approval context; journal implementation exists |
| `RuntimeStore` | durable runtime state | Journal implementation persists Runs/effects and supports recovery/reconciliation |
| `EgressPolicy` | remote-data decision boundary | Enforces HTTPS, host allowlist, embedded-credential rejection, declared data-class admission for current app->provider requests |
| `CredentialStore` | secret boundary | Android Keystore-backed encrypted credential storage |
| `AnthropicClient` | provider transport | HTTP/streaming/provider-side MCP transport; not local authority |
| `SettingsStore` | ordinary configuration | Preferences/configuration separated from credential material |

## 4. Implemented and evidenced

### Control-plane semantics

- Activation sources are first-class in the runtime model.
- Model output does not directly authorize execution.
- Actions can execute without a Model.
- Action capability plans are validated before execution.
- Capability scope is checked against the Action declaration.
- Effect identities are reserved before executor invocation.
- Duplicate effect identities are blocked.
- A missing capability executor fails closed.

### Approval

- Approval-required Actions enter `WAITING_APPROVAL`.
- Approval context is persistent in the Android journal path.
- Context is bound to Run id, requester identity, Action/version, exact input, planned invocations, and fingerprint.
- Approval consumption is one-use.
- Policy is rerun after approval with explicit approval satisfaction.
- Only `ALLOW` proceeds to execution; post-approval `DENY` and `APPROVAL_REQUIRED` do not execute.

### Recovery/effect state

- Android default runtime storage is `JournalRuntimeStore`.
- Effect states include `RESERVED`, `COMPLETED`, `UNKNOWN`, and `CONFIRMED_NOT_EXECUTED`.
- Startup recovery converts stale `RESERVED` effects into `UNKNOWN` once per process.
- Unknown effects require explicit reconciliation.
- Confirming `NOT_EXECUTED` is the only reconciliation outcome that permits a new reservation for the same effect identity.

### Privacy/credentials

- Current provider requests cross `EgressPolicy` before HTTP execution.
- Provider destination is explicitly `https://api.anthropic.com/v1/messages` and the Android composition allowlists `api.anthropic.com`.
- Egress admission validates declared data classes.
- Credentials are stored through Android Keystore-backed encrypted storage, with legacy API-key migration support.
- MCP credential cleanup is part of the settings persistence path.

### Verification-first capability direction

The first proposed personal-developer capability family is deliberately read-heavy and verification-heavy:

```text
dev.workspace.inspect
dev.file.read
dev.file.hash
dev.directory.list
dev.git.status
dev.git.diff
dev.git.log
dev.test.run
dev.build.run
dev.artifact.inspect
dev.config.validate
```

These candidates are preferred because repository state, diffs, test reports, build results, artifacts, hashes, and configuration checks expose objective observations that can be verified without trusting model prose. The complete research proposal, capability tiers, composite Actions, and benchmark dimensions are documented in `PERSONAL_DEVELOPER_CAPABILITY_RESEARCH_2026-09-05.md`.

### CI evidence

- Merged commit `0fccaea041c9dc39a1183f44418897e43433f73a` completed workflow run `147` with conclusion `success`.
- The workflow runs JVM unit tests, builds the debug APK, and uploads the APK artifact.
- Subsequent documentation-only CI run `155` also completed successfully.
- CI results are source/build evidence, not real-device or production evidence.

## 5. Open gates

### G1 — Android lifecycle/process death

The durable journal and startup recovery primitive exist, but an end-to-end Android test has not yet demonstrated behavior through actual process death and restart.

Required proof: kill/restart scenarios around approval, reserved effects, terminal Runs, and recovery visibility.

### G2 — External-effect semantics

The runtime prevents blind duplicate replay locally, but it cannot infer whether an external system actually performed a side effect after a crash or transport uncertainty.

Required proof: at least one real external capability with provider/API-level idempotency or reconciliation semantics.

### G3 — Approval UX

The control-plane approval object exists, but Android UI presentation/resumption is not integrated as a first-class surface.

Required proof: pending approval survives Activity/process restart and resumes the exact bound operation only once.

### G4 — Egress data minimization

The current egress layer authorizes declared categories and destinations for the **application's direct remote request**. It does not inspect payload fields to minimize, redact, or enforce per-data-class destination policy.

Moreover, provider-side MCP calls are not currently represented as individual local egress decisions because MCP execution remains provider-owned. This is a distinct unresolved trust/egress boundary.

Required proof: classified payload transformation plus deny/allow tests for sensitive fields and destinations, followed by an explicit policy model for provider-side MCP if local authorization is expected to cover it.

### G5 — Internal MCP adapter

Current MCP connector handling remains provider-owned inside the Anthropic transport adapter.

Required proof: MCP request/response translation into local Activation/Action/Capability semantics without transferring authority to the protocol layer, or an explicit product decision to keep provider-side MCP as an external trust boundary.

### G6 — Real Android/device capabilities

Current built-ins are deterministic, local, and narrowly scoped. There is no broad Android/device effect adapter set yet.

Required proof: one real device capability with platform permissions, explicit scope, verification, and failure/recovery semantics.

### G7 — Evidence/audit ledger

Evidence is durable as part of the Run journal, but it is not yet a tamper-evident immutable audit ledger with explicit retention/corruption policy.

### G8 — Backup/export/logging review

`AndroidManifest.xml` still has `android:allowBackup="true"`. Credential protection reduces exposure but does not close backup/export/crash/logging risk. Full privacy review remains open.

### G9 — Profiles and background execution

Personal/Developer/Product policies are architectural targets, not active runtime profile implementations. No general WorkManager-backed execution adapter is integrated yet.

## 6. Things that must not be claimed

The current repository must not be described as having:

- exactly-once external execution;
- production-grade process-death recovery evidence;
- a native internal MCP adapter;
- complete payload minimization/redaction;
- local application-level authorization over provider-side MCP destinations;
- tamper-evident audit logging;
- broad Android-native automation integration;
- production profile/policy management;
- unrestricted autonomous background execution;
- a general-purpose coding agent;
- evidence that developer capabilities are broadly autonomous merely because their outputs are verifiable.

## 7. Recommended next sequence

1. Formalize the Capability/Verification contract and implement the first read-only personal developer capability family.
2. Build a 20-case benchmark across at least three repository/project states, measuring verification precision/recall, false-success rate, latency, and human intervention.
3. Add one write capability only after deterministic verification is proven.
4. Android approval UI + process-death integration test.
5. Capability-specific reconciliation proof against one real external effect.
6. Egress minimization/redaction and an explicit provider-side MCP trust/egress decision.
7. Native internal MCP adapter if local authorization over MCP effects is required.
8. First real Android/device CapabilityExecutor adapter.
9. Evidence/audit hardening and backup/export/log review.
10. Only then expand activation surfaces, profiles, background work, and higher-level Actions.

## 8. Review conclusion

The merged architecture is internally coherent enough to continue implementation without reopening the core semantic model. The highest-value next step is not more agentic breadth; it is proving that a small, verification-rich capability family can produce reliable developer utility through the existing control plane. New features must reuse the existing `Activation -> Action -> Policy/Approval/Egress -> Run -> CapabilityExecutor -> Observation/Verification/Evidence` path rather than creating another authority path.
