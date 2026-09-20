# Conversation Findings Addendum — 2026-09-20

Status: Active durable addendum / revalidation record
Branch: refactor/runtime-semantic-stabilization-v0.3
Scope: Findings and conclusions produced during the September 2026 architecture/Q1 review, external research, and product-positioning review.

## 1. Evidence discipline

This addendum records conclusions without promoting them beyond their evidence.

Status vocabulary:
- ESTABLISHED — directly supported by repository state or executed CI/tests.
- RESEARCH-BACKED — supported by an external primary/authoritative source; not proof of this project.
- HYPOTHESIS — design or product proposition requiring experiment/market validation.
- OPEN — intentionally unresolved.
- CONFLICT — an existing contract and implementation are inconsistent.

No statement below should be promoted merely because it appears repeatedly in documentation.

## 2. v0.3 semantic stabilization state

### 2.1 E-12 / G0

ESTABLISHED / CLOSED.

Direct Action.plan failures were previously able to escape the normal Run failure boundary in the ALLOW path. The implementation was changed to normalize planning failures into persisted RunStatus.FAILED Runs, with a regression test.

CI run 167 for the v0.3 branch completed successfully for:
- unit tests;
- debug APK build;
- APK upload.

This closes the specific planning-boundary experiment only. It does not close the remaining semantic gates.

### 2.2 Remaining gates

OPEN.

The remaining high-priority gates are:
- G1 approval/execution durability;
- G2 cross-instance/process concurrency;
- G3 immutable semantic provenance;
- G4 concrete invocation authorization;
- G5 independent verification;
- G6 resource budgets and cancellation.

Capability breadth remains secondary to these semantic/runtime gates.

## 3. Architectural findings reconfirmed

### A-01 — Planning failures

CLOSED for the audited direct-ALLOW case.

All activation paths should eventually share one failure-normalization boundary.

### A-02 — Approval is not execution

OPEN.

Consuming approval and then starting execution through separate durable stores is not one transaction. Moving saveRun(RUNNING) earlier does not by itself establish atomicity.

Required distinction:

    authorization decision
      !=
    execution admission
      !=
    external execution

### A-03 — Local locks are not process-safe transactions

OPEN.

Instance-local synchronized locks are useful for single-instance serialization but do not establish cross-instance/process compare-and-set semantics.

### A-04 — Multi-effect reservation must be a group operation

OPEN.

A logical Action plan containing multiple effects requires an all-or-none durable reservation result. A prefix of effect records must never be interpreted as a committed reservation group.

### A-05 — Version is not immutable semantic identity

OPEN / CONFLICT.

Persisted historical Runs currently reconstruct the Action from the current catalog and copy the stored version. A version number alone does not prove that the exact executable Action definition is preserved.

Required future identity:

    action_id
    action_version
    action_definition_hash OR immutable snapshot identity

The same principle eventually applies to Capability and Verifier definitions.

### A-06/A-07/A-08/A-09 — Authorization must become concrete

OPEN.

Activation source is provenance, not automatically authorization. Caller identity is currently asserted rather than strongly authenticated. Action-level declaration is insufficient for resource-sensitive invocation authorization, and Set<String> scope is not yet a structured resource constraint model.

### A-10/A-11 — Verification must become independent

CONFLICT / OPEN.

CapabilityExecution.postcondition and ActionExecution.postcondition currently influence terminal success. This is a self-attestation path.

Target:

    CapabilityExecutor
      -> Observations
      -> Independent Verifier
      -> VerificationResult
      -> Evidence
      -> terminal Run

### A-13/A-14 — Provider/tool loops need budgets and cancellation

OPEN.

The model/tool loop requires explicit iteration, call-count, wall-time, history/input, and output limits. Cancellation must reach the underlying transport and tool execution.

### A-18/A-19 — MCP identity/configuration

OPEN.

MCP server identity should not depend solely on mutable display names. Configuration validation must be structurally stronger than an https:// prefix check.

### A-20/A-21/A-22 — Secret handling is not equivalent to minimization

OPEN.

Keystore-backed credential storage is useful protection, but secret leakage into ordinary application state and unresolved Android backup semantics remain separate concerns.

## 4. Q1 transaction design — revalidated

### 4.1 Critical correction

