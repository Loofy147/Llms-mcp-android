# Anthropic Security Synchronization — 2026-09-17

Status: **SECURITY RESEARCH ADDENDUM / LOCAL CLAIMS UNCHANGED UNTIL PROVEN**

Primary detailed reference: `docs/architecture/ANTHROPIC_REFERENCE_REVIEW_2026-09-17.md`.

## 1. Core confirmations

Anthropic's current engineering guidance reinforces several existing security invariants:

```text
model output != authorization
policy != human approval
preference != authorization
tool exposure != authorization
execution environment != reasoning authority
```

The local project already encodes these boundaries in `PolicyEngine`, `ApprovalStore`, `CapabilityExecutor`, and the egress boundary.

## 2. Credential-use gap

Anthropic Vaults separate credential storage/reference from the agent-visible configuration and constrain secret substitution by environment egress.

Local state:

```text
Keystore-backed CredentialStore  = protected at rest
CredentialRef + protected egress resolution = OPEN
```

Security target:

```text
CredentialValue must not appear in
- model input
- model-facing tool schema
- ActionPlan
- ordinary Evidence
- durable Run content
- ordinary logs
- exported telemetry
```

Only the protected transport boundary may resolve the raw value.

Primary sources:

- https://platform.claude.com/docs/en/api/beta/vaults/credentials
- https://platform.claude.com/docs/en/managed-agents/mcp-connector

## 3. Containment gap

Anthropic's containment work demonstrates that human approval and probabilistic model-layer defenses are not sufficient by themselves. Sandboxing, filesystem boundaries, and egress controls are separate blast-radius controls.

Local implication:

```text
Capability authorization
        +
execution-environment policy
```

High-power capabilities must eventually define both. This does not justify a generic VM/sandbox in the Android core now.

Primary source:

- https://www.anthropic.com/engineering/how-we-contain-claude

## 4. Egress/provenance gap

The current local egress implementation validates HTTPS, host allowlist, and declared data classes. It does not yet prove content-level minimization or source-aware provenance enforcement.

Anthropic/Muse evidence suggests a stronger future direction:

```text
source/provenance
 + data class
 + purpose
 + destination
 + credential reference
 -> egress decision
```

This remains an experiment, not an implementation requirement.

## 5. Risk classifier boundary

Anthropic's auto mode is a probabilistic safety layer, not a replacement for deterministic authorization or containment. The published evaluation reports non-zero miss rates.

Local rule:

```text
risk classifier -> policy input
risk classifier -/-> authority
```

Primary source:

- https://www.anthropic.com/engineering/claude-code-auto-mode

## 6. MCP security boundary

When native MCP extraction begins, authorization, credential binding, protocol lifecycle, and local capability identity must remain separate. MCP protocol objects must not become the canonical security model.

Primary sources:

- https://platform.claude.com/docs/en/managed-agents/mcp-connector
- https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/

## 7. Security proof rule

External vendor architecture is **research evidence**. It does not establish local security.

For each adopted pattern:

```text
external observation
    -> local invariant
    -> discriminating test
    -> executed evidence
    -> only then claim upgrade
```

This is consistent with the repository's current T2 evidence discipline.
