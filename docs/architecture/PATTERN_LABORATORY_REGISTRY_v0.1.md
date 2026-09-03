# Pattern Laboratory Registry v0.1

## Purpose

This registry treats external projects as **pattern laboratories** rather than templates to copy. A laboratory is valuable when it exposes a concrete architectural solution, invariant, failure mode, or operating model that can improve the runtime without importing unnecessary coupling.

The registry is deliberately evidence-oriented. A project may contribute patterns while being unsuitable as a dependency or product model.

## Operating rule

For every external project we ask:

1. What problem does it solve?
2. Which boundary or mechanism is genuinely strong?
3. What assumption makes that mechanism work?
4. Can the pattern survive in our mobile, user-owned control-plane model?
5. What security or correctness property would be lost by adopting it?
6. What is the smallest experiment that can validate the useful part?

A finding must end in one of four dispositions:

- **ADOPT** — directly compatible with the core invariants.
- **ADAPT** — valuable pattern, but semantics or boundaries must be redesigned.
- **REJECT** — useful lesson, but adoption would weaken the architecture.
- **EXPERIMENT** — promising but insufficiently evidenced.

## Registry

| ID | Laboratory | Domain | Primary pattern under study | Disposition | Evidence / next step |
|---|---|---|---|---|---|
| PL-01 | OpenHands | Agent runtime | Runtime/executor separation, bounded execution, observations | ADAPT | Preserve backend/runtime authority boundary; map to Action/CapabilityInvocation and Android execution constraints. |
| PL-02 | METATRON | Security / local agent | Local model loop, raw→derived observations, persistent scan/report history | ADAPT | M-E1..M-E7 in `METATRON_REFERENCE_REVIEW_2026-09.md`. |
| PL-03 | ClosePaw | Mobile agent safety | Perception, trust boundary, takeover/trace concepts | EXPERIMENT | Define mobile safety/evidence primitives without importing product-specific architecture. |
| PL-04 | MobileAgent-Android | Android agent | Screenshot/accessibility perception and explicit reflection | EXPERIMENT | Measure usefulness and authority implications for observation adapters. |
| PL-05 | Droidrun / MobileRun | Mobile runtime | Provider abstraction, perception, credential/tracing concerns | ADAPT | Compare provider-neutral model boundary and trace semantics with our runtime contracts. |
| PL-06 | Google ADK Kotlin | Agent framework | Agent/session/tool/memory/artifact decomposition | ADAPT | Reference semantics only; avoid framework lock-in in the core. |
| PL-07 | Kotlin MCP SDK | Interoperability | Typed protocol integration and MCP transport/model exposure | EXPERIMENT | Build an internal MCP adapter after durable control/evidence gates. |
| PL-08 | LiteRT-LM / AI Edge | Local inference | On-device model runtime and model metadata | EXPERIMENT | Real-device benchmark: latency, memory, context, lifecycle. |
| PL-09 | Termux / Termux:API | Local execution | High-power local Android execution surface | ADAPT | Optional adapter only; every effect remains behind Capability + Policy. |
| PL-10 | Temporal / Restate | Durable execution | Durable state, retries, recovery, replay/idempotency semantics | ADAPT | Translate semantics into a mobile-scale minimal persistence model; do not embed the platforms. |
| PL-11 | LangGraph | Agent orchestration | Explicit state-machine / checkpoint composition | REJECT for now | Useful reference for durable graphs; premature as the core execution abstraction. |
| PL-12 | OpenTelemetry / Phoenix | Observability | Structured traces, spans, provenance and analysis | ADAPT | Establish evidence/tracing semantics without treating telemetry as authority. |
| PL-13 | OpenAI Agents SDK | Agent governance | Approvals, guardrails, resumable runs | ADAPT | Reproduce semantics with our policy/approval contracts; provider remains replaceable. |
| PL-14 | Pydantic AI | Agent composition | Typed tools, deferred approvals, durable patterns | ADAPT | Use as contract-design reference; Kotlin core remains independent. |
| PL-15 | A2A | Agent interoperability | Remote-agent task/message federation | EXPERIMENT | Defer until local authority and run/evidence invariants are stable. |
| PL-16 | AG-UI | Agent UI interoperability | External UI/event protocol | EXPERIMENT | Candidate external surface after activation/control boundary is stable. |
| PL-17 | Android sandbox / platform controls | Mobile security | Defense in depth and OS authority boundary | ADOPT | Foundational constraint; never represent OS authority as application authority. |
| PL-18 | Android on-device GenAI / ML Kit | Mobile AI | Device-local model/capability adapters | EXPERIMENT | Evaluate as replaceable providers/capabilities, not core runtime dependencies. |

