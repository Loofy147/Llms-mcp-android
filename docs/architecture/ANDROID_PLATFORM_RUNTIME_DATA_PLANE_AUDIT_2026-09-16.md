# Android Platform Runtime & Cross-App Data Plane Audit — 2026-09-16

Status: **RESEARCH RECORD / ARCHITECTURE INPUT**

This document records the current research boundary around Android as a machine/runtime substrate for `Llms-mcp-android`. It is intentionally separated from implementation commitments. Claims use the project's evidence discipline:

- **ESTABLISHED** — supported by primary Android/AOSP documentation.
- **INFERENCE** — architectural consequence derived from established behavior.
- **HYPOTHESIS** — promising design idea requiring validation.
- **UNKNOWN** — not established or not safely inferable.
- **CONTRADICTED** — a prior assumption rejected by stronger evidence.

## 1. Scope

This audit expands the architecture review below the AndroidX/library layer and above the physical machine:

```text
Physical machine
  -> boot / trust
  -> kernel
  -> init / native services
  -> Zygote / ART
  -> system_server / system services
  -> Binder / IPC fabric
  -> package / permission / resolution model
  -> app UID / sandbox / SELinux
  -> app process + components
  -> lifecycle / state restoration
  -> AndroidX / Kotlin abstractions
  -> our domain
```

It also audits cross-app data movement:

```text
Control plane:
  Intent / Bundle / Binder / AppFunction / capability metadata

Data plane:
  Content URI / ContentProvider / FD / stream / SharedMemory / BlobStore / files

Durable truth:
  Room / SQLite / durable artifact metadata
```

## 2. Core corrected model

Android is not a linear stack where every item is a one-time stage. It is a set of interacting ownership domains.

```text
                         PHYSICAL MACHINE
                                |
                 +--------------+--------------+
                 |                             |
             kernel/hardware              power/thermal/I/O
                 |                             |
                 +--------------+--------------+
                                |
                         PLATFORM CONTROL
                                |
            +-------------------+-------------------+
            |                   |                   |
         Binder            system services      Package/Policy
            |                   |                   |
            +-------------------+-------------------+
                                |
                         APP EXECUTION
                                |
            +-------------------+-------------------+
            |                   |                   |
         process            components             ART
            |                   |                   |
            +-------------------+-------------------+
                                |
                      AndroidX / Kotlin
                                |
                           OUR DOMAIN
```

Important correction: **Binder is not a stage after `system_server`; it is an IPC substrate connecting applications, `system_server`, native services, and other system components.** PackageManagerService, ActivityManagerService, permission-related services, etc. are system services hosted in the system-server/control-plane architecture rather than a separate sequential layer.

## 3. Runtime identity model

### 3.1 Application identity

A logical installed application is more than an APK filename:

```text
application/package identity
+ signing identity
+ UID / sandbox identity
+ declared components/capabilities
+ permissions
+ installed artifacts/splits
```

### 3.2 Runtime identity

An execution instance is different:

```text
process
+ PID
+ threads
+ ART state
+ component instances
+ lifecycle
```

Therefore:

```text
application identity != process identity
process lifetime != operation lifetime
component lifetime != process lifetime
```

**ESTABLISHED:** Android may kill application processes under memory pressure and recreate them later when a component is needed. `Activity` destruction is not a reliable durable-finalization event. Android explicitly documents process importance/lifecycle in those terms.

**ARCHITECTURAL INFERENCE:** durable operation state must not be owned by an Activity, Service object, Coroutine Job, Worker instance, or process-local singleton.

## 4. Boot/runtime chain

The more accurate causal chain is:

```text
bootloader / verified boot
        -> kernel
        -> init
        -> Zygote / native services
        -> system_server + system services
        -> app process specialization
        -> ART executes DEX
```

### 4.1 Bootloader / trust

Android Verified Boot establishes a chain of trust from a hardware root of trust through boot stages before normal Android execution.

**ESTABLISHED**

Source:
- https://source.android.com/docs/core/architecture/bootloader

### 4.2 init

`init` performs early userspace initialization and starts/manages core services.

**ESTABLISHED**

Sources:
- https://source.android.com/docs/core/perf/boot-times

### 4.3 Zygote

Zygote initializes common runtime state and is used to create/specialize Android application processes. USAP mechanisms can further optimize application process startup on supported configurations.

**ESTABLISHED**

Source:
- https://source.android.com/docs/core/runtime/zygote

### 4.4 system_server / system services

