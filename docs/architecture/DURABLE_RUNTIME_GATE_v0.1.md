# Durable Runtime Gate v0.1

Status: Engineering proof, not production authorization
Date: 2026-09-03

## Purpose

This gate establishes the minimum persistence semantics required before the runtime is allowed to perform externally visible side effects.

## Contract

```text
Activation
  -> Policy / Approval
  -> ActionPlan
  -> validate Capability + scope + identity
  -> materialize CapabilityInvocation
  -> durable reserve(effect set)
  -> execute Action
  -> verify
  -> complete effects OR mark UNKNOWN
  -> durable Run/Evidence
```

### Invariants

1. `ActionPlan` is the canonical declaration of intended capability effects.
2. An Action cannot introduce new effects after planning.
3. All effect identities for one Action are validated before reservation.
4. Reservation is atomic: either the complete effect set is newly reserved or execution does not start.
5. A previously seen effect identity blocks automatic replay.
6. Any uncertainty after reservation is represented as `UNKNOWN`, not silently retried.
7. Run lifecycle and Evidence snapshots are persisted independently of the in-memory runtime instance.
8. Stable idempotency identity is scoped by Action id, version, Capability id, and caller-provided idempotency key.
9. Effect identity is not evidence of successful execution; completion requires post-execution verification.

## Current implementation

`JournalRuntimeStore` provides an append-only journal with forced writes and replay. `InMemoryRuntimeStore` is retained for deterministic unit tests. The Android application is not yet promoted to use the journal by default; lifecycle wiring and Android restart validation remain open.

## Explicit non-guarantees

The current gate does not yet solve:

- external side-effect execution through a dedicated `CapabilityExecutor`;
- reconciliation of effects left `UNKNOWN` after crash or uncertain remote outcome;
- cross-device/distributed effect coordination;
- immutable tamper-evident audit storage;
- persistent approval context;
- data egress authorization.

Those are separate gates and must not be inferred from this slice.
