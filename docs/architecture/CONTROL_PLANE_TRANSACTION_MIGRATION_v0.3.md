# Control-Plane Transaction Migration v0.3

Status: Implementation preparation baseline — reviewed, not approved
Date: 2026-09-06

## 1. Goal

Evolve the current split `ApprovalStore` + `RuntimeStore` design into one explicit durable control-plane transaction boundary without silently changing external-effect semantics.

## 2. Preserve before replacing

The migration must preserve:

```text
existing Run journal records
existing approval records
existing effect identity format
existing UNKNOWN reconciliation semantics
existing public Action/Capability semantics
```

Historical records must remain readable unless a deliberate format version migration is introduced and tested.

## 3. Introduce an explicit protocol facade

Target interface:

```kotlin
interface ControlPlaneStore {
    fun createApproval(request: ActivationRequest, action: ActionDefinition, plan: ActionPlan): ApprovalAdmission
    fun resolveApproved(...): ExecutionAdmission
    fun resolveDenied(...): ResolutionResult
    fun commitTerminal(run: Run, effects: List<CapabilityInvocation>): CommitResult
    fun recover(): RecoveryReport
}
```

The exact Kotlin signatures may change. The important semantic property is that callers no longer coordinate ApprovalStore and RuntimeStore independently for the approval-to-execution boundary.

A facade/coordinator by itself is **not** a transaction. It only becomes a durable transaction boundary when its underlying persistence primitive can commit the required state atomically or provides an equivalently proven CAS/commit protocol.

## 4. Two-stage migration

### Stage A — semantic coordination

Keep existing journal storage and introduce one coordinator that owns the ordering, validation, and recovery protocol.

Stage A proves:

```text
one orchestration authority
explicit state transitions
failure classification
protocol-level tests
```

Stage A does **not** prove:

```text
cross-file atomicity
crash atomicity
cross-process CAS
```

Those remain provisional until Stage B.

### Stage B — durable transaction backend

Replace or consolidate the persistence implementation with a storage primitive that provides atomic compare-and-set or equivalent transactional behavior across Run, approval, execution claim, and effect-group reservation.

SQLite is a leading Android candidate because the required properties map naturally to transactional storage, but it remains an implementation choice rather than part of the semantic contract.

## 5. Durable state vocabulary

Approval lifecycle should distinguish the authorization decision from execution binding. An equivalent model may use events rather than enum values:

```text
PENDING
APPROVED_UNBOUND
APPROVED_BOUND
DENIED
```

Execution admission has a separate identity:

```text
executionClaimId
runId
approvalId
ownerId
claimedAt
status
```

The identities must not be conflated.

## 6. Required journal versioning

Add a record/version discriminator before introducing new transaction events.

Example:

```text
v1 existing records
v2 transaction events
```

Replay must reject malformed records safely and ignore unsupported future record versions without fabricating valid state.

Fault tests must distinguish:

```text
prepared
committed
visible after reopen
```

An exception does not by itself prove that a durable write did not occur.

## 7. Compatibility rules

### Approval

Old `PENDING` approvals remain resolvable only through the new coordinator after their context is revalidated.

A durable approval decision without execution binding remains recoverable rather than being treated as completed execution.

### Effects

Existing `RESERVED`, `COMPLETED`, `UNKNOWN`, and `CONFIRMED_NOT_EXECUTED` records retain meaning.

### Runs

Existing terminal Runs remain terminal. Migration must never reopen them merely because related approval/effect records are incomplete.

## 8. Execution claim and recovery

A claim is an execution ownership record, not proof that replay is safe.

Recovery must classify according to:

```text
claim state
reservation state
effect idempotency semantics
effect class
external uncertainty
```

Required recovery dispositions:

```text
WAITING_APPROVAL
SAFE_TO_START
SAFE_TO_RESUME
EFFECTS_UNKNOWN
TERMINAL
MANUAL_RECONCILIATION_REQUIRED
```

`SAFE_TO_START` is allowed only when no committed effect exists or the effect's replay semantics explicitly permit another execution admission.

`SAFE_TO_RESUME` means the existing execution owner can continue without re-admitting already-reserved effects.

An interrupted effect with uncertain external completion must remain `UNKNOWN` until reconciled.

## 9. Effect group identity

For a multi-invocation plan, introduce a deterministic group identity for the particular execution attempt:

```text
reservationGroupId = hash(runId + canonical planned invocation set)
```

The group identity is control-plane metadata. Individual `effectId` values remain the stable replay identities.

Atomic group reservation means:

```text
all intended effects reserved
```

or:

```text
no committed reservation group
```

It does not imply atomic external execution.

## 10. Terminal transition enforcement

Terminality must be enforced in the durable write path.

Forbidden examples include:

```text
SUCCEEDED -> RUNNING
FAILED -> WAITING_APPROVAL
CANCELLED -> SUCCEEDED
```

The persistence operation should return an explicit transition result rather than silently accepting stale writes.

## 11. Failure semantics during migration

Until Stage B exists:

```text
semantic transaction protocol = REQUIRED
physical atomicity             = PROVISIONAL
cross-process correctness      = OPEN
```

No documentation may describe Stage A as crash-atomic across separate journal files.

## 12. Test-first order

Implement tests in this order:

1. approval single-winner;
2. execution claim single-winner;
3. group reservation all-or-none;
4. stale terminal write rejection;
5. recovery classification;
6. injected crash boundaries;
7. malformed/torn journal handling;
8. repeated recovery idempotence;
9. separate-instance and process-visible concurrency.

Every recovery test must reopen durable state and assert both local result and reconstructed state.

## 13. Exit criteria

Stage A is complete when all protocol tests are deterministic and the runtime has one coordination authority, while atomicity claims remain explicitly provisional.

Stage B is complete only when the durable backend demonstrates:

```text
CAS/transaction semantics
cross-instance correctness
cross-process behavior where applicable
crash recovery
atomic group reservation
terminal immutability
```

Android process-death evidence remains a separate L5 gate.
