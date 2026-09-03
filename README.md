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

**Real:** the source under `app/src/main/` is present in the repository. The request/response schema and MCP connector shape were previously checked against the then-current Anthropic documentation; current provider contracts must be revalidated before production use.

**Not done:** the new agent architecture is not implemented yet. The current prototype still has a direct Anthropic client, client-side demo tools, in-memory conversation state, and a simple settings store. The architecture documents deliberately describe the target rather than pretending those capabilities already exist.

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
survive an app restart yet — the simplest thing that demonstrates the real
pattern, not a feature-complete product.

**How MCP support actually works here:** this app does *not* implement the
MCP protocol itself. It has a UI for adding MCP server URLs, and passes
them straight through in the `mcp_servers` field of every request; Anthropic's
API performs the server-side connection and tool resolution for this path.

Client-side tools (`get_current_time`, `calculate`) work differently: the
model asks for them, the app executes them locally, and sends the result
back in a follow-up request. That loop lives in `AnthropicClient.runConversation()`.

## Known prototype limitations

- API key is in plain `SharedPreferences`; this is not an acceptable product security baseline.
- No retry/backoff on network failure and no request cancellation when a new message arrives.
- No durable conversation history or Mission/Task/Run state.
- No local privacy/egress policy engine or approval object.
- No verified evidence ledger.
- MCP is currently delegated to the Anthropic connector rather than implemented as an app-owned capability adapter.
- The build is intentionally conservative until the architecture is validated; technology versions are not themselves architectural decisions.

## Build

The repository includes `.github/workflows/build-apk.yml` for a hosted debug build. A normal local Android development environment can also build the project after dependency synchronization.
