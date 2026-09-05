# Approval -> Execution Transaction Contract v0.3

Status: Proposed / implementation gate
Date: 2026-09-06

## 1. Problem

The current runtime persists an approval in `ApprovalStore` and the Run/effect state in `RuntimeStore`. Approval consumption can therefore become durable before the corresponding Run transition and effect reservation are durable.

The failure window is:

```text
WAITING_APPROVAL
    -> approval consumed
    -> process death
    -> execution not started or not durably represented
```

This is not an acceptable terminal ambiguity for the control plane.

## 2. Required property

Approval is an authorization input to execution, not an independent terminal effect. The durable system must guarantee that an approved Run is recoverable to exactly one of these states:

```text
WAITING_APPROVAL
EXECUTION_IN_PROGRESS
TERMINAL
```

A crash must not create an authorization that is permanently consumed while its corresponding execution state is unrecoverable.

## 3. Target transaction

The preferred design is a single control-plane persistence boundary for:

```text
Run state
Approval state
Effect reservations
```

The authorization/execution transition should be represented by a durable transaction or equivalent compare-and-swap protocol:

```text
WAITING_APPROVAL
      |
      | approve + bind
      v
EXECUTION_IN_PROGRESS
      |
      +--> effect reservation
      |
      +--> execution
      |
      +--> verification
      v
TERMINAL
```

The transaction must be recoverable after process death.

## 4. Rejection of weaker fixes

The following are insufficient by themselves:

- merely moving `saveRun(RUNNING)` earlier;
- allowing an already-consumed approval to be reused without a durable execution claim;
- relying on in-memory locks;
- relying on UI retries;
- treating a consumed approval plus a `WAITING_APPROVAL` Run as success;
- claiming exactly-once external execution.

Such changes may narrow the failure window but do not establish a durable transaction boundary.

## 5. Recovery contract

On process restart, recovery must inspect the durable control-plane state and classify interrupted approved Runs without guessing external completion.

A recovery result must distinguish at least:

```text
SAFE_TO_START
EXECUTION_STATE_UNKNOWN
TERMINAL
```

When an effect reservation exists but external completion is uncertain, the effect must remain explicitly reconcilable as `UNKNOWN`.

## 6. Concurrency contract

Approval resolution must be single-winner for a given Run/approval pair. Two concurrent approval requests must not both enter execution.

The test must use separate store instances at minimum, and the journal implementation should also be safe across processes where Android process boundaries permit concurrent access.

## 7. Required experiment E-13

Inject process termination at these boundaries:

1. after approval decision is accepted but before execution claim;
2. after execution claim but before effect reservation;
3. after effect reservation but before capability execution;
4. after capability execution but before terminal Run persistence;
5. after terminal Run persistence but before approval cleanup/finalization.

For each boundary, prove:

- no lost authorization;
- no second successful execution without an independently valid execution claim;
- all interrupted effects become explicitly reconcilable;
- terminal Run state is not overwritten by stale recovery.

## 8. Promotion rule

E-13 remains OPEN until crash-injection and concurrent-resolution tests pass on the durable implementation. Until then, the project must describe approval/execution as provisionally durable rather than transactionally atomic.
