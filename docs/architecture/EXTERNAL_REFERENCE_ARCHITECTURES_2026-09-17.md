# External Reference Architectures — 2026-09-17

Status: **RESEARCH RECORD / CURRENT INPUT TO B4-B7 DESIGN**

Purpose: record external systems and engineering patterns that address the same classes of problems being tested in this repository: durable execution, process loss, retry, operation identity, reconciliation, concurrency, stale results, transactional boundaries, and scheduler/runtime separation.

This document is a research record, not an instruction to copy another system. Each reference is evaluated for the specific primitive it contributes, the evidence boundary, and the parts that should remain out of scope.

## 1. Current question

The repository has case-level experimental support for T2 B2/B3:

```text
provider process A
    -> durable receipt/effect state
    -> provider process loss
    -> provider process B
    -> recover state
    -> reconcile without duplicating the established effect
```

The next boundary is caller-side recovery, followed by concurrent recovery and stale result ordering:

```text
caller durable checkpoint
    -> provider operation
    -> caller process loss
    -> UNKNOWN_OUTCOME
    -> reconcile(operation_id)
    -> terminal state without duplicate effect

then:
B6 concurrent recovery
B7 stale callback/result ordering
```

The external research below is therefore evaluated primarily against those boundaries.

## 2. Reference matrix

| Reference | Primary primitive | What it contributes here | Current classification |
|---|---|---|---|
| Restate | durable log, durable invocations, keyed state | log-centric recovery, keyed serialization, durable progress, explicit partial-failure handling | REFERENCE |
| Temporal | workflow history + replay | durable orchestration, history as source of truth, workflow/activity separation, replay discipline | REFERENCE |
| DBOS | transaction + durability record | stronger checkpoint/effect atomicity when application state and durability record share a transaction boundary | REFERENCE / DESIGN INPUT |
| Cloudflare Durable Objects | keyed single-threaded coordination + transactional storage | B6 concurrency model, ownership key, storage/persistence gates, avoiding locks across external I/O | REFERENCE / DESIGN INPUT |
| Stripe idempotency | operation/idempotency key + parameter matching | practical duplicate-request contract and key misuse detection | REFERENCE / DESIGN INPUT |
| Transactional Outbox | transactionally persisted intent + asynchronous relay | transport/delivery is not semantic completion; duplicate delivery remains possible | REFERENCE |
| Idempotent Consumer | durable processed-message identity | explicit deduplication at the receiving semantic boundary | REFERENCE |
| Android Binder | IPC + death notification | transport/process death is an observation, not a semantic outcome | PLATFORM FACT |
| Android WorkManager | persistent scheduling | execution/scheduling adapter; durable scheduler is not the semantic model | PLATFORM FACT |
| SQLite | atomic transactions/crash recovery | candidate implementation substrate for stronger local durability/concurrency semantics | RESEARCH CANDIDATE |
| TLA+ / TLC | model checking | pre-implementation state-space exploration for B6/B7 invariants | VERIFICATION TOOL |

## 3. Restate

Source:
- https://restate.dev/blog/why-we-built-restate

Restate describes a model where durable execution is built around a durable execution log. It treats invocations, promises, communication, and state as durable concerns and reconstructs partial execution after failures. Its keyed handlers serialize invocations per key and attach state to that key; state updates are committed with handler completion in its model.

Relevant observations:

```text
keyed identity
    -> ordered/serialized handling
    -> durable state
    -> recovery from partial execution
```

This strongly resembles the problem represented by `operation_id` plus a durable effect record, but Restate is a distributed execution system and our current runtime is intentionally local/portable.

### What to borrow

- Treat durable history/state as the recovery source rather than process memory.
- Consider keyed ownership/serialization for one logical operation.
- Treat partial execution and recovery as first-class states.
- Keep transport/RPC separate from semantic durability.

### What not to borrow yet

- Distributed log/Raft infrastructure.
- A network proxy as a mandatory execution component.
- The operational complexity of a general-purpose distributed runtime.

Classification: **REFERENCE**. The design principles are useful; the full system is out of scope.

## 4. Temporal

Sources:
- https://docs.temporal.io/workflows
- https://docs.temporal.io/activities

Temporal states that Workflow Executions are resilient across infrastructure failures and that their Event History is the source of truth used for replay. Activities isolate external/non-deterministic work such as API calls, database queries, LLM calls, and file I/O. Temporal recommends idempotent Activities because retries can happen.

Relevant model:

```text
Workflow
    = durable deterministic orchestration

Activity
    = external/non-deterministic work

Event History
    = replay source of truth
```

### What this validates in our architecture

Our separation:

```text
Run / Action
    -> CapabilityInvocation
    -> CapabilityExecutor
    -> Android adapter / external effect
```

is directionally consistent with a mature durable-execution architecture.

### Specific design input

Historical execution must be interpretable after code/configuration changes. Temporal's replay model makes history compatibility a first-class concern. This reinforces the open requirement in our architecture to define immutable or versioned historical Action/Capability definitions rather than assuming current code is sufficient to interpret old runs.

### What not to borrow yet

We do not need a full workflow server, task queue, or distributed history service to prove the Android semantics currently under investigation.

Classification: **REFERENCE / DESIGN INPUT**.

## 5. DBOS

Sources:
- https://docs.dbos.dev/golang/tutorials/transaction-tutorial
- https://docs.dbos.dev/java/tutorials/step-factory-tutorial

DBOS explicitly distinguishes a normal step checkpoint from a transaction in which application writes and the durability record commit atomically. Their documentation states that without a shared transaction boundary, a crash after the application write but before the checkpoint can cause a step to execute again; transactional execution can instead return the recorded output on recovery without re-executing the function.

This gives us a sharper question than "do we have a journal?":

```text
When does semantic effect state become durable?

Can effect state and recovery record commit atomically?
```

### Design consequence

For future local implementations we should distinguish at least:

1. durable checkpoint before external effect;
2. durable record after external effect;
3. atomic application-state + durability commit;
4. reconciliation after an ambiguous external effect.

The existence of a journal alone does not prove the stronger property in (3).

Classification: **REFERENCE / DESIGN INPUT**.

## 6. Cloudflare Durable Objects

Sources:
- https://developers.cloudflare.com/durable-objects/concepts/what-are-durable-objects/
- https://developers.cloudflare.com/durable-objects/best-practices/rules-of-durable-objects/
- https://developers.cloudflare.com/durable-objects/best-practices/access-durable-objects-storage/

Cloudflare describes Durable Objects as stateful, uniquely identified coordination units with durable, transactional, strongly consistent storage. Their current guidance emphasizes choosing an atom of coordination, using storage primitives rather than global locking, and understanding input/output gates. Storage writes can be protected so a response is not exposed before persistence, while external I/O can still permit interleaving and therefore requires an explicit concurrency design.

This is directly relevant to B6.

### Design candidate for B6

Use a logical ownership key such as:

```text
operation_id
```

or, where appropriate:

```text
provider_id + operation_id
```

and define exactly which transitions are serialized for that key.

Do not hold a global or long-lived lock across external I/O. Separate:

```text
read/check state
    -> decide/claim
    -> external call
    -> record outcome
```

from the concurrency mechanism that protects durable transitions.

Cloudflare's model is not identical to Android/JVM semantics, so it is a reference for the coordination primitive, not evidence that our implementation is correct.

Classification: **REFERENCE / DESIGN INPUT**.

## 7. Stripe idempotency

Source:
- https://docs.stripe.com/api/idempotent_requests

Stripe documents an idempotency-key contract for retrying requests without accidentally creating a second object or applying the same update twice. The documented behavior also checks that the parameters match the original request and treats concurrent conflicts specially when execution has not been initiated.

The useful lesson is that an operation identity should not be just a random UUID with undefined reuse semantics.

### Candidate contract for this repository

```text
operation_id
request_fingerprint
capability_identity
capability_version
```

Potential rule:

```text
same operation_id + same request fingerprint
    -> same logical operation / safe retry path

same operation_id + different request fingerprint
    -> conflict
```

This is a design candidate, not yet a repository contract.

Classification: **REFERENCE / OPEN DESIGN INPUT**.

## 8. Transactional Outbox and Idempotent Consumer

Sources:
- https://microservices.io/patterns/data/transactional-outbox
- https://microservices.io/patterns/communication-style/idempotent-consumer.html

The transactional outbox pattern addresses the gap between committing application state and reliably publishing a message. It persists the outgoing intent in the same local transaction, then a relay sends it later. The relay can still publish the same message more than once after a crash, so consumers must tolerate duplicate delivery, commonly by recording processed message IDs.

This pattern supports two current repository boundaries:

```text
transport success
    !=
semantic completion
```

and:

```text
at-least-once transport
    -> requires deduplication/idempotent semantic handling
```

### What to borrow

- Durable intent before asynchronous delivery.
- Separate relay/transport from semantic state.
- Explicit duplicate identity at the receiver.

