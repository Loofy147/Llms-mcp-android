# Decision Register v0.1

Status: **Proposed baseline**  
Date: 2026-09-03

This register separates decisions from assumptions, implementation choices, experiments, and rejected ideas. A decision is not silently changed because a newer library, model, or protocol becomes available.

## Decision classes

- **D — Foundational decision:** changes require architectural review.
- **I — Invariant:** must hold across implementations and profiles.
- **P — Policy:** behavior may vary by profile or user configuration.
- **T — Technology choice:** replaceable implementation mechanism.
- **E — Experiment:** must be validated before becoming a decision.
- **R — Rejected:** intentionally not adopted under the present architecture.

## Foundational decisions

| ID | Decision | Rationale | Consequence | Status |
|---|---|---|---|---|
| D-01 | `Llms-mcp-android` is a user-owned mobile agent runtime, not merely a chat/MCP client. | The long-term use cases include personal use, development, and productization. | Chat becomes one activation surface; the runtime/control plane become first-class. | ACCEPTED |
| D-02 | Authority remains on the device even when reasoning or execution is remote. | Privacy and user control must survive provider changes and remote execution. | Remote services never become the source of authorization truth. | ACCEPTED |
| D-03 | Privacy is enforced as a data-egress boundary. | Keeping a secret locally is insufficient if private content can leave the device implicitly. | Data classification and destination policy must precede remote invocation. | ACCEPTED |
| D-04 | Models may propose; models do not authorize. | LLM output is probabilistic and must not be the security boundary. | Policy/consent checks remain outside the model. | ACCEPTED |
| D-05 | Capability is the internal unit of effect. | Generic tools are too coarse for scope, effect, data, and approval policy. | Every effectful integration is represented through a capability contract. | ACCEPTED |
| D-06 | External protocols are adapters, not the internal architecture. | MCP, HTTP, Android APIs, and local transports have different lifecycle and trust semantics. | Internal capability semantics remain stable as protocols evolve. | ACCEPTED |
| D-07 | Mission, Task, and Run are separate concepts. | User intent, bounded work, and concrete execution have different lifecycles. | Mission may span Runs; Run ownership is never redefined by Mission. | ACCEPTED |
| D-08 | All activation surfaces converge on the same control plane. | Separate security logic for Chat, Automation, Widgets, etc. would create bypass paths. | Activation is normalized before policy evaluation. | ACCEPTED |
| D-09 | Evidence and Verification are first-class. | Successful model text is not proof that an action occurred or a postcondition holds. | Results and artifacts need attribution and verification. | ACCEPTED |
| D-10 | Important agent state is durable and recoverable. | In-memory state is not sufficient for mobile process death or long-running work. | Structured state must be persisted independently from UI state. | ACCEPTED |
| D-11 | Execution is bounded by multiple budgets. | Step count alone does not constrain cost, network, time, or data egress. | Budget contracts include time, steps, model cost/tokens, network/capability use, and egress as applicable. | ACCEPTED |
| D-12 | One core supports Personal, Developer, and Product profiles. | Separate cores would duplicate authority semantics and create divergence. | Profiles may change policy/defaults but not fundamental security boundaries. | ACCEPTED |
| D-13 | Model providers are replaceable. | Provider lock-in would turn vendor SDKs into the application architecture. | Runtime depends on provider-neutral contracts. | ACCEPTED |
| D-14 | Background execution must follow Android OS constraints. | Android does not provide an unrestricted persistent agent daemon model. | Foreground execution, user triggers, WorkManager, notifications, and OS policy are explicit design surfaces. | ACCEPTED |

## Security and safety invariants

| ID | Invariant |
|---|---|
| I-01 | No model response can grant, expand, or bypass its own permissions. |
| I-02 | No capability executor may bypass policy/consent checks for an effectful operation. |
| I-03 | Remote execution does not transfer authorization ownership from the device. |
| I-04 | Secrets are not persisted in ordinary conversation/mission/evidence state. |
| I-05 | A model claim is not promoted to evidence solely because the model produced it. |
| I-06 | A failed, denied, or cancelled Run must not become successful through state mutation after terminal settlement. |
| I-07 | Activation source, authorizing identity, capability identity, and execution identity must remain attributable for auditable actions. |
| I-08 | Destructive or high-impact effects require explicit policy permission and, where configured, explicit approval. |
| I-09 | Private/sensitive data may only cross the device boundary when local policy authorizes that destination and purpose. |
| I-10 | Public/Product profiles cannot bypass controls merely through UI feature flags. |

