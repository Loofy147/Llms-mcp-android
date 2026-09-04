# Whole-App Unification v0.1

Status: Active engineering baseline
Date: 2026-09-04

## Purpose

The application must have one execution authority. Chat, model providers, native tools, automation, and future integrations are activation/exposure surfaces; none may become a second execution authority.

## Canonical boundary

```text
Surface
  -> Activation
  -> Action resolution
  -> Policy
  -> Approval (when required)
  -> Egress (when remote/protected data is involved)
  -> Run
  -> CapabilityInvocation
  -> CapabilityExecutor
  -> Observation
  -> Verification
  -> Evidence
```

## Current migration rule

The existing Anthropic conversation adapter remains responsible for provider transport and streaming. Local tool execution is behind the runtime's Action/Capability boundary. Remote provider requests also cross the local EgressPolicy boundary. MCP transport remains provider-owned until a native internal MCP adapter exists.

No new direct effectful path may be added to the UI, model adapter, or ToolRegistry.

## Authority rules

- `AgentRuntime` owns execution admission and lifecycle semantics.
- `CapabilityExecutor` is the only runtime-owned effect boundary.
- `EgressPolicy` is the local decision boundary for remote data transmission.
- `ToolRegistry` may describe model-facing tools but must not execute effects directly.
- `ModelProvider` may reason/stream but cannot authorize effects.
- `SettingsStore` persists preferences/configuration; `CredentialStore` owns secrets.
- A provider adapter cannot bypass local policy or approval by issuing a local effectful tool call.
- Remote/provider execution does not transfer local authorization ownership.

## Migration order

1. Stabilize persistent approval and terminal-state semantics.
2. Introduce one application-level runtime composition root for durable runtime stores and capability adapters.
3. Convert native local tools into Action/Capability implementations.
4. Route model tool calls through the runtime boundary.
5. Remove direct execution responsibility from `ToolRegistry`.
6. Fix settings/credential lifecycle leaks and backup/privacy surfaces.
7. Introduce explicit local EgressPolicy before protected remote data flows.
8. Extract MCP into an internal protocol adapter whose locally authorized effects converge on runtime semantics.
9. Add Android lifecycle/process-death integration tests.
10. Only then expand activation surfaces.

## Current egress slice

`EgressRequest` classifies the broad data carried by a remote request (`USER_CONTENT`, `USER_CONFIGURATION`, `CREDENTIAL`). `AllowlistEgressPolicy` currently enforces HTTPS and an explicit destination-host allowlist, and rejects credentials embedded in destination URLs. The Android composition currently allows only `api.anthropic.com`.

This is a control boundary, not yet a complete classification, minimization, or redaction system.

## Promotion criterion

The migration is not considered complete while an effect can execute through a path other than `CapabilityExecutor`, while protected remote data can bypass `EgressPolicy`, or while the UI/model path and runtime path maintain conflicting authority semantics.