`system_server` hosts many core framework services and becomes a central control plane for package, activity/process, permission, power, connectivity, and other system concerns.

**ESTABLISHED**

Sources:
- https://source.android.com/docs/core/architecture
- https://source.android.com/docs/core/perf/boot-times

## 5. Binder as the system's IPC fabric

Binder is the main Android IPC mechanism used by application processes, framework services, and lower-level system components.

```text
client process
   -> Binder proxy
   -> kernel/driver mediation
   -> server process/service
```

**ESTABLISHED**

Source:
- https://source.android.com/docs/core/architecture/ipc/binder-overview

### 5.1 Concurrency consequence

Cross-process calls are not equivalent to ordinary same-thread method calls. Services may receive concurrent calls on Binder threads and must implement the appropriate synchronization model.

**ESTABLISHED / ARCHITECTURAL INFERENCE**

### 5.2 Transport outcome is not semantic outcome

A Binder transaction failure, especially around oversized responses, does not always establish that the underlying operation did not execute.

**ESTABLISHED:** `TransactionTooLargeException` exists specifically because transaction size is bounded and because large IPC transactions can fail.

**ARCHITECTURAL INFERENCE:** every cross-process operation that matters to correctness needs a durable `operation_id` or equivalent semantic identity so the application can reconcile `UNKNOWN` outcomes instead of blindly retrying.

Source:
- https://developer.android.com/reference/android/os/TransactionTooLargeException

## 6. Package Manager, manifest, and resolution

The Android manifest is an OS-facing declaration of components, permissions, intent filters, and selected capabilities/metadata.

```text
manifest declarations
        -> Package Manager metadata
        -> resolution / launch / policy
```

**ESTABLISHED**

Sources:
- https://developer.android.com/guide/topics/manifest/manifest-intro
- https://developer.android.com/reference/android/content/pm/PackageManager

### 6.1 Intent resolution

Intent filters allow the system to discover components capable of handling an action/data/category combination. Intent resolution is therefore a native composition primitive.

**ESTABLISHED**

Source:
- https://developer.android.com/guide/components/intents-filters

### 6.2 Security correction

Discoverability does not imply authorization. `exported`, permissions, identity, and platform security policy remain separate concerns.

**ESTABLISHED / ARCHITECTURAL INFERENCE**

## 7. UID / sandbox / SELinux

Android application isolation is rooted in Linux UID/process separation, with permissions and SELinux adding further policy controls.

**ESTABLISHED**

Sources:
- https://source.android.com/docs/security/app-sandbox
- https://source.android.com/docs/core/architecture

Therefore:

```text
language runtime != primary security boundary
```

The security boundary is primarily platform/kernel/package/policy based.

## 8. Components are OS entry surfaces

Classic Android components:

```text
Activity
Service
BroadcastReceiver
ContentProvider
```

They should be understood as **OS entry surfaces**, not as durable owners of application truth.

**ESTABLISHED**

Source:
- https://developer.android.com/guide/components/fundamentals

### 8.1 Entry point lifetime

A short-lived OS callback should hand off work to an appropriate lifecycle/scheduling mechanism rather than assuming the callback's process will remain alive.

This applies especially to `BroadcastReceiver` callbacks.

**ESTABLISHED / ARCHITECTURAL INFERENCE**

## 9. ART / DEX / JIT / AOT

The runtime path is approximately:

```text
Kotlin/Java
   -> JVM bytecode
   -> D8/R8
   -> DEX
   -> ART verification/execution
   -> interpreter/JIT/AOT according to runtime state and profiles
```

Android 7 introduced the hybrid interpretation/JIT/AOT direction, while Android 14 introduced ART Service as a stronger system-managed AOT compilation path.

**ESTABLISHED**

Sources:
- https://source.android.com/docs/core/runtime
- https://source.android.com/docs/core/runtime/configure
- https://source.android.com/docs/core/runtime/zygote

### 9.1 Runtime version is not identical to API level

ART and related runtime components have evolved toward independently updatable modules. Therefore:

```text
Android API level != complete runtime implementation identity
```

**ESTABLISHED / ARCHITECTURAL INFERENCE**

Source:
- https://source.android.com/docs/core/runtime/configure/art-service

## 10. APK / AAB / splits / runtime artifacts

### 10.1 Artifact roles

```text
Source
  -> build artifacts
  -> AAB (publishing artifact, when used)
  -> generated APK set / APKs
  -> installed package/splits
  -> runtime DEX/native/resources
  -> ART-generated runtime artifacts/profiles
```

