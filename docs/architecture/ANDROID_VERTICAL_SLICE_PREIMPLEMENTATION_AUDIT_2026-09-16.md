# Android Vertical Slice — Pre-Implementation Audit — 2026-09-16

Status: **RESEARCH RECORD / PRE-IMPLEMENTATION GATE**

Purpose: audit the proposed cross-app durable workflow vertical slice from every material angle before changing the implementation. This document deliberately separates repository facts, Android platform facts, architectural inferences, hypotheses, and open experiments.

Claim labels:

- **ESTABLISHED** — directly supported by repository evidence or primary Android/AOSP documentation.
- **EXPERIMENTALLY_SUPPORTED** — confirmed by a controlled test already present in the repository/device evidence.
- **INFERENCE** — architectural consequence of established behavior.
- **HYPOTHESIS** — design proposition requiring validation.
- **UNKNOWN** — not established safely enough to rely on.
- **CONFLICT** — current implementation contradicts an accepted invariant.
- **OPEN** — requires a discriminating test or implementation change.

---

## 1. Current baseline: do not change implementation yet

### 1.1 Repository state

**ESTABLISHED:** the application currently targets Android API 35, with minSdk 26, and does **not** yet depend on Room or WorkManager. The current dependency set is Compose, lifecycle, OkHttp, and JUnit.

Source:
- `app/build.gradle.kts`

**ESTABLISHED:** Android composition currently creates a `JournalRuntimeStore` under the application's `filesDir`, performs one recovery pass per process, and constructs `AgentRuntime` around that store.

Sources:
- `app/src/main/java/com/hicham/llmchat/runtime/AndroidRuntimeFactory.kt`
- `app/src/main/java/com/hicham/llmchat/runtime/RuntimeStore.kt`

**ESTABLISHED:** the repository already has deterministic tests for UNKNOWN-effect enumeration, explicit reconciliation, controlled replay after `CONFIRMED_NOT_EXECUTED`, and journal restart across separate store instances.

Source:
- `app/src/test/java/com/hicham/llmchat/runtime/UnknownEffectReconciliationTest.kt`

**ESTABLISHED:** the existing durable-runtime gate explicitly states that real Android process-death behavior and capability-specific external reconciliation remain open, and it does not claim exactly-once external execution.

Source:
- `docs/architecture/DURABLE_RUNTIME_GATE_v0.1.md`

### 1.2 Consequence for the proposed vertical slice

The previous architecture note proposed:

```text
Room Operation
 -> WorkManager Worker
 -> cross-process provider
 -> operation_id
 -> side effect + durable result
 -> process loss
 -> reconcile
 -> resume/commit
```

**CONTRADICTED AS AN IMMEDIATE IMPLEMENTATION REQUIREMENT:** Room and WorkManager are not yet part of the current runtime. Introducing both before proving the cross-process ambiguity model would combine several independent variables and make failures harder to localize.

**INFERENCE / REVISED RULE:** the first vertical slice should reuse the existing `RuntimeStore` abstraction and current `JournalRuntimeStore` unless an experiment demonstrates that a missing SQLite transaction/query primitive is the actual blocker.

Revised experimental path:

```text
existing RuntimeStore
      |
      v
cross-process Binder provider
      |
      v
operation_id + explicit failpoints
      |
      v
process/provider loss
      |
      v
recovery + reconciliation
```

WorkManager becomes a separate scheduling adapter experiment after the semantic recovery path is proven.

---

## 2. Semantics audit

### S-01 — Operation identity vs process identity

**ESTABLISHED / INFERENCE:** the durable semantic operation cannot be an Activity, Service instance, Worker instance, Binder proxy, coroutine Job, or process-local object. The cross-app audit already separates operation, process, transport, and artifact-locator lifetimes.

Required invariant:

```text
recovery input = durable semantic identity
recovery implementation = reacquired runtime handles
```

