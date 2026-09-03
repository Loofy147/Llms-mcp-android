# Ecosystem Research & Reusable Patterns — 2026-09

Date: 2026-09-03  
Status: **Research input to architecture review; not an implementation commitment**

This document records external projects, specifications, and platform work that can provide real implementation value to `Llms-mcp-android`. The purpose is selective extraction of proven ideas, not dependency accumulation or wholesale copying.

## 1. Evaluation rule

For every external project, classify its value as:

- **ADOPT PATTERN** — a concept directly strengthens the core architecture.
- **ADAPTER CANDIDATE** — useful behind an internal interface.
- **REFERENCE** — useful for design/test comparison but not suitable as a dependency.
- **EXPERIMENT** — promising but must be tested on target devices/workloads.
- **REJECT** — conflicts with the project's ownership, privacy, or authority model.

External popularity is not evidence of architectural fit. A project must demonstrate a concrete invariant, capability, or implementation technique we can reuse.

## 2. Highest-value findings

### 2.1 Android AppFunctions — native capability exposure

Source: Android platform documentation and official samples.

- Android AppFunctions allows applications to expose functions to the Android intelligence system as structured, type-safe functions.
- Android describes AppFunctions as an Android-native equivalent to tools in MCP and as a way for apps to behave like local MCP servers.
- The API is still experimental; current guidance requires target/compile levels newer than the prototype baseline.

Value for us:

**ADAPTER CANDIDATE.** Treat AppFunctions as one native activation/capability surface over our internal `Capability` contract. Do not let Android AppFunctions become the source of authority or the canonical capability model.

Important consequence:

```text
Our Capability
      -> AppFunctions adapter
      -> Android intelligence system
```

rather than:

```text
AppFunctions
      -> our architecture
```

Sources:

- https://developer.android.com/ai/appfunctions
- https://developer.android.com/agents/skills/device-ai/appfunctions/skill
- https://github.com/android/appfunctions

### 2.2 LiteRT-LM — serious local model runtime

Source: Google AI Edge.

LiteRT-LM is an open-source edge inference framework targeting Android and other platforms, with CPU/GPU support on Android and a Kotlin API. The project exposes a production-oriented engine rather than a simple demo abstraction. It supports `.litertlm` model artifacts and provides Android integration guidance.

Value for us:

**EXPERIMENT / ADAPTER CANDIDATE.** It should be evaluated as a LocalModelProvider implementation. It should not become part of the domain model.

Important facts:

- Android CPU/GPU/NPU support is advertised, although NPU availability is hardware/vendor dependent.
- Kotlin/Gradle integration exists.
- Model loading can take meaningful time and native crashes/performance differences exist on real devices, so lifecycle and resource tests are mandatory.

Sources:

- https://github.com/google-ai-edge/LiteRT-LM
- https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md

### 2.3 Google AI Edge Gallery — model management and evaluation reference

Source: Google AI Edge Gallery.

The Gallery is a real Android application for running local generative models and includes model discovery/import and agent-oriented experiences. Its model allowlist also demonstrates that a model catalog can carry machine-readable constraints such as model size, minimum device memory, modality, context length, accelerator preferences, and supported task types.

Value for us:

**REFERENCE + ADOPT PATTERN.** The model registry should eventually include device/resource metadata rather than only `modelName`.

Potential internal model metadata:

```text
model_id
provider
local_or_remote
size
required_memory
modalities
context_limit
accelerators
estimated_cost
availability
quality_profile
```

Sources:

- https://github.com/google-ai-edge/gallery
- https://github.com/google-ai-edge/gallery/blob/main/model_allowlists/1_0_15.json

### 2.4 Google ADK Kotlin — useful comparison for an Android agent runtime

Source: Google ADK Kotlin.

ADK Kotlin now exposes agent, model, tool, session, memory, artifacts, runner, A2A, LiteRT-LM, Firebase AI, and ML Kit integrations. Its Android examples demonstrate persisted Room-backed sessions, skills loaded from assets, on-device Gemini Nano, LiteRT-LM tool calling, and cloud agents.

