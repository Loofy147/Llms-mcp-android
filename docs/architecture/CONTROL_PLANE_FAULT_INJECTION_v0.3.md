# Control-Plane Fault Injection v0.3

Status: Test design baseline
Date: 2026-09-06

## Purpose

Make durability failures deterministic enough to test without coupling production code to process-kill behavior.

## Fault model

A test-only `FaultInjector` may expose:

```kotlin
enum class FaultPoint {
    APPROVAL_VALIDATED,
    APPROVAL_COMMITTED,
    EXECUTION_CLAIM_PREPARED,
    EXECUTION_CLAIM_COMMITTED,
    EFFECT_GROUP_COMMITTED,
    CAPABILITY_STARTED,
    CAPABILITY_RETURNED,
    TERMINAL_RUN_COMMITTED
}
```

The injector throws a dedicated test exception when configured for a point.

## Rule

The injected exception models abrupt loss of the current execution context. It must not provide recovery behavior itself.

Recovery is performed only by reopening the durable store and invoking the normal recovery path.

## Assertions by fault point

| Fault | Required durable interpretation |
|---|---|
| Approval validated | no consumed authorization unless commit event exists |
| Approval committed | approved operation remains recoverable |
| Claim prepared | no claim visible unless commit exists |
| Claim committed | at most one execution owner |
| Effect group committed | all intended effects are reserved or group is not committed |
| Capability started | affected effect may require `UNKNOWN` reconciliation |
| Capability returned | terminalization still required |
| Terminal Run committed | later stale work cannot regress terminal state |

## Process boundary simulation

A full Android process-death test is still required later. JVM fault injection proves protocol behavior; it does not prove Android process semantics.

## Concurrency stress

Run each race scenario repeatedly with barriers/latches so both participants reach the same decision point:

```text
resolver A ─┐
             ├─ resolve same approval
resolver B ─┘
```

The test records winner count, execution admissions, and final durable state.

## Repetition

For non-deterministic scheduling tests, require a minimum repetition count sufficient to expose scheduling races and record the seed/iteration when a failure occurs. The exact count should be selected by test runtime budget rather than presented as a universal guarantee.