### S-02 — Scheduler state is not domain state

**ESTABLISHED:** WorkManager retry is a scheduling instruction, not evidence about the external effect. `Result.retry()` requests another execution attempt using WorkManager backoff semantics.

**INFERENCE:**

```text
scheduler_retry
    !=
semantic_retry
```

The Worker must inspect domain state before requesting another attempt.

### S-03 — UNKNOWN is a real state

**ESTABLISHED / INFERENCE:** if the provider may have performed an effect but the caller did not obtain a definitive outcome, the safe semantic state is UNKNOWN until reconciliation establishes a stronger fact.

Forbidden transition:

```text
transport failure -> FAILED -> automatic side-effect retry
```

Required transition:

```text
transport ambiguity -> UNKNOWN -> RECONCILIATION -> classified outcome
```

### S-04 — Completion precision

**CONFLICT:** current `AgentRuntime.execute()` reserves all invocations, executes them, computes a combined reduction/verification result, and only then marks **all** effects `COMPLETED`. If a later invocation fails after an earlier effect has actually succeeded, the exception/failure path marks the entire invocation set `UNKNOWN`.

This loses per-effect knowledge.

Example:

```text
Effect A -> completed externally
Effect B -> throws / ambiguous

Current model can become:
A = UNKNOWN
B = UNKNOWN
```

But the most precise state may be:

```text
A = COMPLETED
B = UNKNOWN
```

**REQUIRED DESIGN CORRECTION:** effect records need per-effect execution classification; later failure must not erase established facts about earlier effects.

This does **not** eliminate the need for reconciliation because process death can still occur between the external effect and the local completion record.

### S-05 — Multi-effect Actions

**OPEN:** an Action may declare multiple capability invocations. The system must choose and document one semantic model:

```text
MODEL A — group transaction semantics
    all reservations and execution form one logical unit
    group state is durable and atomically visible

MODEL B — independently durable effects
    each effect has its own lifecycle
    Action reduction is a derived aggregate
```

**RECOMMENDATION FOR VALIDATION:** prefer Model B unless a real workload proves that group-level atomicity is necessary, because Android cross-app effects generally cannot be made physically atomic across different providers anyway.

Under Model B:

```text
Action
  -> Effect A
  -> Effect B
  -> Effect C

Each Effect has its own identity/outcome.
Action outcome = verified aggregate over effect states.
```

**OPEN:** the current code and documents still describe reservation as an all-or-nothing complete effect set, while the journal is physically appending one record per effect. This mismatch must be resolved before claiming transaction semantics.

---

## 3. Journal and storage audit

### J-01 — Current journal durability

**ESTABLISHED:** `JournalRuntimeStore` appends records using `FileChannel` and calls `channel.force(true)` before returning from each append.

Source:
- `RuntimeStore.kt`

**LIMIT:** this is evidence about the journal's local write path, not proof of power-loss durability on every filesystem/device.

### J-02 — Concurrent writer safety

**OPEN:** the current lock is private to each `JournalRuntimeStore` instance. Separate instances/processes can concurrently replay, validate, and append against the same file.

The architecture ledger already records this as an open concurrency issue.

Required experiment:

```text
N store instances / processes
same journal
same effect identity
simultaneous reserve

Expected:
exactly one accepted reservation
no corrupt journal
no duplicate accepted ownership
```

### J-03 — Group reservation atomicity

**OPEN / CONFLICT WITH CLAIMED SEMANTICS:** `reserveEffects()` appends one `E` record per invocation. A process loss during the sequence can leave a durable prefix rather than a fully visible logical reservation set.

Required decision:

```text
Either:
    implement a group record / transaction marker / commit protocol

or:
    stop claiming group-atomic reservation
    and model each Effect independently.
```

### J-04 — Torn tail / corruption

**OPEN:** the journal intentionally ignores malformed/torn final records during replay. That is useful crash tolerance but not yet a corruption policy.

