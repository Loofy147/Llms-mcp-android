# Decision Register Addendum — 2026-09-20

Status: Active proposal/revalidation record
Branch: refactor/runtime-semantic-stabilization-v0.3

## D-21 — Authoritative transactional control plane

Status: PROPOSED / OPEN

The long-term authoritative control-plane persistence boundary should be one transactional backend capable of atomic local transactions across Run, Approval, ExecutionClaim, EffectReservationGroup, and related state.

A coordinator above independent journals may remain as an orchestration interface, but it is not itself physical transaction atomicity.

The existing journal should be treated as transitional/compatibility infrastructure unless its required atomicity and cross-process CAS semantics are independently demonstrated.

Evidence needed:
- Q1 schema invariant tests;
- concurrency tests;
- crash/reopen tests;
- durable backend evidence.

## D-22 — SQLite/Room as leading backend candidate

Status: PROPOSED / OPEN

SQLite/Room is the leading implementation candidate because the project needs:
- local transactions;
- durable state;
- crash recovery;
- concurrent readers/writers within Android constraints;
- schema migrations;
- testable persistence semantics.

This is not an unconditional technology decision. The backend remains open until the proposed schema and invariant matrix demonstrate that the required semantics can be expressed and tested without introducing new semantic gaps.

Primary technical references:
- https://www.sqlite.org/atomiccommit.html
- https://sqlite.org/pragma.html
- https://developer.android.com/training/data-storage/room

## D-23 — Local transaction is not external exactly-once

Status: ACCEPTED DESIGN LIMIT

A local database transaction can establish an atomic control-plane admission, but it cannot by itself establish exactly-once execution of an external side effect.

External effects therefore require explicit idempotency and/or reconciliation semantics.

This decision does not claim exactly-once external execution.

## D-24 — WorkManager is scheduler/recovery infrastructure, not authority

Status: PROPOSED / OPEN

If persistent background execution is later required, WorkManager may launch/retry work from durable control-plane state.

It must not become the authority for:
- authorization;
- approval;
- effect identity;
- terminal Run truth;
- evidence provenance.

Reference:
https://developer.android.com/reference/androidx/work/WorkManager

## D-25 — Primary product thesis

Status: HYPOTHESIS / VALIDATION REQUIRED

The project should be pursued primarily as a user-owned Agent Runtime / Control Plane rather than as a generic universal assistant.

Potential commercial expression:
- Agent Control Plane;
- embedded execution-control SDK;
- model/provider-neutral runtime;
- authorization/approval/recovery/evidence layer.

This is a product hypothesis, not a claim of product-market fit.

## D-26 — Developer capabilities as proving ground

Status: ACCEPTED DIRECTION

The initial capability family should remain read-heavy and independently verifiable:

    dev.workspace.inspect
    dev.file.read
    dev.file.hash
    dev.git.status
    dev.git.diff

Capability breadth should remain subordinate to semantic stabilization and measured evidence.

## D-27 — No architecture expansion from market/category existence alone

Status: ACCEPTED DIRECTION

The existence of agent platforms, MCP, AppFunctions, workflow engines, or multi-agent products is not itself evidence that this project should implement those features.

Expansion requires measured pressure, a concrete workload, or a validated product need.

## D-28 — Commercial PMF remains UNKNOWN

Status: UNKNOWN

Current technical coherence and external evidence that agent identity/authorization is an active problem do not establish customer willingness to pay.

Required validation:
- buyer and user interviews;
- replacement/alternative analysis;
- concrete failure-cost measurement;
- narrow paid wedge definition;
- evidence that model/provider neutrality or user-owned control changes purchase behavior.
