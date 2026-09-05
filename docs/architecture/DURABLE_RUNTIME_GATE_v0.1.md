# Durable Runtime Gate v0.1

Status: Implemented vertical proof; not production authorization
Date: 2026-09-05

## Purpose

This gate defines the minimum durable-state semantics required before the runtime is allowed to perform externally visible side effects. The current implementation now satisfies the local persistence contract for Run/effect state and startup recovery, but Android process-death integration and capability-specific reconciliation remain open.

## Contract

```text
Activation
  -> Policy / Approval
  -> ActionPlan
  -> validate Capability + scope + identity
  -> materialize CapabilityInvocation
  -> durable reserve(effect set)
  -> CapabilityExecutor
  -> verify
  -> complete effects OR mark UNKNOWN
  -> durable Run/Evidence
```

### Invariants

1. `ActionPlan` is the canonical declaration of intended capability effects.
2. An Action cannot introduce new effects after planning.
3. All effect identities for one Action are validated before reservation.
4. Reservation is all-or-nothing for the complete effect set: execution does not start when the reservation is rejected.
5. A previously seen effect identity blocks automatic replay unless the prior effect was explicitly reconciled as `CONFIRMED_NOT_EXECUTED`.
6. Uncertainty after reservation is represented as `UNKNOWN`, not silently retried.
7. Run lifecycle and Evidence snapshots are persisted independently of the in-memory runtime instance when the Android journal store is used.
8. Stable idempotency identity is scoped by Action id, version, Capability id, and caller-provided idempotency key.
9. Effect identity is not evidence of successful execution; completion requires post-execution verification.
10. A process-start recovery pass converts stale `RESERVED` effects into `UNKNOWN`, making uncertainty explicit before later reconciliation.
11. `UNKNOWN` effects may only transition through the explicit reconciliation API; only `CONFIRMED_NOT_EXECUTED` permits a subsequent reservation of the same effect identity.

## Current implementation

`JournalRuntimeStore` is the Android default store wired by `AndroidRuntimeFactory`. It appends Run and effect records to a durable journal and forces each append before returning. `InMemoryRuntimeStore` remains available for deterministic unit tests.

`AndroidRuntimeFactory` performs one recovery pass per application process. The pass converts effects left `RESERVED` into `UNKNOWN` so process interruption cannot be silently interpreted as "not executed" or "executed".

`AgentRuntime` reserves the complete materialized effect set before calling `CapabilityExecutor`, persists terminal Run snapshots, and marks effects `COMPLETED` only after a successful verification result. Failures after reservation are represented as `UNKNOWN` and require reconciliation rather than blind retry.

This is strong local/deterministic evidence of the durable boundary. It is not yet proof of correct behavior across real Android process death, OS termination windows, or external systems that lack idempotency/query support.

## Explicit non-guarantees

The current gate does not provide:

- exactly-once external execution;
- capability-specific reconciliation adapters against real external systems;
- cross-device/distributed effect coordination;
- immutable tamper-evident audit storage;
- Android UI presentation/resumption for pending approvals;
- production-grade crash/process-death integration evidence;
- a guarantee that every future CapabilityExecutor implementation preserves the contract.

These remain separate gates and must not be inferred from the current journal implementation.

## Promotion gate

Promotion beyond vertical-proof status requires:

1. an Android process-death/restart test that exercises a real persisted journal;
2. at least one real externally visible capability with an explicit idempotency/reconciliation contract;
3. evidence that stale callbacks/retries cannot overwrite terminal state;
4. explicit operational handling for `UNKNOWN` effects;
5. device-level validation of journal durability and recovery behavior.