Need tests for:

```text
truncated final record
partial UTF-8 sequence
partial multi-effect reservation
invalid enum value
unknown record type
reordered records
repeated terminal records
```

Required outcome classification:

```text
recover safely
OR
fail closed and surface corruption
```

Silent reinterpretation must not occur.

### J-05 — Terminal-state immutability

**OPEN:** current journal is append-only, but semantic terminal-state rules need explicit validation against stale callbacks and late writes.

Test:

```text
RUN -> SUCCEEDED
late provider callback
late failure callback
late retry

Expected:
terminal state remains authoritative
late data cannot regress it
```

### J-06 — Historical Action semantics

**OPEN / CONFLICT:** journal reconstruction uses the current Action definition by ID while copying the historical version number. A version number alone does not prove executable semantic identity.

Required durable provenance:

```text
action_id
action_version
action_definition_hash
```

or an immutable action snapshot identity.

---

## 4. Effect identity and idempotency audit

### E-01 — Current effect identity scope

**ESTABLISHED:** deterministic `effectId` is derived from:

```text
action.id
+ action.version
+ capabilityId
+ caller idempotencyKey
```

**OPEN:** this excludes, unless intentionally supplied through the key:

```text
requester identity
concrete parameters
scope
provider identity
provider version
resource identity
```

Therefore the project must explicitly define whether `idempotencyKey` is:

```text
A. a caller-global semantic operation key
B. scoped to one Action/Capability definition
C. scoped to one provider/resource
D. merely a local duplicate-prevention token
```

### E-02 — Provider reconciliation contract

**INFERENCE:** local duplicate blocking is insufficient for an external effect. A provider must expose at least one of:

```text
idempotent operation_id
query/reconcile by operation_id
stable result artifact
compensation
explicit non-retryable semantics
```

Otherwise the orchestrator may be unable to distinguish:

```text
not executed
vs
executed but response lost
```

### E-03 — Exactly-once

**CONTRADICTED if interpreted as a platform guarantee:** Android cannot by itself turn arbitrary cross-app effects into exactly-once execution.

The strongest realistic statement is conditional:

```text
at-most-one local admission
+
provider idempotency/reconciliation
+
correct recovery protocol
```

can produce a useful exactly-once-like semantic contract for a specific capability.

No general exactly-once guarantee should be claimed.

---

## 5. Concurrency and stale callback audit

### C-01 — Duplicate workers

**OPEN:** multiple schedulers/workers may attempt recovery for the same operation unless durable ownership/claiming is explicit.

Required invariant:

```text
same operation_id
same recoverable checkpoint
concurrent recovery attempts

=> one authoritative attempt OR explicit safe concurrent readers
```

### C-02 — Compare-and-set semantics

**OPEN:** the store needs an atomic primitive equivalent to:

```text
if state == EXPECTED
    transition to NEW_STATE
else
    reject
```

Without this, two processes can both observe a retryable state and both dispatch.

### C-03 — Stale callbacks

**OPEN:** every callback/result should carry enough identity to reject an old attempt:

```text
operation_id
attempt_id
provider/capability version
```

A result from attempt N must not silently complete attempt N+1.

### C-04 — Ordering is not proof

**INFERENCE:** event order alone cannot establish semantic truth across processes. Durable state + identity + validation are required.

---

## 6. Android IPC / provider audit

### P-01 — Binder as first provider mechanism

**INFERENCE:** Binder is the best first T2 mechanism because it makes process boundaries explicit and exposes meaningful failure modes such as remote process death and transaction failure.

It is not being chosen because Binder is universally better than ContentProvider/AppFunctions; it is chosen because it isolates the cross-process hypothesis with minimal additional semantics.

### P-02 — Binder failure classification

Required fault cases:

```text
provider unavailable before dispatch
provider dies before receiving request
provider dies after accepting request
provider completes effect then dies before reply
caller dies after dispatch
caller dies after reply but before local completion persist
```

