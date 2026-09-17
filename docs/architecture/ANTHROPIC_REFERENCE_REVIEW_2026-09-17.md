# Anthropic Reference Review — 2026-09-17

Status: **EXTERNAL RESEARCH RECORD / SYNCHRONIZED WITH CURRENT RUNTIME STATE**

Date: 2026-09-17  
Repository baseline reviewed: `main` at documentation/state-sync commit `40576a7cb81a9d5ec9223d70fbbea7d8b9693815`

Purpose: record current Anthropic engineering and Managed Agents primitives that materially inform `Llms-mcp-android`, map them to existing architecture and evidence, and prevent external ideas from being silently promoted into implementation requirements.

This document is a research record. It does not itself change runtime semantics. A proposed adoption becomes authoritative only when added to the decision register or proven by an experiment.

## 1. Evidence discipline

External findings use the project claim vocabulary:

- **ESTABLISHED — EXTERNAL**: directly documented by Anthropic primary documentation.
- **EXPERIMENTALLY_SUPPORTED — EXTERNAL**: backed by an Anthropic-published measurement/evaluation; vendor-reported and limited to the described test population/setup.
- **INFERENCE**: architectural consequence drawn from the external evidence.
- **HYPOTHESIS**: candidate adaptation for this repository that still requires local evidence.
- **OPEN**: material question not resolved by the external material or by current repository evidence.
- **NOT_ADOPTED**: useful external pattern that is deliberately not being added now.

Rule: Anthropic product capability is not evidence that the same implementation is correct for Android, local-first deployment, or this repository's threat model.

## 2. Current architecture baseline we synchronized against

The repository currently defines:

```text
Activation
  -> Action
  -> Policy / Approval / Egress
  -> Run
  -> CapabilityInvocation
  -> CapabilityExecutor
  -> Observation
  -> Verification
  -> Evidence
```

The model is a reasoning/selection component, not an authority channel. `CapabilityExecutor` is the single runtime-owned effect boundary. Approval is exact-bound and one-use. Egress is a distinct decision. Durable state is carried by `RuntimeStore` / `JournalRuntimeStore` / `ApprovalStore`. fileciteturn29file0L2-L2

The latest state synchronization records T2 provider-process B2/B3 as case-level experimentally supported while caller-side `UNKNOWN_OUTCOME`, B4/B5/B6/B7, cross-package trust, reboot/force-stop/update behavior, and general exactly-once remain open. fileciteturn30file0L2-L2

## 3. Anthropic finding A — decouple agent, environment, and session

### External evidence

Anthropic Managed Agents separates:

```text
Agent
  -> model, system prompt, tools, MCP servers, skills

Environment
  -> execution location / sandbox

Session
  -> long-running interaction instance

Events
  -> persistent interaction/execution history
```

Agents are reusable definitions, environments determine where execution occurs, and sessions reference both. Managed Agents communication is event-based and session event history is persisted server-side. urlhttps://platform.claude.com/docs/en/managed-agents/overview|https://platform.claude.com/docs/en/managed-agents/overview

### Repository mapping

This strongly aligns with the current split:

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
```

The current state-sync explicitly says the domain model is independent of the Android transport mechanism. fileciteturn30file0L2-L2

### Claim

**ESTABLISHED — EXTERNAL + STRONGLY ALIGNED:** durable semantic state should not be identified with the agent process, harness, worker, or execution environment.

### What we adopt

**ADOPT PATTERN:** preserve the existing `CapabilityExecutor`/runtime separation and make future session/event state explicit rather than making a process or sandbox the source of truth.

### What we do not adopt yet

**NOT_ADOPTED:** Anthropic's managed service/session APIs as the internal ontology. Our local semantic entities remain canonical.

## 4. Anthropic finding B — persistent event stream as authoritative session history

### External evidence

Managed Agents communication is event-based. Persisted events use typed `{domain}.{action}` names, carry processing state, and can be listed after the live stream. Stream-only delta previews are explicitly not persisted. Sessions preserve conversation history until deletion. urlhttps://platform.claude.com/docs/en/managed-agents/events-and-streaming|https://platform.claude.com/docs/en/managed-agents/events-and-streaming urlhttps://platform.claude.com/docs/en/managed-agents/reference|https://platform.claude.com/docs/en/managed-agents/reference

### Architectural implication

The distinction is:

```text
persisted event
  != live preview