ESTABLISHED DESIGN CONCLUSION.

A coordinator placed above two independent journal files is not a physical transaction boundary.

Therefore:

    Coordinator
      !=
    atomic durable transaction

A coordinator remains useful as an orchestration/semantic interface, but physical atomicity must come from one authoritative transactional persistence boundary or an equivalently proven CAS protocol.

### 4.2 Authoritative control-plane state

Target logical state:

    Run
    Approval
    ExecutionClaim
    EffectReservationGroup
    Effect
    RecoveryDisposition

The transaction should cover the local admission decision:

    approve
    + bind plan
    + claim execution
    + reserve effect group
    = durable execution admission

It must not claim to include the external side effect itself.

### 4.3 External-effect boundary

ESTABLISHED DESIGN LIMIT.

    local transaction atomicity
        !=
    external exactly-once execution

An effect may reach:

    COMPLETED
    NOT_EXECUTED
    UNKNOWN

and UNKNOWN must remain explicitly reconcilable rather than being upgraded to success during recovery.

### 4.4 Execution claim

The execution claim is an internal control-plane identity, not a public success state.

Required binding includes:

    runId
    approvalId
    requester identity
    approver identity
    approval fingerprint
    action semantic identity
    planned invocation-set identity
    claim identity/owner

At most one resolver may acquire the execution claim for a Run.

### 4.5 Terminality

Terminal states:

    SUCCEEDED
    FAILED
    DENIED
    CANCELLED

must be write-once semantically. The storage layer must reject or deterministically ignore stale attempts to regress or overwrite a terminal Run.

### 4.6 Recovery

Recovery must distinguish at least:

    SAFE_TO_START
    EXECUTION_IN_PROGRESS
    EFFECTS_UNKNOWN
    TERMINAL
    MANUAL_RECONCILIATION_REQUIRED

Recovery may not infer external success merely from approval, a RUNNING state, or the existence of an execution claim.

## 5. Backend conclusion after research

### 5.1 SQLite/Room

RESEARCH-BACKED / STRONG CANDIDATE.

SQLite provides atomic commit semantics for transactions and documents crash testing that repeatedly simulates power-loss conditions and verifies that a transaction is either fully applied or not applied. SQLite WAL provides a different concurrency model with serialized writers; durability settings still matter.

Android Room provides a higher-level persistence layer with transaction support and is therefore a practical candidate for the authoritative control-plane database.

Primary references:
- https://www.sqlite.org/atomiccommit.html
- https://sqlite.org/pragma.html
- https://developer.android.com/training/data-storage/room

### 5.2 Journal

DESIGN CONCLUSION / OPEN IMPLEMENTATION CHOICE.

The existing append-only journal should be treated primarily as:
- legacy compatibility reader;
- migration source;
- test fixture;
- or temporary transitional backend.

It should not be treated as the long-term authoritative transaction boundary unless its multi-record atomicity and cross-process CAS semantics are independently proven.

### 5.3 WorkManager

DESIGN CONCLUSION.

WorkManager can be considered a scheduler/recovery launcher for persistent Android background work. It should not become the source of truth for authorization, approval, effect identity, or evidence.

Primary reference:
https://developer.android.com/reference/androidx/work/WorkManager

### 5.4 Backend decision status

OPEN.

SQLite/Room is the strongest current candidate, but the repository should not record it as final until the proposed schema and invariant matrix are tested against actual transaction/recovery scenarios.

## 6. Q1 testing conclusions

The required test class is not merely happy-path unit testing.

The matrix must establish:

    single-winner approval
    single execution admission
    plan/fingerprint binding
    all-or-none effect-group reservation
    terminal immutability
    recovery idempotence
    malformed/torn-record handling
    concurrent writer behavior
    crash-boundary behavior

The strongest evidence requires reopening durable state after each injected failure. JVM fault injection remains below Android process-death evidence.

SQLite's own crash-testing methodology is a useful model: vary the failure point, reopen, and test the post-crash state rather than treating an exception as equivalent to process death.

## 7. Product/architecture conclusion

### 7.1 Worth pursuing

HYPOTHESIS -> RESEARCH-SUPPORTED STRATEGIC DIRECTION, NOT PMF PROOF.

The project remains worth pursuing as a user-owned Agent Runtime / Control Plane.