At least the latter three can produce UNKNOWN semantics.

### P-03 — Binder payload boundary

**ESTABLISHED:** Binder transactions have bounded size. Large data should move as references/descriptors/managed artifacts rather than inline payloads.

The vertical slice should therefore keep the control message small:

```text
operation_id
capability_id
capability_version
parameters/reference metadata
```

### P-04 — Provider process is not durable truth

**INFERENCE:** provider process memory must not be the only record of an accepted operation. The provider needs its own durable or reconstructable reconciliation state for effects that matter.

---

## 7. Package, capability, and security audit

### K-01 — Provider discovery

**OPEN:** Android package visibility filtering applies when querying for other applications on modern Android targets. Cross-app capability discovery therefore cannot assume that `PackageManager` sees every installed package without the appropriate visibility declarations or another supported path.

This must be included in the capability-resolution experiment.

### K-02 — Discovery != authorization

**ESTABLISHED / INFERENCE:** knowing which package can handle an operation does not prove the caller is authorized to invoke it.

Authorization must consider:

```text
caller principal
activation source
capability
resource/scope
provider policy
user approval where required
```

### K-03 — Identity is currently caller-asserted

**OPEN:** `ActivationRequest.identity` is application data, not a cryptographically authenticated principal.

This is acceptable for the current trusted in-process path but insufficient as a security boundary for arbitrary external callers.

### K-04 — Provider package version

**OPEN:** durable workflow state should retain:

```text
provider package
provider version
capability ID
capability version
```

Recovery must perform compatibility checks rather than assuming implementation stability.

### K-05 — Uninstall/reinstall

**OPEN:** a provider that disappears and later returns may not be semantically equivalent to the original provider. Recovery must not equate package-name continuity with state continuity.

---

## 8. Lifecycle / scheduling audit

### L-01 — Process death

**OPEN:** actual Android process-death evidence is not yet present. The first device experiment must demonstrate journal recovery with real application process loss.

### L-02 — Reboot

**INFERENCE:** reboot is a separate experiment from process death. Runtime handles disappear; durable state must be reloaded and platform-managed scheduling/access grants must be re-established according to their own contracts.

### L-03 — Force-stop

**ESTABLISHED:** Android force-stop/stopped-state behavior is materially different from ordinary worker preemption. The recovery subsystem must not turn a user stop into an autonomous retry loop.

### L-04 — WorkManager

**INFERENCE:** WorkManager should be added after T2 semantic proof as a scheduler adapter:

```text
WorkManager
   -> load durable operation
   -> classify state
   -> reconcile if needed
   -> execute one safe attempt
   -> persist outcome
```

It should not become the domain state machine.

### L-05 — Cancellation

**OPEN:** cancellation semantics need separate treatment from process loss.

Required distinctions:

```text
cooperative cancellation
system stop
user stop
process death
provider death
network timeout
```

The provider must not be assumed to have rolled back a side effect merely because the caller cancelled locally.

---

## 9. Data-plane / artifact audit

### D-01 — Control plane vs data plane

**ESTABLISHED / INFERENCE:** the operation message should carry identifiers/references; large binary data should cross as URI/FD/stream/shared object where appropriate.

### D-02 — Artifact identity vs locator

**INFERENCE:**

```text
artifact_id + version = semantic identity
locator = replaceable access mechanism
```

A changed URI or file location must not silently mean a new semantic artifact.

### D-03 — URI permission lifetime

**OPEN:** URI grants must be tested across process death, reboot, provider update, and document disappearance. Permission validity and artifact existence are separate state dimensions.

### D-04 — BlobStore

**OPEN:** BlobStore is a managed shared-data primitive with leases/quotas, not a canonical business database. Its failure/expiry behavior must be tested separately from effect recovery.

---

## 10. Backup / restore / privacy audit

### B-01 — Current backup posture

