# LLM MCP Android

A native Android Kotlin/Jetpack Compose project evolving into a **user-owned mobile agent runtime and control plane**.

## What the project is

The product idea is simple:

> **A personal assistant on the phone that can actually execute things, not only talk.**

Simple/repeated operations can execute directly and deterministically. Tasks that require interpretation or choice can use an optional Model. In both cases, local policy remains the authority boundary.

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

The model may select an Action from a policy-filtered set, but model output never authorizes execution. Deterministic Actions can execute without any model. Chat is one activation surface, not the runtime itself.

## Architecture documents

- `docs/architecture/NORTH_STAR_ARCHITECTURE_v0.2.md`
- `docs/architecture/DECISION_REGISTER_v0.2.md`
- `docs/architecture/ASSUMPTION_REGISTER_v0.2.md`
- `docs/architecture/REVIEW_CHECKLIST_v0.2.md`
- `docs/architecture/ACTION_MODEL_v0.2.md`
- `docs/architecture/IMPLEMENTATION_RECONCILIATION_v0.2.md`
- `docs/architecture/ECOSYSTEM_RESEARCH_2026-09.md`
- `docs/security/PRIVACY_SECURITY_INVARIANTS_v0.2.md`

The reconciliation document explicitly separates accepted architecture from implemented evidence and tracks contradictions that still remain.

## Current implementation slice

The open `agent-runtime/vertical-slice-v0.1` branch introduces the first runtime proof: `Activation`, `Action`, `Capability`, `Policy`, `Run`, `Observation`, `Verification`, and `Evidence`, with deterministic execution and explicit approval-vs-denial semantics.

It also introduces:

- a provider-neutral `ModelProvider` boundary with the current Anthropic implementation behind an adapter;
- a Keystore-backed `CredentialStore` for API/MCP authorization material;
- a one-time migration path away from the previous plaintext settings representation;
- CI unit-test execution before debug APK assembly.

This is still a vertical proof, not a completed production runtime.

## Known open gaps

- Run and Evidence are not yet durable across process death/restart.
- Approval context is not yet persisted with replay protection.
- Explicit local data-egress policy is not yet implemented.
- MCP still lives in the current Anthropic adapter rather than a native internal MCP adapter.
- The current catalog/executor remains deliberately small and deterministic.
- Conversation history is still in-memory.
- Android-native activation adapters beyond the current UI path are not yet implemented.

These gaps are tracked as engineering gates, not hidden behind feature claims.

## Build

`.github/workflows/build-apk.yml` provides a hosted debug build path and runs JVM unit tests before assembling the APK. A normal Android development environment can build the project after dependency synchronization.
