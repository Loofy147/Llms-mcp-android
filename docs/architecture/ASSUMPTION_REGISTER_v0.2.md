# Assumption Register v0.2

Status: Architecture reconciliation baseline
Date: 2026-09-03

This register keeps hypotheses separate from decisions. Nothing here is treated as a security or architecture fact until its evidence gate is passed.

## Provisional assumptions

| ID | Hypothesis | Confidence | Test |
|---|---|---:|---|
| A-01 | Prebuilt deterministic Actions can materially reduce latency and unnecessary model usage for repeated/common operations. | Medium | Compare direct vs model-mediated paths on real devices. |
| A-02 | Models can choose useful Actions more reliably when exposed to a small semantic catalog instead of low-level capability plumbing. | Medium | Controlled task benchmark. |
| A-03 | Action contracts can cover most reusable operations without requiring a general workflow engine. | Medium | Build several primitive/composite Actions from distinct domains. |
| A-04 | One Action model can support human, automation, and model-mediated activation without semantic drift. | Medium-high | Same Action through three activation sources. |
| A-05 | Mission/Task/Run separation is valuable for long-running or recoverable work, while short actions can use Run without Mission. | High | Recovery experiment with and without Mission. |
| A-06 | Provider-neutral reasoning interfaces can preserve policy/evidence semantics across local and remote models. | High | Provider substitution experiment. |
| A-07 | A compact catalog plus dependency references is sufficient before a full Capability Graph becomes necessary. | Medium | Track composition complexity during Action experiments. |
| A-08 | User preferences can improve action/model selection while remaining strictly subordinate to policy. | High | Policy/preference conflict tests. |
| A-09 | Scoped skills can improve model action selection without becoming an authority channel. | Medium | Skill-loading experiment with adversarial policy tests. |

## Explicitly rejected assumptions

- The model is the authority.
- Every useful operation must be exposed as a Tool.
- Every operation requires a model.
- Local storage automatically means private.
- A UI switch is a security control.
- A remote execution target may redefine local authorization.
- A Mission must wrap every Run.
- Preferences can be treated as permissions.
- MCP or App Functions should define the internal domain.
- A larger number of agents implies better behavior.
- All external repositories should be merged into this project.
- A full graph/workflow abstraction is justified before real composition pressure exists.

## Open questions

| ID | Question | Gate |
|---|---|---|
| Q-01 | What is the minimum Action contract that is useful to humans, automation, and models without excessive metadata? | Before catalog stabilization. |
| Q-02 | Which Action types should be visible to models by default, and which should remain internal? | Before broad model exposure. |
| Q-03 | How should composite Actions report partial completion and compensation/rollback? | Before high-impact composite Actions. |
| Q-04 | What data-classification vocabulary best supports local egress decisions? | Before remote private-data workflows. |
| Q-05 | What approval token/context prevents replay and scope widening? | Before destructive Actions. |
| Q-06 | What evidence depth is required per effect class? | Before production catalog. |
| Q-07 | Which local model runtimes deliver acceptable quality, memory, thermals, and latency on target devices? | Before local-first profile. |
| Q-08 | How much of current MCP 2026-07-28 belongs in an adapter versus the domain? | Before native MCP integration. |
| Q-09 | How should credential lifecycle/refresh be modeled without leaking secrets into state or prompts? | Before OAuth/short-lived credentials. |
| Q-10 | What is the smallest workload that actually defeats a single runtime and justifies multi-agent coordination? | Before multi-agent work. |

## Evidence rule

Promotion requires reproducible tests, direct platform/protocol documentation, real integration behavior, or measured operational evidence. Recommendations, popularity, and intuition can motivate experiments but do not establish architecture facts.
