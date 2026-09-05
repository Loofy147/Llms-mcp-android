# Architecture Documentation

This directory is the controlled architectural reference for `Llms-mcp-android`.

## Current baseline

`NORTH_STAR_ARCHITECTURE_v0.2.md` is the current reconciled North Star. It supersedes v0.1 as the active design vocabulary while preserving the earlier documents as historical review artifacts.

`DECISION_REGISTER_v0.2.md` records current foundational decisions, invariants, policies, technology choices, experiments, and rejected designs.

`ASSUMPTION_REGISTER_v0.2.md` records hypotheses and open questions that must not silently become architecture.

`../security/PRIVACY_SECURITY_INVARIANTS_v0.2.md` is the current security/privacy baseline.

`REVIEW_CHECKLIST_v0.2.md` is the implementation and promotion gate.

`CURRENT_STATE_AUDIT_2026-09-05.md` is the current whole-app implementation truth record after PR #7.

`PERSONAL_DEVELOPER_CAPABILITY_RESEARCH_2026-09-05.md` translates the verification-first thesis into a proposed capability catalog and benchmark for personal developer use. It is research input, not yet a committed runtime catalog.

`ECOSYSTEM_RESEARCH_2026-09.md` remains research input. External repositories and protocols do not override the architecture.

`PATTERN_LABORATORY_REGISTRY_v0.1.md` is the controlled registry for extracting reusable patterns, invariants, experiments, and rejected designs from external projects across domains.

## Canonical semantic distinction

```text
Activation -> Action -> CapabilityInvocation -> execution
                 ^
                 |
          optional Model selection

Tool = exposure/interface
Capability = controlled primitive effect
Action = reusable execution contract
```

## Verification-first principle

Automation priority is increased when a capability has high observability, independent verification, clear scope, useful reuse, and reversible/idempotent behavior. High ambiguity, high consequence, and external uncertainty lower priority.

The preferred progression is:

```text
observable effect
    -> independent verification
    -> attributable evidence
    -> safe automation
    -> broader composition
```

This principle is a prioritization heuristic and must be validated by measured runs rather than treated as a universal law.

## Pattern laboratory rule

External repositories are treated as laboratories, not templates. A finding must be classified as `ADOPT`, `ADAPT`, `REJECT`, or `EXPERIMENT`, and should produce an architectural decision, invariant, measurable experiment, implementation contract, or explicit rejection before it influences the core.

## Review rule

Do not elevate a library, protocol, model, or repository into a foundational dependency merely because it is popular or mature. Promote it only when its measured behavior satisfies a required contract without weakening authority, privacy, evidence, or replaceability.