**ESTABLISHED:** AAB is a publishing format used to generate device-appropriate APKs; APKs are the installable runtime artifacts.

Sources:
- https://developer.android.com/guide/app-bundle/app-bundle-format
- https://developer.android.com/guide/components/fundamentals

### 10.2 Application is not a single file

Split APKs allow code/resources/features/configurations to be composed into one logical installed application.

**ESTABLISHED**

Source:
- https://developer.android.com/guide/app-bundle/app-bundle-format

### 10.3 Artifact lineage

**ARCHITECTURAL INFERENCE:** an application should be traceable as a lineage:

```text
source commit
 -> build run
 -> AAB/APK artifact
 -> signing identity
 -> installed package/version
 -> runtime profile/state
 -> execution evidence
```

This should eventually connect to the project's existing Artifact/Run/Evidence model.

## 11. Signing and update continuity

APK signing is part of package identity and update trust. Android signing schemes evolved across versions, with v2/v3/v4 adding stronger verification and, for v3, proof-of-rotation semantics.

**ESTABLISHED**

Sources:
- https://source.android.com/docs/security/features/apksigning
- https://source.android.com/docs/security/features/apksigning/v3

**ARCHITECTURAL INFERENCE:** app update should be considered a state transition, not merely file replacement:

```text
new artifact
+ signing continuity
+ package/version transition
+ schema/data migration
+ runtime adaptation
```

## 12. Lifecycle and state restoration

Android lifecycle state is transient; saved state is for recreation; durable domain state requires durable storage.

```text
View/UI state -> lifecycle/saved-state mechanisms
Durable domain state -> Room/SQLite/files/etc.
Execution state -> durable operation record when recovery is required
```

**ESTABLISHED / ARCHITECTURAL INFERENCE**

## 13. Data-plane model

### 13.1 Control plane

Suitable for:

```text
operation_id
capability name
small arguments
provider selection
policy
status
artifact references
```

Typical mechanisms:

```text
Intent
Bundle / Parcel
Binder
AppFunction
MCP adapter
```

### 13.2 Data plane

Suitable for:

```text
large payloads
binary artifacts
streams
shared buffers
persistent external objects
```

Typical mechanisms:

```text
Content URI
ContentProvider
ParcelFileDescriptor / FD
SharedMemory
pipe/socket
MediaStore
SAF
BlobStoreManager
files
```

### 13.3 Durable semantic plane

```text
Run / operation state
Artifact identity
provenance
checkpoint
workflow state
```

Typical mechanism in our architecture:

```text
Room / SQLite
```

## 14. Intent / Bundle / Parcel

Best used for control information and modest payloads. Binder transaction size is bounded and oversized payloads should be moved into references/streaming/storage mechanisms.

**ESTABLISHED**

Sources:
- https://developer.android.com/guide/components/activities/parcelables-and-bundles
- https://developer.android.com/reference/android/os/TransactionTooLargeException

## 15. Content URI / URI grants

A `content://` URI can act as a scoped reference to data owned by a provider, with read/write permissions granted at URI scope.

```text
owner
 + locator
 + access scope
```

**ESTABLISHED**

Sources:
- https://developer.android.com/privacy-and-security/security-best-practices
- https://developer.android.com/reference/android/content/ContentResolver

**ARCHITECTURAL INFERENCE:** for app-to-app artifact handoff, prefer reference + scoped access over copying payloads whenever possible.

## 16. SAF

Storage Access Framework provides a user-mediated access boundary to documents exposed by document providers. Persistable URI permissions can survive reboot where supported; however, the external document remains provider-owned and may disappear or move.

**ESTABLISHED**

Source:
- https://developer.android.com/training/data-storage/shared/documents-files

Therefore:

```text
SAF reference != locally owned durable artifact
```

## 17. MediaStore

MediaStore is a system-managed/shared-media model using content URIs, intended for user/shared media collections rather than generic app-private data.

**ESTABLISHED**

Source:
- https://developer.android.com/training/data-storage/shared/media

## 18. ContentProvider

ContentProvider is suitable when the producer owns a structured/queryable dataset or wants controlled file/data access rather than handing over raw storage.

**ESTABLISHED**

Sources:
- https://developer.android.com/guide/topics/providers/content-providers
- https://developer.android.com/privacy-and-security/security-tips

