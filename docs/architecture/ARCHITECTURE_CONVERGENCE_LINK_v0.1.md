# Architecture Convergence Link v0.1

Canonical convergence contract:
https://github.com/Loofy147/Open-System-One/blob/architecture/convergence-v0.1-2026-10-02/docs/CANONICAL_ARCHITECTURE_CONVERGENCE_v0.1.md

## Why

Llms-mcp-android owns the Android local execution authority. It is the strongest concrete implementation of the separation between Action, Capability, Tool, Model, Policy, Approval, Egress, Run, Observation, Verification, and Evidence.

## What propagates from Android

- Action/Capability/Tool semantic separation;
- capability invocation identity and effect identity;
- policy/approval/egress boundary semantics;
- lifecycle recovery and UNKNOWN-effect handling;
- capability execution behind one runtime-owned executor boundary.

## What does not propagate automatically

- Android-specific storage or UI as the canonical schema;
- a model vendor/provider as the canonical model layer;
- Android APIs as universal capability definitions.

## Local obligation

For convergence, Android should provide conformance adapters/fixtures over the shared semantic intersection while keeping Android-specific fields local.

Current Android main ref pinned during convergence audit:
4880767491d29a5f105765683c8224c82244d1a7.

The runtime remains a vertical proof, not production-complete autonomous execution.
