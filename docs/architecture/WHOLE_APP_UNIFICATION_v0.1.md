# Whole-App Unification v0.1

Status: Convergence baseline implemented; remaining integration gates explicit
Date: 2026-09-05

## Purpose

The application must have one execution authority. Chat, model providers, native tools, automation, and future integrations are activation/exposure surfaces; none may become a second execution authority.

## Canonical boundary

```text
Surface
  -> Activation
  -> Action resolution
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

## Current state

The core structural convergence is implemented and merged into `main`.

Completed in the current baseline:

1. persistent approval context and one-use consumption;
2. one Android runtime composition root using durable runtime/approval journals;
3. native local tools represented as Action/Capability definitions;
4. model local-tool calls routed through the runtime;
5. direct effect responsibility removed from `ToolRegistry`;
6. MCP credential cleanup enforced through settings persistence;
7. explicit local `EgressPolicy` before current remote provider HTTP requests;
8. capability execution placed behind `CapabilityExecutor`;
9. startup recovery of stale `RESERVED` effects into explicit `UNKNOWN` state;
10. post-approval policy re-evaluation with only `ALLOW` permitted to execute;
11. documentation now treats independent verifiability/observability as a first-class capability-prioritization criterion.

## Verification-first capability direction

The next capability family is intentionally developer-centric and read-heavy:

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

The preferred progression is:

```text
observable effect
    -> independent verification
    -> attributable evidence
    -> safe automation
    -> broader composition
```

This is a prioritization heuristic, not a claim that all verifiable tasks are intrinsically safe. Consequence, scope, reversibility, idempotency, and external uncertainty remain independent controls.

The detailed proposal, capability tiers, composite Actions, verification dimensions, and benchmark are in `PERSONAL_DEVELOPER_CAPABILITY_RESEARCH_2026-09-05.md`.

## Authority rules

- `AgentRuntime` owns execution admission and lifecycle semantics.
- `CapabilityExecutor` is the runtime-owned local effect boundary.
- `EgressPolicy` is the local decision boundary for remote data transmission.
- `ToolRegistry` may describe model-facing tools but must not execute effects directly.
- `ModelProvider` may reason/stream but cannot authorize effects.
- `SettingsStore` persists preferences/configuration; `CredentialStore` owns credential material.
- A provider adapter cannot bypass local policy or approval by issuing a local effectful tool call.
- Remote/provider execution does not transfer local authorization ownership.

## Remaining migration order

1. Formalize the Capability/Verification contract.
2. Implement the first read-only personal developer capability family and its deterministic verifiers.
3. Run the 20-case / 3-project-state benchmark and record false-success and intervention metrics.
4. Add one narrowly scoped write capability only after verification evidence is satisfactory.
5. Add Android approval UI presentation/resumption.
6. Validate approval and durable runtime behavior through real Android process death/restart.
7. Add capability-specific reconciliation against at least one real external effect.
8. Add richer egress classification, minimization, redaction, and per-data-class policy.
9. Extract MCP into an internal protocol adapter whose locally authorized effects converge on runtime semantics.
10. Add real Android/device CapabilityExecutor adapters with platform permission mapping.
11. Define immutable/tamper-evident evidence/audit semantics and retention/corruption handling.
12. Review backup/export/logging/telemetry privacy surfaces.
13. Only then expand activation surfaces, profiles, background execution, and richer Actions.

## Current egress slice

`EgressRequest` classifies broad data carried by a remote request (`USER_CONTENT`, `USER_CONFIGURATION`, `CREDENTIAL`). `AllowlistEgressPolicy` enforces HTTPS, an explicit destination-host allowlist, rejection of credentials embedded in destination URLs, and declared data-class admission. The Android composition currently allows only `api.anthropic.com`.

This is an application-to-provider admission/classification boundary, not a guarantee over provider-side downstream network interactions. It is not yet a complete payload minimization, redaction, or fine-grained data-class policy system.

## Promotion criterion

The migration is not considered complete while an effect can execute through a path other than `CapabilityExecutor`, protected remote data can bypass `EgressPolicy`, Android process-death/restart behavior lacks integration evidence, or the UI/model path and runtime path maintain conflicting authority semantics.
