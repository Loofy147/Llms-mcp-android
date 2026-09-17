# Muse Adoption Deltas — 2026-09-17

Status: **RESEARCH-TO-ENGINEERING BRIDGE / NO RUNTIME CHANGE**

This file is the compact bridge from Meta Muse findings to local engineering gates. It does not promote vendor claims into local proof.

## Keep / strengthen

- Model/reasoning is never execution authority.
- Approval is an authoritative control-plane record, not a chat token.
- CapabilityExecutor remains the controlled effect boundary.
- Egress remains a separate authorization decision.
- Runtime/process/sandbox are replaceable execution mechanisms, not durable semantic identity.
- Execution history, Evidence, Memory, and UI activity views remain distinct.

## New research gates

### MUSE-G1 — caller-side recovery

Complete B4/B5 and durable `UNKNOWN_OUTCOME` before adding broader orchestration.

### MUSE-G2 — concurrency and stale results

Complete B6/B7. Require authoritative operation/attempt identity and reject stale results.

### MUSE-G3 — credential-use isolation

Define `CredentialRef` and prove raw credential values cannot cross model/tool/action/evidence/logging boundaries.

### MUSE-G4 — provenance-aware egress

Extend current coarse data-class egress policy only after a local experiment demonstrates the required provenance invariant.

### MUSE-G5 — containment for high-power capabilities

For the first genuinely high-power capability, specify filesystem/network/environment boundaries and test blast-radius limits under policy/model failure.

### MUSE-G6 — event sufficiency

Determine whether Run/Effect state can represent B4-B7 safely. Add a minimal event-history primitive only if an empirical gap is demonstrated.

## Defer

- General Secure VM/VM layer in the APK.
- Kernel-level taint tracking/eBPF replication.
- Browser broker before a real browser capability exists.
- Multi-agent coordinator.
- Generic event bus.
- Confidential-computing infrastructure.
- General exactly-once guarantee.

## External references

- https://ai.meta.com/muse/
- https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse
- https://research.meta.ai/blog/addressing-third-party-testing-misconfiguration-muse-spark-1-1
- https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/
