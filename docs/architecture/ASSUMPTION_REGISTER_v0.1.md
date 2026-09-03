# Assumption, Rejection, and Open-Question Register v0.1

Date: 2026-09-03  
Status: **Architecture review baseline**

This document exists to prevent unverified beliefs from silently becoming architecture.

## A. Assumptions accepted provisionally

| ID | Assumption | Evidence / rationale | Confidence | Required action |
|---|---|---|---|---|
| A-01 | A mobile device can be the authority boundary while reasoning/execution happens remotely. | Authorization and egress decisions can be made locally before remote calls. | High | Implement and test end-to-end. |
| A-02 | One runtime can support private use and product profiles. | Differences can be represented as policy/profile configuration rather than separate cores. | High | Validate with policy fixtures. |
| A-03 | Capability abstraction can unify Android, local, HTTP, MCP, and remote integrations. | Each can expose a normalized invocation/result contract. | Medium-high | Prove with two distinct adapters. |
| A-04 | Mission/Task/Run separation is useful for long-running agent behavior. | User intent, bounded work, and concrete execution have distinct failure/recovery semantics. | High | Implement durable minimal model. |
| A-05 | Evidence/verification can remain provider-neutral. | Evidence can be sourced from executor observations and artifacts rather than model-specific structures. | High | Define schema and tests. |

## B. Assumptions explicitly rejected

### A-R01 — "The LLM is the agent authority"

Rejected. The model is a reasoning component. It is not trusted to authorize itself.

### A-R02 — "MCP should define the whole architecture"

Rejected. MCP is an interoperability mechanism. The application needs its own identity, policy, activation, state, and evidence semantics.

### A-R03 — "Every action is a Tool"

Rejected. A capability may be a native Android function, a file operation, a model call, a local process, an HTTP API, or an MCP invocation. The common property is controlled effect, not tool syntax.

### A-R04 — "A planner is mandatory"

Rejected. Planning is conditional. Direct bounded actions should not pay the complexity cost of a planner.

### A-R05 — "Mission should own another execution state machine"

Rejected. Mission is a control-plane envelope. Execution instances remain Runs.

### A-R06 — "Permanent background daemon is the natural mobile agent model"

Rejected. Android background execution is platform-governed. The runtime must use supported foreground/background mechanisms rather than assuming unrestricted persistence.

### A-R07 — "Local persistence automatically means private"

Rejected. Backup, telemetry, diagnostics, exports, and network egress all remain privacy surfaces.

### A-R08 — "More agents automatically produce a better agent"

Rejected. Multi-agent coordination is deferred until a concrete workload proves that the added coordination is worth its complexity and failure modes.

### A-R09 — "All related projects should be merged into this repository"

Rejected. Repositories are capability/service sources. Code is integrated only where a shared contract and ownership boundary justify it.

### A-R10 — "Public product mode can simply expose the same capabilities"

Rejected. Product profiles can share the core, but policy must constrain capability availability, scopes, data egress, and effects.

### A-R11 — "Remote execution means surrendering control"

Rejected. Remote execution is compatible with local authority when admission, egress, identity, and evidence remain locally governed.

### A-R12 — "UI hiding is a security mechanism"

Rejected. UI controls are convenience and consent surfaces. Enforcement must happen below UI.

## C. Open questions

| ID | Question | Why it matters | Resolution gate |
|---|---|---|---|
| Q-01 | What is the minimum durable state needed to recover an in-flight Mission after process death? | Determines persistence model and recovery semantics. | Before long-running Missions. |
| Q-02 | What exact data-classification vocabulary should govern egress? | Too coarse loses privacy control; too fine becomes unusable. | Before remote/private hybrid mode. |
| Q-03 | How should user approval be represented so approval cannot be replayed or widened? | Approval is part of the authority boundary. | Before destructive capabilities. |
| Q-04 | How should capability scope be represented for files, apps, network destinations, and remote services? | Scope is the main control surface for least privilege. | Before broad capability exposure. |
| Q-05 | What evidence level is sufficient for each effect type? | Not every read/write/remote action needs identical verification. | Before production capability catalog. |
| Q-06 | Which local model runtime gives the best reliability/resource tradeoff on target devices? | Device constraints vary. | Before local-first model profile. |
| Q-07 | Which parts of current MCP 2026-07-28 should be supported directly versus only through an adapter? | Avoids accidental coupling to protocol details. | Before shipping native MCP client/server interoperability. |
| Q-08 | Should App Functions be a first-class integration path or an optional compatibility adapter? | API remains alpha and may evolve. | After a real cross-app use case. |
| Q-09 | What is the correct credential lifecycle for OAuth, API keys, and short-lived tokens? | Secret storage and refresh semantics affect privacy and UX. | Before remote integrations beyond static keys. |
| Q-10 | Do we need a full Capability Graph, or is a registry + dependency references enough? | A graph adds complexity and storage semantics. | Only after real capability composition requires it. |
| Q-11 | What is the smallest multi-agent use case that cannot be represented as one bounded agent? | Prevents premature swarm architecture. | Before any multi-agent core work. |
| Q-12 | What distribution/security posture is required for a public product? | Personal builds may legitimately expose capabilities that public builds must not. | Before public release. |

## D. Evidence policy for changing assumptions

An assumption may be promoted only when backed by one or more of:

- deterministic unit/integration tests;
- reproducible runtime experiments;
- direct platform documentation;
- a real integration with observed behavior;
- measured operational evidence.

A model recommendation, blog post, or intuition can motivate an experiment but is not sufficient evidence by itself.