**ESTABLISHED:** the application currently declares `android:allowBackup="true"`.

Source:
- `app/src/main/AndroidManifest.xml`

**OPEN:** the runtime journal lives under `filesDir` and may contain Run/Evidence/effect metadata and potentially user-derived content. Backup/restore behavior therefore needs an explicit data classification before production.

The correct decision is not automatically “disable backup”. It is:

```text
classify each durable artifact
    -> allow cloud restore?
    -> allow device-to-device transfer?
    -> exclude?
    -> transform/sanitize?
```

For Android 12+, cloud backup and device-to-device transfer have separate rule surfaces. The project must define both deliberately.

### B-02 — Key material vs runtime state

**INFERENCE:** encrypted credentials and recoverable runtime journal should not automatically have the same backup policy. A credential encrypted with device-bound key material may be non-portable in ways that ordinary runtime metadata is not.

### B-03 — Evidence privacy

**OPEN:** durable evidence should be classified for:

```text
secrets
conversation content
provider responses
local file paths
resource identifiers
external account references
operation metadata
```

Storage durability is not data minimization.

---

## 11. Update / compatibility audit

### U-01 — Provider update during suspended operation

Required experiment:

```text
plan operation against provider version N
suspend
update provider -> N+1
resume
```

Recovery must detect compatibility state rather than blindly invoking a stale contract.

### U-02 — Consumer app update

The orchestrator itself may update while operations are pending.

Required durable schema/provenance:

```text
runtime schema version
operation schema version
action version
action definition hash
capability version
provider version
```

### U-03 — Migration failure

**OPEN:** schema or journal migration failure must fail closed with explicit recovery evidence. It must not silently reinterpret older records.

---

## 12. Resource / cancellation / boundedness audit

### R-01 — Workload budget

**OPEN:** any future model -> tool -> model or cross-app recovery loop needs explicit budgets:

```text
max attempts
max provider calls
max wall time
max payload size
max stored artifact size
max history size
```

### R-02 — Backoff is not recovery correctness

Backoff controls pressure and timing. It does not establish whether an external side effect occurred.

### R-03 — Provider starvation / binder thread pressure

**ESTABLISHED / INFERENCE:** long blocking provider calls can consume Binder/provider threads and lead to system-level responsiveness failures. Long-running work therefore belongs in an asynchronous workflow where possible.

---

## 13. Observability / evidence audit

Every experimental attempt should emit a reconstructable record containing:

```text
experiment_id
operation_id
attempt_id
hardware
Android release
API level
build fingerprint
orchestrator version
provider package/version
capability version
fault injection point
timestamp sequence
scheduler state
transport result
provider observation
domain state before/after
artifact state
reconciliation decision
final classification
```

### Evidence hierarchy

```text
L0 design statement
L1 code inspection
L2 deterministic test
L3 CI evidence
L4 local restart evidence
L5 Android device lifecycle evidence
L6 real external-effect evidence
L7 repeated field evidence
```

No lower level should be represented as a higher-level guarantee.

---

## 14. Failure-injection matrix

The first implementation test harness must support explicit failpoints:

```text
F0  before reservation
F1  during multi-effect reservation
F2  after provider receives operation_id, before effect
F3  after effect, before provider reply
F4  after provider reply, before local effect completion persist
F5  after effect completion persist, before Run terminal persist
F6  during reconciliation
F7  provider package update/uninstall
F8  user force-stop
F9  reboot
F10 journal torn/corrupt tail
F11 concurrent duplicate recovery
F12 stale callback from previous attempt
F13 approval consumed, execution not yet reserved
```

The key adversarial windows are F3/F4/F13.

---

## 15. Test ladder — revised

### T0 — deterministic semantic state machine

No Android dependency.

Validate:

```text
UNKNOWN classification
per-effect completion
retry eligibility
reconciliation
stale attempt rejection
terminal-state immutability
```

### T1 — journal crash/concurrency harness

