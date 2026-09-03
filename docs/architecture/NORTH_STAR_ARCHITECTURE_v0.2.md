# Llms-mcp-android — North Star Architecture v0.2

Status: Proposed architecture reconciliation baseline
Date: 2026-09-03

## 1. Architectural thesis

**The application owns the local control boundary; models reason; Actions express reusable intent-level execution; Capabilities produce controlled effects; protocols provide interoperability; verification and evidence establish what happened.**

The project is a user-owned mobile agent runtime. It is not defined as a Claude client, MCP client, chatbot, or Android clone of another agent framework.

## 2. The decisive separation

```text
Capability = primitive controlled effect
Action     = reusable executable contract composed from capabilities
Tool       = exposure/interface presented to a model or other client
Model      = optional reasoning component
Activation = request to start or resume permitted work
```

These are related but not interchangeable.

A Capability may be exposed directly. An Action may contain one or many CapabilityInvocations. A Tool may expose a Capability or an Action to a model. A user, automation, OS surface, or program may invoke an Action without any model.

## 3. Canonical execution paths

### Direct

```text
Activation -> Action -> Policy/Approval/Egress -> Run -> Execute -> Observe -> Verify -> Evidence
```

No model is required.

### Model-mediated

```text
Activation -> Model -> policy-filtered Action/Capability set -> selected Action -> revalidation -> Run -> Execute -> Observe -> Verify -> Evidence
```

Model selection never authorizes execution.

### Mission-driven

```text
Activation -> Mission -> Task -> Run(s)
```

Mission is optional and durable; it is not a mandatory wrapper around every action.

## 4. Local control boundary

The Android application owns the local agent-mediated control boundary for:

- identity and execution attribution;
- policy and capability admission;
- scope/effect checks;
- privacy and data-egress decisions;
- approval/consent records;
- durable agent control state;
- evidence and audit references;
- profile defaults and restrictions.

Android OS permissions, sandboxing, lifecycle rules, and external-service authority remain separate higher/lower trust domains. The application does not claim absolute authority over them.

## 5. Core model

```text
User / Local Identity
        |
        +--> Activation
        |       |
        |       +--> Direct Action
        |       |
        |       +--> Model-mediated selection
        |
        +--> Mission (optional)
                |
                +--> Task (optional bounded work)
                        |
                        +--> Run
                               |
                               +--> Action
                               |      |
                               |      +--> CapabilityInvocation(s)
                               |
                               +--> Observation
                               +--> Verification
                               +--> Evidence
                               +--> Artifact
```

### Activation
An identifiable request to begin or resume work. Sources include user UI, quick action, widget, notification, automation, external invocation, or Android-native integration.

### Action
A reusable executable contract. It has purpose, input/output schema, required capabilities, data requirements, effect class, scope, approval requirements, verification requirements, and provenance/version. Actions may be deterministic, model-assisted, or hybrid.

### Capability
A typed ability that can produce a controlled effect. Examples include `camera.capture`, `file.read`, `notification.post`, or `http.request`.

### Tool
A protocol/interface representation presented to a model or other consumer. It is not the authorization layer and is not the canonical semantic model.

### CapabilityInvocation
One concrete authorized attempt to use one Capability, including invocation identity, scope, parameters, budget context, and attribution.

### Run
A concrete execution instance. It has its own lifecycle and terminal-state rules. Retries create a new attempt identity where the effect semantics require it.

### Task
A bounded unit of work, usually inside a Mission but usable without one.

### Mission
A durable user objective that may span Tasks and multiple Runs. It is optional for short-lived actions.

### Observation
A structured fact returned by an executor, provider, device, or environment.

### Verification
A procedure or result that tests preconditions/postconditions or otherwise establishes whether an expected outcome is supported.

### Evidence
An attributable record assembled from observations, executor results, artifacts, tests, or verification. Model prose alone is not evidence.

## 6. Action contract

Every catalogued Action should eventually define at least:

```text
id / version
purpose
input schema
output schema
required capabilities
scope requirements
data requirements
effect class
network/destination requirements
approval mode
verification contract
idempotency / replay semantics
provenance
```

