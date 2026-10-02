# Gemini API integration v0.1

## Purpose

Gemini is an interchangeable model backend for the same Android runtime used by the Nebius and CSCS/Apertus paths.

## Transport

The integration uses Google's documented OpenAI-compatible endpoint:

`https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`

Authentication is sent as a Bearer Gemini API key. Gemini function calling is represented as standard OpenAI-style `tools` / `tool_calls` and translated through `RuntimeToolGateway` into the canonical `AgentRuntime`.

## Route

Configure the persisted model setting as:

`gemini:gemini-3.8-flash`

The existing API-key setting remains the credential source and is stored through `CredentialStore` / Android Keystore.

## Evidence boundary

- The code path is implemented.
- GitHub Actions can perform a live smoke test when `GEMINI_API_KEY` is configured as a repository secret.
- A live Gemini inference result is not claimed until that smoke test succeeds.
- Android real-device execution remains a separate verification event.

## Security

Do not commit Gemini API keys. Do not place them in source, documentation, issue comments, or build artifacts.
