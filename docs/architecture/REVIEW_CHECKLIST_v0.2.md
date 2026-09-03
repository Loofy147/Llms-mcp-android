# Pre-Implementation Architecture Review Checklist v0.2

Date: 2026-09-03

## A. Semantic model

- [ ] Activation is a first-class request type.
- [ ] Capability, Action, Tool, and Model have non-overlapping responsibilities.
- [ ] A deterministic Action can execute without a model.
- [ ] Model-mediated selection is optional.
- [ ] Mission is optional; Task is bounded; Run is concrete execution.

## B. Authority

- [ ] The application is the local agent-mediated control boundary.
- [ ] Android OS and external resource authority are explicitly respected.
- [ ] Models cannot authorize themselves.
- [ ] Tool/action exposure cannot bypass authorization.
- [ ] Preferences cannot grant authority.

## C. Action/Capability contract

- [ ] Every effectful Action/Capability has identity and version.
- [ ] Required scope, effect, data access, and destination are explicit.
- [ ] Composite Actions cannot widen component authority implicitly.
- [ ] Approval requirements are machine-checkable.
- [ ] Verification and replay/idempotency semantics are defined as applicable.

## D. Privacy/security

- [ ] Data classification exists before remote/private hybrid workflows.
- [ ] Egress decisions are local and explicit.
- [ ] Secrets are isolated from normal state/model context.
- [ ] Backup/export/telemetry behavior is reviewed.
- [ ] Logging is bounded and redacted.

## E. Runtime/recovery

- [ ] Activation → Action/Model → Policy → Run uses one control path.
- [ ] Run identities and terminal states are durable/monotonic.
- [ ] Process-death recovery has explicit behavior.
- [ ] Retried effects have capability-appropriate idempotency/reconciliation.
- [ ] Relevant budgets constrain time, steps, model use, capability use, and egress.

## F. Evidence

- [ ] Executor results are structured.
- [ ] Verification can reject claimed success.
- [ ] Evidence is attributable to invocation/run/activation.
- [ ] Artifacts have stable references/hashes where appropriate.
- [ ] Model prose cannot automatically establish evidence.

## G. Interoperability

- [ ] MCP is an adapter.
- [ ] App Functions is an adapter/optional integration.
- [ ] HTTP/A2A/local-process integrations map to the same capability semantics.
- [ ] External protocol state does not become the application authority state.

## H. Architecture scope control

- [ ] No general workflow engine is added without measured need.
- [ ] No full Capability Graph without real composition pressure.
- [ ] No multi-agent core without a workload that defeats one bounded runtime.
- [ ] No marketplace/plugin platform without independent demand and security model.
- [ ] External repositories contribute patterns/adapters, not unexamined architecture.

## Gate

Implementation may proceed when unresolved checklist items no longer threaten a foundational decision or security invariant. Experimental technology choices may remain open behind replaceable interfaces.
