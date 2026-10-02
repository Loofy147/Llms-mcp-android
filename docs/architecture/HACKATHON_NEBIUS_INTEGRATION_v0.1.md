# Nebius/Nemotron Personal AI Integration Contract v0.1

Status: IMPLEMENTATION CANDIDATE
Repository: Loofy147/Llms-mcp-android
Branch: hackathon/nebius-personal-ai
Base: 78afd5d3d7716fe31a5f109a5796eb3db7f99970

## Purpose

Adapt the existing Android runtime into a valid Nebius x NVIDIA Global AI Hackathon Personal AI submission without introducing a second execution authority.

## Existing authority

The current architecture already separates:

ModelProvider
-> provider transport/reasoning

RuntimeToolGateway
-> model-facing tool translation

AgentRuntime
-> action admission, policy, approval, run lifecycle

CapabilityExecutor
-> local effect boundary

Observation / Verification / Evidence
-> executable outcome evidence

EgressPolicy
-> protected remote transport admission

CredentialStore
-> protected credential material

These boundaries remain canonical.

## Nebius integration boundary

Implement one concrete provider adapter:

Nebius/Nemotron
-> ModelProvider
-> existing EgressPolicy
-> existing CredentialStore
-> existing RuntimeToolGateway
-> existing AgentRuntime

The adapter must not execute Android capabilities directly.

No provider-specific effect authority is allowed.

## First vertical slice

The smallest useful Personal AI slice is:

1. User starts a task.
2. Nebius/Nemotron produces reasoning and, when needed, a model tool request.
3. Tool request enters RuntimeToolGateway.
4. AgentRuntime evaluates the requested Action and policy.
5. Approval is required when the Action requires it.
6. CapabilityExecutor performs one bounded local action.
7. Observation and verification are persisted in the Run/Evidence path.
8. The model receives the verified result and continues the conversation.

The demo should show one end-to-end task, not a generic chatbot.

## Required tests before live-provider testing

- Provider selection chooses Nebius without changing runtime authority.
- Nebius destination is allowed only through EgressPolicy.
- Wrong scheme / wrong host / embedded destination credentials are denied.
- Credential material never appears in ordinary settings serialization.
- Model tool requests cannot bypass RuntimeToolGateway.
- Existing deterministic local Actions still execute without a model.
- Provider failure does not create a false successful Run.
- A live provider response cannot authorize a local effect by itself.

## Live-provider gate

Do not invent the Nebius request/response contract from memory.

The live adapter remains blocked until the actual Nebius Token Factory/API specimen is obtained and recorded, including:

- endpoint
- authentication mechanism
- model identifier
- request format
- streaming behavior
- tool/function-calling shape
- error semantics
- usage metadata

Once a real specimen is available, encode only the fields demonstrated by that specimen.

## Hackathon gates

The resulting project must demonstrate:

- NVIDIA open-source model usage through Nebius Token Factory or Nebius AI Cloud.
- A Personal AI workflow rather than a provider showcase.
- Persistent context/tools and a bounded real action.
- Public repository and OSI-compatible open-source license before submission.
- Working demonstration suitable for the required video.
- Explicit explanation of what Nebius and NVIDIA contribute.

## Kill test

Stop implementation if either condition becomes true:

1. Nebius integration requires bypassing the existing Policy / Egress / Run / CapabilityExecutor boundaries.
2. The resulting product is only a model/provider demonstration and has no durable user workflow that survives the hackathon.

## Evidence state

ESTABLISHED:
- Provider-neutral ModelProvider exists.
- Anthropic is currently one vendor adapter.
- EgressPolicy is an explicit local remote-data boundary.
- CredentialStore is separated from ordinary settings.
- AgentRuntime owns local execution semantics.

UNKNOWN:
- Exact Nebius API contract for this repository.
- Real-device Nebius end-to-end execution.
- Tool calling compatibility with the current RuntimeToolGateway.
- Performance/latency/cost under the intended Personal AI workflow.

No UNKNOWN item may be presented as verified until a concrete run and artifact exist.
