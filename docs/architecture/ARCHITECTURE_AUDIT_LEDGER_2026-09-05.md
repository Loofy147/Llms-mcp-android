# Architecture Audit Ledger — 2026-09-05

Status: Active audit ledger / design-control baseline
Date: 2026-09-05
Scope: Whole application architecture, runtime correctness, authorization, persistence, security/privacy, provider boundary, Android lifecycle, capability direction, testing, and build/reproducibility.

## 1. Purpose

This document consolidates the findings from the 2026-09-05 architecture reviews into one durable record. It is deliberately stricter than a design proposal: every statement is classified so that hypotheses, implementation observations, external research signals, and verified behavior are not silently mixed.

Classification:

- **ESTABLISHED** — directly supported by current implementation or repository evidence.
- **PROVISIONALLY VERIFIED** — supported by deterministic/unit/CI evidence but not yet closed at Android, concurrency, or external-effect level.
- **RESEARCH-BACKED** — supported by external 2026 research or product/engineering signals; not proof of this project's behavior.
- **HYPOTHESIS** — plausible design proposition requiring measurement.
- **OPEN** — intentionally unresolved gate.
- **CONFLICT** — an invariant or semantic contract is not consistently enforced.

## 2. Consolidated architecture findings

### 2.1 Control-plane convergence

**ESTABLISHED.** The application has converged on one local execution authority: `AgentRuntime`. Direct/local and model-mediated native tool calls both enter the same Action/Capability path; `ToolRegistry` is descriptive rather than an execution authority; `CapabilityExecutor` is the runtime-owned local effect boundary. Provider transport remains behind `ModelProvider`/Anthropic adapters.

### 2.2 Deterministic local capability direction

**ESTABLISHED.** The built-in native capability set is deliberately small: time and bounded arithmetic. The calculator bounds input length, rejects unsupported characters, parses without a scripting/eval facility, and rejects non-finite results.

**RESEARCH-BACKED.** The capability research recommends a developer-centric, read-heavy proving ground because repository state, tests, diffs, hashes, build artifacts, and configuration outputs are unusually observable and independently verifiable. This is a proving-ground decision, not a commitment to build a general coding agent.

### 2.3 Verification-first prioritization

**ESTABLISHED.** Decision D-16 is active: capability adoption is prioritized by independent verifiability and observability before breadth of autonomy.

**HYPOTHESIS.** The proposed heuristic is:

```text
priority ∝ observability × independent verifiability × reversibility × reuse
          ---------------------------------------------------------------
          ambiguity × consequence × external uncertainty
```

This is a prioritization heuristic, not a scientific law; it must be validated with measured runs.

## 3. Research findings and proper status

### 3.1 APEX-Agents

**RESEARCH-BACKED / EXTERNAL BENCHMARK.** The reviewed 2026 material reports 24.0% Pass@1 for the best evaluated agent on its long-horizon, cross-application professional task benchmark. This is evidence about that benchmark, not a universal agent success rate.

### 3.2 MobileWorld

**RESEARCH-BACKED / EXTERNAL BENCHMARK.** MobileWorld reports 20.9% end-to-end success for its best evaluated model on a substantially harder mobile benchmark. It is not directly comparable with APEX-Agents because the task distributions and evaluation settings differ.

### 3.3 Compounding arithmetic

**ESTABLISHED MATHEMATICS / EXPLANATORY MODEL.** `0.85^10 ≈ 19.7%` and `0.95^10 ≈ 59.9%` are correct arithmetic under an independence assumption. The calculation is a teaching model for compounding failure probability, not a measured universal property of agent systems.

### 3.4 PersonalAgents

**RESEARCH-BACKED PRODUCT SIGNAL.** PersonalAgents provides separate email/calendar/web agents or functions. This supports bounded specialization as a useful product pattern, but does not prove those domains are uniquely optimal.

### 3.5 Gemini Spark

**RESEARCH-BACKED PRODUCT SIGNAL.** The reviewed product material shows connected personal applications such as Calendar, Gmail, Drive, Docs, and Tasks and illustrates bounded multi-application compositions. This supports composition as a product pattern, not the need for a general autonomous agent.

### 3.6 FinAgent

**RESEARCH-BACKED / RESEARCH SIGNAL.** FinAgent provides a composition example spanning finance and nutrition. It remains simulation/research evidence and is not evidence that a general consumer product should expose the same composition.

### 3.7 Sherlocks incident analysis

**RESEARCH-BACKED / SINGLE-SOURCE OPERATIONAL SIGNAL.** The reviewed analysis of 73 production incidents includes the cited 54-minute versus 4.2-hour repair comparison and highlights tool/schema drift as a major listed failure layer. This remains a single-source operational signal, not a universal statistic.