Value for us:

**REFERENCE, not a core dependency by default.** It is especially useful as a comparative implementation for:

- agent/session boundaries;
- on-device/cloud model interchangeability;
- artifact handling;
- Android-specific local model integration;
- skills packaging;
- runner semantics.

We should avoid importing an entire second agent framework when our project already has explicit control-plane requirements that the application must own.

Sources:

- https://github.com/google/adk-kotlin
- https://github.com/google/adk-kotlin/blob/main/examples/android/README.md

### 2.5 Official Kotlin MCP SDK — native protocol implementation option

Source: Model Context Protocol official Kotlin SDK, maintained with JetBrains.

The SDK provides client/server modules, Kotlin Multiplatform support, Streamable HTTP, SSE, WebSocket, STDIO, MCP primitives, capabilities, tools, resources, prompts, logging, pagination, roots, and sampling.

Value for us:

**ADAPTER CANDIDATE.** When we eventually implement app-owned MCP client/server capability adapters, use the official SDK rather than maintaining a home-grown MCP parser/protocol stack.

Crucial boundary:

```text
Internal Capability Contract
          |
          +--> MCP adapter (official SDK)
```

The SDK must not define our Mission/Task/Run/Policy/Evidence model.

Source:

- https://github.com/modelcontextprotocol/kotlin-sdk

### 2.6 ClosePaw — strongest mobile-agent reference for safety and perception

ClosePaw is an open-source Android phone-use agent with accessibility-based perception/actions, optional screenshots, background virtual-display operation through Shizuku, pluggable LLMs, per-app approval, hard blocks for sensitive apps, traces on device, skills, an AndroidWorld evaluation harness, and an autotune workflow.

Value for us:

**REFERENCE + ADOPT PATTERNS.** This is one of the most directly relevant repositories to study in detail.

Patterns worth extracting:

- app-level trust policy;
- hard-blocked sensitive application classes;
- user-visible pause/takeover;
- per-app approval modes;
- on-device traces;
- separate perception platform from agent loop;
- AndroidWorld-style repeatable evaluation;
- skills loaded by scope rather than always injecting all instructions.

Patterns we should not copy blindly:

- its exact tool taxonomy;
- its platform-specific Shizuku assumptions;
- any UI-specific behavior that bypasses our Capability/Policy boundary.

Source:

- https://github.com/imoonkey/closepaw

### 2.7 MobileAgent-Android — vision/accessibility action loop

This Android-native project implements a vision-driven agent loop with screenshot capture, accessibility-tree UI detection, Manager/Executor/Reflector/Notetaker roles, multi-model support, action primitives, and step-by-step traces.

Value for us:

**REFERENCE.** It validates that a mobile agent benefits from combining the accessibility tree with screenshots and from having an explicit post-action reflection step.

Most valuable idea for our architecture:

```text
perception
   -> action
   -> observation
   -> verification/reflection
```

That maps directly to our Evidence/Verification model without requiring a four-agent system.

Source:

- https://github.com/GiggleWang/MobileAgent-Android

### 2.8 Droidrun / MobileRun — cross-platform phone-use and trace model

Droidrun exposes natural-language mobile automation with multiple model providers, accessibility + screenshot perception, custom tools, credentials, and trace integrations. It separates a local framework from a managed cloud offering.

Value for us:

**REFERENCE.** Useful for studying:

- provider-agnostic model selection;
- credential handling;
- visual + structural perception;
- trace/export boundaries;
- local versus managed deployment separation.

We should preserve our stronger local-authority semantics rather than copying its deployment model.

Sources:

- https://github.com/appwiz/droidrun
- https://github.com/droidrun/mobilerun

### 2.9 OmniBot — breadth reference for mobile agent product surface

