# Architecture Documentation

This directory is the controlled architectural reference for `Llms-mcp-android`.

## Current baseline

`NORTH_STAR_ARCHITECTURE_v0.2.md` is the current reconciled North Star. It supersedes v0.1 as the active design vocabulary while preserving the earlier documents as historical review artifacts.

`DECISION_REGISTER_v0.2.md` records current foundational decisions, invariants, policies, technology choices, experiments, and rejected designs.

`ASSUMPTION_REGISTER_v0.2.md` records hypotheses and open questions that must not silently become architecture.

`../security/PRIVACY_SECURITY_INVARIANTS_v0.2.md` is the current security/privacy baseline.

`REVIEW_CHECKLIST_v0.2.md` is the implementation gate.

`ECOSYSTEM_RESEARCH_2026-09.md` remains research input. External repositories and protocols do not override the architecture.

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

## Review rule

Do not elevate a library, protocol, model, or repository into a foundational dependency merely because it is popular or mature. Promote it only when its measured behavior satisfies a required contract without weakening authority, privacy, evidence, or replaceability.