## 4. Developer capability research incorporated into project state

**ESTABLISHED.** `PERSONAL_DEVELOPER_CAPABILITY_RESEARCH_2026-09-05.md` is a research-backed proposal, not a committed runtime catalog.

### Tier A — safe / deterministic / highly verifiable

```text
dev.workspace.inspect
dev.file.read
dev.file.hash
dev.directory.list
dev.git.status
dev.git.diff
dev.git.log
dev.test.run
dev.build.run
dev.artifact.inspect
dev.config.validate
```

### Tier B — prepared or local writes

```text
dev.file.write
dev.file.patch
dev.git.branch.create
dev.git.commit.prepare
dev.test.fix
dev.build.reproduce
dev.documentation.sync
```

### Tier C — external/consequential developer effects

```text
dev.git.push
dev.pull_request.open
dev.issue.create
dev.release.publish
dev.deploy
dev.secret.rotate
```

### Tier D — Android/device authority

```text
device.notification.post
device.clipboard.read
device.clipboard.write
device.file.pick
device.screen.capture
device.app.launch
device.accessibility.act
```

**ESTABLISHED DIRECTION.** The intended progression is:

```text
Capability/Verification Contract
        ↓
Read-only developer capability family
        ↓
20-case benchmark across 3+ repository/project states
        ↓
First narrowly scoped write capability
        ↓
Android approval/process-death evidence
        ↓
External-effect reconciliation
        ↓
MCP/device expansion
```

## 5. Cross-angle runtime and architecture findings

### A-01 — Direct ALLOW planning failure escapes normal Run failure semantics

**CONFLICT / P0.** The direct `ALLOW` path invokes `action.plan(request.input)` before entering `execute()`'s exception boundary. A throwing plan therefore escapes instead of returning a persisted FAILED Run. The approval path already normalizes plan failures.

Required invariant:

```text
Every activation attempt produces a terminal Run or a documented asynchronous state;
planning failures never escape as an uncaught runtime exception.
```

### A-02 — Approval consumption and execution are not one durable transaction

**OPEN / P0.5-P1.** `resolveApproval()` consumes approval before `execute()` persists RUNNING/reserves effects. A process death in that window can leave durable state with consumed approval but a non-started Run. Recovery semantics for this boundary are not yet atomic.

### A-03 — Journal concurrency is instance-local, not process-global

**OPEN / P1.** `JournalRuntimeStore` and `JournalApprovalStore` use private in-memory locks. Separate instances/processes can race on replay/check/append. Existing tests prove persistence across instances sequentially, not compare-and-set correctness under concurrent writers.

### A-04 — Multi-effect reservation is not group-atomic

**OPEN / P1.** `reserveEffects()` appends one record per invocation. A crash during the sequence can leave only a prefix of a logical reservation set. This does not automatically imply duplicate execution, but the durable model does not provide an all-or-nothing group reservation transaction.

### A-05 — Historical Run reconstruction does not pin immutable Action semantics

**CONFLICT / P1.** The journal reconstructs a persisted Run by looking up the current `ActionDefinition` by ID and then copying the recorded version number onto that current object. A version number therefore does not by itself prove that the historical executable Action definition is the same implementation.

Required future provenance:

```text
Action ID
Action version
Action definition hash / immutable snapshot identity
```

### A-06 — Activation source is provenance, not authorization

**OPEN / P1.** `ActivationSource` is carried through request and Evidence but is not currently a PolicyEngine decision input. `USER_UI`, `MODEL`, `AUTOMATION`, and other sources can share authorization behavior when other attributes are equal. This is acceptable for the current trusted in-app model but insufficient for untrusted/external/background sources.

### A-07 — Activation identity is asserted, not authenticated

**OPEN / P1.** `ActivationRequest.identity` is caller-provided. The runtime propagates and binds it but does not authenticate the principal. This is identity propagation, not authentication.

### A-08 — Invocation authorization is weaker than Action authorization

**OPEN / P1.** The main policy decision is made from request and Action-level capability metadata; invocation-specific parameters and final scope are checked later in `AgentRuntime`. Future resource-sensitive capabilities require authorization over the concrete invocation.

### A-09 — Scope is currently a label-set, not a resource constraint model

**OPEN / P1.** Scope is modeled as `Set<String>` with containment checking. That cannot yet express hierarchical paths, exclusions, repository/branch boundaries, host constraints, or other structured authority limits.

### A-10 — Verification is still partially self-attested

**CONFLICT / P0.5-P1.** `CapabilityExecution` exposes `postcondition`, and `ActionExecution` propagates that into `Verification`. The producer of the execution result can therefore materially influence the success predicate. This conflicts with the project direction of independent verification.

Required future semantic split:

