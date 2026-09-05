# Run State Machine v0.3

Status: Proposed semantic baseline
Date: 2026-09-06

## States

```text
CREATED
WAITING_APPROVAL
RUNNING
SUCCEEDED
FAILED
DENIED
CANCELLED
```

## Terminality

```text
SUCCEEDED  terminal
FAILED     terminal
DENIED     terminal
CANCELLED  terminal
```

Terminal states are immutable: a stale callback, retry, recovery worker, or provider response must never overwrite them.

## Valid transitions

```text
CREATED          -> WAITING_APPROVAL | RUNNING | DENIED | FAILED
WAITING_APPROVAL -> RUNNING | DENIED | FAILED
RUNNING          -> SUCCEEDED | FAILED | CANCELLED
```

No transition may leave a terminal state.

## Approval invariant

An approval decision does not itself constitute execution. The transition from `WAITING_APPROVAL` to `RUNNING` must be durably attributable to exactly one approval resolution.

## Recovery invariant

A process restart may recover a non-terminal Run only through explicit recovery semantics. Recovery must not infer that an external effect completed merely because a Run had reached `RUNNING`.

## Current implementation status

The state names and normal happy-path transitions exist in the runtime, but transition enforcement is not centralized in a state-machine primitive. The journal can append a newer Run record without an explicit transition validator.

Therefore this document is a target contract, not evidence that the invariant is already fully enforced.

## Required tests

- invalid terminal-state overwrite;
- stale callback after terminal success;
- concurrent approval resolution single winner;
- crash recovery at each durable boundary;
- repeated recovery is idempotent;
- persisted state cannot skip authorization requirements.
