# Architecture Review & Promotion Checklist v0.2

Status: Active post-merge review gate
Date: 2026-09-05

Use this checklist against the current repository state. A checked item means the control is implemented and supported by current repository/CI evidence; an unchecked item remains an explicit gate.

## A. Semantic model

- [x] Activation is a first-class request type.
- [x] Capability, Action, Tool, and Model have non-overlapping responsibilities.
- [x] A deterministic Action can execute without a model.
- [x] Model-mediated selection is optional.
- [x] Mission is optional in the architecture; it is not required for the current short-action runtime.

## B. Authority

- [x] The application is the local agent-mediated control boundary.
- [x] Android OS and external resource authority are explicitly respected.
- [x] Models cannot authorize themselves.
- [x] Tool/action exposure cannot bypass runtime authorization for current local tools.
- [x] Preferences are not used as authorization grants.

## C. Action/Capability contract

- [x] Current effectful capability descriptors have identity/effect/scope metadata.
- [x] Action plans declare capability invocations before execution.
- [x] Required scope is checked against the Action capability declaration.
- [x] Approval requirements are machine-checkable for current Actions.
- [x] Replay/idempotency semantics are represented at effect identity level.
- [ ] Full production-grade Action contract metadata/provenance is implemented for all future Actions.

## D. Privacy/security

- [x] A local egress decision exists before current remote provider HTTP requests.
- [x] Egress destination must be HTTPS and host-allowlisted.
- [x] Declared egress data classes are checked against policy.
- [x] Secrets are isolated behind `CredentialStore`/Android Keystore storage.
- [ ] Payload-level minimization/redaction is implemented.
- [ ] Backup/export/telemetry behavior has a complete privacy review.
- [ ] Logging/crash surfaces have a complete secret-redaction review.

## E. Runtime/recovery

- [x] Activation -> Action -> Policy -> Run uses one local control path.
- [x] Effect reservation occurs before `CapabilityExecutor` invocation.
- [x] Run/effect state is durable in the Android default journal store.
- [x] Interrupted `RESERVED` effects are converted to `UNKNOWN` at process start.
- [x] Unknown effects require explicit reconciliation; blind retry is blocked.
- [ ] Actual Android process-death/restart behavior is integration-tested on a device/emulator.
- [ ] Capability-specific external idempotency/reconciliation is proven.
- [ ] Runtime budgets for steps/time/model/egress are fully enforced.

## F. Evidence

- [x] Executor results are structured.
- [x] Verification can reject a claimed successful execution.
- [x] Evidence is attributable to run/action/activation/invocation context.
- [x] Evidence is persisted with Run snapshots in the journal store.
- [ ] Immutable/tamper-evident audit semantics are implemented.
- [ ] Retention/corruption handling is fully specified and tested.

## G. Interoperability

- [x] Model provider transport is behind a provider-facing adapter boundary.
- [x] Current local model tool execution crosses the runtime control boundary.
- [ ] MCP connector semantics are extracted into a native internal protocol adapter.
- [ ] Android App Functions adapter is integrated.
- [ ] HTTP/A2A/local-process integrations are mapped into the canonical capability contract.
- [x] External protocol state is not treated as the application authority state.

## H. Android/application integration

- [x] One Android runtime composition root wires durable stores and current capability adapters.
- [x] `AssistantRuntime` is the active application facade for the current chat flow.
- [ ] Approval UI presentation/resumption is integrated.
- [ ] Broader Android-native activation adapters are integrated.
- [ ] Background execution uses an explicit Android-supported scheduler/work mechanism.
- [ ] Profile-specific policy management is implemented.

## I. Scope control

- [x] No general workflow engine was added without measured need.
- [x] No Capability Graph was added without real composition pressure.
- [x] No multi-agent core was added without a workload requiring it.
- [x] No marketplace/plugin platform is part of the current core.
- [x] External repositories/protocols are treated as pattern/research inputs rather than unexamined architecture.

## Current gate

The repository is suitable for continued implementation of the existing control-plane model. It is **not** promoted to production-agent status until the unchecked lifecycle, external-effect, egress-data, MCP, evidence/audit, and Android integration gates are closed with reproducible evidence.

## Evidence reference

The merged `main` commit `0fccaea041c9dc39a1183f44418897e43433f73a` passed workflow run `147` on 2026-09-05. See `CURRENT_STATE_AUDIT_2026-09-05.md` for the detailed state and explicit non-claims.
