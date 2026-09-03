# Decision Register v0.2

Status: Proposed architecture reconciliation baseline
Date: 2026-09-03
Supersedes: `DECISION_REGISTER_v0.1.md` for new design decisions

## Decision classes

- **D** — Foundational architecture decision; requires explicit review to change.
- **I** — Security/safety invariant; must hold across implementations and profiles.
- **P** — Product/policy behavior; may vary by profile or user configuration.
- **T** — Technology choice; replaceable implementation mechanism.
- **E** — Experiment; not a commitment until evidence is recorded.
- **R** — Rejected design/assumption.

## Foundational decisions

| ID | Decision | Status |
|---|---|---|
| D-01 | `Llms-mcp-android` is a user-owned mobile agent runtime, not merely a chat or MCP client. | ACCEPTED |
| D-02 | The application owns the local agent-mediated control boundary; Android OS and external services retain their own authority domains. | ACCEPTED |
| D-03 | Models provide reasoning/selection but never authorize their own execution. | ACCEPTED |
| D-04 | **Activation** is a first-class entry concept shared by UI, automation, OS surfaces, models, and external clients. | ACCEPTED |
| D-05 | **Capability** is the primitive unit of controlled effect. | ACCEPTED |
| D-06 | **Action** is a reusable executable contract composed of one or more capability invocations; Actions may be deterministic, model-assisted, or hybrid. | ACCEPTED |
| D-07 | **Tool** is an exposure/interface mechanism for a model or client. It is not the canonical authority or semantic model. | ACCEPTED |
| D-08 | Activation may resolve directly to an Action without a model. Model-mediated selection is optional. | ACCEPTED |
| D-09 | Mission is optional durable intent; Task is bounded work; Run is a concrete execution instance. | ACCEPTED |
| D-10 | Policy, approval, and egress authorization are evaluated independently of model output and user preferences. | ACCEPTED |
| D-11 | Verification and Evidence are first-class execution outputs; model prose alone is not evidence. | ACCEPTED |
| D-12 | Remote execution does not transfer local authorization ownership. | ACCEPTED |
| D-13 | Model providers and interoperability protocols are replaceable adapters behind stable internal semantics. | ACCEPTED |
| D-14 | One runtime core supports Personal, Developer, and Product/Public profiles. | ACCEPTED |
| D-15 | Important control-plane state is durable and recoverable; execution attempts have explicit identity and terminal-state semantics. | ACCEPTED |

## Core semantic relationship

```text
Activation
   -> Intent (optional)
   -> Direct Action OR Model reasoning
   -> Action
   -> CapabilityInvocation(s)
   -> Policy / Approval / Egress
   -> Run
   -> Execute
   -> Observe
   -> Verify
   -> Evidence / Artifact
```

```text
Capability = primitive controlled effect
Action     = reusable execution contract
Tool       = exposure/interface
Model      = optional reasoning component
```

## Security invariants

| ID | Invariant |
|---|---|
| I-01 | Model output cannot grant, widen, or bypass permission. |
| I-02 | No effectful executor bypasses the control-plane policy boundary. |
| I-03 | A Tool schema does not constitute authorization. |
| I-04 | A user preference does not constitute authorization or approval. |
| I-05 | A CapabilityInvocation must have explicit identity, scope/effect context, and attribution. |
| I-06 | Approval is bound to the specific operation/context and cannot be replayed to widen authority. |
| I-07 | Protected data may leave the device only under an explicit local egress decision. |
| I-08 | Secrets remain outside ordinary Mission/Task/Run/model content and logs. |
| I-09 | Verification can reject a claimed success. |
| I-10 | Terminal execution state cannot be overwritten by stale callbacks/retries. |
| I-11 | Recovery must account for duplicate external effects and capability-specific idempotency. |
| I-12 | Activation source, authorizing identity, Action/Capability identity, and Run identity remain attributable. |
| I-13 | High-impact effects require appropriate policy controls and, where configured, explicit approval. |
| I-14 | Product/Public profiles cannot weaken foundational security invariants through UI configuration. |

## Product policies

| ID | Policy |
|---|---|
| P-01 | Chat is a primary surface, not the execution architecture. |
| P-02 | Quick Actions, widgets, notifications, automation, external invocation, and Android-native surfaces reuse the same Activation contract. |
| P-03 | Local-only, remote-allowed, and hybrid execution are valid policy modes. |
| P-04 | Preferences may rank or select options but never widen authority. |
| P-05 | Trusted/prebuilt Actions may be surfaced as one-tap operations for low-latency use. |

