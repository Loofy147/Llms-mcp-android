# Conversation Findings Sync — 2026-09-20

Status: DURABLE ARCHITECTURE/SECURITY SYNTHESIS
Repository: Loofy147/Llms-mcp-android
Branch: main
Base branch head observed before this write: 84a3a81029fd84972e2675b0eb7575c2d0a4a4fb

Purpose
-------
Persist the durable conclusions from the current architecture/security discussion. Detailed vendor studies already live in the Anthropic/Muse reference, adoption, and security-sync documents; this file records the resulting local claim frontier and execution order without duplicating those reports.

## 1. Local authority model remains canonical

Status: ACCEPTED

Core relation:
- Model/reasoning chooses or proposes.
- Policy authorizes or denies.
- Human approval is a separate scoped control-plane decision.
- Risk signals are advisory, not authorization.
- Tool exposure is not authority.
- Capability execution is the controlled effect boundary.
- Observation/verification produce evidence.

Invariant:
Model output never authorizes an effect.

## 2. External research conclusion: architecture principles converge

Status: RESEARCH-SUPPORTED, not local implementation proof

Anthropic and Meta Muse independently reinforce these separations:
- reasoning/agent != execution authority
- policy != human approval
- risk classifier != final authorization
- CredentialRef != credential value
- process/sandbox/VM != durable semantic identity
- MCP/external protocol != canonical domain model
- live session history != durable domain truth
- containment complements policy rather than replacing it

Detailed evidence remains in:
- docs/architecture/ANTHROPIC_REFERENCE_REVIEW_2026-09-17.md
- docs/architecture/ANTHROPIC_ADOPTION_DELTAS_2026-09-17.md
- docs/security/ANTHROPIC_SECURITY_SYNC_2026-09-17.md
- docs/architecture/MUSE_REFERENCE_REVIEW_2026-09-17.md
- docs/architecture/MUSE_ADOPTION_DELTAS_2026-09-17.md
- docs/security/MUSE_SECURITY_SYNC_2026-09-17.md

Vendor evidence does not upgrade local implementation claims.

## 3. T2 Binder recovery frontier

Experiment: Binder failure-injection experiment on API 35 emulator
Workflow/run: GitHub Actions run #262 / 35216212638

Status: EXPERIMENTALLY_SUPPORTED — case-level

Established cases:
- B2: provider durable receipt survives provider process death before effect; recovery observes RECEIVED, effect count remains 0, and later execution reaches COMPLETED once.
- B3: provider effect is persisted before provider process death/reply loss; recovery observes EFFECT_APPLIED and explicit reconciliation reaches COMPLETED without re-executing the effect; count remains 1.

Open cases:
- B1 provider unavailable before dispatch
- B4 caller dies after provider completion
- B5 caller dies before dispatch
- B6 concurrent recovery
- B7 stale callback/result ordering
- caller-side durable UNKNOWN_OUTCOME
- cross-package authorization/discovery
- reboot/force-stop/update
- power-loss durability

No general exactly-once claim is established.

## 4. Required T2 progression

The next proving sequence remains:

B4/B5 caller-side durable checkpoint -> provider process loss/effect -> caller process loss -> restart -> classify UNKNOWN_OUTCOME -> reconcile by operation_id -> commit without duplicate effect

then:
- B6 concurrent recovery
- B7 stale callback/result ordering

Only after those are evidenced should the next boundary be promoted.

## 5. Credential-use isolation

Decision target:
CredentialRef must be distinct from raw credential value.

Status: TARGET INVARIANT / OPEN PROOF

Required test:
- raw secret absent from model input
- absent from model-facing tool schemas
- absent from ActionPlan content
- absent from ordinary Evidence
- absent from normal logs
- absent from durable Run content
- absent from exported telemetry
- raw value resolved only at the protected final transport/use boundary

Security boundary:
Credential storage protection alone is insufficient; the end-to-end observation/non-observability path must be tested.

## 6. Provenance-aware egress

Finding:
A coarse data class plus destination check is not enough when authorization depends on where data came from and why it is being sent.

Status: TARGET INVARIANT / OPEN PROOF

Required test:
- same nominal data class from different provenance/flows
- same capability targeting different destinations
- credential binding to a concrete destination
- policy outcome must remain attributable to data lineage + destination + purpose.

Current EgressPolicy therefore remains an important boundary, but not a full content-level minimization/redaction or provenance proof.

## 7. Containment is capability-specific

Finding:
High-power capabilities need execution-environment boundaries in addition to policy/approval.

Status: ACCEPTED DESIGN PRINCIPLE

Do not add a general VM/eBPF/browser/multi-agent substrate merely because Muse or another vendor uses it.
First demonstrate a capability whose blast radius exceeds what the current policy/effect boundary can safely contain.

## 8. Durable event history is a measured requirement, not a default

Status: OPEN

Add a durable execution event log only if B4-B7 demonstrate that Run/Effect state cannot represent the required recovery/order semantics.

Do not introduce an event bus or new durable substrate speculatively.

## 9. Native MCP remains an adapter boundary

Finding:
MCP authentication/lifecycle semantics must not become the canonical domain model or authority model.

Next:
Re-audit the native MCP adapter after T2 caller recovery is closed, preserving the internal Capability/Action/Policy semantics.

## 10. Process/provenance lesson

A process, worker, sandbox, browser, or vendor runtime is replaceable execution machinery.
Durable semantic identity belongs to the Run/Effect/operation layer.

This remains one of the strongest cross-vendor architectural conclusions from the discussion.

## 11. Current implementation scope

The Muse/Anthropic review produced architecture/security documentation only; it did not justify runtime implementation changes.

Therefore:
- no generic VM/sandbox added
- no eBPF taint mechanism added
- no browser automation layer added
- no multi-agent/event-bus layer added
- no general exactly-once claim added
- no Room/WorkManager addition as a response to T2

Status: ESTABLISHED for current repository scope.

## 12. Operating provenance rule

For every important engineering action record:
repository + branch + exact base/head or commit + files/changes + executed command/experiment + actual result + claim/status + interpretation boundary + next discriminating action.

Conversation-only work is CONVERSATION-ONLY and is not repository evidence.

## Next discriminating actions

1. B4/B5 caller recovery and durable UNKNOWN_OUTCOME.
2. B6 concurrent recovery.
3. B7 stale ordering.
4. Credential-use isolation.
5. Provenance-aware egress.
6. Minimal event-sufficiency test.
7. Native MCP authorization/lifecycle re-audit.
8. First high-power capability containment test.

No broader autonomy substrate should be promoted ahead of these gates.