Use the existing `RuntimeStore` implementation.

Inject:

```text
partial append
concurrent writers
duplicate reservation
stale callback
restart after each journal boundary
```

Do not add Room yet.

### T2 — real Android Binder provider

Two packages/processes:

```text
orchestrator app
provider test app
```

Use a narrow Binder contract and explicit `operation_id`.

Inject process death at F2/F3/F4.

### T3 — WorkManager scheduling adapter

Only after T2 semantics are understood.

Use WorkManager for:

```text
when to attempt
constraints
backoff
rescheduling
```

Use `RuntimeStore` for:

```text
what the operation means
what was known
whether reconciliation is required
```

### T4 — lifecycle matrix

Test on real device(s):

```text
normal completion
process death
reboot
force-stop
provider unavailable
provider update
provider uninstall/reinstall
permission change
```

At least two Android API generations should be represented before generalizing.

### T5 — data-plane adapters

Only after T2-T4:

```text
ContentProvider
URI grants / SAF
AppFunctions
BlobStore
FD/stream artifacts
```

Each is an adapter experiment, not a new semantic authority.

---

## 16. Acceptance invariants

The vertical slice cannot be promoted unless these are demonstrated:

```text
I1  Recovered execution never depends on a dead process-local handle.

I2  UNKNOWN outcomes are never blindly re-executed.

I3  A terminal operation cannot be regressed by stale callbacks or retries.

I4  User force-stop is respected as an authority boundary.

I5  Provider identity/version is revalidated on recovery.

I6  Artifact identity survives locator replacement.

I7  Scheduler state is not used as semantic truth.

I8  Per-effect outcomes are preserved precisely.

I9  Multi-effect transaction semantics are explicit and physically representable.

I10 Concurrent recovery attempts cannot both claim the same effect.

I11 The provider can reconcile at least one ambiguous externally visible operation.

I12 Backup/restore classification exists for every durable artifact.

I13 Historical Action semantics can be identified immutably.

I14 Corrupt/torn journal state fails closed or is repaired through an explicit, evidenced path.

I15 Resource/cancellation budgets prevent unbounded autonomous execution.
```

---

## 17. No-go conditions

Do **not** promote the slice to a general cross-app orchestration substrate when any of the following remains unresolved:

```text
NG1  "transport failure = operation failure" is still encoded anywhere.
NG2  external side effects have no operation identity/reconciliation path.
NG3  concurrent recovery can claim the same effect twice.
NG4  a user force-stop can silently trigger autonomous retry.
NG5  terminal state can be overwritten by stale callbacks.
NG6  provider update/replacement is treated as transparent implementation continuity.
NG7  backup/restore can silently replicate sensitive runtime state.
NG8  multi-effect semantics are described as atomic while implementation is not atomic.
NG9  verification depends only on producer self-attestation for consequential effects.
NG10 process-death evidence is replaced by unit-test evidence.
```

---

## 18. Revised minimal vertical slice

The smallest falsifiable implementation is now:

```text
Activation
   ↓
ActionPlan
   ↓
RuntimeStore.reserve / effect identity
   ↓
Binder Provider
   ↓
operation_id
   ↓
provider-side durable/reconstructable effect record
   ↓
controlled side effect
   ↓
configurable failure injection
   ↓
UNKNOWN / COMPLETED classification
   ↓
process restart
   ↓
provider reconciliation by operation_id
   ↓
per-effect verification
   ↓
terminal Run/Evidence
```

The first implementation should use a deterministic test-side effect such as an append-only provider-owned record rather than email/payment/delete/network mutation.

The purpose is not product functionality. The purpose is to falsify or support the recovery semantics under ambiguity.

---

## 19. What this experiment can and cannot prove

### It can prove

```text
cross-process identity propagation
recovery without live handles
journal recovery behavior
concurrent-claim behavior
UNKNOWN handling
stale callback rejection
provider re-resolution
scheduler/domain separation
```

