# Muse Reference Review — 2026-09-17

Status: **EXTERNAL RESEARCH RECORD / SYNCHRONIZED WITH CURRENT RUNTIME STATE**

Date: 2026-09-17  
Repository baseline reviewed: `main` at `40576a7cb81a9d5ec9223d70fbbea7d8b9693815`

Purpose: record Meta Muse architecture, security, and operational patterns that materially inform `Llms-mcp-android`; map each finding to repository evidence; keep vendor claims separate from local proof; and prevent product features from silently becoming architecture requirements.

## 1. Evidence discipline

- **ESTABLISHED — EXTERNAL:** directly documented by Meta primary material.
- **EXPERIMENTALLY_SUPPORTED — EXTERNAL:** supported by a Meta-published experiment/evaluation; vendor-reported and bounded by its setup.
- **INFERENCE:** architectural consequence derived from external evidence.
- **HYPOTHESIS:** possible adaptation for this repository requiring local proof.
- **OPEN:** unresolved by current external or local evidence.
- **NOT_ADOPTED:** useful external pattern deliberately deferred.

Meta product claims are not local implementation evidence. Current repository status remains governed by code, executed experiments, and `ARCHITECTURE_STATE_SYNC_2026-09-17.md`.

## 2. Current repository baseline

The current semantic architecture is:

```text
DOMAIN
  Action / Capability / Run / Effect / Evidence
      |
DURABILITY
  RuntimeStore / JournalRuntimeStore / ApprovalStore
      |
EXECUTION
  CapabilityExecutor
      |
ANDROID ADAPTERS
  Binder / lifecycle / future WorkManager / future AppFunctions
      |
EXPERIMENTS
  T0 / T1 / T2 / later T3-T5
```

T2 currently provides case-level evidence for B2/B3 provider-process recovery. Caller-side `UNKNOWN_OUTCOME`, B1, B4-B7, cross-package trust, reboot/force-stop/update behavior, power-loss durability, and general exactly-once remain open.

## 3. Muse finding A — Secure VM as a separate execution environment

### External evidence

Meta describes Muse Secure VM as a dedicated persistent virtual computer with its own browser, used for web/app interaction and agent execution. Muse can continue work in the background after the application UI is closed. Muse's product material also describes a persistent audit trail and explicit user permission controls.  
Sources:
- https://ai.meta.com/muse/
- https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/

### Architectural implication

```text
reasoning/model
      !=
execution environment
```

A high-capability agent should not receive unrestricted direct access to the host environment.

### Repository mapping

This reinforces the existing `CapabilityExecutor` boundary and Android OS/app sandbox as separate trust domains. It does **not** justify introducing a VM into the current Android core.

### Status

**ESTABLISHED — EXTERNAL + ALIGNED LOCALLY**

### Adoption

**ADOPT PATTERN:** treat execution environment as a first-class trust boundary when high-power capabilities are introduced.

**NOT_ADOPTED:** add a generic VM/sandbox runtime to the APK now.

## 4. Muse finding B — Sentinel is external execution authority

### External evidence

Meta describes Sentinel as the authority for connector actions and network egress. The agent can propose an action, but Sentinel determines whether the concrete operation is permitted. User approvals are represented as scoped authorization rather than conversational text.  
Source:
- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse

### Repository mapping

The repository already enforces:

```text
Model
  -> Activation
  -> Policy / Approval / Egress
  -> CapabilityExecutor
```

and documents that model output is not authorization. Approval is exact-bound and one-use; post-approval only `ALLOW` can execute.

### Status

**ESTABLISHED — EXTERNAL + ESTABLISHED LOCALLY**

### Adoption

**KEEP:** current separation of reasoning from authorization.

**HYPOTHESIS:** when cross-process/package execution becomes security-sensitive, introduce an enforcement component whose authority is explicit and separable from the model/runtime process.

**NOT_ADOPTED:** create a second authority model inside the app merely to resemble Sentinel.

## 5. Muse finding C — scoped approval/capability semantics

### External evidence

Muse allows the user to review/approve critical actions, with permission controls that can be configured for future operations. Meta's security description frames authorization as scoped, exact enforcement rather than a UI-only decision.  
Sources:
- https://ai.meta.com/muse/
- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse

### Repository mapping

Our approval context binds Run identity, requester identity, Action/version, input, planned invocations, and a fingerprint; approval is consumed once and revalidated before execution.

### Status

**ESTABLISHED — LOCAL IMPLEMENTATION / EXTERNAL CONVERGENCE**

### Adoption

No core redesign. Continue to treat approval as an authoritative control-plane record, not a conversation state or UI boolean.

## 6. Muse finding D — credential surrogation / just-in-time insertion

### External evidence

Meta states that runtime code sees only surrogate tokens. Sentinel resolves the real credential at the network boundary after the concrete request is authorized; the agent never receives the real token. Meta also describes a secure credential store and one-time payment-card numbers for eligible purchases.  
Sources:
- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse
- https://ai.meta.com/muse/

### Repository gap

Current `CredentialStore` provides protected storage with Android Keystore, but the provider transport still obtains concrete credentials at the request-building layer. Local documentation explicitly treats storage protection as distinct from downstream secret exposure.

### Status

**OPEN LOCALLY:** credential-use isolation is not end-to-end proven.

### Adoption

**HYPOTHESIS / NEXT SECURITY GATE:** introduce an internal `CredentialRef` concept:

```text
agent / Action / Tool
        |
        +--> CredentialRef
                 |
           final egress boundary
                 |
             secret value
```

The secret value should not enter model-visible tool schemas, ActionPlan parameters, Evidence, normal logs, or durable Run content.

Discriminating test required before implementation promotion.

## 7. Muse finding E — network egress is an enforcement boundary

### External evidence

Meta describes Sentinel-controlled egress using a forward proxy and Linux networking controls. Sentinel evaluates destination properties at layer 4 and layer 7, including hostname, resolved IP, port, protocol, HTTP method, path, and actual decoded request. SSRF protections also prevent public-looking names from resolving to private infrastructure.  
Source:
- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse

### Repository mapping

Current `EgressPolicy` enforces HTTPS, host allowlists, rejects credentials embedded in URLs, and checks declared data classes. This is an admission/classification boundary, not content-level inspection.

### Status

**PROVISIONALLY VERIFIED LOCALLY / STRONGER EXTERNAL PATTERN**

### Adoption

**KEEP:** current egress boundary.

**HYPOTHESIS:** strengthen it later toward actual request/provenance-aware authorization rather than only declared data classes.

**NOT_ADOPTED:** replicate kernel-level eBPF infrastructure in the Android core without a demonstrated need.

## 8. Muse finding F — tainted egress links data provenance to authorization

### External evidence

Meta's "tainted egress" marks a process tainted after it reads user data. Narrow auto-allow network rules can be revoked when data provenance becomes tainted or unverifiable; the system uses eBPF cgroup and LSM hooks for interception, attribution, and taint propagation.  
Source:
- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse

### Architectural implication

```text
data provenance
   -> execution provenance
   -> egress authorization
```

### Repository gap

Current `EgressRequest` carries coarse `dataClasses`, destination, and purpose. It does not yet carry strong provenance linking a concrete payload/reference to its originating observation or artifact.

### Status

**OPEN LOCALLY / HYPOTHESIS**

### Adoption

Define and test a provenance-aware application-level form before considering OS/kernel tracking:

```text
DataRef / Observation
    + provenance
    + sensitivity
    + purpose
        -> EgressDecision
```

## 9. Muse finding G — credential separation, authorization, and process privilege are different layers

### External evidence

Meta describes separate services/roles around credential access and privileged execution, including `authd`, `privsep`, and Sentinel, so possession of a credential, permission to obtain it, and permission to use it are distinct concerns.  
Source:
- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse

### Repository mapping

Current project security vocabulary already distinguishes credential storage, policy, approval, egress, and execution, but credential possession/use is not yet represented as a first-class capability reference.

### Status

**INFERENCE / GAP**

### Adoption

**ADOPT PATTERN:** do not collapse identity, credential possession, and execution authorization into one object.

## 10. Muse finding H — browser is a narrow broker, not unrestricted browser access

### External evidence

Meta describes a browser broker where the agent sees a restricted accessibility representation; browser-side scripting and DevTools capabilities are constrained. User takeover and sensitive credential entry create explicit boundaries.  
Source:
- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse

### Architectural implication

```text
powerful environment
      |
  narrow broker
      |
agent-facing capability
```

### Repository mapping

This matches the distinction between `ToolRegistry` exposure and `CapabilityExecutor` authority.

### Status

**ESTABLISHED — EXTERNAL / ALIGNED LOCALLY**

### Adoption

**FUTURE ADAPTER:** browser access, if introduced, must expose a narrow capability surface rather than unrestricted browser execution.

## 11. Muse finding I — durable state and replay/restart separation

### External evidence

Meta's Muse Code materials describe a durable event log covering model calls, tool runs, approvals, and edits, with restart/replay behavior designed around recorded events. Muse product/security materials also describe audit history and durable state outside transient execution processes.  
Sources:
- https://about.fb.com/news/2026/06/introducing-muse-code-and-muse-spark-1-2/
- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse

### Repository mapping

Our Run/Effect journal and T2 recovery experiments target the same semantic separation. However, the current evidence does not yet prove that a distinct general event-log layer is required.

### Status

**EXTERNAL REFERENCE / LOCAL HYPOTHESIS**

### Adoption

Use event-log semantics only if B4-B7 demonstrate a missing ordering/causality primitive. Do not create a generic event bus preemptively.

## 12. Muse finding J — memory, execution history, and user-visible audit are distinct concerns

### External evidence

Muse exposes activity/audit history and user-controllable memories while continuing background work toward goals. Meta describes user control over what the agent learns and what actions it can take.  
Source:
- https://ai.meta.com/muse/

### Repository implication

Preserve the separation:

```text
Execution history != Evidence != Memory != UI activity view
```

### Status

**ARCHITECTURALLY ALIGNED**

### Adoption

Do not use chat history as a substitute for durable Run/Evidence state or future memory.

## 13. Muse finding K — prompt injection remains an open problem

### External evidence

Meta explicitly states that prompt injection remains an open problem and describes layered defenses intended to reduce blast radius rather than claiming model immunity.  
Source:
- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse

Meta also disclosed a third-party evaluation incident involving a misconfigured test environment; the company attributed the event to the evaluation setup rather than a sandbox escape and described changes to harden test isolation.  
Source:
- https://research.meta.ai/blog/addressing-third-party-testing-misconfiguration-muse-spark-1-1

### Status

**ESTABLISHED — EXTERNAL** that Meta does not treat prompt injection as solved.

### Repository implication

Do not treat prompt filtering, risk scoring, or model behavior as the final execution authority.

The system should remain safe-by-boundary where possible:

```text
model defense
  + policy
  + credential isolation
  + egress controls
  + environment containment
  + approval
```

## 14. Muse finding L — current confidentiality boundary is not the same as confidential computing

### External evidence

Meta distinguishes current Muse isolation from a future Confidential VM design intended to prevent Meta from technically accessing the contents of the execution environment.  
Source:
- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse

### Repository implication

Do not conflate:

```text
application/runtime isolation
```

with:

```text
cryptographic non-access by infrastructure operator
```

### Status

**ESTABLISHED — EXTERNAL DISTINCTION**

### Adoption

**REFERENCE only** for future privacy architecture. Not required for the current Android control-plane proof.

## 15. Muse finding M — real-world launch evidence must remain separate from architecture claims

### External evidence

Reuters reported on Meta's September 2026 Muse launch that internal tests had identified security/reliability issues and privacy exposure concerns; Meta had delayed the original launch to improve security. This is external reporting, not a primary technical audit.  
Source:
- https://www.reuters.com/business/meta-launches-ai-agent-that-can-access-other-apps-send-emails-make-payments-2026-09-08/

### Status

**EXTERNAL REPORT / NOT A LOCAL ARCHITECTURE FACT**

### Repository implication

Architecture diagrams and vendor claims must not be treated as proof of production safety. Continue requiring local failure injection and reproducible evidence for our own guarantees.

## 16. Cross-source synthesis — Muse + Anthropic + local architecture

The useful common pattern is:

```text
                    REASONING
                 model / planner
                       |
                       v
                   ACTIVATION
                       |
             +---------+---------+
             |         |         |
          POLICY   APPROVAL   RISK SIGNAL
             |         |         |
             +---------+---------+
                       |
                 AUTHORIZATION
                       |
                CapabilityRef
                CredentialRef
                       |
                       v
                EXECUTION PLANE
                       |
          +------------+------------+
          |            |            |
       Android       MCP       Sandbox/VM
          |            |            |
          +------------+------------+
                       |
                     EFFECT
                       |
                 OBSERVATION
                       |
                 VERIFICATION
                       |
                    EVIDENCE
                       |
              DURABLE RUN / EVENT
                       |
             +---------+---------+
             |                   |
          SESSION              MEMORY
```

This is our synthesis, not a claim that Meta exposes this exact internal graph.

## 17. Adoption deltas

### Keep / strengthen

1. Model is never execution authority.
2. CapabilityExecutor remains the controlled effect boundary.
3. Approval remains exact-bound, one-use, and revalidated.
4. Egress remains a separate decision.
5. Execution environments remain separate trust domains.
6. Execution history remains distinct from memory and UI presentation.

### New research gates

1. Caller-side recovery: B4/B5 + durable `UNKNOWN_OUTCOME`.
2. Concurrent recovery: B6.
3. Stale callback/result rejection: B7.
4. Credential-use isolation using `CredentialRef` semantics.
5. Provenance-aware egress beyond coarse data classes.
6. Determine whether a minimal durable execution-event model is required by B4-B7.

### Defer

- general VM/sandbox inside the Android core;
- browser broker until a real browser capability is needed;
- kernel-level taint tracking without an Android-specific threat/feasibility experiment;
- multi-agent orchestration;
- WorkManager as domain truth;
- generic event bus;
- general exactly-once claims;
- confidential-computing infrastructure.

## 18. Current claim frontier

| Area | Classification | Evidence |
|---|---|---|
| Model non-authority | ESTABLISHED | local architecture + external convergence |
| Scoped approval semantics | ESTABLISHED / PROVISIONALLY VERIFIED | local tests + Meta description |
| CapabilityExecutor boundary | PROVISIONALLY VERIFIED | deterministic runtime tests |
| Provider-side recovery B2/B3 | EXPERIMENTALLY_SUPPORTED | Run #262 |
| Caller-side UNKNOWN_OUTCOME | OPEN | not yet executed |
| Concurrent recovery | OPEN | not yet executed |
| Stale callback ordering | OPEN | not yet executed |
| Credential storage | PROVISIONALLY VERIFIED | Keystore-backed store |
| Credential-use isolation | OPEN | no end-to-end proof |
| Coarse egress admission | PROVISIONALLY VERIFIED | current EgressPolicy |
| Provenance-aware egress | OPEN | pattern identified, no local proof |
| Durable event model | OPEN / HYPOTHESIS | needs B4-B7 discrimination |
| Browser capability boundary | FUTURE ADAPTER | no current implementation |
| High-power execution containment | FUTURE / CAPABILITY-SPECIFIC | no current high-power capability |
| General exactly-once | NOT CLAIMED | provider-specific only |

## 19. Primary sources

- Meta — Muse product: https://ai.meta.com/muse/
- Meta — Introducing Muse: https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/
- Meta AI Research — How We Built Safety Into Muse: https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse
- Meta AI Research — addressing third-party testing misconfiguration: https://research.meta.ai/blog/addressing-third-party-testing-misconfiguration-muse-spark-1-1
- Meta AI Research — Scaling How We Build and Test Our Most Advanced AI: https://ai.meta.com/blog/scaling-how-we-build-test-advanced-ai/
- Reuters — Muse launch/reporting: https://www.reuters.com/business/meta-launches-ai-agent-that-can-access-other-apps-send-emails-make-payments-2026-09-08/

## 20. Synchronization targets

This record should be referenced from:

- `README.md`
- `docs/architecture/ECOSYSTEM_RESEARCH_2026-09.md`
- `docs/architecture/DECISION_REGISTER_v0.2.md`
- `docs/architecture/ARCHITECTURE_STATE_SYNC_2026-09-17.md`
- `docs/security/MUSE_SECURITY_SYNC_2026-09-17.md`

External research remains a sourced record. Current implementation status remains governed by repository code and executed evidence.