## Product decisions

| ID | Decision |
|---|---|
| P-01 | Chat is the default human interaction surface, but not the only one. |
| P-02 | Quick Actions, notifications, widgets, automation, external invocation, and Android-native surfaces should be able to reuse the same activation contract. |
| P-03 | Local-only, remote-allowed, and hybrid execution are all valid modes. |
| P-04 | The product may expose simpler workflows while retaining the full internal control model. |

## Technology choices currently permitted, not frozen

| ID | Candidate | Position |
|---|---|---|
| T-01 | DataStore | Preferred for ordinary application settings; not a secret store. |
| T-02 | Android Keystore | Preferred primitive for protecting locally held cryptographic material/secrets. |
| T-03 | Room | Preferred candidate for durable structured agent state when relational persistence is useful. |
| T-04 | MCP | Supported interoperability adapter; not the authority model. |
| T-05 | Android App Functions | Optional Android-native integration surface; currently experimental. |
| T-06 | WorkManager | Preferred candidate for appropriate persistent/deferred background work. |
| T-07 | llama.cpp / other local runtimes | Implementation options behind a local model provider, not architectural commitments. |

## Experiments required before stronger commitments

| ID | Experiment | Success criterion |
|---|---|---|
| E-01 | Implement one local capability through the full policy → execution → verification → evidence path. | Denied/approval-required paths never execute; allowed path is attributable and repeatable. |
| E-02 | Implement one remote capability without moving authority to the server. | Egress policy is evaluated locally and remote result maps back to local evidence. |
| E-03 | Persist Mission/Task/Run state and recover after simulated process death. | No state corruption or duplicate effect after recovery. |
| E-04 | Compare a local model and a remote model behind one provider-neutral contract. | Runtime semantics remain unchanged when the provider changes. |
| E-05 | Exercise multiple activation surfaces through one ActivationRequest. | No surface-specific policy bypass. |
| E-06 | Validate App Functions as an adapter without making them a core dependency. | Feature can be disabled/removed without changing the control-plane model. |
| E-07 | Validate current MCP 2026-07-28 interoperability where needed. | Adapter follows current stateless/task/auth semantics without importing transport state into core domain. |

## Rejected decisions / anti-goals

| ID | Rejected idea | Reason |
|---|---|---|
| R-01 | Build this as an Android clone of Agora. | Agora provides reusable patterns, but the target architecture is user-control-centered and mobile-native. |
| R-02 | Make Anthropic's MCP connector the core MCP implementation. | That makes the external provider the protocol executor and moves too much control away from the device. |
| R-03 | Let the LLM directly call arbitrary executors. | It collapses reasoning and authorization boundaries. |
| R-04 | Treat every capability as a generic `ToolRegistry` entry. | Generic naming does not provide enough policy information. |
| R-05 | Create a permanent unrestricted Android agent daemon. | Conflicts with the platform execution model and introduces unnecessary background risk. |
| R-06 | Start with swarm/multi-agent orchestration. | Adds coordination complexity before single-agent control/evidence semantics are proven. |
| R-07 | Merge all related repositories into the Android application. | Integration should be through capability adapters/services until shared contracts are proven. |
| R-08 | Create a ProjectRegistry/ProjectAdapter federation before multiple real consumers exist. | The abstraction must be extracted from demonstrated shared invariants, not speculation. |
| R-09 | Equate local storage with privacy. | Backup, telemetry, logs, exports, and egress can still violate privacy. |
| R-10 | Require a planner for every interaction. | Direct, bounded actions do not need a heavyweight planning stage. |
| R-11 | Use UI feature flags as the primary security boundary. | Security must be enforced below the UI. |

## Change-control rule

A change to a **D** or **I** item requires an explicit architecture review and a new decision record. A **T** item may change through implementation work provided no D/I invariant is violated. An **E** item becomes a D/T only after evidence is recorded.
