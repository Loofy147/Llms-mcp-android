# Android T2 B6 Concurrent Recovery Design — 2026-09-17

Status: **DESIGN GATE / IMPLEMENTATION READY**

Purpose: isolate duplicate recovery of one semantic operation across two independent caller processes without introducing Room, WorkManager, a second package, or a general distributed lock abstraction.

## 1. Question

B4/B5 established case-level caller recovery. The next question is whether two independently restarted caller processes can recover the same `operation_id` concurrently without producing a duplicate non-repeatable effect.

```text
caller process C1       caller process C2
       |                       |
       | load operation X     | load operation X
       |                       |
       +-----------+-----------+
                   |
                   v
             provider process P
                   |
                   v
             same effect X
```

The desired semantic property is not "two callers never call Binder". The property is:

```text
same semantic effect
    -> effect_count <= 1
    -> durable terminal knowledge is convergent
```

## 2. External reference input

The external research record identifies operation-keyed coordination as a useful comparison point. Cloudflare Durable Objects are a reference for keyed ownership/serialization; Stripe is a reference for stable operation identity; transactional-outbox/idempotent-consumer patterns demonstrate that duplicate delivery must be handled at the semantic destination boundary.

We will not copy these systems wholesale. B6 only asks whether the current T2 provider contract can preserve one effect under concurrent recovery.

## 3. Topology

Use two independently killable caller processes plus the existing provider process:

```text
Instrumentation runner
       |
       +-------------------+
       |                   |
       v                   v
:callerA                :callerB
       |                   |
       +---------+---------+
                 |
               Binder
                 |
                 v
             :provider
```

All processes remain in the same installed package. Package/discovery/authorization variables remain deferred to T3.

## 4. Preconditions

1. Create a provider operation that is already durable as `RECEIVED` with `effect_count = 0`.
2. Create the same caller operation identity in both caller processes and persist the pre-dispatch checkpoint.
3. Kill both caller processes before dispatch.
4. Restart both callers.
5. Invoke `recover(operation_id)` concurrently from both caller process instances.

The pre-existing provider `RECEIVED` record prevents B6 from being conflated with B5's explicit absence rule.

## 5. Test implementation

The production runtime remains unchanged.

Test-only process classes:

```text
T2CallerServiceA -> :callerA
T2CallerServiceB -> :callerB
```

Both subclasses reuse the same caller recovery implementation and durable caller store. Their only purpose is to create two real process owners for the same operation.

The instrumentation test uses two executor threads, each invoking Binder against a different caller process. This creates two caller-side recovery attempts that are independent at the Android process boundary.

## 6. Acceptance invariants

### I-B6-1 — real process concurrency

Caller process instances C1 and C2 are distinct before recovery and both are independently invoked.

### I-B6-2 — same semantic identity

Both recovery attempts use the exact same `operation_id` and therefore target the same provider-side operation record.

### I-B6-3 — no duplicate non-repeatable effect

```text
provider effect_count <= 1
```

For this experiment, `effect_count == 1` is the expected terminal result.

### I-B6-4 — convergent terminal knowledge

Both caller recovery attempts may complete through different observations, but the durable provider state must converge to `COMPLETED` and both callers must report `COMPLETED` after recovery.

### I-B6-5 — no process-local mutex proof

The result must remain correct even though the two caller JVMs do not share a process-local lock.

## 7. What the test does not establish

A passing B6 case does not establish:

- arbitrary external side effects are globally exactly-once;
- cross-package concurrency semantics;
- crash during the provider's critical section;
- power-loss atomicity;
- stale-result ordering (B7);
- general database transaction semantics;
- production reliability.

The current provider uses an operation record and synchronized state transitions. Therefore a B6 pass primarily proves the modeled provider-side ownership/deduplication boundary under real concurrent caller processes.

## 8. Failure interpretation

FAIL when:

```text
provider effect_count > 1
caller terminal states diverge
one recovery loses the established effect fact
operation identity changes during recovery
```

INCONCLUSIVE when the emulator or instrumentation cannot establish that both caller processes actually executed concurrently.

## 9. Promotion rule

Only a real emulator run with:

```text
caller process A != caller process B
provider effect_count == 1
both callers COMPLETED
```

may promote B6 from OPEN to case-level EXPERIMENTALLY_SUPPORTED.

A compile/build success is not B6 evidence.

## 10. Next gate after B6

After B6, design B7 around explicit causal/result identity:

```text
operation_id
attempt_id
state_version or equivalent monotonic authority
```

A late result from attempt N must not overwrite durable state established by attempt N+1.
