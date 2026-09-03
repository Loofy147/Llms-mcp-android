# LLM MCP Android

A native Android Kotlin/Jetpack Compose prototype evolving into a **user-owned mobile agent runtime and control plane**.

## Current architecture baseline

The active design baseline is `docs/architecture/NORTH_STAR_ARCHITECTURE_v0.2.md`.

The central separation is:

```text
Activation -> Action -> CapabilityInvocation -> execution
                  ^
                  |
             optional Model

Capability = primitive controlled effect
Action     = reusable execution contract
Tool       = exposure/interface for a model/client
Model      = optional reasoning component
```

The model may select an Action from a policy-filtered set, but model output never authorizes execution. Deterministic Actions can execute without any model. Chat is therefore one activation surface, not the runtime itself.

## Architecture documents

- `docs/architecture/NORTH_STAR_ARCHITECTURE_v0.2.md`
- `docs/architecture/DECISION_REGISTER_v0.2.md`
- `docs/architecture/ASSUMPTION_REGISTER_v0.2.md`
- `docs/architecture/REVIEW_CHECKLIST_v0.2.md`
- `docs/architecture/ECOSYSTEM_RESEARCH_2026-09.md`
- `docs/security/PRIVACY_SECURITY_INVARIANTS_v0.2.md`

The older v0.1 documents remain as historical review artifacts. The new implementation should follow v0.2 unless an explicit decision record says otherwise.

## Current prototype

The code under `app/src/main/` is still a small direct Anthropic/Compose prototype with streaming, local demo tools, MCP configuration, settings persistence, and in-memory conversation state. The v0.2 runtime redesign is not yet implemented.

## Known prototype limitations

- API key remains in ordinary `SharedPreferences` and is not a product security baseline.
- Conversation history is not durable.
- No first-class Activation/Action/Capability runtime contract yet.
- No local policy/approval/egress engine.
- No verified evidence ledger.
- MCP is currently delegated to the Anthropic connector.
- No provider-neutral model runtime boundary beyond the current client.

## Build

`.github/workflows/build-apk.yml` provides a hosted debug build path. A normal Android development environment can build the project after dependency synchronization.
