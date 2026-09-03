# LLM Chat (Android)

A native Android chat client (Kotlin + Jetpack Compose) that talks directly
to the Anthropic Messages API: streaming chat, client-side tool calling, and
MCP server support via Anthropic's MCP connector.

## Architecture baseline

The project is now being evaluated as a **user-owned mobile agent runtime** rather than only a chat/MCP client. Before implementing that redesign, the architecture has been separated into explicit decisions, invariants, assumptions, experiments, and rejected designs.

See:

- `docs/architecture/NORTH_STAR_ARCHITECTURE_v0.1.md` — target architecture and system boundaries.
- `docs/architecture/DECISION_REGISTER_v0.1.md` — decisions, invariants, technology choices, experiments, and rejections.
- `docs/architecture/ASSUMPTION_REGISTER_v0.1.md` — provisional assumptions and open questions.
- `docs/security/PRIVACY_SECURITY_INVARIANTS_v0.1.md` — security and privacy invariants.

The documentation baseline is intentionally separate from implementation work. The current code remains a small prototype until the architecture review is accepted.

## What's real here, and what isn't yet

**Real:** every line of code in `app/src/main/`. The request/response schema
was verified on 2026-08-30 against the current docs at
`platform.claude.com/docs/en/agents-and-tools/mcp-connector` (not from
memory — the connector changed its beta header and request shape at some
point after most blog posts/tutorials about it were written, so this project
uses the *current* form: header `anthropic-beta: mcp-client-2025-11-20`,
and an `mcp_toolset` entry in `tools` for every server listed in
`mcp_servers`). The SSE parsing state machine in `AnthropicClient.kt` was
tested standalone against a mock stream shaped like the documented event
format (including the newer `mcp_tool_use`/`mcp_tool_result` block types)
before being written into this project.

**Not done: compiling it.** This project was written in a sandboxed
environment whose network access is limited to a small allowlist (GitHub,
package registries, api.anthropic.com, a few others). Building an Android APK
needs Google's Maven repo and Gradle distribution infrastructure. Those were
unreachable from the original sandbox, so the repository includes GitHub
Actions for a normal-network build instead of claiming a local APK was built.

## Current prototype architecture

```text
app/src/main/java/com/hicham/llmchat/
├── MainActivity.kt         entry point, switches between Chat/Settings
├── model/ChatModels.kt     data classes + JSON serialization
├── data/
│   ├── SettingsStore.kt    SharedPreferences-backed settings
│   ├── ToolRegistry.kt     client-side tools (get_current_time, calculate)
│   └── AnthropicClient.kt  request building, SSE streaming, tool-use loop
└── ui/
    ├── ChatViewModel.kt
    ├── ChatScreen.kt
    ├── SettingsScreen.kt
    └── Theme.kt
```

Deliberately minimal dependencies: Compose (UI), OkHttp (networking), and
Android's built-in `org.json` for parsing — no Room, no DataStore, no
Retrofit, no DI framework. Settings persist; conversation history does not
survive an app restart yet (kept in memory only) — the simplest thing that
demonstrates the real pattern, not a feature-complete product.

**How MCP support actually works here:** this app does *not* implement the
MCP protocol itself. It has a UI for adding MCP server URLs, and passes
them straight through in the `mcp_servers` field of every request; Anthropic's
API does the actual connecting, tool discovery, and tool execution
server-side. That's *why* this is a small amount of code — see the
`mcp_servers` handling in `AnthropicClient.buildRequestBody()`. Only
remote (Streamable HTTP / SSE) MCP servers work this way — not local
stdio-based ones, which don't really make sense on Android anyway.

Client-side tools (`get_current_time`, `calculate`) work differently: the
model asks for them, the app executes them locally, and sends the result
back in a follow-up request — the classic tool-use loop. That loop lives in
`AnthropicClient.runConversation()`.

## Known simplifications (v1, on purpose)

- API key is in plain `SharedPreferences`, not `EncryptedSharedPreferences`.
  Fine for a single-user device; worth hardening before wider distribution.
- No retry/backoff on network failure, no request cancellation if you send
  a new message mid-stream, no persistence of chat history across restarts.
- One demo MCP server config, no OAuth flow for MCP servers that need it
  (only static bearer tokens via `authorization_token`).
- App icon is a plain vector drawable, not a proper adaptive icon.
- This project targets AGP 8.6.0 / Gradle 8.9 / Kotlin 2.0.21 — a
  combination originally selected as an internally consistent baseline.

None of these are hard — they're just not what a first working version needs.

## Finishing the build

You need Android Studio or the command-line Android SDK with normal network
access. GitHub Actions can perform the build on a hosted runner using the
workflow in `.github/workflows/build-apk.yml`.
