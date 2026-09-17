# Muse Security Synchronization — 2026-09-17

Status: **RESEARCH/SECURITY RECORD — NO RUNTIME CHANGE**

Muse provides external evidence for several security invariants already present in `Llms-mcp-android` and exposes additional gaps that require local experiments.

## 1. Security invariants confirmed by Muse

1. Model output is not authorization.
2. User approval must be a scoped control-plane decision, not conversational text.
3. Credential possession and execution authorization are separate concerns.
4. Real credentials should remain outside agent/model context where possible.
5. Network egress is an enforcement boundary, not merely an HTTP convenience.
6. Data provenance can affect whether an egress request is permitted automatically.
7. Browser/UI automation should expose a narrow brokered capability surface.
8. Runtime containment is complementary to model/policy defenses.
9. Execution/audit history is distinct from memory and user-facing presentation.
10. Prompt injection remains a threat model assumption, not a solved property.

## 2. Existing local implementation

Already present:

- Model non-authority.
- Policy/Approval/Egress separation.
- One-use, exact-bound approvals.
- Controlled `CapabilityExecutor` boundary.
- Durable effect reservation and `UNKNOWN` recovery state.
- Keystore-backed credential storage.
- Explicit EgressPolicy.
- Attribution and Evidence vocabulary.

Sources in repository:
- `docs/security/PRIVACY_SECURITY_INVARIANTS_v0.2.md`
- `docs/architecture/CAPABILITY_EXECUTOR_BOUNDARY_v0.1.md`
- `docs/architecture/ARCHITECTURE_STATE_SYNC_2026-09-17.md`

## 3. Security gaps exposed by Muse

### S-MUSE-01 — credential-use isolation

Current storage protection does not prove that a secret cannot enter model/tool/action/evidence/logging context.

Candidate invariant:

```text
CredentialRef may cross the reasoning/control plane.
CredentialValue may cross only a protected final transport boundary.
```

Status: **OPEN / HYPOTHESIS**

Required proof: instrument the complete credential path and prove forbidden surfaces never receive the plaintext value.

### S-MUSE-02 — provenance-aware egress

Current EgressPolicy relies on destination, purpose, and declared data classes. It does not yet bind a concrete outbound payload to provenance.

Candidate invariant:

```text
Egress authorization depends on destination + purpose + data class + provenance.
```

Status: **OPEN / HYPOTHESIS**

Required proof: construct controlled data lineage cases where identical destinations carry differently sourced data and verify authorization differs when policy requires it.

### S-MUSE-03 — execution-environment containment

High-power future capabilities may require an execution boundary stronger than in-process policy checks.

Status: **FUTURE / CAPABILITY-SPECIFIC**

No generic sandbox/VM should be introduced before a real capability demonstrates the need.

### S-MUSE-04 — browser broker

A future browser capability must not expose an unrestricted browser process to the model.

Status: **FUTURE ADAPTER**

Candidate contract: narrow accessibility/interaction operations, explicit takeover, credential-entry isolation, and policy-bound navigation/egress.

### S-MUSE-05 — prompt injection containment

Do not claim immunity. Prompt injection is an assumed adversarial condition.

Status: **ESTABLISHED THREAT-MODEL RULE**

Containment should limit blast radius even when reasoning-layer defenses fail.

## 4. Security test priorities

1. Credential-flow non-disclosure.
2. Caller/provider recovery and UNKNOWN_OUTCOME.
3. Concurrent recovery and stale-result rejection.
4. Provenance-aware egress.
5. Capability-specific containment for the first genuinely high-power Android capability.

## 5. External sources

- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse
- https://ai.meta.com/muse/
- https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/
- https://research.meta.ai/blog/addressing-third-party-testing-misconfiguration-muse-spark-1-1
