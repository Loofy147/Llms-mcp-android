# Unknown Effect Reconciliation v0.1

Status: Proposed implementation gate
Date: 2026-09-04

## Problem

A CapabilityInvocation is durably reserved before execution. If the process, adapter, or transport fails after reservation but before verified completion, the effect is `UNKNOWN`.

`UNKNOWN` means the core cannot prove whether the external effect happened. It is not equivalent to failure and it is not permission to retry.

## Safety rule

Never automatically retry an `UNKNOWN` effect using the same effect identity.

An `UNKNOWN` effect may move only through an explicit reconciliation decision supplied by a trusted recovery path:

- `CONFIRMED_COMPLETED` -> terminal `COMPLETED`.
- `CONFIRMED_NOT_EXECUTED` -> terminal `CONFIRMED_NOT_EXECUTED`; a later retry must create a new execution attempt while retaining the original effect record.

No transition from `UNKNOWN` directly to a new external side effect is permitted.

## Required store semantics

The durable store must provide:

1. enumeration/query of currently `UNKNOWN` effects;
2. effect metadata sufficient for a capability-specific reconciliation adapter;
3. compare-and-append style reconciliation so an effect can be resolved only while still `UNKNOWN`;
4. durable persistence of the reconciliation outcome;
5. replay behavior that preserves the complete history of reservation and reconciliation records.

## Capability-specific proof

The core does not invent proof that an external effect did or did not happen. The adapter/domain owning the capability supplies the evidence used by a reconciliation path.

Examples:

- a remote API with an idempotency-status endpoint can prove completion or non-execution;
- a local filesystem capability can inspect the authoritative object and verify its expected identity;
- an Android intent or notification may require domain-specific observation and may remain unresolved when authoritative observation is unavailable.

Absence of proof must remain `UNKNOWN`.

## Recovery invariant

For an effect identity `E`:

`RESERVED -> UNKNOWN -> {COMPLETED | CONFIRMED_NOT_EXECUTED}`

and never:

`UNKNOWN -> EXECUTE(E)`

or

`UNKNOWN -> RESERVED` without an explicit reconciliation outcome.

## Proof gate

The implementation is not considered closed until tests demonstrate:

- unknown effects survive a new store instance;
- only unknown effects are reconcilable;
- a completed effect cannot be reconciled again;
- a confirmed-not-executed effect cannot silently execute through replay;
- reconciliation survives process restart through the journal;
- a failed reconciliation attempt leaves the effect `UNKNOWN`;
- no runtime path invokes `CapabilityExecutor` as part of reconciliation itself.

## Scope boundary

This document does not claim exactly-once external execution. It defines the durable state machine required when exactly-once cannot be proven.
