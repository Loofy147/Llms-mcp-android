# Anthropic Reference Review — 2026-09-17

Status: **EXTERNAL RESEARCH RECORD / SYNCHRONIZED WITH CURRENT RUNTIME STATE**

Date: 2026-09-17

Purpose: capture Anthropic's current agent architecture, security, durability, credential, memory, MCP, and containment patterns that materially inform `Llms-mcp-android`. This is external evidence, not local implementation proof.

Repository evidence authority remains `docs/architecture/ARCHITECTURE_STATE_SYNC_2026-09-17.md` plus the referenced code and executed experiments.

## 1. Baseline being synchronized

Current local execution model:

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

The model is a reasoning/selection component, not an execution authority. `CapabilityExecutor` is the controlled effect boundary. Approval is exact-bound and one-use. Egress is a distinct decision. Durable state is represented by `RuntimeStore` / `JournalRuntimeStore` / `ApprovalStore`.

Current T2 evidence is case-level: B2 and B3 are experimentally supported on the API-35 emulator. Caller-side `UNKNOWN_OUTCOME`, B4/B5/B6/B7, cross-package trust, reboot/force-stop/update behavior, power-loss durability, and general exactly-once remain open.

See:

- `docs/architecture/ARCHITECTURE_STATE_SYNC_2026-09-17.md`
- `docs/architecture/ANDROID_T2_EXECUTION_LEDGER_2026-09-17.md`
- `docs/architecture/CAPABILITY_EXECUTOR_BOUNDARY_v0.1.md`

## 2. Agent, environment, session, and events are separate

Anthropic Managed Agents defines reusable agents, execution environments, and long-lived sessions separately. Sessions communicate through typed events and persist event history server-side.

Primary sources:

- https://platform.claude.com/docs/en/managed-agents/overview
- https://platform.claude.com/docs/en/managed-agents/events-and-streaming
- https://www.anthropic.com/engineering/managed-agents

Architectural consequence:

```text
agent definition
    != execution environment
    != process
    != durable session state
```

This strongly agrees with the repository's existing semantic/durability/execution separation.

**Adopt:** preserve `CapabilityExecutor` and durable runtime state as independent boundaries.

**Do not adopt:** Anthropic Managed Agents APIs or resource identifiers as the internal domain ontology.

## 3. Persistent event history is different from live streaming

Anthropic distinguishes persisted session events from stream-only deltas. Persisted events are addressable history; live delta previews are not part of the durable event history.

Primary source:

- https://platform.claude.com/docs/en/managed-agents/events-and-streaming

This gives a useful invariant:

```text
persisted event != live preview
```

It also supports separating:

```text
Run/Effect state
Session/event history
Sandbox state
Memory
Evidence
```

Our next T2 cases are precisely where event ordering may become relevant: caller death, concurrent recovery, and stale callbacks.

**Hypothesis:** only add a dedicated durable execution-event layer if B4-B7 expose information that Run/Effect state cannot represent safely.

Do not add a generic event bus merely because Anthropic uses an event stream.

## 4. Sandbox lifetime is not session truth

Anthropic documents that session history persists until the session is deleted, while sandbox state is checkpointed but only preserved for a limited window; after the stated 30-day window a resumed session can start with a fresh sandbox.

Primary source:

- https://platform.claude.com/docs/en/managed-agents/events-and-streaming

This reinforces:

```text
sandbox/filesystem state
    !=
semantic durable operation identity
```

It matches our T2 principle that the provider process may die while durable operation/effect state survives.

## 5. Brain/harness versus hands/execution

Anthropic's engineering description explicitly frames Managed Agents as decoupling the model-side "brain" from execution "hands" and keeping interfaces stable while execution environments change.

Primary source:

- https://www.anthropic.com/engineering/managed-agents

Local mapping:

```text
reasoning / planning
        -> CapabilityInvocation
        -> CapabilityExecutor
        -> concrete Android/MCP/sandbox adapter
```

This validates, rather than replaces, our current `CapabilityExecutor` boundary.

Local status: **PROVISIONALLY VERIFIED** for the controlled executor contract; real Android/device adapters remain open.

## 6. Credential references and credential values are different objects

Anthropic Vaults store credentials outside reusable agent definitions. Sensitive fields are never returned in normal credential responses. Credentials can be restricted to explicit hosts or to environment-permitted egress. MCP authentication is supplied by session-side vault references rather than embedded in the reusable MCP server definition.

Primary sources:

- https://platform.claude.com/docs/en/api/beta/vaults
- https://platform.claude.com/docs/en/api/beta/vaults/credentials
- https://platform.claude.com/docs/en/managed-agents/mcp-connector

Useful abstraction:

```text
CredentialRef
    !=
CredentialValue
```

