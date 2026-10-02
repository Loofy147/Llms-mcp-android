# Hack Apertus integration

This branch adds a CSCS Inference provider for the Hack Apertus online hackathon.

## Runtime route

Persist the model field as:

`cscs:swiss-ai/Apertus-v1.5-8B`

or:

`cscs:swiss-ai/Apertus-v1.5-70B`

The provider uses the same `AgentRuntime` and `RuntimeToolGateway` as the existing Anthropic/Nebius paths. CSCS is therefore a reasoning provider, not a second local-effects authority.

## Access

The official Hack Apertus announcement states that CSCS provides inference support to all online participants. The CSCS API is OpenAI-compatible at:

`https://api.inference.cscs.ch/v1`

The current service exposes Apertus 1.5 8B and 70B, while the `-thinking` variants have tool use disabled.

A participant-provided `CSCS_INFERENCE_API_KEY` is required for live verification. Do not commit it. Use the Android app's existing Keystore-backed API-key setting for local testing and a GitHub Actions repository secret for CI smoke tests.

## Verification boundary

The repository can be build-tested without CSCS access. Live evidence requires:

1. a valid CSCS inference credential;
2. successful `/models` discovery of the selected Apertus model;
3. a real `/chat/completions` response;
4. a real function/tool call for `remember`;
5. Android device verification that the tool call still enters `RuntimeToolGateway` and the canonical runtime.

Until steps 1–4 run successfully, the live-provider status remains OPEN.