OmniBot combines on-device Android Kotlin/Flutter, skills, browser access, MCP, Android system tools, scheduling, calendars, file/workspace access, terminal access, and short/long-term memory.

Value for us:

**REFERENCE.** It demonstrates the product breadth users may eventually expect from a serious mobile agent. It is useful for discovering future capability categories but should not drive our core architecture toward feature accumulation.

Source:

- https://github.com/omnimind-ai/OmniBot

### 2.10 Knotwork — local-first pipeline composition

Knotwork presents an Android local-first agent using LiteRT-LM, with explicitly assembled typed pipeline blocks, optional cloud reasoning, tool calls, routing, decomposition, evaluation, and user confirmation for risky actions.

Value for us:

**EXPERIMENT / REFERENCE.** The important reusable idea is explicit workflow composition rather than an opaque monolithic agent loop.

This may inform a future `Workflow`/`Pipeline` abstraction, but it must remain subordinate to our Capability/Policy/Evidence invariants.

Source:

- https://github.com/alexeyw/knotwork

### 2.11 Termux + Termux:API — optional local execution bridge

Termux provides an Android terminal/Linux environment; Termux:API exposes Android device functionality to command-line programs.

Value for us:

**ADAPTER CANDIDATE / OPTIONAL POWER USER MODE.** A Termux bridge could provide Python/git/curl/jq and other local utilities without embedding a full Linux userland into our APK.

Security requirement:

Termux execution must be modeled as an explicitly high-power local capability with strict scope and profile policy. It must never be treated as a generic safe tool.

Sources:

- https://github.com/termux/termux-app
- https://github.com/termux/termux-api

### 2.12 OpenAI Agents SDK — approval/guardrail/resume patterns

OpenAI's current Agents SDK separates model-requested tool calls from application-side execution and supports input/output guardrails, human-in-the-loop approval interruptions, resumable run state, bounded tool concurrency, and sandbox-backed execution.

Value for us:

**REFERENCE.** Several execution ordering patterns are valuable:

```text
validate input
 -> approval / interruption
 -> revalidate
 -> execute
 -> output validation
 -> persist/resume state
```

The SDK's own documentation also explicitly warns that client-submitted approval is not, by itself, an authorization boundary. That reinforces our decision that approval must be backed by local identity and policy state rather than a UI boolean.

Sources:

- https://github.com/openai/openai-agents-python
- https://openai.github.io/openai-agents-python/

### 2.13 Pydantic AI — capability composition and durable HITL patterns

Pydantic AI provides typed capabilities, MCP support, dynamic tool approval, deferred tool requests, and integrations for durable execution backends.

Value for us:

**REFERENCE.** Particularly useful concepts:

- capability as a composable primitive;
- deferred approval requests;
- distinction between approval and authorization;
- durable execution interfaces that are externalized from agent semantics.

The project's warning that UI-submitted approvals alone are not a security boundary is directly relevant to our local-control thesis.

Sources:

- https://github.com/pydantic/pydantic-ai
- https://github.com/pydantic/pydantic-ai/blob/main/docs/deferred-tools.md
- https://github.com/pydantic/pydantic-ai/blob/main/docs/durable_execution/overview.md

### 2.14 LangGraph — durable state-machine comparison

LangGraph is useful as a reference for long-running, stateful agents, durable execution, human-in-the-loop checkpoints, and memory.

Value for us:

**REFERENCE only.** Its graph abstraction may inform future workflow design, but adopting a large graph framework inside the mobile core would be premature and may conflict with our intentionally small domain model.

Source:

- https://github.com/langchain-ai/langgraph

### 2.15 Restate / Temporal — durable execution theory, not mobile dependencies

Restate provides durable workflows/stateful actors/async tasks and has Java/Kotlin support. Temporal is a major durable-workflow ecosystem.

Value for us:

**REFERENCE.** We should extract the semantics of durable effects, deterministic replay, retries, idempotency, timers, and failure recovery without assuming that a distributed server workflow engine belongs inside an Android APK.

