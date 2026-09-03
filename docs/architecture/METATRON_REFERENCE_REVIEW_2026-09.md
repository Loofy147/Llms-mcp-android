# METATRON Reference Review — 2026-09

Status: Research input / architecture comparison
Date: 2026-09-03

This document evaluates `Loofy147/METATRON` as an external reference for `Llms-mcp-android`. It does not imply dependency adoption or architectural inheritance.

## 1. Why METATRON is relevant

METATRON is a local AI penetration-testing assistant built around a simple operational loop:

```text
collect tool output
    -> give evidence to local model
    -> model analyzes
    -> model requests more tools/search when needed
    -> collect results
    -> compress long observations
    -> repeat with a bounded loop
    -> parse structured result
    -> persist history/report
```

This is directly relevant to our thesis because it demonstrates a useful pattern: **the model can decide when more information is needed while the runtime executes bounded, pre-existing operations**.

The project currently uses Ollama with a local `metatron-qwen` model, several recon tools, an explicit nine-round tool-loop budget, structured vulnerability/exploit parsing, MariaDB-backed scan history, and PDF/HTML export. See the repository README and implementation files.

## 2. Patterns worth adopting

### M-01 — Bounded agentic loop

METATRON sets `MAX_TOOL_LOOPS = 9` and repeatedly alternates model analysis with tool execution.

Potential use in our runtime:

```text
Model request
   -> policy-filtered Action/Capability selection
   -> bounded execution budget
   -> Observation
   -> model continues or terminates
```

**Decision:** ADOPT PATTERN.

Constraint: the budget is a safety/performance control, not an authorization mechanism. Every effect still goes through local policy.

### M-02 — Local-first reasoning

METATRON is explicitly designed to run the model locally and avoid API keys/subscriptions for its main workflow.

Potential use:

```text
Local ModelProvider
      <-> same runtime contracts <-> Remote ModelProvider
```

**Decision:** ADOPT PATTERN at the product-policy level; provider implementation remains replaceable.

### M-03 — Long-observation compression

METATRON detects large tool outputs and asks the model to compress them before injecting them into later context.

The important architectural idea is not the extra model call; it is the distinction between:

```text
raw observation
      -> derived compact observation
      -> reasoning context
```

**Decision:** ADOPT PATTERN, but make compression explicitly derived/untrusted evidence. Compression must never replace the authoritative raw artifact when exact reconstruction matters.

### M-04 — Persistent execution history

METATRON stores scan sessions, vulnerabilities, fixes, exploit attempts, and summaries in a linked database and exposes a history UI.

Potential use:

```text
Run / Evidence / Artifact
        -> durable local store
        -> trace/history viewer
        -> explicit export
```

**Decision:** ADOPT PATTERN.

The storage model should be redesigned around our Run/Invocation/Evidence semantics rather than copied table-for-table.

### M-05 — Structured result extraction

The model produces a constrained textual format (`VULN`, `FIX`, `RISK_LEVEL`, etc.) which is then parsed into structured records.

The useful lesson is that a model should emit machine-consumable semantics when the runtime needs structured state.

**Decision:** ADOPT PATTERN, but prefer typed structured output/schema validation over free-form text parsing wherever our model/provider APIs permit it.

### M-06 — Selective tool execution

The interactive mode lets the user select which operations are run, while the automatic path has a conservative default set and keeps a noisy scan out of the default flow.

Potential use:

```text
available capabilities
    -> context/policy filter
    -> default safe set
    -> explicit expansion for stronger operations
```

**Decision:** ADOPT PATTERN.

This maps well to our distinction between installed capabilities, exposed capabilities, and executable capabilities.

### M-07 — Report/export as a first-class product operation

METATRON turns accumulated results into PDF/HTML reports.

Potential use:

```text
Evidence + Artifacts
      -> Report Action
      -> policy / egress
      -> exported artifact
```

**Decision:** ADOPT PATTERN for a later Artifact/Report layer.

## 3. Patterns to improve rather than copy

### M-08 — Free-form textual tool dispatch

METATRON lets the model emit strings such as `[TOOL: nmap -sV target]` and routes them through `run_tool_by_command()`.

Although the executable name is allowlisted, the arguments remain model-controlled and are passed to `subprocess.run()` as a parsed argument list. This is materially safer than `shell=True`, but it is still broader authority than our architecture should give a model.

**Our stronger form:**

```text
Model
  -> selects Action/Capability by stable identity
  -> supplies typed parameters
  -> local schema validation
  -> policy evaluation
  -> bounded executor
```

The model should not construct arbitrary system commands when a typed capability can represent the same intent.

**Decision:** REJECT free-form command dispatch as a core pattern.

### M-09 — AI-authored severity/risk as stored state

METATRON parses the model's risk rating and stores it directly.

Our stronger rule is:

```text
Model assessment = hypothesis / analysis
Observed facts   = evidence source
Verification     = support test
Final decision   = policy/product logic over evidence
```

A model may recommend severity; it must not silently turn its prose into verified fact.

**Decision:** ADOPT THE SEPARATION, not the direct trust model.

### M-10 — Web search as an unrestricted reasoning extension

METATRON lets the model request `[SEARCH:]` and uses routing heuristics to decide between general search, CVE lookup, exploit search, and mitigation search.

This is useful as a prototype but too implicit for our control plane.

**Our stronger form:**

```text
Search Capability
  -> explicit purpose
  -> destination policy
  -> data classification / egress decision
  -> bounded result set
  -> provenance
```

Search results should be attributable inputs, not trusted truth.

**Decision:** ADAPTER CANDIDATE with explicit egress/provenance semantics.

