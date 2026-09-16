# Android Cross-App Durable Workflow Audit — 2026-09-16

Status: **RESEARCH RECORD / ARCHITECTURE INPUT**

Purpose: determine what Android already guarantees for a multi-app workflow that must survive process death, reboot, user stopping, provider replacement, and package updates. This is deliberately separated from implementation commitments.

Claim labels used here:

- **ESTABLISHED** — directly supported by Android/AOSP documentation.
- **INFERENCE** — architectural consequence of established behavior.
- **HYPOTHESIS** — promising design idea requiring device/workload validation.
- **UNKNOWN** — not established or not safely inferable.
- **CONTRADICTED** — prior assumption rejected by stronger evidence.

## 1. Core model

An Android cross-app workflow must separate four lifetimes:

```text
Operation lifetime
    != process lifetime
    != transport/channel lifetime
    != artifact locator lifetime
```

The durable unit should therefore be a semantic **Operation** with stable identity, not an Activity, Worker, Binder object, URI, or process.

Recommended conceptual state:

```text
Operation
├── operation_id
├── workflow_id
├── semantic_state
├── attempt
├── checkpoint
├── provider_identity
├── artifact_refs
├── last_platform_observation
└── outcome
```

## 2. WorkManager: execution scheduling, not domain truth

**ESTABLISHED:** WorkManager persists work and can reschedule after system events such as reboot; its implementation explicitly listens for boot/time/constraint-related changes to reschedule work when appropriate. It also provides unique work, constraints, retry/backoff, and stop reasons. Source: AndroidX WorkManager API/package documentation.

Source:
- https://developer.android.com/reference/androidx/work/package-summary
- https://developer.android.com/reference/kotlin/androidx/work/WorkInfo

**INFERENCE:**

```text
Room/SQLite
    = durable semantic operation state

WorkManager
    = Android-managed execution scheduling
```

Do not make WorkManager's internal state the canonical domain state.

## 3. Reboot is not process death

**ESTABLISHED:** WorkManager is designed to reschedule work across reboot/time changes. Persistable URI permissions can also survive reboot when properly requested by the receiving application. BlobStore sessions/leases have explicit persistence semantics.

**INFERENCE:** A durable workflow can cross reboot only when each required component has its own reboot-safe contract. Runtime handles must be recreated.

```text
Before reboot:
    Binder handle       -> invalid
    FileDescriptor      -> invalid
    coroutine Job       -> gone
    process             -> gone

After reboot:
    operation_id        -> durable
    database state      -> durable
    persistable URI     -> may survive
    WorkManager work    -> may be rescheduled
    BlobStore lease     -> persisted subject to lease rules
```

Sources:
- https://developer.android.com/reference/androidx/work/package-summary
- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/reference/kotlin/android/content/Intent
- https://developer.android.com/reference/kotlin/android/app/blob/BlobStoreManager.html

## 4. Force-stop is a semantic boundary, not an ordinary retry

**ESTABLISHED:** Android exposes `ApplicationExitInfo.REASON_USER_REQUESTED` for user-requested termination such as force-stop. Android's foreground-service Task Manager can stop the whole app; no app callback is delivered for that action. Android 15 also maintains a package stopped-state model after force-stop until an explicit user interaction clears it.

Sources:
- https://developer.android.com/reference/android/app/ApplicationExitInfo
- https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping
- https://developer.android.com/about/versions/15/behavior-changes-all

**INFERENCE:** Do not treat force-stop as a transient `Worker` failure and immediately recreate work. The user's action is an explicit authority boundary. A recovery subsystem may record:

```text
USER_STOPPED
```

and wait for a subsequent valid activation path.

## 5. Foreground-service stop and scheduled work are different

**ESTABLISHED:** When the Android 13+ foreground-service Task Manager stops an app, the app process/memory and activity back stack are removed and the foreground service ends, but scheduled jobs and alarms can still execute at their scheduled times. No callback is delivered to the app at the stop moment.

Source:
- https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping

**INFERENCE:**

```text
process stopped
    !=
scheduled work cancelled
```

The workflow model must represent both independently.

## 6. URI grants are references with explicit persistence semantics

**ESTABLISHED:** Ordinary temporary URI grants generally last until device restart. `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` only makes persistence possible; the receiver must call `takePersistableUriPermission()`. Even a persisted permission does not guarantee that the underlying document remains available: if the document is moved/deleted, access is lost.

Sources:
- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/reference/kotlin/android/content/Intent

**INFERENCE:**

```text
Artifact identity
    != URI
    != URI permission
```

A workflow should persist:

```text
artifact_id
provider/package identity
uri when known
version/integrity when available
required access mode
```

Then re-resolve/reconcile at resume time.

## 7. App Functions URI grants require the same reasoning

**ESTABLISHED:** App Functions can return URI grants to callers. These grants normally persist until reboot unless persistability is explicitly established and taken. The underlying provider must allow URI grants.

