# Llms-mcp-android — Engineering Guardrails v0.1

Status: Active engineering gate
Date: 2026-09-13

## Purpose

This document turns recurring architecture-review failures into explicit checks that must be satisfied before extending the runtime.

It is a guardrail, not a second architecture. The North Star Architecture and Decision Register remain authoritative.

## 1. Authority boundary gate

Before adding any execution path, answer:

- What is the single semantic authority for this operation?
- Does the path enter `AgentRuntime` / the established control plane?
- Can any model, tool, protocol adapter, backend, or Android surface cause an effect without the same policy/approval boundary?
- Does the implementation introduce a second object that can independently authorize execution?

**Fail condition:** a feature creates a parallel execution authority, hidden policy, or backend-specific permission path.

## 2. Capability-before-backend gate

Do not choose an implementation technology before defining the capability contract.

Required order:

```text
Need -> Capability contract -> Action contract -> Policy/approval semantics
     -> Verification/evidence -> Backend selection -> Implementation
```

Backends such as Android processes, Termux, PRoot/Linux environments, HTTP services, or MCP are implementation choices.

**Fail condition:** a technology/repository becomes the architecture before the required semantic contract is established.

## 3. Evidence-before-autonomy gate

Every new capability must identify:

- observable outputs;
- independently checkable success/failure conditions;
- expected failure states;
- durable evidence to retain;
- uncertainty handling (`UNKNOWN` where appropriate);
- replay/idempotency/reconciliation semantics when effects can escape the app.

A model-generated success statement is not evidence.

**Fail condition:** the feature can claim success without an independent observation or verification path.

## 4. Durability gate for effects

For any non-trivial or external effect:

```text
validate -> materialize identity -> reserve durably -> execute
-> observe -> verify -> complete
```

On uncertainty:

```text
execute/interrupt ambiguity -> UNKNOWN -> reconcile explicitly
```

Never replace durable effect identity with an in-memory boolean, callback, or process-local flag.

**Fail condition:** process death or retry can silently cause a duplicate effect or silently convert uncertainty into success.

## 5. Approval gate

Approval is a control decision, not a UI decoration.

An approval must be bound to the exact execution context it authorizes, including the Run/request/action/version/input and planned invocations as applicable.

After approval:

- `ALLOW` may continue;
- `DENY` must not continue;
- `APPROVAL_REQUIRED` must not be interpreted as `ALLOW`;
- stale or mismatched approval must fail closed.

**Fail condition:** any post-approval branch can execute without a verified `ALLOW` for the exact request.

## 6. Scope and data-egress gate

Before remote or privileged execution, establish:

```text
scope -> data classification -> minimization/redaction
-> destination/purpose -> policy -> invocation
```

Credentials are never embedded in action inputs, logs, evidence, or model-visible observations unless the contract explicitly requires and protects them.

**Fail condition:** the implementation relies on a backend to decide application-level data authority.

## 7. Verification of recovery claims

Do not promote a lifecycle/recovery claim from code inspection alone.

Claims such as:

- process-death safety;
- durable journal correctness;
- no duplicate external effect;
- restart recovery;
- reconciliation correctness;
- Android background behavior

require executable or device-level evidence appropriate to the claim.

**Fail condition:** documentation claims a guarantee that has not been experimentally demonstrated at the relevant boundary.

## 8. Architecture expansion gate

A new subsystem, runtime, service, graph, workflow engine, daemon, adapter layer, or persistence abstraction requires a concrete missing primitive.

The change proposal must state:

1. the measured problem;
2. why the current primitive cannot express it;
3. the smallest new primitive that fixes it;
4. what complexity it adds;
5. how it can be removed or replaced later.

**Default:** extend the existing control plane before introducing another authority.

**Fail condition:** the new subsystem mainly duplicates an existing semantic responsibility.

## 9. External repository / technology gate

When borrowing ideas or code from another repository:

- separate **pattern evidence** from **code reuse**;
- inspect license obligations before copying code;
- identify security assumptions that do not survive transplantation;
- identify which semantics belong to the source project and which belong to ours;
- prefer an adapter when the source technology is only an implementation backend.

**Fail condition:** source architecture is imported wholesale because it solves an adjacent implementation problem.

## 10. Scope-control gate

Before implementing a feature, state:

```text
Goal
Non-goals
Current evidence
Open unknowns
Minimal implementation
Acceptance tests
Promotion criteria
Rollback/removal path
```

Do not expand from one verified need into a general framework without evidence.

## 11. Review order

Architecture review must follow this order:

```text
1. Semantic authority
2. Safety / policy / approval
3. State & durability
4. Observability & verification
5. Capability contract
6. Backend implementation
7. Performance / ergonomics
8. Optional integrations
```

This prevents implementation details from deciding architecture prematurely.

## 12. Current hard stops for this repository

Until experimentally closed, treat these as explicit non-goals rather than implicit assumptions:

- no second execution runtime;
- no unrestricted shell/command surface;
- no exactly-once external execution claim;
- no automatic replay of uncertain effects;
- no production authorization claim for the current durable-runtime vertical proof;
- no LinuxOnAndroid/Termux dependency merely to obtain a generic execution layer;
- no workflow engine/capability graph/swarm/plugin marketplace/tenancy without a measured missing primitive.

## 13. Promotion rule

A capability moves from **prototype** to **trusted runtime primitive** only when:

- its contract is explicit;
- the control-plane path is used end-to-end;
- negative cases are tested;
- evidence is durable and attributable;
- recovery semantics are known;
- external effects have capability-appropriate idempotency/reconciliation where required;
- the limits of the implementation are documented.

The burden of proof increases with effect scope and uncertainty.

## 14. Discriminating test rule

When two architectural options appear plausible, do not settle by preference.

Define the smallest test that can distinguish them, run it, and record:

```text
Hypothesis
Test
Observed result
Interpretation
Remaining uncertainty
Decision
```

This guardrail deliberately favors measured architecture over speculative abstraction.
