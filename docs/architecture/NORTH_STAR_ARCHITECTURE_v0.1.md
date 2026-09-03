# Llms-mcp-android — North Star Architecture v0.1

Status: **Proposed baseline for architectural review**  
Branch: `architecture/north-star-v0.1`  
Date: 2026-09-03

## 1. Purpose

This document defines the target architecture for `Llms-mcp-android` after the review of the current Android prototype, the recent Agora work, and the architectural lessons extracted from the user's other agent and evidence-oriented projects.

The project is intended to serve three compatible roles from one core:

1. a private, user-owned mobile agent;
2. a developer/runtime platform for experimenting with agents, models, tools, and integrations;
3. a product surface that can expose a safer policy profile without changing the core authority model.

The project is **not** defined as a Claude client, an MCP client, an Android chatbot, or an Android port of Agora.

## 2. Architectural thesis

> **The device owns authority; models provide reasoning; capabilities provide effects; protocols provide interoperability; verification and evidence establish what actually happened.**

The central distinction is between **reasoning** and **authority**.

A model may propose a plan, select among available capabilities, produce parameters, or request an action. It never grants itself authorization, changes the security policy, or turns a denied operation into an allowed one.

## 3. System boundary

```text
                 USER / OS / ACTIVATION SURFACE
                              |
                              v
                 +-----------------------------+
                 |       CONTROL PLANE         |
                 |                             |
                 | identity                    |
                 | policy                      |
                 | privacy / egress            |
                 | consent / approval           |
                 | budget                      |
                 | mission / task metadata     |
                 +--------------+--------------+
                                |
                                v
                 +-----------------------------+
                 |        AGENT RUNTIME         |
                 |                             |
                 | plan -> act -> observe      |
                 | -> verify -> continue       |
                 +------+------------+----------+
                        |            |
              +---------+            +----------+
              v                                v
      +---------------+                 +-------------+
      | MODEL LAYER   |                 | CAPABILITY  |
      |               |                 | LAYER       |
      | local         |                 | Android     |
      | remote        |                 | local       |
      | interchangeable|                | HTTP        |
      +-------+-------+                 | MCP         |
              |                         | remote      |
              |                         +------+------+
              |                                |
              +----------------+---------------+
                               v
                 +-----------------------------+
                 |   STATE / EVIDENCE LAYER    |
                 |                             |
                 | runs / missions / tasks    |
                 | observations                |
                 | verification                |
                 | artifacts                   |
                 | durable memory / audit      |
                 +-----------------------------+
```

No capability adapter may bypass the control plane for an action that has an effect on protected state, user data, or external systems.

## 4. Foundational principles

### 4.1 User-owned authority

The application is the local authority boundary for authorization, privacy policy, identity, consent, durable agent state, and evidence.

Remote execution does **not** transfer ownership of those decisions.

### 4.2 Model-agnostic reasoning

The runtime must not depend on one model vendor. A model provider is replaceable infrastructure behind a stable internal interface.

### 4.3 Capability-oriented execution

The unit of external effect is a **capability invocation**, not a generic tool call. Capabilities must be describable in a way that permits policy decisions over scope, effect, data access, network use, destination, approval, and verification requirements.

### 4.4 Protocol independence

MCP, HTTP APIs, Android App Functions, local APIs, and other integration mechanisms are adapters/protocols behind the internal capability model. No external protocol is the architectural center of the application.

### 4.5 Evidence over assertion

A model statement is not automatically evidence. Evidence should originate from observations, executor results, artifacts, tests, or verification records and remain attributable to a concrete invocation or system event.

### 4.6 Explicit activation

Every execution path begins with an identifiable activation source: foreground user action, quick action, automation, notification interaction, external invocation, or another explicitly permitted trigger.

All activation paths converge on the same control-plane checks.

### 4.7 Durable state, ephemeral computation

Agent computation may be transient; important control-plane state must be durable and recoverable. The implementation should not rely on an in-memory conversation object as the authoritative state store.

### 4.8 Bounded execution

Long-running or recursive agent behavior must be constrained by budgets such as time, steps, tokens/cost, network use, tool/capability count, and data egress.

## 5. Core domain model

The initial domain vocabulary is:

```text
User / Local Identity
        |
        +--> Activation
        |
        +--> Mission
                |
                +--> Task
                        |
                        +--> Run
                                |
                                +--> CapabilityInvocation
                                        |
                                        +--> Observation
                                        +--> Verification
                                        +--> Evidence
                                        +--> Artifact
```

### Mission

The user's durable objective and constraints. A Mission is a control-plane object and may span multiple Runs.

### Task

A bounded unit of work within a Mission. Tasks may express dependencies, required capabilities, effect level, and verification requirements.

### Run

A concrete execution instance. Run identity belongs to execution, not to user intent. A Run is never reactivated merely to satisfy a higher-level Mission.

### Capability

A named, typed ability that can be requested and executed under policy.

### CapabilityInvocation

The concrete request to execute one capability with explicit identity, scope, parameters, and budget context.

### Observation

A structured fact observed from an executor, provider, device, or environment.

### Verification

A procedure or result that checks whether an expected postcondition or claim is supported.

### Evidence

An attributable record assembled from observations, results, artifacts, or verification. Evidence is not synonymous with model text.

### Artifact

A concrete persistent output such as a file, generated object, report, or other externally inspectable result.

## 6. Control-plane responsibilities

The control plane owns:

- local execution identity;
- capability admission;
- scope and effect checks;
- privacy/data-egress policy;
- explicit user approval/consent when required;
- mission/task lifecycle metadata;
- execution budgets;
- audit and evidence references;
- background execution policy;
- provider and adapter eligibility.