### What not to claim

Outbox does not create a universal exactly-once guarantee for arbitrary external side effects.

Classification: **REFERENCE**.

## 9. Android Binder

Source:
- https://developer.android.com/reference/android/os/IBinder

Android documents that `linkToDeath()` notifies a client when a remote Binder object goes away, typically because its hosting process was killed. `isBinderAlive()` can return true even though the process may die while the call is returning.

This is direct platform evidence for an important semantic boundary:

```text
Binder/process signal
    !=
semantic effect outcome
```

Therefore a Binder death path should normally feed the runtime's ambiguity/recovery machinery, not directly assign `FAILED` or `SUCCEEDED` to the business operation.

This is directly relevant to the next caller-side T2 work.

Classification: **PLATFORM FACT**.

## 10. Android WorkManager

Sources:
- https://developer.android.com/develop/background-work/background-tasks/persistent
- https://developer.android.com/develop/background-work/background-tasks/persistent/threading

Android documents WorkManager as the recommended mechanism for persistent background work and notes that its work persists across app restarts and device reboots. It provides scheduling, constraints, retry, chaining, and an internally managed SQLite database.

The important architectural boundary is:

```text
WorkManager
    = persistent scheduling/execution mechanism

RuntimeStore
    = semantic operation truth
```

Therefore WorkManager should be introduced only as T4 adapter work after the semantic recovery contracts are established. WorkManager persistence is not evidence that a particular external effect was applied exactly once.

Classification: **PLATFORM FACT**.

## 11. SQLite

Source:
- https://www.sqlite.org/atomiccommit.html

SQLite documents atomic transactions and crash/power-failure behavior for its transactional database design. This makes SQLite a serious candidate for a future `RuntimeStore` implementation, particularly when we need multi-process locking/transaction semantics rather than a custom file journal.

However:

```text
SQLite exists
    !=
we should replace JournalRuntimeStore now
```

The next step is comparative testing against explicit requirements:

- cross-process locking;
- atomic multi-record transitions;
- recovery after process death;
- corruption policy;
- performance on Android storage;
- power-loss semantics relevant to our threat model;
- ability to preserve our domain-level semantics without coupling the domain to SQLite.

Classification: **RESEARCH CANDIDATE**.

## 12. TLA+ / TLC

Source:
- https://docs.tlapl.us/

TLA+ is intended for modeling concurrent and distributed systems, and TLC is a model checker for TLA+ specifications. The value here is not replacing instrumentation tests; it is searching the small state spaces around B6/B7 before committing to a particular implementation.

### Candidate invariants

```text
I1: effect_count <= 1 for a non-repeatable effect

I2: COMPLETED implies durable evidence exists

I3: UNKNOWN does not imply FAILED

I4: reconcile(operation_id) cannot create a second effect

I5: a stale callback cannot overwrite newer durable truth

I6: same operation_id with a different request fingerprint is rejected

I7: recovery is monotonic with respect to established semantic facts
```

These are candidate invariants and must be mapped to the actual model before they are treated as verified properties.

Classification: **VERIFICATION TOOL / DESIGN INPUT**.

## 13. Cross-reference with current repository state

The current repository state already establishes the following boundary:

```text
DOMAIN
  Action / Capability / Run / Effect / Evidence
        |
        v
DURABILITY
  RuntimeStore / JournalRuntimeStore / ApprovalStore
        |
        v
EXECUTION
  CapabilityExecutor
        |
        v
ANDROID ADAPTERS
  Binder / lifecycle / future WorkManager / future AppFunctions
```

Current T2 evidence is limited to provider-process B2/B3. The current synchronization authority explicitly leaves caller `UNKNOWN_OUTCOME`, B1, B4, B5, B6, B7, cross-package trust/discovery, WorkManager, reboot/force-stop/update, and power-loss durability open.

Reference:
- `docs/architecture/ARCHITECTURE_STATE_SYNC_2026-09-17.md`

## 14. Synthesis: what appears repeatedly across independent systems

Across Restate, Temporal, DBOS, Durable Objects, Stripe, and outbox/idempotent-consumer patterns, the following architectural ideas recur:

```text
1. Durable identity
2. Durable state/history
3. Explicit recovery semantics
4. External effects isolated from orchestration state
5. Idempotency/deduplication at the semantic boundary
6. Explicit concurrency ownership
7. Transport/lifecycle failure separated from business outcome
8. Version/history compatibility treated as a real concern
```