```

and:

```text
session history
  != sandbox lifetime
```

Managed Agents documents that sandbox state is checkpointed while idle but is only preserved for 30 days; session history persists until session deletion. urlhttps://platform.claude.com/docs/en/managed-agents/events-and-streaming|https://platform.claude.com/docs/en/managed-agents/events-and-streaming

### Repository mapping

Our Run/Effect/Evidence journal already provides durable state and effect identity. T2 B2/B3 proves a provider-side operation can survive provider-process loss in the tested topology. However, caller-side durable `UNKNOWN_OUTCOME` and stale/concurrent recovery remain open. fileciteturn30file0L2-L2

### Decision impact

**ADOPT PATTERN / HYPOTHESIS:** define a durable event history as a separate concern from the Run/Effect state model only if B4/B5 experiments demonstrate that explicit event ordering/causality is the missing primitive.

Do not create a generic event bus merely because Anthropic has one.

## 5. Anthropic finding C — decouple brain from hands

### External evidence

Anthropic explicitly describes Managed Agents as separating the "brain" from execution "hands": a stable agent/harness interface can target different sandboxes/execution environments, while the model side remains replaceable. urlhttps://www.anthropic.com/engineering/managed-agents|https://www.anthropic.com/engineering/managed-agents

### Repository mapping

This matches our `CapabilityExecutor` contract:

```text
Reasoning / Action planning
        |
        v
CapabilityInvocation
        |
        v
CapabilityExecutor
        |
        +--> Android adapter
        +--> MCP adapter
        +--> future sandbox/worker
```

`CapabilityExecutor` is explicitly the controlled execution boundary and does not grant authority itself. fileciteturn6file0L2-L2

### Claim

**ESTABLISHED — EXTERNAL + ESTABLISHED IN REPOSITORY:** the stable abstraction should be the semantic execution contract, not a specific runtime/harness/provider.

### Action

**KEEP:** current `CapabilityExecutor` boundary.  
**DO NOT ADD:** a second generic `AgentRuntime`/executor abstraction solely to resemble Anthropic.

## 6. Anthropic finding D — credentials are references, not agent-visible values

### External evidence

Managed Agents Vaults store credentials separately. Sensitive credential fields are never returned in API responses. Credential networking can be unrestricted only to environment-permitted destinations or restricted to an explicit host allowlist. Vault-backed credentials are referenced by sessions; reusable MCP server definitions do not contain authentication tokens. urlhttps://platform.claude.com/docs/en/api/beta/vaults/credentials|https://platform.claude.com/docs/en/api/beta/vaults/credentials urlhttps://platform.claude.com/docs/en/managed-agents/mcp-connector|https://platform.claude.com/docs/en/managed-agents/mcp-connector

### Repository mapping

Our current credential boundary is strong **at rest**: API/MCP credentials use Android Keystore-backed encryption. The security baseline explicitly states that this is a storage boundary and does not by itself prove every downstream log/export/crash surface is safe. fileciteturn29file0L2-L2

The current Anthropic transport code still holds the concrete API key in the request-building layer and currently treats MCP connector transport as provider-owned. fileciteturn26file0L2-L2

### Gap

```text
CURRENT
CredentialStore
    -> protected storage

MISSING
CredentialRef
    -> final egress resolver
    -> secret value only at protected transport boundary