**ARCHITECTURAL INFERENCE:** provider ownership should remain with the provider; consuming applications should receive bounded access rather than direct database/storage ownership.

## 19. ContentObserver

ContentResolver/ContentProvider support observing changes to provider-backed resources so consumers can react to state changes instead of continuously polling.

**ESTABLISHED**

Source:
- https://developer.android.com/reference/android/content/ContentResolver

**ARCHITECTURAL INFERENCE:** event notification is not state itself:

```text
change signal != durable state
```

## 20. ParcelFileDescriptor / file descriptors

Large or streamed payloads should often cross the process boundary as a descriptor/reference rather than an in-memory byte array.

**ESTABLISHED**

Sources:
- https://developer.android.com/reference/android/os/ParcelFileDescriptor
- https://developer.android.com/training/data-storage/shared/documents-files

## 21. SharedMemory

`SharedMemory` provides shared mapped memory across processes. It is useful for high-volume in-memory data where copy/serialization overhead is materially important.

**ESTABLISHED:** shared memory mapping primitive exists.

**INFERENCE:** it can reduce serialization/copy overhead for specific topologies; this does **not** imply a guaranteed zero-copy end-to-end pipeline.

Source:
- https://developer.android.com/reference/android/os/SharedMemory

## 22. Pipes / sockets

Use streaming-oriented channels when the data is naturally continuous or when the consumer should process incrementally rather than materialize a complete payload.

**ARCHITECTURAL INFERENCE**

No custom message bus should be built when the problem is fundamentally a byte stream and an existing OS transport is sufficient.

## 23. BlobStoreManager

Android 11+ provides `BlobStoreManager` for system-managed sharing of large blobs between applications, with access rules and leases.

**ESTABLISHED**

Sources:
- https://developer.android.com/training/data-storage/shared/datasets
- https://developer.android.com/reference/android/app/blob/BlobStoreManager

Important correction:

```text
BlobStore != canonical business storage
```

BlobStore is lease/system-managed shared data. Leases/quotas and user/system lifecycle can affect availability. Therefore irreplaceable domain truth must have its own durable identity/state.

## 24. WorkManager and durable workflow

WorkManager owns scheduling mechanics such as constraints, unique work, retry/backoff, chaining, and stop semantics.

It should not be treated as the sole authoritative store for domain operation state when the application requires strong recovery semantics.

Recommended model:

```text
Room:
    operation_id
    state
    checkpoint
    attempt
    provenance

WorkManager:
    how/when the next execution attempt is scheduled
```

**ESTABLISHED / ARCHITECTURAL INFERENCE**

Source:
- https://developer.android.com/topic/architecture/data-layer/offline-first
- https://developer.android.com/develop/background-work/background-tasks

## 25. ArtifactRef — proposed domain primitive

The current research supports a domain-level reference object independent from Android transport details.

Proposed conceptual shape:

```text
ArtifactRef
    artifact_id
    version
    media_type
    size?
    integrity?
    owner
    locator
    access_mode
    durability_class
    provenance
```

Possible locator kinds:

```text
CONTENT_URI
FILE_DESCRIPTOR
SHARED_MEMORY
BLOB
FILE
DATABASE_OBJECT
NETWORK_REFERENCE
```

This is a **proposal**, not yet an implementation commitment.

### Why identity must be separate from locator

```text
artifact_id != URI
```

A locator can expire, move, or be replaced while the semantic artifact remains the same.

**ARCHITECTURAL INFERENCE**

## 26. Failure model for cross-app operations

We must distinguish:

```text
CONTROL_FAILURE
DATA_ACCESS_FAILURE
PROVIDER_FAILURE
OPERATION_FAILURE
ARTIFACT_FAILURE
UNKNOWN_OUTCOME
```

Example:

```text
provider executes operation
    -> provider dies before returning result
    -> caller sees Binder exception
```

Correct application state is not automatically `FAILED`.
It may be:

```text
UNKNOWN
```

Reconciliation should use `operation_id`, artifact identity, and provider state where possible.

**ARCHITECTURAL INFERENCE**

## 27. App Fabric hypothesis

The current evidence supports this architecture hypothesis:

```text
                         USER GOAL
                             |
                       ORCHESTRATOR
                             |
                       CONTROL PLANE
                             |
            +----------------+----------------+
            |                |                |
         Intent         AppFunction         Binder
            |                |                |
            +----------------+----------------+
                             |
                        ArtifactRef
                             |
                        DATA PLANE
                             |
          +------------------+------------------+
          |                  |                  |
       URI/Provider       FD/stream          Blob/shared
          |                  |                  |
       Provider A         Provider B         Provider C
          |                  |                  |
          +------------------+------------------+
                             |
                       Domain result
                             |
                            Room
```

