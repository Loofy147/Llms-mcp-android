# Control-Plane Transaction Migration v0.3

Status: Implementation preparation baseline
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
    fun resolveApproved(
        runId: String,
        approvalId: String,
        requesterIdentity: String,
        approverIdentity: String,
        fingerprint: String
    ): ExecutionAdmission
    fun resolveDenied(...): ResolutionResult
    fun commitTerminal(run: Run, effects: List<CapabilityInvocation>): CommitResult
    fun recover(): RecoveryReport
}
```

The exact Kotlin signatures may change. The important property is that callers no longer coordinate ApprovalStore and RuntimeStore independently for the approval-to-execution boundary.

## 4. Two-stage migration

### Stage A — semantic facade

Keep existing journal storage but add one coordinator that owns the ordering and single-winner decision in one object.

This stage proves runtime semantics and testability without requiring an immediate storage rewrite.

### Stage B — durable transaction backend

Replace the coordinator's implementation with a storage primitive that provides atomic compare-and-set or equivalent transactional behavior across Run, approval, and effect records.

SQLite is the leading candidate for Android, but it is an implementation choice rather than a semantic requirement.

## 5. Required journal versioning

Add a record/version discriminator before introducing new transaction events.

Example:

```text
v1 existing records
v2 transaction events
```

Replay must reject malformed records safely and ignore unsupported future record versions without fabricating valid state.

## 6. Compatibility rules

### Approval

Old `PENDING` approvals remain resolvable only through the new coordinator after their context is revalidated.

### Effects

Existing `RESERVED`, `COMPLETED`, `UNKNOWN`, and `CONFIRMED_NOT_EXECUTED` records retain meaning.

### Runs

Existing terminal Runs remain terminal. Migration must never reopen them merely because related approval/effect records are incomplete.

## 7. Execution claim

Do not add a public `SUCCEEDED`/`RUNNING` shortcut for an approval decision.

The new internal execution claim must have a unique identity, for example:

```text
executionClaimId
runId
approvalId
claimedAt
ownerId
status
```

A second resolver must be unable to acquire the same claim.

## 8. Effect group identity

For a multi-invocation plan, introduce a deterministic group identity:

```text
reservationGroupId = hash(runId + canonical planned invocation set)
```

The group identity is control-plane metadata. Individual effect IDs remain the stable replay keys used for external idempotency where applicable.

## 9. Failure semantics during migration

Until Stage B exists:

```text
semantic transaction protocol = REQUIRED
physical atomicity             = PROVISIONAL
```

No documentation may describe Stage A as crash-atomic across separate journal files.

## 10. Test-first order

Implement tests in this order:

1. approval single-winner;
2. execution claim single-winner;
3. group reservation all-or-none;
4. stale terminal write rejection;
5. recovery classification;
6. injected crash boundaries;
7. malformed/torn journal handling;
8. repeated recovery idempotence.

Only after these tests exist should the storage implementation be changed.

## 11. Exit criteria

Stage A is complete when all protocol tests are deterministic and the runtime has one coordination authority.

Stage B is complete when the durable backend demonstrates:

```text
CAS/transaction semantics
cross-instance correctness
crash recovery
atomic group reservation
terminal immutability
```

Android process-death evidence remains a separate L5 gate.