```

### Claim

**OPEN:** repository has not yet demonstrated credential-use isolation from model/tool/action/evidence context.

### Proposed adoption

**HYPOTHESIS / NEXT SECURITY GATE:** introduce an internal `CredentialRef`/protected credential-resolution concept without changing the current public product behavior. The secret value should be unavailable to model-facing tool schemas, ActionPlan data, Evidence, normal logs, and durable Run content.

No code should be added until a discriminating test can demonstrate that the secret does not cross the forbidden boundary.

## 7. Anthropic finding E — policy is not the same as human approval

### External evidence

Managed Agents supports `always_allow`, `always_ask`, and `auto`. `always_ask` pauses for user approval. `auto` makes a server-side allow/deny/pause decision per call and is not equivalent to human approval. MCP toolsets default to `always_ask`. urlhttps://platform.claude.com/docs/en/managed-agents/permission-policies|https://platform.claude.com/docs/en/managed-agents/permission-policies

### Repository mapping

Our runtime already separates `DENY`, `APPROVAL_REQUIRED`, and `ALLOW`, and after an approval only `ALLOW` can reach execution. Approval context is bound to Run/requester/Action/version/input/plan fingerprint and consumed once. fileciteturn2file0L2-L2

### Claim

**ESTABLISHED:** the current semantic distinction is correct.

### Refinement to preserve

Treat these as separate dimensions:

```text
Policy authorization
Human approval
Automated risk evaluation
Model reasoning
Preference
```

A future risk classifier must never become the authority boundary.

## 8. Anthropic finding F — containment is a second security plane

### External evidence

Anthropic's containment work emphasizes limiting blast radius through sandboxes, VMs, filesystem boundaries, and egress controls rather than depending on human permission prompts alone. Anthropic reports that users approved roughly 93% of Claude Code permission prompts, and notes that probabilistic defenses have non-zero miss rates. It also documents failures where prompt-injection or model behavior could have produced damaging actions, motivating environment-level boundaries. urlhttps://www.anthropic.com/engineering/how-we-contain-claude|https://www.anthropic.com/engineering/how-we-contain-claude

### Repository mapping

Our security model already treats Android OS permissions, app sandboxing, lifecycle, and external-service authority as separate trust domains. The application control plane is not supposed to replace platform isolation. fileciteturn29file0L2-L2

### Claim

**ESTABLISHED — EXTERNAL:** authorization and containment are complementary layers.

### Action

**ADOPT PATTERN:** when a Capability becomes high-power, its safety contract must include the execution environment, not only policy and approval.

**NOT_ADOPTED:** adding a general VM/sandbox layer to the APK now. The current capability set is intentionally narrow, and T2 recovery semantics remain the active proof frontier.

## 9. Anthropic finding G — self-hosted execution confirms control-plane/data-plane separation

### External evidence

Managed Agents supports self-hosted sandboxes where Anthropic retains orchestration/model control while the customer controls the execution environment, filesystem, processes, and network egress. Tool inputs/outputs still flow to the Anthropic control plane. urlhttps://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes|https://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes

### Repository mapping

This reinforces our existing principle:

```text
canonical semantics/control
        !=
replaceable execution plane
```

It also supports the existing decision that remote providers never inherit local authorization ownership. fileciteturn31file0L2-L2

### Action

**KEEP:** provider/transport adapters outside the semantic core.  
**FUTURE ADAPTER:** remote/self-hosted execution environments only after actual workloads require them.

## 10. Anthropic finding H — memory is separate from session state

### External evidence

Managed Agents memory stores persist information across sessions such as user preferences, project conventions, prior mistakes, and domain context. A session starts with a fresh context; memory is a separate persisted resource. Memory stores can be attached with explicit access behavior. urlhttps://platform.claude.com/docs/en/managed-agents/memory|https://platform.claude.com/docs/en/managed-agents/memory

### Repository mapping

Our architecture already distinguishes Run/Evidence from model reasoning, but a first-class session/event layer and durable user/project memory are not the same thing and should not be merged.

### Claim

**ESTABLISHED — EXTERNAL + ARCHITECTURALLY ALIGNED:**

```text
Execution history
  != Session context
  != Memory
  != Evidence
```

### Action

**ADOPT PATTERN:** keep these semantic categories separate when durable memory is implemented.

**NOT_ADOPTED:** copy Anthropic's memory store file layout or service APIs into the Android core.

## 11. Anthropic finding I — memory can be externally controlled in self-hosted execution

### External evidence

For self-hosted sandboxes, memory stores are materialized by the worker, synchronized back to the Anthropic-side source of truth, and can be mounted read-only. The vendor docs explicitly describe the memory store on Anthropic's side as the source of truth for that feature. urlhttps://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes|https://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes

### Repository implication

The useful invariant is not "memory lives in files". It is:

```text
memory semantic identity
    >
replaceable materialization / locator
```

This is consistent with the project's broader artifact-identity principle.

## 12. Anthropic finding J — MCP authentication is separated from reusable tool configuration

### External evidence

Managed Agents declares MCP server name/URL at agent-definition time and supplies authentication via vault references when creating a session. Authentication failures are exposed as session events rather than turning session creation into an implicit authorization guarantee. urlhttps://platform.claude.com/docs/en/managed-agents/mcp-connector|https://platform.claude.com/docs/en/managed-agents/mcp-connector

### Repository mapping

Our current implementation intentionally keeps native MCP extraction open; provider-owned MCP handling is an explicit limitation. The security baseline classifies MCP as an external adapter until its identity/scope/credential/destination/data/effect/recovery semantics are mapped into the local contract. fileciteturn29file0L2-L2

### Action

**ADOPT PATTERN:** when native MCP begins, split:

```text
server/tool declaration
    !=