```text
CapabilityExecutor
    -> observations
Verifier(observations, expected postcondition)
    -> VerificationResult
Evidence
```

### A-11 — Verification result is under-specified for forensics

**OPEN / P1.** Current Verification has only `passed` and `reason`. A durable verifier contract should eventually identify verifier identity/version, expected predicate, observed facts, and a reproducible link to source observations/artifacts.

### A-12 — Capability effect classification is declarative and trusted

**OPEN / P2 now; P1 for plugins.** `CapabilityDescriptor.effect` describes READ_ONLY/REVERSIBLE/HIGH_IMPACT, but the executor is trusted to honor that classification. This is acceptable while capabilities are application-owned and static; it becomes a major concern for third-party/dynamic capabilities.

### A-13 — Tool-calling loop has no explicit resource budget

**OPEN / P1.** The provider loop can repeat model -> tool -> model cycles until the model stops. There is no explicit maximum iteration count, wall-clock budget, tool-call budget, or history-size budget.

Required future controls include:

```text
max_iterations
max_tool_calls
max_wall_time
max_input_bytes / history budget
max_output_bytes
```

### A-14 — Conversation lifecycle has no explicit cancellation propagation

**OPEN / P1.** The ViewModel launches the conversation on `viewModelScope` while the provider owns blocking OkHttp execution. Coroutine cancellation is not currently modeled as cancellation of the underlying HTTP call and tool loop.

### A-15 — Conversation/session correlation is incomplete

**OPEN / P1-P2.** Runs, invocations, effects, approvals, and provider tool-use blocks have separate identifiers, but no single correlation/causation context spans the complete chain.

Desired future relationship:

```text
Session
  -> Conversation
    -> ModelTurn
      -> ToolCall
        -> Run
          -> Invocation
            -> Effect
```

### A-16 — Streaming terminal-state contract is not strict

**CONFLICT / P1.** Error reporting and completion are not guarded by a single terminal-state contract. UI consumers should observe exactly one terminal transition per conversation execution.

### A-17 — Android initialization and durable recovery are synchronous in app construction

**OPEN / P1.** Runtime journal construction/recovery and settings/credential migration occur during object construction. State is small today, but this does not yet separate durable initialization from UI lifecycle work.

### A-18 — MCP server identity is name-based

**OPEN / P1 before MCP expansion.** MCP credential keys are derived from `serverName`, so duplicate names can collide. Display name should be separate from immutable identity.

Preferred model:

```text
serverId = immutable UUID
name     = mutable display label
url      = endpoint
credential namespace = serverId
```

### A-19 — MCP configuration validation is weaker than runtime egress validation

**OPEN / P1.** The settings UI currently checks only an `https://` prefix, while the provider egress policy performs stronger URI/host/credential checks for the application-to-provider destination. MCP configuration needs its own structural validation before storage/forwarding.

### A-20 — Credential protection is not the same as data minimization

**PROVISIONALLY VERIFIED / OPEN.** API/MCP credentials are persisted behind Android Keystore-backed encryption, but application state, conversation content, and Evidence/Run data can still carry sensitive material. Storage protection does not establish minimization or redaction.

### A-21 — `AppSettings` carries the API key through ordinary application state

**OPEN / P1-P2.** The key is loaded into `AppSettings` and held in ViewModel/Compose state. A stronger model separates secret access from ordinary settings/configuration state.

### A-22 — Android backup semantics remain unresolved

**OPEN / P1.** `android:allowBackup="true"` remains enabled. The project needs an explicit classification for runtime state, settings, journals, and encrypted credential material under backup/restore and device-bound Keystore behavior.

### A-23 — Release artifact evidence is incomplete

**PROVISIONALLY VERIFIED / P2.** CI currently tests the debug unit-test target and builds/uploads a debug APK. This is not release-build, signing, shrinking, device, or store-readiness evidence.

### A-24 — Dependency/supply-chain controls are baseline-level

**PROVISIONALLY VERIFIED / P2 now; P1 for release.** Main plugin/dependency versions are explicitly pinned and repositories are constrained to Google/Maven Central. Dependency verification, lock/attestation, SBOM, and vulnerability-gating are not yet project contracts.

### A-25 — Current tests under-cover adversarial runtime behavior

**ESTABLISHED GAP / P1.** Existing tests cover policy denial, approval, replay blocking, scope, undeclared capability, executor failure, persistence, and related invariants. Missing systematic coverage includes concurrent journals, crash injection at transaction boundaries, partial journal writes, resource exhaustion, cancellation, historical Action mismatch, independent verification, MCP identity collisions, and secret leakage paths.

## 6. CI and evidence interpretation

**ESTABLISHED.** The current workflow runs:

```text
gradle testDebugUnitTest
gradle assembleDebug
```

and uploads the debug APK. This proves unit/source behavior and debug packaging for the tested commit. It does not prove Android process-death behavior, real-device behavior, production release packaging, external effect semantics, or tamper-evident evidence.

**Recorded project history.** The project update that motivated this ledger recorded a successful CI baseline and explicitly named run 155 as successful before the capability-documentation additions. Subsequent repository history may contain later successful runs. These remain CI facts, not broad runtime guarantees.

## 7. Security model clarified by the audit

```text
Authentication
    Who is the caller?

Authorization
    May this principal perform this concrete invocation?

Approval
    Has required human authorization been durably granted?

Effect reservation
    Has this exact effect identity been durably claimed?

Execution
    Did the capability actually run?

Observation
    What independently inspectable state resulted?

Verification
    Does observed state satisfy the declared postcondition?

Evidence
    Can the entire decision/effect chain be reconstructed?
```

The current prototype has meaningful authorization/approval/effect-reservation structure, but authentication, invocation-level authorization, independent verification, transaction atomicity, and audit-grade evidence remain incomplete.

## 8. Evidence hierarchy

```text
L0  design statement
L1  code inspection
L2  deterministic unit/integration test
L3  CI build/test evidence
L4  local restart / persistence evidence
L5  Android device lifecycle evidence
L6  real external-effect evidence
L7  repeated field / production evidence
```

L2/L3 results must not be described as L5/L6/L7 guarantees.

## 9. Capability promotion gates

A capability should not advance merely because its happy path passes. Promotion requires, as applicable:

1. declared effect class and narrow scope;
2. concrete invocation authorization;
3. independent verification contract;
4. stable effect identity / idempotency strategy;
5. durable transaction semantics;
6. restart/recovery behavior;
7. evidence provenance;
8. resource/cancellation budget;
9. privacy/minimization review;
10. external reconciliation when effects leave the device.

## 10. Research-backed capability benchmark

The developer capability experiment remains a capability-family experiment, not a general coding-agent decision.

Target:

```text
at least 20 independent cases
across at least 3 distinct repository/project states
```

Record at minimum:

```text
success_rate
verification_precision
verification_recall
false_success_rate
false_failure_rate
mean_latency
p95_latency
retry_rate
recovery_rate
human_intervention_rate
duplicate_effect_rate
```

For model-mediated runs also measure token and latency cost against the deterministic direct path.

## 11. Decision and experiment updates

### Decisions reinforced

- **D-16:** independent verifiability and observability are first-class capability-prioritization criteria.
- Developer capabilities are a **proving ground**, not a product-identity lock-in.
- Typed capabilities remain preferable to broad arbitrary-command primitives.
- Remote provider execution does not transfer local authorization ownership.
- One runtime authority remains the architectural center.

### New/extended experiments

```text
E-12  Normalize direct Action planning failures into terminal FAILED Runs.
E-13  Approval-to-execution crash-injection and recovery campaign.
E-14  Cross-instance/process concurrent reservation and approval race campaign.
E-15  Historical Action definition hash / immutable snapshot reconstruction.
E-16  Source-aware and invocation-aware authorization.
E-17  Independent verifier prototype separate from CapabilityExecution.postcondition.
E-18  Tool-loop budget and cancellation campaign.
E-19  Session/correlation provenance chain.
E-20  MCP immutable server identity and configuration validation.
E-21  Secret-free AppSettings/configuration model.
E-22  Backup/restore state classification and device lifecycle test.
E-23  Release build + dependency integrity baseline.
```

Existing capability experiments:

```text
E-10  Personal developer capability family
E-11  Verification-first ranking
```

## 12. Final architectural judgment

**DECISION: CONTINUE.** The evidence supports continuing the architecture. The current system is a useful control-plane prototype with a strong direction toward typed, governed, observable, and verifiable effects.

**NEXT GATE.** The highest-value work is not broader autonomy. It is closing the semantic gap between:

```text
Action -> Invocation -> Execute -> Verify
```

and:

```text
Action definition
    -> authenticated/authorized context
    -> durable execution transaction
    -> capability effect
    -> independent observation
    -> independent verification
    -> durable evidence
    -> reconciliation
```

Only after those semantics are experimentally demonstrated should the project widen into write, remote, MCP, Android-device, background, or broader automation capabilities.

## 13. Anti-claims

Until the relevant evidence levels are closed, do not claim:

- exactly-once external execution;
- production process-death recovery;
- native/internal MCP control over provider-side downstream destinations;
- complete redaction/minimization;
- tamper-evident audit;
- unrestricted background autonomy;
- fully authenticated multi-source activation;
- general-purpose coding-agent behavior;
- general workflow-engine guarantees.
