# Capability Executor Boundary v0.1

Status: Engineering proof
Date: 2026-09-04

## Purpose

The CapabilityExecutor is the sole runtime-owned execution boundary for capability effects.

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

## Trust boundary

The executor implementation is an adapter owned by the application/runtime integration layer. It may delegate to Android APIs, local storage, HTTP, MCP, or other domains, but those adapters must preserve the invocation identity, scope, attribution, and policy context supplied by the runtime.

The executor does not grant authority. Authority is established before the executor is called.

## Current proof

The runtime test suite verifies:

- a deterministic Action can execute without model inference;
- capability output is supplied through the executor;
- undeclared capabilities and out-of-scope invocations fail before execution;
- duplicate effect identities fail before execution;
- replay protection still prevents the executor from being called twice for the same durable effect;
- a missing executor fails closed without falling back to Action reduction.

## Explicit non-claims

This boundary does not yet provide:

- persistent approval artifacts;
- EgressDecision;
- capability-specific reconciliation of UNKNOWN effects;
- Android process-death recovery validation;
- real Android/device capability adapters.

Those remain subsequent gates.