credential binding
    !=
execution authorization
```

Do not promote the external MCP protocol to the canonical domain model.

## 13. Anthropic finding K — event observability is part of the product contract

### External evidence

Managed Agents exposes persisted session/span/agent/user/system events and tooling to inspect session history, tool usage, failures, threads, costs, and outputs. urlhttps://platform.claude.com/docs/en/managed-agents/events-and-streaming|https://platform.claude.com/docs/en/managed-agents/events-and-streaming

### Repository mapping

Our Evidence and Run state are already designed as attributable execution outputs. However, the current open T2 cases B4/B5/B6/B7 are fundamentally ordering and recovery questions. fileciteturn30file0L2-L2

### Action

**HYPOTHESIS:** if B4/B5/B6/B7 cannot be expressed cleanly from Run/Effect state alone, introduce a minimal durable execution-event model rather than a general-purpose event bus.

Candidate events, subject to experiment:

```text
ActivationRequested
PolicyEvaluated
ApprovalRequested
ApprovalConsumed
EffectReserved
EffectDispatched
EffectApplied
EffectUnknown
Reconciled
VerificationCompleted
EvidenceCommitted
RunTerminated
```

These names are proposals, not current domain entities.

## 14. Anthropic finding L — containment should be tested with real failure evidence

### External evidence

Anthropic publicly documents incidents and red-team findings where model-layer defenses did not fully protect against unexpected access paths, and describes containment as a way to cap blast radius when probabilistic defenses fail. urlhttps://www.anthropic.com/engineering/how-we-contain-claude|https://www.anthropic.com/engineering/how-we-contain-claude

### Repository implication

This reinforces our evidence discipline:

```text
architecture claim
    !=
security proof
```

A local security mechanism should not be promoted from code inspection to "safe" status until it has a discriminating failure test.

## 15. Anthropic finding M — MCP 2026-07-28 release candidate is a protocol evolution signal

### External evidence

The MCP 2026-07-28 release candidate introduces a stateless protocol core, Extensions, Tasks, MCP Apps, authorization hardening, and a formal deprecation policy. urlhttps://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/|https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/

### Repository mapping

This reinforces, rather than weakens, the current decision:

```text
MCP = replaceable protocol adapter
MCP != canonical internal ontology
```

Protocol evolution is evidence against making MCP types our durable domain model.

### Action

Keep native MCP adapter work behind the existing gate. When it begins, re-audit authorization, Tasks/lifecycle semantics, and credential binding against the current specification version rather than against historical MCP behavior.

## 16. Anthropic finding N — auto/risk classification remains probabilistic

### External evidence

Anthropic documents that Claude Code auto mode uses a classifier to automate safer approvals, but explicitly notes non-zero miss rates; one published evaluation reported a 17% end-to-end false-negative rate in the described test set of real over-eager actions. This is a vendor-reported test result and is not a general error rate. urlhttps://www.anthropic.com/engineering/claude-code-auto-mode|https://www.anthropic.com/engineering/claude-code-auto-mode

### Repository implication

Do not model an ML risk classifier as an authoritative evidence or authorization source.

It may become:

```text
advisory risk signal
    -> policy input
```

but the execution boundary must remain deterministic and attributable.

## 17. Cross-source synthesis: what Anthropic adds to our model

The useful common abstraction is:

```text
                       REASONING
                    model / planner
                          |
                          v
                      ACTIVATION
                          |
            +-------------+-------------+
            |             |             |
         POLICY       APPROVAL      RISK SIGNAL
            |             |             |
            +-------------+-------------+
                          |
                    AUTHORIZATION
                          |
                   CapabilityRef
                   CredentialRef
                          |
                          v
                    EXECUTION PLANE
                          |
        +-----------------+-----------------+
        |                 |                 |
      Android            MCP            Sandbox
        |                 |                 |
        +-----------------+-----------------+
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
              +-----------+-----------+
              |                       |
           SESSION                  MEMORY