The control plane does **not** own the implementation details of every model provider or every capability executor.

## 7. Agent runtime responsibilities

The runtime turns an admitted activation or mission into bounded work:

```text
Activate
  -> interpret
  -> optionally plan
  -> request capability
  -> policy admission
  -> execute
  -> observe
  -> verify
  -> persist evidence
  -> decide next step
```

Planning is optional. A direct action can bypass a heavyweight planner while still passing the same policy and execution boundaries.

## 8. Capability architecture

Capabilities are internal abstractions. Implementations may use different transports:

```text
Capability
 |
 +-- Android capability
 +-- Local process capability
 +-- File capability
 +-- Model capability
 +-- HTTP/API capability
 +-- MCP capability
 +-- Remote service capability
```

The internal representation should eventually expose at least:

```text
identity
name / action
scope
effect level
data access
network access
destination
activation / approval requirement
reversibility
verification requirements
provider / adapter identity
```

The first implementation may use a registry rather than a full graph. A graph is an extension point, not a v0.1 requirement.

## 9. Privacy and data egress

Privacy is an execution boundary, not a UI feature.

```text
Data
 -> classify
 -> determine permitted destination
 -> apply egress policy
 -> redact/minimize if necessary
 -> authorize invocation
 -> execute
```

The policy must distinguish at least:

- credentials/secrets;
- private conversation;
- memory;
- files/artifacts;
- telemetry and diagnostics;
- content that may leave the device;
- content that must remain local.

A remote model may be allowed to reason over data only when the local policy permits that data to leave the device for the stated purpose.

## 10. Model layer

The model layer is provider-neutral:

```text
ModelProvider
 |
 +-- Anthropic
 +-- OpenAI / OpenAI-compatible
 +-- Local runtime
 +-- other providers
```

The runtime should depend on stable capabilities such as text generation, structured output, tool/capability selection, or multimodal inference rather than vendor-specific client code.

## 11. MCP and interoperability

MCP is an interoperability mechanism, not the internal authority model.

The current MCP specification released on 2026-07-28 is explicitly stateless at the protocol core, adds Tasks as an extension, and hardens authorization. These changes reinforce the decision to keep application state and authorization in the application rather than assuming transport-level sessions are the source of truth.

Reference: https://blog.modelcontextprotocol.io/posts/2026-07-28/

The application should therefore support MCP as an adapter while preserving its own local Mission/Task/Run/Policy/Evidence semantics.

## 12. Android-native surfaces

The runtime must be able to expose one control plane through multiple activation surfaces:

```text
Chat UI
Quick Action
Notification action
Widget
Automation
External intent
Android App Functions
```

All surfaces converge on an activation request and do not receive separate authorization logic.

Android App Functions are treated as an optional integration surface while the API remains experimental. As of 2026-08-26 the current Jetpack artifact is `1.0.0-alpha11`.

Reference: https://developer.android.com/jetpack/androidx/releases/appfunctions

## 13. Persistence strategy

The target separation is:

```text
Configuration -> DataStore
Secrets        -> Keystore-backed credential storage
Agent state    -> Room or equivalent durable structured store
Artifacts      -> private app storage / content store
```

The current prototype uses SharedPreferences and in-memory conversation state. That is explicitly prototype-only and must not be treated as the target architecture.

## 14. Background execution

Android background execution is governed by the operating system. The architecture therefore uses:

```text
foreground execution
+ user-triggered actions
+ WorkManager for appropriate durable/background work
+ notifications / explicit status surfaces
```

A permanent unrestricted Android daemon is not a foundational assumption.

## 15. Personal, developer, and product profiles

One core should support different policy profiles rather than separate codebases.

```text
Core
 |
 +-- Personal profile
 +-- Developer profile
 +-- Public/Product profile
```

Profiles may change defaults, available capabilities, telemetry, or approval policy, but they must not bypass the same fundamental authority boundaries.

## 16. Reuse from Agora

Agora is a source of proven patterns and lessons, not the architecture to clone.

Candidate extractions:

- explicit Run identity;
- deterministic runtime transitions/reducers;
- cancellation and recovery patterns;
- execution tracing;
- bounded command processing;
- provider/tool integration patterns;
- startup recovery concepts.

Rejected as a direct architectural dependency:

- duplicating a second Run state machine for Missions;
- making the LLM an authorization authority;
- making a remote MCP connector the center of the system;
- copying upstream complexity before the mobile-specific invariants are established.

## 17. Deferred architecture

The following are intentionally deferred until evidence justifies them:

- full multi-agent orchestration;
- full Capability Graph implementation;
- ProjectRegistry / ProjectAdapter federation;
- large plugin ecosystem;
- server-side account architecture in the core;
- autonomous self-modifying code;
- broad remote orchestration fabric.

## 18. Architectural acceptance test

The architecture is considered internally coherent when each of the following is true:

1. a model can request a capability without being able to authorize it;
2. an allowed action can be executed locally or remotely without changing authority semantics;
3. all activation surfaces converge on one policy boundary;
4. private data can be prevented from leaving the device by policy;
5. a Mission can span multiple Runs without redefining Run ownership;
6. execution results can be converted into attributable evidence;
7. the system can recover durable control-plane state after process death;
8. model vendors and protocols can be replaced without rewriting policy;
9. Personal and Product modes can share the same runtime core;
10. no deferred feature is required for the core to remain useful.

## 19. Stability level

This document is a **North Star baseline**, not a frozen implementation specification.

Foundational principles and security invariants may only change through an explicit decision review. Technology choices and implementation details remain replaceable until validated in code.
