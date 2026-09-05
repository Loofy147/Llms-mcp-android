# Capability Executor Boundary v0.1

Status: Implemented vertical proof
Date: 2026-09-05

## Purpose

`CapabilityExecutor` is the sole runtime-owned execution boundary for capability effects in the current control-plane design.

```text
Activation
  -> ActionPlan
  -> CapabilityInvocation(s)
  -> Policy / Approval / Egress
  -> durable effect reservation
  -> CapabilityExecutor
  -> CapabilityExecution
  -> Action reduction
  -> Verification
  -> Evidence
```

## Contract

`ActionDefinition.plan` declares the complete capability effect set before execution.

`CapabilityExecutor.execute` receives a concrete, attributed `CapabilityInvocation` only after declaration, scope validation, policy admission, and durable duplicate reservation.

`ActionDefinition.reduce` is effect-free application logic. It may combine capability results into Action output, observations, and postcondition state, but it is not the place to perform device, filesystem, network, MCP, or other external effects.

Unknown capability IDs fail closed. There is no fallback path from a missing executor to Action reduction.

Remote transport is separately guarded by the application `EgressPolicy`; the current provider transport remains an adapter and is not treated as a local CapabilityExecutor implementation. MCP connector execution is still provider-owned and therefore remains an explicit architectural limitation.

## Trust boundary

The executor implementation is an adapter owned by the application/runtime integration layer. It may delegate to Android APIs, local storage, HTTP, MCP, or other domains, but those adapters must preserve the invocation identity, scope, attribution, and policy context supplied by the runtime.

The executor does not grant authority. Authority is established before the executor is called.

## Current proof

The runtime test suite verifies:

- a deterministic Action can execute without model inference;
- capability output is supplied through the executor;
- undeclared capabilities and out-of-scope invocations fail before execution;
- duplicate effect identities fail before execution;
- replay protection prevents the executor from being called twice for the same durable effect;
- a missing executor fails closed without falling back to Action reduction;
- model-facing local tool calls converge on the same runtime path.

The Android composition wires `RegistryCapabilityExecutor` through `AndroidRuntimeFactory` for the current native time and arithmetic capabilities.

## Related control gates already present

- persistent approval context is implemented through `ApprovalStore`/`JournalApprovalStore` and bound to the exact Run, requester, Action/version, input, planned invocations, and fingerprint;
- explicit `EgressPolicy`/`EgressDecision` gates current remote provider requests;
- `JournalRuntimeStore` provides durable effect reservation, completion, `UNKNOWN` state, and reconciliation primitives;
- startup recovery converts interrupted `RESERVED` effects to `UNKNOWN` once per application process.

## Explicit non-claims

This boundary does not yet provide:

- exactly-once guarantees for external effects;
- capability-specific reconciliation against real external systems;
- Android process-death integration evidence;
- real Android/device capability adapters beyond the current deterministic built-ins;
- complete data minimization/redaction before egress;
- a native internal MCP adapter.