### M-11 — Database history as the audit model

METATRON has useful relational persistence, but editable/deletable records are product data rather than immutable execution evidence.

Our system needs two distinct concepts:

```text
mutable user/product state
        !=
immutable-at-least-in-principle execution evidence
```

**Decision:** ADOPT persistence; reject conflating mutable history with immutable evidence.

### M-12 — Operational credentials in application configuration

The current METATRON example includes a hard-coded MariaDB password in source/configuration instructions.

That is not a pattern to carry forward.

**Decision:** REJECT. Credentials must remain outside ordinary product state and source-controlled configuration.

## 4. What METATRON reveals about our own architecture

It strengthens several decisions we already made:

### 4.1 Model as planner, not executor

METATRON already behaves as though the model is a planning/analysis component that requests additional operations. Our architecture makes this explicit and adds an independent authorization boundary.

### 4.2 The Action idea is stronger than a Tool loop

METATRON's loop is expressed in terms of concrete tools. Our Action layer can capture the same convenience at a higher semantic level:

```text
METATRON:
Model -> [TOOL: nmap ...]

Our model:
Model -> Action: inspect_target
      -> typed CapabilityInvocation(s)
```

This should make repeated operations reusable across direct UI, automation, and model-mediated activation rather than making every model interaction synthesize low-level commands.

### 4.3 Evidence becomes more important as autonomy increases

The more the model can request follow-up operations, the more important it becomes to distinguish:

```text
request
-> authorization
-> invocation
-> raw observation
-> derived observation
-> verification
-> evidence
```

The current `CapabilityInvocation` work is therefore not bookkeeping; it is a prerequisite for safe agentic loops.

### 4.4 Compression needs provenance

A compressed observation is useful for context efficiency but should carry a relation to its source observation/artifact.

Candidate future shape:

```text
DerivedObservation {
    id
    sourceObservationIds[]
    method
    modelProvider?
    confidence?
    createdAt
}
```

This is a candidate, not yet a committed domain type.

## 5. Stronger references to compare against

METATRON should not be our only reference for these patterns.

### OpenHands Runtime

OpenHands separates agent-generated Actions from an execution runtime and returns structured Observations. Its current runtime architecture uses an action execution server, sandboxed execution, and an event stream between the agent and execution environment.

Why compare:

- action/observation protocol;
- runtime/executor separation;
- bounded execution environments;
- reproducibility and runtime isolation;
- event-driven tracing.

Source: https://github.com/OpenHands/docs/blob/main/openhands/usage/architecture/runtime.mdx

### OpenHands SDK v1 design

OpenHands V1 explicitly emphasizes strict separation between SDK/agent core, tools, workspace, and agent server, plus composable typed components and optional isolation.

Why compare:

- composability without making every concern a core authority;
- typed tool/model/context composition;
- lessons from configuration sprawl and mutable state.

Source: https://github.com/OpenHands/docs/blob/main/sdk/arch/design.mdx

### MobileAgent-Android / ClosePaw

For the mobile-specific part, these remain stronger than METATRON for perception, accessibility integration, takeover, app-specific trust policy, traces, and evaluation.

METATRON is stronger for a simple local analysis loop and persistent security-report workflow; mobile-agent projects are stronger for real device interaction.

## 6. Candidate architecture upgrade inspired by the comparison

The combined pattern we should aim for is:

```text
Activation
    ↓
Intent / Model reasoning (optional)
    ↓
Policy-filtered Action selection
    ↓
Typed parameters
    ↓
Independent authorization
    ↓
CapabilityInvocation
    ↓
Executor
    ↓
Raw Observation / Artifact
    ↓
Derived Observation (optional)
    ↓
Verification
    ↓
Evidence
    ↓
Run completion / bounded continuation
```

For iterative agentic work:

```text
Run
  ├─ Invocation 1 -> Observation 1
  ├─ Invocation 2 -> Observation 2
  ├─ ...
  └─ Verification -> continue / stop
```

The loop may continue only while explicit runtime budgets and policy conditions remain satisfied.

## 7. New experiments worth adding

| ID | Experiment | Evidence |
|---|---|---|
| M-E1 | Bounded multi-step Action loop | Same policy path across 1..N invocations; no authority expansion. |
| M-E2 | Raw vs compressed observations | Context size reduction without loss of required verification facts. |
| M-E3 | Typed Action parameters vs free-form tool command | Lower invalid/injection-prone invocation rate and comparable task success. |
| M-E4 | Local model continuation | Local provider can request follow-up Actions under the same runtime contract. |
| M-E5 | Durable invocation/effect recovery | Process death and retry preserve effect identity and avoid duplicate effects. |
| M-E6 | Evidence provenance chain | Derived observations remain traceable to raw observations/artifacts. |
| M-E7 | Report Action | Evidence can produce an exportable artifact without exposing secrets or bypassing egress policy. |

## 8. Current conclusion

METATRON contributes real ideas, but its greatest value for us is as a lower-level prototype that makes several requirements concrete:

- a useful local agent loop can be simple;
- the model can decide when to gather more information;
- history and reports turn an agent from a transient chat into a persistent operational product;
- observation compression can reduce reasoning cost;
- the model should be allowed to request operations, not silently own the authority to execute arbitrary commands.

Our architecture should therefore aim to **surpass METATRON at the control boundary**, while preserving its useful product ergonomics:

```text
METATRON strength:
local model + bounded tool loop + history + reports

Our target:
local/remote interchangeable reasoning
+ bounded Action loop
+ typed CapabilityInvocations
+ independent policy/approval/egress
+ durable Run/Evidence
+ verification/provenance
+ Android-native activation surfaces
```