## Cross-laboratory pattern matrix

| Pattern | Relevant laboratories | Our interpretation |
|---|---|---|
| Bounded agent loop | METATRON, OpenHands | Model continuation must remain bounded and policy-filtered. |
| Typed action boundary | OpenHands, Pydantic AI | Prefer stable Action IDs + typed parameters over free-form command text. |
| Runtime/executor separation | OpenHands | Agent reasoning is not execution authority. |
| Raw vs derived observation | METATRON, observability systems | Preserve raw evidence; derived summaries are non-authoritative unless independently verified. |
| Durable run semantics | Temporal, Restate, OpenHands | Run identity, retry, recovery and terminal-state rules must be explicit. |
| Human approval | OpenAI Agents SDK, Pydantic AI | Approval is an operation-scoped control decision, not model output or preference. |
| Mobile perception | ClosePaw, MobileAgent-Android, Droidrun | Perception is input/observation, never authority. |
| Local inference | METATRON, LiteRT-LM, AI Edge | Local model is a provider option; the core must remain model-optional. |
| Interoperability | MCP, A2A, AG-UI | Protocol is an adapter boundary, not the canonical authority model. |
| Observability | OpenTelemetry, Phoenix | Traceability supports evidence and diagnosis; telemetry cannot authorize effects. |
| OS security boundary | Android platform | Application authority is subordinate to platform authority. |

## Explicit anti-patterns tracked

These are not merely implementation details; they are reusable negative knowledge.

| Anti-pattern | Why rejected |
|---|---|
| Free-form model-authored shell/command execution | Tool text becomes an oversized authority surface and weakens typed policy/evidence boundaries. |
| Model-generated severity/risk treated as authoritative | A model assessment is analysis/hypothesis, not proof of an observed effect or vulnerability. |
| Mutable history treated as immutable evidence | Editable database records do not by themselves provide trustworthy execution provenance. |
| Credentials in source/config | Secrets must be isolated from ordinary mission/task/run/model content and logs. |
| Protocol schema treated as authorization | Interoperability and permission are separate concerns. |
| Universal workflow engine before demonstrated need | Adds state, recovery, scheduling and debugging complexity before workload evidence justifies it. |
| Capability graph as mandatory core | A compact catalog + explicit dependency references should be tested first. |
| Multi-agent swarm as default architecture | Adds coordination and failure surfaces before a concrete workload requires it. |

## Research status model

Each laboratory finding should be recorded with:

- **Pattern** — the reusable mechanism.
- **Boundary** — where the mechanism starts/stops.
- **Invariant** — what must remain true if adopted.
- **Evidence level** — observation, code inspection, local experiment, real-device test, or production evidence.
- **Disposition** — ADOPT / ADAPT / REJECT / EXPERIMENT.
- **Experiment ID** — when validation is still open.
- **Decision ID** — when the finding changes architecture policy.

## Guardrail

The registry must not become a feature backlog. A laboratory entry is useful only when it changes one of:

- an architectural decision;
- a security/correctness invariant;
- a measurable experiment;
- an implementation contract;
- or a documented rejection.

Otherwise it remains research notes outside the core.

## Current priority laboratories

The next research sequence is intentionally narrow:

1. **Durable execution** — Temporal / Restate semantics translated into the smallest viable mobile model.
2. **Mobile safety/perception** — ClosePaw + MobileAgent + Droidrun comparisons.
3. **Interoperability** — MCP boundary after control/evidence persistence is stable.
4. **Local inference** — LiteRT-LM / Android on-device options under real-device measurements.
5. **External UI / remote agents** — AG-UI / A2A only after local invariants are proven.

The registry is therefore a research-control mechanism, not a commitment to integrate any listed project.