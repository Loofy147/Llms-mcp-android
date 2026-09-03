# Action Model v0.2

Status: Canonical semantic reference
Date: 2026-09-03

## Purpose

This document fixes the distinction between Activation, Action, Capability, Tool, and Model so implementation can remain small without collapsing authority and execution semantics.

## Definitions

### Activation
A request to begin or resume permitted work. Sources include direct UI, quick actions, widgets, notifications, automation, external invocation, Android-native surfaces, or model-mediated requests.

### Capability
A primitive controlled ability that can produce an effect.

Examples:

```text
camera.capture
file.read
file.write
notification.post
http.request
```

Capabilities are where scope, data access, effect class, destination, and execution restrictions are evaluated.

### Action
A reusable executable contract meaningful at the task/user level. An Action invokes one or more Capabilities and defines the expected input/output, required scopes, effects, approval, verification, and replay semantics.

Action classes:

```text
Deterministic   -> no model required
Model-assisted  -> reasoning used inside the action
Hybrid          -> deterministic execution plus targeted reasoning
```

### Tool
A model/client-facing representation or invocation interface. A Tool may expose an Action or Capability. Tool exposure is not authorization.

### Model
A reasoning component. It may interpret intent, rank options, select an Action, produce structured parameters, or assist a Hybrid Action. It is not an authority source.

## Relationship

```text
Installed capabilities/actions
          ↓
Policy-filtered exposure
          ↓
Model/client discovery (optional)
          ↓
Action selection/request
          ↓
Independent policy + approval + egress check
          ↓
Capability invocation(s)
          ↓
Run / execution
          ↓
Observation → Verification → Evidence
```

## Examples

### Zero-model

```text
User taps "Take screenshot"
  → Activation
  → screenshot Action
  → policy
  → capability: screenshot.capture
  → verify artifact
  → evidence
```

### Model-mediated

```text
User: "Prepare my latest invoice for sending"
  → Activation
  → Model interprets request
  → selects prepare_invoice Action
  → policy/egress/approval
  → capabilities: file.find + file.read + document.extract
  → verification
  → artifact/evidence
```

### Hybrid

```text
Action: send_message
  deterministic: resolve account + validate destination
  model-assisted: draft message from approved context
  policy/approval: authorize send
  deterministic: send + verify accepted result
```

## Design rules

1. Not every Capability is model-visible.
2. Not every Action requires a Model.
3. Not every Action needs multiple Capabilities.
4. An Action must not silently widen the scope of its component Capabilities.
5. The model sees only the policy-filtered surface intended for its role.
6. Every effectful execution is authorized independently from model selection.
7. Direct, automated, and model-mediated activations of the same Action share execution semantics.
8. Action metadata is descriptive unless a separate local policy grants authority.
9. Repeated Actions should be preferred over repeatedly synthesizing low-level tool sequences when this improves latency, safety, or verification.
10. Catalog growth must be justified by real reuse; Actions are not a marketplace by default.

## Action versus Workflow

An Action is the minimum reusable execution unit that deserves a stable semantic identity. A future Workflow may compose multiple Actions across longer-lived dependencies/timers/human checkpoints. A Workflow engine is not required for v0.2.

## Action provenance

Each trusted Action should have identity/version/provenance so behavior changes can be reviewed and evidence can identify which contract executed.
