# Decision Register Audit Addendum — 2026-09-05

Status: Active addendum
Date: 2026-09-05
Related: `DECISION_REGISTER_v0.2.md`, `ARCHITECTURE_AUDIT_LEDGER_2026-09-05.md`

## Purpose

This addendum records which 2026-09-05 cross-angle audit findings affect architecture decisions without prematurely promoting unresolved implementation hypotheses to accepted decisions.

## Decisions reaffirmed

### D-16 — Verification-first capability prioritization

**ACCEPTED / reaffirmed.** Capability adoption should be prioritized by independent verifiability and observability before breadth of autonomy. The developer capability family is a proving ground for the runtime, not a commitment to a general coding-agent product.

### D-17 — Observation and verification are distinct semantic stages

**PROPOSED / OPEN.** Capability execution should produce observations; an independent verifier should evaluate whether observations satisfy an expected postcondition. The executor must not be the sole authority that declares its own success.

Promotion condition: implement and test a first verifier contract and demonstrate that a false/self-reported success can be rejected from authoritative Evidence.

### D-18 — Durable execution requires a recoverable transaction model

**PROPOSED / OPEN.** Approval consumption, execution intent, effect reservation, and Run transitions must form a recoverable durable state machine. Approval consumption must not leave an unrecoverable gap before execution begins.

Promotion condition: crash-injection tests demonstrate coherent recovery across approval, Run, and effect state.

### D-19 — Historical execution must have immutable semantic provenance

**PROPOSED / OPEN.** Action ID/version alone is insufficient for future forensic guarantees. Durable execution records should reference an immutable Action definition hash or snapshot identity.

Promotion condition: historical Run reconstruction can prove which exact Action definition was used.

### D-20 — Authorization must eventually operate at concrete invocation level

**PROPOSED / OPEN.** Action-level authorization is not sufficient for resource-sensitive capabilities. Policy must eventually evaluate concrete scope and sensitive parameters before effect execution.

Promotion condition: at least one resource-sensitive capability demonstrates parameter/scope-dependent allow/deny behavior.

## Findings kept as open architecture gates

The following findings are intentionally not promoted to accepted decisions yet:

```text
A-01  direct ALLOW planning failure normalization
A-02  approval/execution transaction gap
A-03  cross-instance/process journal concurrency
A-04  non-atomic multi-effect reservation
A-05  historical Action semantic provenance
A-06  activation source not used for authorization
A-07  caller identity not authenticated
A-08  invocation-level authorization gap
A-09  scope is a label set rather than structured resource constraint
A-10  partially self-attested verification
A-11  under-specified verifier evidence
A-12  trusted effect classification
A-13  missing tool-loop resource budget
A-14  missing HTTP/tool-loop cancellation propagation
A-15  incomplete session/correlation provenance
A-16  non-strict streaming terminal semantics
A-17  synchronous runtime initialization/recovery
A-18  name-based MCP identity
A-19  shallow MCP configuration validation
A-20  incomplete data minimization/redaction
A-21  secrets carried in ordinary AppSettings state
A-22  unresolved Android backup semantics
A-23  debug-only CI evidence
A-24  baseline-only supply-chain controls
A-25  adversarial test coverage gaps
```

The full analysis is maintained in `ARCHITECTURE_AUDIT_LEDGER_2026-09-05.md`.

## Research record

The capability research and its evidence classification remain active:

- APEX-Agents: 24.0% Pass@1 reported for the reviewed benchmark.
- MobileWorld: 20.9% end-to-end success reported for the reviewed benchmark.
- `0.85^10 ≈ 19.7%` and `0.95^10 ≈ 59.9%`: explanatory arithmetic under an independence assumption.
- PersonalAgents: bounded email/calendar/web specialization signal.
- Gemini Spark: bounded composition across connected personal applications.
- FinAgent: research/simulation composition signal.
- Sherlocks: 73-incident dataset with the cited repair-time comparison; single-source operational signal.

None of these external findings are treated as direct evidence of this repository's runtime reliability.

## Capability adoption experiment record

The planned developer family remains experimental:

```text
E-10  Personal developer capability family
E-11  Verification-first ranking
E-12  direct planning failure normalization
E-13  approval/execution crash recovery
E-14  concurrent journal/approval races
E-15  immutable Action provenance
E-16  source/invocation-aware authorization
E-17  independent verifier
E-18  tool-loop budget/cancellation
E-19  correlation/provenance chain
E-20  MCP identity/validation
E-21  secret-free settings model
E-22  backup/restore lifecycle
E-23  release/dependency integrity
```

## Control rule

No proposed D-17 through D-20 becomes an accepted foundational decision until the corresponding experiment produces evidence at the required level. The project therefore preserves the existing architecture while explicitly recording where the current implementation is not yet strong enough to support stronger guarantees.
