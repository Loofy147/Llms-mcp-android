# Architecture Documentation

This directory is the controlled reference for the architecture of `Llms-mcp-android`.

## Canonical documents

### `NORTH_STAR_ARCHITECTURE_v0.1.md`

Defines the target system boundary, domain model, control plane, runtime, capability model, provider boundary, MCP position, Android integration surfaces, persistence strategy, background execution model, and the intended reuse boundary with Agora.

### `DECISION_REGISTER_v0.1.md`

Records foundational decisions, invariants, product policies, technology choices, experiments, rejected designs, and change-control rules.

### `../security/PRIVACY_SECURITY_INVARIANTS_v0.1.md`

Defines security/privacy properties that must remain true across implementations, providers, capabilities, UI surfaces, and distribution profiles.

### `ASSUMPTION_REGISTER_v0.1.md`

Separates provisional assumptions from rejected assumptions and open questions. It is the main anti-drift document for preventing speculation from becoming architecture.

### `ECOSYSTEM_RESEARCH_2026-09.md`

Records external repositories, platform APIs, protocols, and agent frameworks reviewed for reusable implementation value. Each item is classified as an adoption pattern, adapter candidate, reference, experiment, or reject; this prevents technology popularity from silently becoming an architecture decision.

## Review rule

Use these documents together. Do not treat a technology choice as a foundational architectural decision unless the Decision Register says so.

When implementation evidence contradicts an assumption, update the register before changing the architecture silently.

External research is input, not authority. A repository or framework may influence an experiment or adapter design, but it does not override the project's foundational authority, privacy, or evidence invariants.