The hypothesis is not that every workflow should span multiple applications. The boundary should exist only when it provides a concrete benefit such as:

```text
security isolation
independent replacement
independent ownership
reuse across apps
resource/lifecycle isolation
existing system capability
```

Otherwise, in-process composition is simpler and cheaper.

## 28. Core design principles extracted so far

1. **Process lifetime is not operation lifetime.**
2. **Application identity is not a process identity.**
3. **Declare capability/requirements when the platform can perform resolution.**
4. **Control plane should carry references, not bulk payloads.**
5. **Prefer references over copies.**
6. **Transport failure is not automatically operation failure.**
7. **Artifact identity is independent from locator.**
8. **Platform-owned transient state should be queried rather than mirrored as durable truth.**
9. **Persist historical platform facts only when they explain a meaningful decision/outcome.**
10. **Use platform mechanisms at the layer that owns the invariant.**
11. **Use Room/SQLite for semantic durable truth; use WorkManager for scheduling mechanics.**
12. **Use least-authority, scoped data access between apps.**
13. **Treat shared/derived data as rebuildable where possible.**
14. **Do not introduce a process/app boundary without a concrete invariant or ownership reason.**
15. **UNKNOWN is a valid operational state and should not be silently converted to failure.**

## 29. Implications for `Llms-mcp-android`

The project should not implement:

```text
custom bulk IPC bus
custom network-polling file exchange
custom cross-app filesystem sharing
custom durable background scheduler
custom process lifetime manager
custom search/storage truth for artifacts
```

before proving that platform/AndroidX primitives cannot satisfy the requirement.

The project should own:

```text
Capability contract
Policy/authorization semantics
Operation identity
Run state
Checkpoint/recovery semantics
Artifact identity/provenance
Evidence
Workflow semantics
Provider selection policy
```

## 30. Open research questions

The next audit should verify end-to-end durable behavior across:

```text
process death
force-stop
reboot
package update
provider update/removal
URI grant expiration/revocation
BlobStore lease expiration
WorkManager retry
artifact version transitions
cross-app capability version compatibility
```

The desired output is a **cross-app durable workflow contract**, not another transport abstraction.

## 31. Primary source set

Android/AOSP sources used throughout this audit:

- https://source.android.com/docs/core/architecture
- https://source.android.com/docs/core/architecture/ipc/binder-overview
- https://source.android.com/docs/core/runtime/zygote
- https://source.android.com/docs/core/runtime
- https://source.android.com/docs/core/runtime/configure
- https://source.android.com/docs/core/runtime/configure/art-service
- https://source.android.com/docs/security/app-sandbox
- https://source.android.com/docs/security/features/apksigning
- https://source.android.com/docs/security/features/apksigning/v3
- https://source.android.com/docs/core/architecture/bootloader
- https://developer.android.com/guide/components/fundamentals
- https://developer.android.com/guide/components/intents-filters
- https://developer.android.com/guide/topics/manifest/manifest-intro
- https://developer.android.com/reference/android/content/pm/PackageManager
- https://developer.android.com/guide/components/processes-and-threads
- https://developer.android.com/reference/android/os/TransactionTooLargeException
- https://developer.android.com/privacy-and-security/security-best-practices
- https://developer.android.com/privacy-and-security/security-tips
- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/training/data-storage/shared/media
- https://developer.android.com/training/data-storage/shared/datasets
- https://developer.android.com/reference/android/app/blob/BlobStoreManager
- https://developer.android.com/reference/android/os/SharedMemory
- https://developer.android.com/reference/android/os/ParcelFileDescriptor
- https://developer.android.com/reference/android/content/ContentResolver
- https://developer.android.com/topic/architecture/data-layer/offline-first
- https://developer.android.com/develop/background-work/background-tasks

## 32. Confidence and contamination policy

This document is a research record. A platform claim is not upgraded merely because an architecture proposal depends on it.

Where behavior is OEM-, API-level-, module-, driver-, or workload-dependent, the project should prefer runtime observation or a targeted experiment over assumption.

Implementation decisions should reference this document plus the relevant primary source and should not silently promote an `INFERENCE`, `HYPOTHESIS`, or `UNKNOWN` entry to `ESTABLISHED`.