Local state:

- API/MCP credentials are protected at rest with Android Keystore-backed encryption.
- The repository does not yet prove that raw secret values remain isolated across the complete model/tool/action/evidence/log/export path.

This is a real local gap.

**Next gate:** define and test a protected credential-resolution boundary. Raw secret values should be unavailable to model context, model-facing tool schemas, ActionPlan content, ordinary Evidence, durable Run content, ordinary logs, and exported telemetry.

## 7. Policy, human approval, and automated evaluation are distinct

Anthropic Managed Agents exposes three permission modes:

```text
always_allow
always_ask
auto
```

`always_ask` is human approval. `auto` is a server-side policy evaluation that can allow, deny, or pause. It is not the same thing as human approval.

Primary source:

- https://platform.claude.com/docs/en/managed-agents/permission-policies

Our local model already separates:

```text
DENY
APPROVAL_REQUIRED
ALLOW
```

with post-approval re-evaluation and execution only from `ALLOW`.

This alignment is **established locally**; no new implementation is required from this finding.

The refined invariant is:

```text
Model reasoning
!= Policy authorization
!= Human approval
!= Automated risk signal
!= Preference
```

## 8. Containment is a separate security plane

Anthropic's containment work emphasizes sandboxes, VMs, filesystem boundaries, and egress controls as defenses that cap blast radius even when probabilistic or human-supervision defenses fail. Anthropic reports that users approved roughly 93% of Claude Code permission prompts and explicitly discusses approval fatigue and non-zero miss rates for probabilistic defenses.

Primary source:

- https://www.anthropic.com/engineering/how-we-contain-claude

Anthropic also documents examples where model-layer defenses did not fully protect the environment and where containment remained the decisive boundary.

Local mapping:

```text
policy / approval
        +
execution-environment containment
```

The local architecture already treats Android OS permissions, app sandboxing, lifecycle, and external-service authority as separate trust domains.

**Adopt pattern:** high-power Capabilities must specify execution-environment boundaries in addition to policy/approval.

**Do not adopt now:** a generic VM/sandbox inside the Android core. Current capabilities are intentionally narrow and T2 recovery is still the active proof frontier.

## 9. Self-hosted execution confirms control-plane/data-plane separation

Anthropic's self-hosted sandboxes keep orchestration on Anthropic's side while the customer controls the filesystem, processes, and network egress of the execution environment.

Primary source:

- https://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes

The important reusable abstraction is:

```text
control plane
    !=
execution plane
```

This supports our existing decision that remote providers and transport adapters do not acquire local authorization ownership.

## 10. Memory is separate from session state

Anthropic provides Memory Stores so information can survive across sessions, including preferences, project conventions, prior mistakes, and domain context. Each session can start with fresh context while memory remains separately persisted.

Primary sources:

- https://platform.claude.com/docs/en/managed-agents/memory
- https://platform.claude.com/docs/en/api/beta/memory_stores/memories/create

Local semantic implication:

```text
Session/event history
    !=
Memory
    !=
Evidence
    !=
Run/Effect state
```

**Adopt:** preserve this distinction if durable memory is implemented.

**Do not adopt:** Anthropic's memory-store API or file layout as the Android core ontology.

## 11. MCP declaration, authentication, and authorization are separate

Managed Agents declares MCP servers in the reusable agent configuration, then attaches authentication through vault references at session creation. The MCP permission policy can independently require approval. Connection/authentication failures are surfaced as session events.

Primary source:

- https://platform.claude.com/docs/en/managed-agents/mcp-connector

Local target shape for future native MCP:

```text
server/tool declaration
    != credential binding
    != authorization
    != execution lifecycle
```

Our current decision to keep MCP as an external adapter is reinforced.

## 12. MCP itself is evolving; protocol types should not become our domain truth

The MCP 2026-07-28 release candidate introduced a stateless protocol core, Extensions, Tasks, MCP Apps, authorization hardening, and a formal deprecation policy.

Primary source:

- https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/

Implication:

```text
MCP = replaceable protocol adapter
MCP != canonical Action/Capability/Run/Evidence ontology
```

When native MCP extraction begins, security and lifecycle behavior must be re-audited against the current specification rather than historical MCP behavior.

## 13. Auto/risk classification is useful but not authoritative

Anthropic documents Claude Code auto mode as a classifier-assisted approval mechanism and explicitly notes non-zero miss rates. One published evaluation reported a 17% end-to-end false-negative rate on its described set of real over-eager actions; this is a vendor-reported result for that evaluation, not a general classifier error rate.

Primary source:

- https://www.anthropic.com/engineering/claude-code-auto-mode

Local implication:

```text
risk classifier
    -> advisory input to policy
```

not:

```text
risk classifier
    -> authority
```

This supports keeping deterministic local authorization and verification above any future ML risk signal.

## 14. Containment evidence should be tested, not inferred

Anthropic's public containment engineering describes real red-team findings and failures that motivated changes to sandboxing, filesystem boundaries, and egress control.

Primary source:

- https://www.anthropic.com/engineering/how-we-contain-claude

Local evidence rule:

```text
architecture claim
    != security proof
```

The same rule already governs T2: a passing build is not a failure-injection pass, and a code path is not evidence of runtime recovery.

## 15. Cross-source synthesis

The useful normalized model is:

```text
                  REASONING
               model / planner
                     |
                     v
                 ACTIVATION
                     |
       +-------------+-------------+
       |             |             |
     POLICY      APPROVAL      RISK SIGNAL
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
        +------------+------------+
        |            |            |
      Android       MCP        Sandbox
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
             DURABLE RUN/EVENT
                 /         \
             SESSION      MEMORY
```

This diagram is our synthesis. It is not a claim that Anthropic publishes or internally implements exactly this graph.

## 16. Mapping to the current repository

| External primitive | Local status | Action |
|---|---|---|
| Agent/environment/session separation | Strong architectural alignment | Preserve |
| Brain/hands separation | CapabilityExecutor exists | Preserve; prove real adapters later |
| Policy/approval separation | Implemented and tested | Preserve |
| Containment as independent layer | Platform trust domains already separate | Include in future high-power capability contracts |
| Credential reference isolation | Missing end-to-end | New security gate |
| Provenance-aware egress | Coarse declared data classes only | New research gate |
| Durable session/event history | Run/Effect journal exists | Test whether a new event layer is actually necessary |
| Memory/session separation | Architectural direction | Preserve |
| MCP auth separation | Native adapter open | Re-audit at implementation |
| Risk classifier as advisory | Not implemented | Do not add as authority |
| General managed sandbox | Not needed now | Defer |

## 17. Current implementation gaps exposed by the synchronization

1. Caller-side durable `UNKNOWN_OUTCOME` is still open.
2. Concurrent recovery and stale result ordering are still open.
3. Credential storage is protected, but credential-use isolation is not yet demonstrated.
4. Egress admission exists, but content-level minimization and provenance-aware policy are not yet implemented.
5. Durable execution-event semantics are not yet established as a missing primitive.
6. Native MCP is still a future adapter and must be re-audited against the current protocol.
7. Higher-power execution environments need explicit containment contracts before broader autonomy.

## 18. What this research does not justify

Do not infer from Anthropic's platform that we should add any of the following now:

- generic event bus;
- VM/sandbox subsystem in the APK;
- cloud tenant/account architecture;
- multi-agent coordinator;
- WorkManager as domain truth;
- ML risk classifier as final authorization;
- Anthropic Managed Agents APIs as our internal semantic model;
- MCP types as canonical domain state.

## 19. Immediate engineering order

```text
1. B4/B5 caller-side recovery + durable UNKNOWN_OUTCOME
2. B6 concurrent recovery
3. B7 stale callback/result ordering
4. Credential-use isolation (CredentialRef)
5. Provenance-aware egress
6. Decide whether a durable execution-event layer is actually necessary
7. Native MCP authorization/lifecycle re-audit
8. High-power capability containment experiments
```

No Room or WorkManager addition is justified by this review alone.

## 20. Primary sources

- https://platform.claude.com/docs/en/managed-agents/overview
- https://platform.claude.com/docs/en/managed-agents/events-and-streaming
- https://platform.claude.com/docs/en/managed-agents/reference
- https://www.anthropic.com/engineering/managed-agents
- https://platform.claude.com/docs/en/managed-agents/permission-policies
- https://platform.claude.com/docs/en/managed-agents/mcp-connector
- https://platform.claude.com/docs/en/api/beta/vaults
- https://platform.claude.com/docs/en/api/beta/vaults/credentials
- https://platform.claude.com/docs/en/managed-agents/memory
- https://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes
- https://www.anthropic.com/engineering/how-we-contain-claude
- https://www.anthropic.com/engineering/claude-code-auto-mode
- https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/

## 21. Repository synchronization

Durable repository references:

- `docs/architecture/ANTHROPIC_ADOPTION_DELTAS_2026-09-17.md`
- `docs/architecture/DECISION_REGISTER_v0.2.md`
- `docs/architecture/ARCHITECTURE_STATE_SYNC_2026-09-17.md`
- `docs/architecture/ECOSYSTEM_RESEARCH_2026-09.md`
- `docs/security/PRIVACY_SECURITY_INVARIANTS_v0.2.md`

External findings remain research records. Local implementation status continues to be determined by repository code plus executed evidence.