Actions are the preferred high-level interface for repeated or meaningful user operations. They are not a reason to create hundreds of wrappers.

## 7. Capability classes

Capabilities may be:

- internal/local;
- Android/device;
- filesystem/data;
- model/inference;
- HTTP/API;
- MCP-backed;
- remote-service backed;
- high-power process execution.

The shared contract is controlled effect, not a specific transport.

## 8. Action exposure and discovery

The system must distinguish **what exists**, **what may be exposed**, and **what may execute**.

```text
Installed Actions/Capabilities
        |
        v
Policy-filtered exposure
        |
        v
Model or client discovery
        |
        v
Requested invocation
        |
        v
Independent authorization recheck
```

A model must not receive or infer permissions from its own tool/action schema.

## 9. Preferences versus authority

User preferences can guide selection:

```text
preferred model
local-first preference
preferred Action
confirmation style
latency/quality preference
```

Preferences are decision inputs, not authorization grants.

```text
Preference != Policy != Approval
```

## 10. Privacy and egress

Remote reasoning or execution is an egress event.

```text
Data -> classify -> minimize/redact -> destination/purpose check -> policy decision -> invocation
```

Secrets, private memory, protected files, diagnostics, exports, and telemetry are all privacy surfaces.

## 11. Model layer

The model is optional and replaceable infrastructure behind provider-neutral interfaces. The runtime should depend on semantic operations such as structured reasoning, multimodal inference, or action selection rather than a vendor-specific architecture.

Local and remote models are peers from the domain's perspective; policy determines what data may be sent to either.

## 12. Interoperability

MCP, HTTP, Android App Functions, A2A, local process bridges, and other protocols are adapters. They do not define Mission/Task/Run, Policy, Approval, Action, Capability, or Evidence semantics.

The current MCP specification (2026-07-28) has a stateless protocol core, Tasks as an extension, authorization hardening, and deprecation of the legacy HTTP+SSE direction. This reinforces keeping application state and authorization in the application rather than in transport sessions. Reference: https://blog.modelcontextprotocol.io/posts/2026-07-28/ 

Android App Functions is an Android-native cross-app function surface; as of 2026-08-26 the Jetpack artifact is `1.0.0-alpha11`, so it remains an adapter candidate rather than a core dependency. References: https://developer.android.com/jetpack/androidx/releases/appfunctions and https://developer.android.com/reference/android/app/appfunctions/package-summary

## 13. Persistence and recovery

```text
Settings       -> ordinary settings store
Secrets        -> Keystore-backed credential boundary
Agent state    -> durable structured store
Artifacts      -> private app/content store
Evidence       -> durable attributable records
```

Recovery must prevent duplicate effects using capability-appropriate effect identity/idempotency semantics.

## 14. Background execution

Execution must respect Android lifecycle and background restrictions. The architecture uses foreground/user-triggered work and OS-supported deferred/persistent mechanisms such as WorkManager where appropriate. It assumes no unrestricted permanent daemon.

## 15. Profiles

One runtime core supports:

```text
Personal
Developer
Product/Public
```

Profiles may alter defaults, exposed actions, approval rules, telemetry, and capability restrictions. They cannot bypass foundational security invariants.

## 16. Deferred complexity

Do not add a general workflow engine, full capability graph, multi-agent swarm, plugin marketplace, or server tenancy model until measured workloads prove a missing primitive.

A future workflow can be a composition layer over Actions. It does not need to become a core authority model.

## 17. Architectural acceptance criteria

The architecture is coherent when:

1. a useful deterministic Action can execute without a model;
2. a model can select an Action without authorizing it;
3. the same Action can be activated by UI, automation, or model mediation;
4. Capability execution cannot bypass policy;
5. preferences cannot grant authority;
6. Missions are optional and may span multiple Runs;
7. verification can reject an apparently successful execution;
8. private data can be denied egress locally;
9. remote execution does not transfer local authorization ownership;
10. model/protocol replacements do not change control semantics;
11. process death cannot silently create a duplicate external effect;
12. the core remains useful with optional integrations disabled.