Source:

- https://github.com/restatedev/restate

### 2.16 A2A — future remote-agent federation

A2A 1.0 defines Agent Cards, skills, interfaces, authentication requirements, supported modes, asynchronous tasks, and agent discovery.

Value for us:

**FUTURE ADAPTER.** A2A should remain outside the core until the product has a real need to delegate missions to independent remote agents.

Potential mapping:

```text
Remote Agent
  -> A2A adapter
  -> local Capability / Mission boundary
```

The local application remains responsible for whether the remote agent may be invoked and what data may be sent.

Sources:

- https://github.com/a2aproject/A2A
- https://github.com/a2aproject/A2A/blob/main/docs/specification.md

### 2.17 AG-UI — future external UI protocol

AG-UI is an event-based agent↔user protocol for connecting agent state, user interaction, UI intents, streaming events, and frontend tool integration.

Value for us:

**REFERENCE / FUTURE ADAPTER.** It is not needed by the native Compose UI, but its event vocabulary can inform how a future external/web client observes the same agent runtime without coupling the runtime to Compose.

Source:

- https://github.com/ag-ui-protocol/ag-ui

### 2.18 OpenTelemetry / Phoenix — observability without ownership leakage

Phoenix uses OpenTelemetry and OpenInference to observe AI applications and traces.

Value for us:

**REFERENCE.** Adopt the principle of standard tracing semantics and correlation IDs without requiring third-party telemetry. For the private profile, local traces should remain local by default; exporting telemetry must itself be an explicit egress decision.

Source:

- https://github.com/Arize-ai/phoenix

## 3. Platform security foundations

### Android application sandbox

Android's application sandbox gives each application a distinct UID and kernel-enforced isolation. This is the foundation beneath our own capability policy; it is not a replacement for it.

We should treat the OS sandbox as one layer of defense-in-depth and avoid pretending that an app-level policy can replace kernel/platform security.

Source:

- https://source.android.com/docs/security/app-sandbox

### Android on-device GenAI

ML Kit GenAI APIs now expose on-device Gemini Nano capabilities through AICore, including prompt, summarization, rewriting, image description, and speech-related functionality. Importantly for our architecture, Google documents that these APIs process inputs/inference/outputs on-device, but some GenAI APIs are constrained to foreground use and have hardware/model availability differences.

Value for us:

**ADAPTER CANDIDATE.** On-device Gemini should be another `LocalModelProvider`/specialized capability, not the only local intelligence option.

Sources:

- https://developers.google.com/ml-kit/genai
- https://developers.google.com/ml-kit/genai/prompt/android
- https://developers.google.com/ml-kit/genai/speech-recognition/android

## 4. What these projects change in our architecture

The external review strengthens, rather than replaces, the current North Star.

### Add/strengthen

1. **Perception as a capability family**
   - accessibility tree
   - screenshot capture
   - structured UI state
   - optional OCR/vision

2. **Application trust policy**
   - per-app trust state
   - sensitive-app deny lists
   - session-only versus persistent approvals
   - explicit takeover/pause

3. **Model catalog metadata**
   - hardware requirements
   - modalities
   - context limits
   - local/remote status
   - availability
   - cost/energy profile

4. **Capability verification recipes**
   - preconditions
   - effect
   - postcondition
   - evidence
   - rollback/idempotency

5. **Skills as scoped knowledge**
   - load only when relevant
   - keep skill text separate from authority policy
   - make skill provenance explicit

6. **Evaluation harness**
   - deterministic capability tests
   - replayable traces
   - mobile task benchmarks
   - failure classification
   - regression suite

7. **Local trace viewer/export**
   - local by default
   - redaction before export
   - explicit egress decision for telemetry

8. **Protocol adapter portfolio**
   - MCP
   - AppFunctions
   - HTTP
   - A2A
   - optional Termux bridge

### Do not add yet