## Technology choices

| ID | Choice | Position |
|---|---|---|
| T-01 | DataStore | Candidate for ordinary settings; not a secret store. |
| T-02 | Android Keystore | Candidate primitive for protected credential material. |
| T-03 | Room/equivalent | Candidate durable structured state store. |
| T-04 | Official Kotlin MCP SDK | Preferred implementation substrate when native MCP adapter work begins. |
| T-05 | Android App Functions | Adapter candidate; current Jetpack artifact is `1.0.0-alpha11` as of 2026-08-26. |
| T-06 | Local model runtime (LiteRT-LM, ML Kit GenAI, etc.) | Provider implementation behind local model contract; no vendor lock-in. |
| T-07 | WorkManager | Candidate for supported persistent/deferred background work. |

## Required experiments

| ID | Experiment | Evidence required |
|---|---|---|
| E-01 | Deterministic Action without any model. | Measured latency and correctness versus model-mediated path. |
| E-02 | Model selects among policy-filtered Actions. | No authorization bypass; selection quality and token/latency measurements. |
| E-03 | Same Action invoked through Chat, direct UI, and automation. | One policy path; identical effect semantics. |
| E-04 | Composite Action using multiple Capabilities. | Scope, failure, partial completion, verification, and recovery semantics. |
| E-05 | Preference ranking versus policy constraints. | Preferences can affect choice but cannot change authorization. |
| E-06 | Process death/retry around an external Action. | No unintended duplicate effect; durable attempt/effect identity. |
| E-07 | Local versus remote ModelProvider behind same runtime contract. | Domain state and policy semantics unchanged. |
| E-08 | Real Android/App Functions integration. | Adapter can be removed without changing core semantics. |
| E-09 | Current MCP 2026-07-28 adapter path. | Protocol behavior remains outside core state/authority model. |

## Research-derived experiments

| ID | Experiment | Rationale |
|---|---|---|
| M-E1 | Bounded multi-step Action loop. | METATRON demonstrates that an agent can iteratively request more information; our version must preserve one policy boundary per invocation and explicit runtime budgets. |
| M-E2 | Raw versus derived observation compression. | METATRON compresses long tool output. Measure context/token reduction while preserving verification-critical facts and source provenance. |
| M-E3 | Typed Action parameters versus free-form command dispatch. | Test whether stable semantic Actions reduce invalid/injection-prone execution requests while maintaining task success. |
| M-E4 | Local-model continuation through the same runtime. | Preserve METATRON's local-first usability while keeping model provider replaceability. |
| M-E5 | Durable effect identity under retry/process death. | Convert stable identity from a deterministic primitive into actual duplicate-effect protection only after persistence/reconciliation exists. |
| M-E6 | Evidence provenance chain for derived observations. | Ensure summaries/compression remain traceable to authoritative raw observations/artifacts. |
| M-E7 | Evidence-to-report Action. | Carry METATRON's useful export workflow into our Artifact/Egress model without turning mutable reports into evidence. |

## Rejected / anti-goals

| ID | Rejected design | Reason |
|---|---|---|
| R-01 | LLM-centered architecture. | Makes probabilistic reasoning an authority boundary. |
| R-02 | Treat every Capability as a model Tool. | Loses distinction between internal effects and exposed interfaces. |
| R-03 | Require a model for every operation. | Adds latency/cost where deterministic execution is sufficient. |
| R-04 | Treat every Action as a monolithic workflow. | Encourages premature abstraction and catalog explosion. |
| R-05 | Mandatory Mission around every action. | Makes simple interactions unnecessarily stateful. |
| R-06 | Preferences as hidden permissions. | Preferences must never become an authorization bypass. |
| R-07 | MCP/App Functions as the canonical internal domain model. | External protocols evolve and have different trust/lifecycle semantics. |
| R-08 | General workflow engine, Capability Graph, swarm, plugin marketplace, or server tenancy in the core before measured need. | High complexity and exit cost without proven demand. |
| R-09 | Free-form model-authored shell/command execution as a core Action interface. | Typed parameters and bounded capabilities provide a stronger authorization and verification boundary. |
| R-10 | Treat model-compressed observations or model-generated risk ratings as authoritative evidence. | Compression and analysis are derived reasoning artifacts; authoritative evidence must remain attributable to observations/artifacts/verification. |

## Change control

Changing a D/I item requires an explicit architecture review. A T item may change provided D/I invariants remain satisfied. An E item may be promoted only after evidence is recorded. Product policies may change without architectural redesign when they remain inside the same authority/security envelope.