This convergence is useful evidence for the direction of the repository, but it does not prove any particular implementation choice for Android.

## 15. What this research does NOT establish

The external systems do not establish that:

- a custom file journal is sufficient for our concurrency model;
- Binder can provide semantic exactly-once execution;
- WorkManager can serve as semantic truth;
- `operation_id` alone is sufficient identity;
- a database automatically solves external side-effect duplication;
- general exactly-once execution is achievable across arbitrary external systems.

These remain design questions or explicit non-claims.

## 16. Research-derived next experiments

### R1 — Operation identity contract

Design and test:

```text
operation_id
request_fingerprint
capability_id
capability_version
provider_id
```

Questions:

- What is the identity scope?
- What makes retry equivalent to the original operation?
- What is a conflict?
- Can the same operation cross process restart safely?

### R2 — Caller recovery (T2 B4/B5)

Instrument real cases for:

```text
caller dies before dispatch
caller dies after provider effect but before reply
caller restarts
caller classifies UNKNOWN_OUTCOME
caller reconciles by operation_id
```

Required assertion:

```text
duplicate provider effect count = 0
```

### R3 — Concurrent recovery (T2 B6)

Before implementation, model the race cases:

```text
reconcile + retry
retry + late completion
reconcile + reconcile
completion + stale callback
```

Prefer per-operation serialization/ownership over a global lock.

### R4 — Stale-result ordering (T2 B7)

Define an ordering mechanism before writing the test:

```text
attempt_id
sequence/version
state version
completion timestamp
```

The semantic rule must be explicit; timestamps alone are not a sufficient ordering contract.

### R5 — Journal vs SQLite

Only after B6/B7 clarify the required semantics, run a comparative experiment between:

```text
JournalRuntimeStore
SQLiteRuntimeStore
```

using the same domain contract and the same failure/concurrency matrix.

### R6 — T3 cross-package

Only after the caller-side and concurrency contracts are stable:

```text
same-app process boundary
    -> distinct provider package
    -> discovery
    -> authorization
    -> invocation
    -> outcome
```

Do not mix T3 trust/discovery variables into the current T2 failure evidence.

## 17. Decision rule

The repository should adopt an external pattern only when it satisfies all of these conditions:

1. it solves a concrete failure mode already represented in our evidence frontier;
2. its semantic boundary can be stated precisely;
3. it can be tested with a discriminating failure experiment;
4. it does not force the domain model to depend on an Android/vendor implementation;
5. its operational cost is justified by the guarantee obtained.

Otherwise it remains a reference, not an adopted architecture.

## 18. Claim ledger

### ESTABLISHED / EXTERNALLY DOCUMENTED

- Temporal uses durable Workflow Event History as replay source of truth and isolates external work in Activities.
- DBOS documents transactionally coupled application state + durability recording as a stronger recovery boundary than a plain checkpoint.
- Cloudflare Durable Objects provide a keyed stateful coordination primitive with durable transactional storage and explicit concurrency gates.
- Stripe documents idempotency keys with parameter matching and duplicate-request behavior.
- Transactional Outbox and Idempotent Consumer patterns document the need to separate durable intent/delivery from duplicate-safe consumption.
- Android Binder exposes process-death notifications, but `isBinderAlive()` is not a durable semantic outcome signal.
- Android WorkManager provides persistent scheduling across app restarts/reboots.
- SQLite documents atomic transaction behavior across crashes and power failures within its documented storage assumptions.
- TLA+/TLC provides model checking for concurrent/distributed state machines.

### INFERENCE

- `operation_id` should likely be a first-class semantic identity rather than a transport-only identifier.
- per-operation serialization is likely more useful than a global lock for B6.
- historical Action/Capability definitions likely need immutable/versioned interpretation data.
- reconciliation should be a semantic operation, not a blind transport retry.

### OPEN

- exact `operation_id` scope;
- request fingerprint contents;
- exact atomicity boundary between external effect and local durable truth;
- caller-side UNKNOWN_OUTCOME semantics;
- concurrency implementation;
- stale-result ordering contract;
- JournalRuntimeStore versus SQLite for the eventual production local store;
- cross-package trust/discovery protocol.

## 19. Current conclusion

The external research increases confidence in the **problem decomposition**, not in any one implementation.

The strongest common pattern is:

```text
execution is transient
        |
        v
identity + durable semantic state
        |
        v
external effect
        |
        v
observed outcome / reconciliation
```

The repository should therefore continue from T2 caller recovery and concurrency modeling rather than jumping directly to a framework or database replacement.
