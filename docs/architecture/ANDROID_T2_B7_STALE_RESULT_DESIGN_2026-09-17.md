# Android T2 B7 Stale-Result Ordering Design — 2026-09-17

Status: **DESIGN GATE / IMPLEMENTATION READY**

Purpose: isolate stale callback/result ordering after B6. The experiment asks whether an older attempt can arrive after a newer authoritative attempt and incorrectly regress durable semantic state.

## 1. Question

B6 concerns concurrent recovery. B7 concerns ordering after concurrency has already produced more than one attempt.

```text
operation X
    |
    +--> attempt 1
    |       |
    |       +--> delayed result
    |
    +--> attempt 2
            |
            +--> becomes authoritative

late result from attempt 1
    -> must not regress the durable state established by attempt 2
```

This is a semantic ordering property, not merely a Binder ordering property.

## 2. Identity model

Every result used by this experiment must carry at least:

```text
operation_id
attempt_id
state_version
result/outcome
```

Optional provider/capability version may also be carried, but it is not the primary ordering primitive for B7.

The proposed invariant is:

```text
operation_id identifies the semantic operation
attempt_id identifies one dispatch/recovery attempt
state_version identifies the durable authority order
```

A timestamp is explicitly not used as the ordering primitive.

## 3. Ordering rule

For one durable operation:

```text
accepted result version > current durable version
    -> may advance/reconcile state

accepted result version == current durable version
    -> idempotent/no semantic change

accepted result version < current durable version
    -> stale; must not regress durable state
```

A stale result may still be retained as diagnostic evidence, but it must not mutate the authoritative terminal state.

## 4. Experiment topology

B7 does not require a new package or WorkManager. Use the experiment-only caller service/store and a deterministic delayed-result harness.

```text
Instrumentation runner
        |
        v
:caller process
        |
        +--> durable operation record
        |
        +--> delayed result injection
```

The provider process may be bypassed for the first B7 semantic test because the target invariant is result ordering, not provider process recovery. A later combined test may connect B7 to a real provider callback if needed.

## 5. Fault sequence

### B7-A — stale older failure after newer completion

1. Create operation X at durable version 0.
2. Dispatch attempt 1 and assign version 1.
3. Hold its result without applying it.
4. Dispatch attempt 2 and assign version 2.
5. Apply attempt 2 result as authoritative `COMPLETED`.
6. Deliver the delayed attempt 1 result as `FAILED` or `UNKNOWN_OUTCOME`.
7. Assert the durable operation remains `COMPLETED`, version 2.

Expected:

```text
attempt 2 COMPLETED(version=2)
late attempt 1 FAILED(version=1)
        |
        v
final durable state = COMPLETED(version=2)
```

### B7-B — stale older completion after newer terminal state

Repeat the ordering but make attempt 2 terminate with an authoritative terminal state before attempt 1's completion arrives.

The late version-1 completion must not overwrite the version-2 terminal state or its evidence.

The exact attempt-2 terminal state must be selected before implementation; this experiment should not silently assume that `COMPLETED` is always the right winner.

## 6. Required caller-store model

The experiment-only caller record needs:

```text
operation_id
current_attempt_id
current_state_version
current_state
```

Result application should be an explicit operation:

```text
applyResult(operation_id, attempt_id, state_version, outcome)
```

and must enforce the ordering rule before changing durable state.

A stale result should return a typed outcome such as:

```text
APPLIED
IDEMPOTENT
STALE_IGNORED
CONFLICT
UNKNOWN_OPERATION
```

Do not encode stale-result rejection as a generic exception.

## 7. Acceptance invariants

### I-B7-1 — identity match

A result for operation X cannot mutate operation Y.

### I-B7-2 — attempt identity is preserved

A result must identify the attempt that produced it.

### I-B7-3 — durable version monotonicity

The authoritative durable `state_version` never decreases.

### I-B7-4 — stale result cannot regress state

A result with a lower `state_version` than the current durable state is ignored for semantic mutation.

### I-B7-5 — equal-version idempotency

Re-delivering the same result at the same authoritative version does not produce another semantic transition.

### I-B7-6 — evidence remains auditable

Ignoring a stale result does not require deleting it from diagnostic/evidence records. Semantic truth and diagnostic history remain distinct.

## 8. Failure interpretation

FAIL when:

```text
state_version decreases
older result changes terminal state
older attempt overwrites newer evidence
result for a different operation mutates current operation
same result causes repeated semantic transition
```

INCONCLUSIVE when the result ordering cannot be attributed to the injected delivery order.

## 9. What B7 does not establish

A passing B7 semantic experiment does not establish:

- global causal ordering across arbitrary networks;
- exactly-once execution of an external side effect;
- cross-package ordering guarantees;
- database transaction serializability in every implementation;
- scheduler ordering;
- power-loss durability.

## 10. Promotion rule

Only a real instrumentation run in which the test deliberately delivers results out of order may promote B7 from OPEN to case-level EXPERIMENTALLY_SUPPORTED.

## 11. Relationship to external reference research

The external research record points to three relevant ideas:

- durable workflow systems preserve history rather than trusting callback arrival order;
- keyed coordination systems serialize one logical ownership boundary;
- idempotency systems distinguish repeated identity from a genuinely new request.

B7 combines these into the smallest repository-specific invariant: **durable authority order must dominate callback arrival order**.

## 12. Next gate

After B7 is proven:

```text
T2 B2/B3/B4/B5/B6/B7
        ↓
T2 evidence synchronization
        ↓
T3 distinct provider package/application
        ↓
discovery + authorization + cross-package trust
```