```

This is a synthesis, not a claim that Anthropic exposes this exact internal architecture.

## 18. What changes in our next work

### Preserve

- Model is not authority.
- Policy, Approval, and Egress remain independent.
- CapabilityExecutor remains the controlled effect boundary.
- Runtime state remains durable and transport-independent.
- MCP remains an adapter.

### Add as research/adoption gates

1. **Caller-side recovery proof** — B4/B5 + `UNKNOWN_OUTCOME` first.
2. **Concurrent/stale recovery proof** — B6/B7.
3. **Credential-use isolation** — define and test `CredentialRef` semantics.
4. **Provenance-aware egress** — move beyond coarse declared data classes.
5. **Minimal execution event model** only if B4-B7 demonstrate a real semantic need.
6. **Native MCP hardening review** against current MCP authorization/lifecycle rules.

### Explicitly defer

- general-purpose sandbox/VM in the Android core;
- WorkManager as domain truth;
- multi-agent coordination;
- third-party event bus;
- generic risk-classifier authority;
- cloud account/tenant architecture;
- protocol-driven replacement of the internal domain model.

## 19. Current claim frontier after Anthropic synchronization

| Area | Classification | Current evidence |
|---|---|---|
| Model non-authority | ESTABLISHED | local architecture + external convergence |
| Policy/approval separation | ESTABLISHED | local runtime tests + Anthropic permission model |
| CapabilityExecutor as controlled execution boundary | PROVISIONALLY VERIFIED | local deterministic tests |
| Durable provider receipt/effect recovery | EXPERIMENTALLY_SUPPORTED | T2 B2/B3 |
| Caller-side durable `UNKNOWN_OUTCOME` | OPEN | not yet executed |
| Concurrent recovery | OPEN | not yet executed |
| Stale callback ordering | OPEN | not yet executed |
| Credential storage protection | PROVISIONALLY VERIFIED | Keystore-backed store |
| Credential-use isolation | OPEN | no end-to-end proof |
| Coarse egress admission | PROVISIONALLY VERIFIED | current EgressPolicy |
| Fine-grained content minimization | OPEN | not implemented |
| Provenance-aware egress | OPEN | external pattern identified, no local proof |
| Durable session/event model | OPEN / HYPOTHESIS | pending caller recovery experiments |
| Memory/session separation | ARCHITECTURAL DECISION | external reference + local principles |
| Native MCP boundary | OPEN | explicit future gate |
| General exactly-once | NOT CLAIMED | provider-specific only |

## 20. Primary sources

- Anthropic — Managed Agents overview: https://platform.claude.com/docs/en/managed-agents/overview
- Anthropic — Managed Agents event stream: https://platform.claude.com/docs/en/managed-agents/events-and-streaming
- Anthropic — Managed Agents reference: https://platform.claude.com/docs/en/managed-agents/reference
- Anthropic — Decoupling the brain from the hands: https://www.anthropic.com/engineering/managed-agents
- Anthropic — Permission policies: https://platform.claude.com/docs/en/managed-agents/permission-policies
- Anthropic — MCP connector: https://platform.claude.com/docs/en/managed-agents/mcp-connector
- Anthropic — Vaults: https://platform.claude.com/docs/en/api/beta/vaults
- Anthropic — Credentials: https://platform.claude.com/docs/en/api/beta/vaults/credentials
- Anthropic — Memory: https://platform.claude.com/docs/en/managed-agents/memory
- Anthropic — Self-hosted sandboxes: https://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes
- Anthropic — How we contain Claude: https://www.anthropic.com/engineering/how-we-contain-claude
- Anthropic — Claude Code auto mode: https://www.anthropic.com/engineering/claude-code-auto-mode
- MCP — 2026-07-28 release candidate: https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/

## 21. Repository synchronization targets

This research record is cross-linked from:

- `README.md` — external research index
- `docs/architecture/ECOSYSTEM_RESEARCH_2026-09.md` — ecosystem findings
- `docs/architecture/DECISION_REGISTER_v0.2.md` — decisions/gates that become durable architecture policy
- `docs/architecture/ARCHITECTURE_STATE_SYNC_2026-09-17.md` — current evidence frontier and next gate

The external research record should remain historical/sourced. Current implementation status continues to come from repository code plus executed evidence and the architecture state-sync authority document.