The primary value proposition is not:
- another LLM;
- another generic chatbot;
- another unrestricted coding agent.

It is:

    Model-agnostic reasoning
    +
    user-owned execution authority
    +
    capability-scoped effects
    +
    policy
    +
    approval
    +
    durable recovery
    +
    verification
    +
    evidence

### 7.2 Personal use

STRONG INTERNAL PRODUCT HYPOTHESIS.

The first practical proving ground remains personal developer tooling because repository state, diffs, hashes, tests, builds, and artifacts provide comparatively strong observability and independent verification.

The intended early capability family is:

    dev.workspace.inspect
    dev.file.read
    dev.file.hash
    dev.git.status
    dev.git.diff

This does not commit the project to becoming a general coding-agent product.

For personal mobile use, Android-native capabilities can later be admitted through the same runtime boundary rather than by granting the model broad direct device authority.

### 7.3 Generic consumer assistant

LOWER-PRIORITY / HYPOTHESIS.

Competing directly as a universal Android assistant is strategically weak because platform and assistant vendors are already integrating agentic execution deeply into Android.

Google's current Android documentation describes AppFunctions as a mechanism for exposing app capabilities to on-device agents, with system-level discovery and execution. AppFunctions remains experimental and is evolving.

Primary references:
- https://developer.android.com/ai/intelligence-system
- https://developer.android.com/ai/appfunctions
- https://developer.android.com/blog/posts/build-intelligent-android-apps-integrate-into-android-s-intelligence-system-using-app-functions

Therefore the project should not optimize for being "another system assistant" unless later evidence creates a specific underserved segment.

### 7.4 Commercial direction

HYPOTHESIS / UNVALIDATED.

The commercially interesting positioning is:

    Agent Control Plane

rather than "AI assistant."

Potential forms:
- model/provider-neutral runtime;
- embedded execution-control SDK;
- agent authorization/approval layer;
- durable action/recovery layer;
- evidence/provenance layer.

This is a market hypothesis, not a claim of product-market fit.

NIST's 2026 work provides independent evidence that identity, authentication, authorization, auditing, non-repudiation, and least-privilege questions for software/AI agents are active technical problems. It does not prove demand for this specific product.

Primary references:
- https://www.nist.gov/news-events/news/2026/02/new-concept-paper-identity-and-authority-software-agents
- https://csrc.nist.gov/pubs/other/2026/02/05/accelerating-the-adoption-of-software-and-ai-agent/ipd
- https://www.nccoe.nist.gov/projects/software-and-ai-agent-identity-and-authorization

## 8. Strategic non-goals reaffirmed

Do not expand the core merely because adjacent systems exist.

Explicitly deferred unless measured pressure appears:

    general workflow engine
    capability graph
    multi-agent swarm
    plugin marketplace
    unrestricted device automation
    unrestricted background execution
    generic deployment authority
    generic consumer assistant positioning

## 9. Updated next sequence

    1. Finalize Q1 schema/invariant review.
    2. Select the authoritative durable backend only after protocol review.
    3. Implement transactional control-plane persistence.
    4. Prove concurrency + crash recovery + terminality.
    5. Re-run the complete Q1 matrix.
    6. Q2 immutable semantic provenance.
    7. Q3 concrete authorization.
    8. Q4 independent verification.
    9. Q5 resource/cancellation controls.
    10. First read-only developer capability family.
    11. 20-case capability benchmark across >=3 repository/project states.
    12. First narrowly scoped write capability.
    13. Product/market validation of Agent Control Plane positioning.

## 10. Final status

    Architecture direction                 WORTH CONTINUING
    v0.3 semantic stabilization            ACTIVE
    E-12 planning boundary                 CLOSED
    Q1 transaction design                  PROPOSED / NOT CLOSED
    SQLite/Room backend                    STRONG CANDIDATE / OPEN
    External exactly-once                  NOT CLAIMED
    Independent verification               OPEN
    Concrete authorization                 OPEN
    Developer proving ground               DEFERRED UNTIL SEMANTIC CLOSURE
    Commercial PMF                         UNKNOWN
    Agent Control Plane positioning        HYPOTHESIS WORTH VALIDATING
    Universal consumer assistant           NOT THE PRIMARY BET