- a general-purpose multi-agent swarm;
- a server-side account/tenant architecture;
- a distributed workflow engine inside the APK;
- a mandatory third-party telemetry service;
- a giant plugin marketplace;
- arbitrary shell as a normal default capability;
- full screen-control automation before the permission/risk model is implemented;
- AppFunctions as a core dependency while its platform contract is still experimental.

## 5. New high-value domain candidates

These were not explicit enough in the original v0.1 domain vocabulary and should be evaluated for v0.2:

```text
Activation
Profile
Capability
CapabilityScope
CapabilityInvocation
Approval
Identity
Budget
Observation
Verification
Evidence
Artifact
Skill
Workflow
ModelProfile
EgressDecision
PermissionGrant
TrustDecision
```

Not all should become persistent entities. Some can remain value objects or policy inputs.

## 6. Research-derived execution pattern

The strongest common pattern across the reviewed agent systems is:

```text
activation
   -> interpret
   -> select capability / plan
   -> validate
   -> authorize
   -> approval if required
   -> execute
   -> observe
   -> verify
   -> persist evidence
   -> continue / stop / recover
```

This is the candidate canonical execution loop for our runtime.

The important distinction is that external projects generally optimize different portions of this loop:

- MobileAgent-Android: perception/action/reflection;
- ClosePaw: mobile execution, safety, takeover, skills, evaluation;
- LiteRT-LM / AI Edge: local inference;
- AppFunctions: Android-native capability exposure;
- MCP SDK: interoperability;
- OpenAI Agents / Pydantic AI: approval, guardrails, durable agent mechanics;
- A2A: remote-agent interoperability;
- AG-UI: agent/user protocol;
- Phoenix: trace/observability.

Our architecture should own the **control boundary between these concerns** rather than trying to compete with each project's strongest subsystem.

## 7. Priority matrix

| Priority | Area | First useful contribution |
|---|---|---|
| P0 | Capability + Policy | Concrete local capability with allow/deny/approval + verification |
| P0 | Secret/egress boundary | Keystore-backed credentials + local egress decision |
| P0 | Durable execution | Mission/Task/Run recovery and idempotent effect identity |
| P1 | Local models | LiteRT-LM and/or ML Kit provider behind common interface |
| P1 | Mobile perception | Accessibility + screenshot capability boundary |
| P1 | Skills | Scoped, typed skill loading without authority leakage |
| P1 | Evaluation | Replayable local traces + deterministic capability tests |
| P2 | MCP | App-owned MCP adapter using official Kotlin SDK |
| P2 | AppFunctions | Android-native integration adapter |
| P2 | Termux | Optional high-power local execution adapter |
| P2 | A2A | Remote-agent delegation adapter |
| P2 | AG-UI | External UI event adapter if needed |
| P3 | Capability Graph | Introduce only when real compositions require it |
| P3 | Multi-agent | Only after a workload defeats the single-agent runtime |
| P3 | ProjectRegistry | Only after multiple real external consumers prove shared invariants |

## 8. Bottom line

The external ecosystem does not invalidate our North Star. It sharpens it.

The strongest emerging architecture for this project is not one giant framework. It is a thin user-owned control plane surrounded by interchangeable subsystems:

```text
                  USER / OS
                     |
                ACTIVATION
                     |
              LOCAL CONTROL PLANE
        identity / policy / privacy / approval
                     |
               AGENT RUNTIME
           plan / act / observe / verify
                     |
        +------------+-------------+
        |            |             |
      MODELS     CAPABILITIES     SKILLS
        |            |             |
        |      +-----+-----+       |
        |      |     |     |       |
      local   Android HTTP MCP    scoped knowledge
      remote AppFn   local A2A
        |            |
        +------------+-------------+
                     |
              STATE / EVIDENCE
                     |
                AUDIT / TRACE
```

The strategic value is therefore in owning the **control point**, not in reimplementing every ecosystem technology.
