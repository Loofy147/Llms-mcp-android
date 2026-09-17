# Anthropic Adoption Deltas — 2026-09-17

Status: **CURRENT RESEARCH-TO-ENGINEERING BRIDGE**

This file is the compact action register extracted from `ANTHROPIC_REFERENCE_REVIEW_2026-09-17.md`. It does not promote any open hypothesis to an implementation fact.

## Confirmed alignment

| Area | Current project state | Anthropic evidence | Result |
|---|---|---|---|
| Model is not authority | Implemented invariant | Managed Agents separates policy/permissions from model execution | Preserve |
| Brain vs hands | `CapabilityExecutor` boundary | Managed Agents explicitly separates agent/harness from execution environments | Preserve |
| Policy vs human approval | `DENY` / `APPROVAL_REQUIRED` / `ALLOW` | `always_allow` / `always_ask` / `auto` are distinct | Preserve and keep semantics explicit |
| Runtime vs durable state | `RuntimeStore` / journal | Session/event history is distinct from sandbox lifetime | Preserve |
| Memory vs session | Not yet first-class | Memory stores are separate cross-session state | Keep separate; do not merge |
| MCP as adapter | Native MCP still open | MCP server declaration and authentication are separate | Preserve |

## New engineering deltas

### D-A1 — Credential reference boundary

Current:

```text
CredentialStore -> protected-at-rest secret -> transport code
```

Target research shape:

```text
CredentialRef
    -> policy-bound resolution
    -> final transport boundary
    -> secret value
```

Required invariant:

```text
CredentialValue must not appear in
model input, model-facing tool schema,
ActionPlan, normal Evidence, durable Run content,
ordinary logs, or exported telemetry.
```

Status: **OPEN**. Needs a discriminating test before code changes.

### D-A2 — Provenance-aware egress

Current `EgressPolicy` checks destination/HTTPS/declared data classes.

Research target:

```text
source/provenance
    + data class
    + purpose
    + destination
    + credential reference
    -> egress decision
```

Status: **OPEN**. Do not copy platform-specific eBPF designs before an Android-native invariant is defined.

### D-A3 — Caller-side durable recovery

Anthropic's persistent session/event model reinforces the current T2 frontier:

```text
caller durable checkpoint
    -> provider outcome may be unknown
    -> caller process loss
    -> restart
    -> UNKNOWN_OUTCOME
    -> reconcile(operation_id)
```

Status: **NEXT GATE**, already represented in `ARCHITECTURE_STATE_SYNC_2026-09-17.md`.

### D-A4 — Minimal durable execution events

Candidate only if B4/B5/B6/B7 cannot be expressed cleanly from existing Run/Effect state.

Potential events:

```text
ActivationRequested
PolicyEvaluated
ApprovalRequested
ApprovalConsumed
EffectReserved
EffectDispatched
EffectApplied
EffectUnknown
Reconciled
VerificationCompleted
EvidenceCommitted
RunTerminated
```

Status: **HYPOTHESIS**, not a current domain model.

### D-A5 — Environment containment as a separate security plane

For high-power capabilities, safety must include the execution environment: filesystem, network, process, sandbox/VM boundaries, and credential reachability—not only approval/policy.

Status: **ADOPT PATTERN / DEFER IMPLEMENTATION**.

### D-A6 — MCP security re-audit at implementation time

Before native MCP extraction:

```text
server declaration
!= credential binding
!= authorization
!= execution state
```

Re-audit against the current MCP specification, including authorization and Tasks/lifecycle semantics.

Status: **OPEN GATE**.

## Explicit non-adoptions

Do not add at this stage:

- Anthropic Managed Agents APIs as the internal ontology;
- a generic event bus;
- a general VM/sandbox inside the APK;
- a cloud account/tenant layer;
- an ML risk classifier as authority;
- multi-agent orchestration;
- WorkManager as domain truth;
- protocol-specific types as canonical domain state.

## Evidence rule

Vendor-reported architecture and evaluation results are external evidence, not local proof. The repository must preserve the distinction:

```text
external reference
    !=
local implementation
    !=
executed local evidence
```

Primary detailed record: `ANTHROPIC_REFERENCE_REVIEW_2026-09-17.md`.