Source:
- https://developer.android.com/reference/android/app/appfunctions/AppFunctionUriGrant

**INFERENCE:** AppFunctions can participate in durable artifact handoff, but they do not remove the underlying URI/provider lifecycle problem.

## 8. ContentProvider is an external execution boundary

**ESTABLISHED:** A remote ContentProvider can be cold-started when a client queries it. Slow remote provider queries can trigger provider ANRs, and concurrent blocking Binder calls can exhaust provider Binder threads.

Source:
- https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs

**INFERENCE:** ContentProvider should be treated as a responsive data/query boundary, not a hidden long-running workflow executor.

For large/long operations:

```text
Provider
    -> create/return artifact reference
    -> asynchronous execution mechanism
    -> durable result
```

rather than holding a synchronous provider query open for the full operation.

## 9. Binder/transport failure is not semantic failure

**INFERENCE based on Binder transaction/error semantics already recorded in the data-plane audit:**

```text
invoke(operation_id)
    ↓
transport failure
    ↓
result = UNKNOWN
```

The system must reconcile by `operation_id` before retrying a side-effecting operation.

This rule applies across:

```text
Binder
Intent handoff
AppFunctions
provider calls
IPC streams
```

## 10. BlobStoreManager is shared managed data, not canonical truth

**ESTABLISHED:** BlobStoreManager permits controlled sharing of large blobs, including package/same-signature access. It has leases and quotas. Active leases help keep a blob around, but blobs may still be deleted by user request; leases can expire. Sessions and lease metadata have persistence semantics across boots.

Sources:
- https://developer.android.com/reference/kotlin/android/app/blob/BlobStoreManager.html

**INFERENCE:** BlobStore is appropriate for:

```text
large shared model/data artifacts
reusable device-local datasets
intermediate shared payloads
```

but should not be treated as the only source of semantic truth for a business-critical artifact.

The durable record should still exist in our own source of truth:

```text
Room/SQLite
    artifact_id
    blob_handle/reference
    version
    integrity
    ownership/provenance
    expected availability
```

## 11. Package update is another workflow boundary

**ESTABLISHED:** An Android app update keeps the same application ID and requires the same signing identity (or valid proof-of-rotation) and a sufficient version code. An incompatible package/signing identity requires uninstall/reinstall, which erases app data.

Source:
- https://developer.android.com/google/play/app-updates

**ESTABLISHED:** `ApplicationExitInfo.REASON_PACKAGE_UPDATED` identifies process termination due to package update on API 34+.

Source:
- https://developer.android.com/reference/android/app/ApplicationExitInfo

**INFERENCE:** Provider compatibility must be versioned at the semantic capability layer.

```text
Capability ID
    + capability version
    + provider package
    + provider version
```

A workflow must not assume that the provider's implementation remains byte-for-byte stable across app updates.

## 12. Package replacement can invalidate live runtime handles

**ESTABLISHED:** Package updates can terminate the old process. Runtime handles such as Binder proxies, file descriptors, coroutine jobs, and component instances belong to the old runtime and cannot be assumed valid after replacement.

**INFERENCE:** Durable workflow state must never contain live OS handles as the recovery primitive.

Persist references/configuration, then reacquire runtime handles after restart/update.

## 13. User stop, system kill, and crash are different

`ApplicationExitInfo` distinguishes, among others:

```text
LOW_MEMORY
CRASH
CRASH_NATIVE
ANR
DEPENDENCY_DIED
EXCESSIVE_RESOURCE_USAGE
USER_REQUESTED
PACKAGE_UPDATED
PERMISSION_CHANGE
MEMORY_LIMITER
```

Source:
- https://developer.android.com/reference/android/app/ApplicationExitInfo

**INFERENCE:** The workflow state machine should not flatten all of these into `FAILED`.

Recommended high-level distinction:

```text
FAILED
STOPPED_BY_USER
PREEMPTED
PROCESS_LOST
PROVIDER_LOST
PACKAGE_UPDATED
PERMISSION_CHANGED
UNKNOWN_OUTCOME
```

Then a recovery policy decides whether to retry, resume, ask the user, reconcile, or terminate.

## 14. App availability is not permanent

A provider app can be:

```text
installed
available
permissioned
exported
stopped
updated
restricted
uninstalled
```

**INFERENCE:** Capability resolution must occur at execution boundaries, not only at initial workflow planning.

A cached provider choice is a hint, not durable truth.

## 15. Cross-app workflow state model

Recommended semantic model:

```text
PLANNED
  ↓
RESOLVING_PROVIDER
  ↓
PROVIDER_SELECTED
  ↓
AUTHORIZED
  ↓
INPUT_READY
  ↓
DISPATCHED
  ↓
EXECUTING
  ↓
CHECKPOINTED ────────────────┐
  ↓                           │
RESULT_AVAILABLE              │
  ↓                           │
VERIFIED                      │
  ↓                           │
COMMITTED                     │
                              │
PROCESS_LOST ────────────────┘
PROVIDER_LOST ───────────────┘
UNKNOWN_OUTCOME ─────────────┘
USER_STOPPED
PACKAGE_UPDATED
PERMISSION_CHANGED
FAILED
```

Important: these are **domain states**, not a mirror of WorkManager/Service/Activity states.

## 16. Recovery algorithm — current proposal

For every durable operation:

```text
1. Load operation_id from local durable state.
2. Inspect last semantic checkpoint.
3. Resolve the provider again.
4. Re-check permissions/capability availability.
5. Re-resolve artifact references.
6. Verify artifact version/integrity where required.
7. Determine whether previous execution outcome is known.
8. If UNKNOWN, reconcile by operation_id.
9. Resume from the durable checkpoint when safe.
10. Only then schedule a new execution attempt.
```

The key rule:

```text
Never retry a side-effecting cross-app operation merely because
its transport/process disappeared.
```

## 17. Control plane / data plane

The workflow should separate:

```text
CONTROL PLANE
---------------
operation_id
capability
provider identity
policy
arguments
ArtifactRef
status

DATA PLANE
-----------
Content URI
ParcelFileDescriptor
FileDescriptor
SharedMemory
BlobStore
file/stream
```

Large payloads should not be inserted into Binder/Intent messages when an artifact reference or stream is available.

## 18. ArtifactRef

Proposed domain primitive:

```text
ArtifactRef
    artifact_id
    version
    media_type
    size (optional)
    integrity (optional/required by policy)
    owner
    locator
    access_mode
    durability_class
    provenance
```

`locator` is replaceable. It may be a Content URI, file/blob reference, or another platform-specific transport reference.

The invariant is the semantic identity:

```text
artifact_id + version
```

not the current Android URI or file descriptor.

## 19. Durability classes

Not every artifact needs the same persistence guarantee.

Suggested classes:

```text
EPHEMERAL
    process/session only

RECONSTRUCTABLE
    can be regenerated from canonical state

PERSISTENT
    must survive process death/reboot

CRITICAL
    must have canonical durable representation and integrity evidence
```

BlobStore, caches, search indexes, and temporary streams should not automatically be promoted to CRITICAL.

## 20. Current design rule set

1. **Operation identity is durable; execution handles are not.**
2. **Transport is not truth.**
3. **Artifact identity is not its locator.**
4. **Force-stop is an authority boundary, not an ordinary retry.**
5. **System kill, user stop, crash, and package update are different failure classes.**
6. **Provider selection must be revalidated at execution/recovery boundaries.**
7. **Large data moves through references/streams/shared data primitives, not Binder payloads.**
8. **WorkManager schedules execution; Room/SQLite records semantic durable state.**
9. **A platform-managed shared blob is not automatically canonical application truth.**
10. **Every cross-app side effect needs an operation identity that survives the transport.**
11. **Recovery must reconcile UNKNOWN outcomes before retrying side effects.**
12. **User-granted external data access is a capability, not ownership.**

## 21. Open experiments

The following must be tested on real devices/API levels before becoming hard guarantees:

```text
E1: process death during Binder request + provider side effect
E2: URI grant across process death
E3: URI grant across reboot with and without persistable permission
E4: WorkManager + Room checkpoint recovery after reboot
E5: provider app update while a workflow is suspended
E6: provider uninstall/reinstall and capability re-resolution
E7: force-stop during an active cross-app workflow
E8: BlobStore lease expiry and workflow recovery
E9: same-signature provider handoff/version mismatch
E10: recovery when provider package is temporarily unavailable
```

## 22. Evidence discipline

A claim about Android behavior should be stored with:

```text
claim
source URL
API/Android version
observed/tested state
limits
failure mode
next discriminating test
```

Primary source preference:

1. Android Developers API/reference docs
2. AOSP documentation/source
3. official AndroidX/Kotlin docs
4. device experiments
5. secondary sources only for discovery

## 23. Sources

Primary references used for this audit:

- https://developer.android.com/reference/androidx/work/package-summary
- https://developer.android.com/reference/kotlin/androidx/work/WorkInfo
- https://developer.android.com/reference/android/app/ApplicationExitInfo
- https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping
- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/reference/kotlin/android/content/Intent
- https://developer.android.com/reference/android/app/appfunctions/AppFunctionUriGrant
- https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs
- https://developer.android.com/reference/kotlin/android/app/blob/BlobStoreManager.html
- https://developer.android.com/google/play/app-updates
- https://developer.android.com/reference/android/os/IBinder

## 24. Status

This document is a **research/architecture record**, not an implementation mandate.

No provider, transport, or Android API should be promoted to a hard dependency until the relevant open experiments and compatibility matrix are completed.