### It cannot prove

```text
universal exactly-once execution
all OEM filesystem durability properties
all Android lifecycle behavior
third-party provider cooperation
security of arbitrary external applications
production reliability
```

Those require separate evidence levels.

---

## 20. Immediate implementation gate

Before writing the Binder test provider, the following code-level corrections should be addressed or explicitly isolated as experimental limitations:

1. **CONFLICT:** direct `ALLOW` planning failures must not escape `AgentRuntime.activate()`.
2. **CONFLICT:** per-effect outcome must not be collapsed into one all-or-nothing completion decision unless Action semantics explicitly require a group transaction.
3. **OPEN:** choose and document independent-effect vs group-transaction semantics.
4. **OPEN:** make reservation/recovery concurrency semantics explicit.
5. **OPEN:** define exact idempotency-key scope.
6. **OPEN:** pin historical Action semantics with immutable identity/hash.
7. **OPEN:** define journal corruption behavior.
8. **OPEN:** define backup/restore classification.

Only after these are either fixed or marked as controlled experimental limitations should the Binder vertical slice be implemented.

---

## 21. Current verdict

**ESTABLISHED:** the repository already contains a useful local durable-effect primitive and deterministic reconciliation tests. It is therefore unnecessary to introduce Room and WorkManager merely to begin falsifying the cross-app recovery hypothesis.

**CONFLICT:** the current multi-effect completion behavior is less precise than the desired evidence model.

**OPEN:** cross-process concurrency, Android process death, provider-side reconciliation, package visibility/update behavior, backup/restore, and real device lifecycle semantics remain unproven.

**HYPOTHESIS:** a small semantic operation/effect model with durable identities, explicit UNKNOWN state, provider reconciliation, and Android-native scheduling/IPC adapters is sufficient to compose multiple Android applications without treating processes as durable actors.

The next implementation should therefore be narrow enough to **kill this hypothesis**, not broad enough to hide its failures behind more infrastructure.

---

## 22. Evidence sources

Repository sources:

- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/hicham/llmchat/runtime/AgentRuntime.kt`
- `app/src/main/java/com/hicham/llmchat/runtime/RuntimeStore.kt`
- `app/src/main/java/com/hicham/llmchat/runtime/RuntimeModel.kt`
- `app/src/main/java/com/hicham/llmchat/runtime/AndroidRuntimeFactory.kt`
- `app/src/test/java/com/hicham/llmchat/runtime/UnknownEffectReconciliationTest.kt`
- `docs/architecture/ARCHITECTURE_AUDIT_LEDGER_2026-09-05.md`
- `docs/architecture/DURABLE_RUNTIME_GATE_v0.1.md`
- `docs/architecture/IMPLEMENTATION_RECONCILIATION_v0.2.md`
- `docs/architecture/ANDROID_PLATFORM_RUNTIME_DATA_PLANE_AUDIT_2026-09-16.md`
- `docs/architecture/ANDROID_CROSS_APP_DURABLE_WORKFLOW_AUDIT_2026-09-16.md`
- `docs/architecture/ANDROID_CROSS_APP_RECOVERY_STATE_MACHINE_AUDIT_2026-09-16.md`

Primary Android platform topics requiring validation in the experiment matrix:

- Binder / `IBinder` / remote process death / transaction limits
- WorkManager retry and stop semantics
- package visibility and capability discovery
- force-stop/stopped state
- URI grants and persistable permissions
- ContentProvider latency/ANR boundaries
- BlobStore lifecycle and leases
- Android 12+ backup/data-extraction rules
- package update/uninstall continuity

---

## 23. Status

This document is a **pre-implementation gate**. It intentionally does not authorize a broad workflow engine, generalized agent loop, or production background executor.

The next implementation should be the smallest controlled Binder/provider experiment satisfying the invariants above, followed by real-device fault injection and evidence capture.
