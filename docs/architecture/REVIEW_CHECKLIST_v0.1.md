# Pre-Implementation Architecture Review Checklist v0.1

Date: 2026-09-03

This checklist is the gate between architecture review and implementation. All foundational items should be answered before substantial runtime code is added.

## A. Authority

- [ ] The device is the authority for identity, policy, consent, and egress.
- [ ] No model response can authorize itself.
- [ ] No adapter can bypass the control plane.
- [ ] Personal and product profiles share the same authority semantics.

## B. Capability boundary

- [ ] Every effectful capability has explicit identity and scope.
- [ ] Effect level is known before execution.
- [ ] Data access and network destination are explicit where applicable.
- [ ] Approval requirements are machine-checkable.
- [ ] Verification requirements are defined for high-impact effects.

## C. Privacy

- [ ] Protected data classes are defined.
- [ ] Remote destinations are governed by local egress policy.
- [ ] Secrets are isolated from normal state and model context.
- [ ] Logs and diagnostics are treated as egress surfaces.
- [ ] Backup/export behavior is explicitly reviewed.

## D. Runtime

- [ ] Activation is normalized into one internal request type.
- [ ] Mission, Task, and Run have separate responsibilities.
- [ ] Planning is optional rather than mandatory.
- [ ] Execution is bounded by relevant budgets.
- [ ] Terminal states cannot be overwritten by stale work.
- [ ] Process-death recovery has explicit semantics.

## E. Evidence

- [ ] Executor results are structured.
- [ ] Evidence is attributable to an invocation/event.
- [ ] Model text cannot automatically become evidence.
- [ ] Verification can reject an apparently successful action.
- [ ] Artifacts can be referenced by stable identity or hash where appropriate.

## F. Interoperability

- [ ] MCP is an adapter, not the core authority model.
- [ ] HTTP/API integrations use the same capability contract.
- [ ] Android-native integrations use the same activation/policy boundary.
- [ ] Experimental platform APIs are isolated behind replaceable adapters.
- [ ] External projects are integrated only through explicit contracts.

## G. Product profiles

- [ ] Personal, Developer, and Product profiles share one core.
- [ ] Public/product profiles have safe capability defaults.
- [ ] Product policy cannot bypass security through UI flags.
- [ ] The core remains useful without optional integrations.

## H. Implementation discipline

- [ ] Foundational decisions are not hidden inside library choices.
- [ ] Technology choices remain replaceable until validated.
- [ ] Each new capability has tests for allow, deny, approval, failure, and recovery as applicable.
- [ ] Each remote integration has transport/error normalization tests.
- [ ] Two real consumers must demonstrate a shared integration invariant before a generalized ProjectRegistry/Adapter layer is introduced.
- [ ] Multi-agent architecture requires a concrete use case that cannot be represented cleanly by the single-agent runtime.

## Gate

The implementation phase may proceed when the architecture documents plus this checklist have been reviewed and no unresolved item would invalidate a foundational security or authority invariant.
