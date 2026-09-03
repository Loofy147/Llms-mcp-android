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

**Real:** the source under `app/src/main/` is present in the repository. The current prototype contains a direct Anthropic client, streaming response parsing, client-side demo tools, MCP configuration, and a Compose UI.

**Not done:** the new agent architecture is not implemented yet. The architecture documents deliberately describe the target rather than pretending those capabilities already exist.

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
Android's built-in `org.json` for parsing. Settings persist; conversation
history does not survive an app restart yet.

**How MCP support currently works:** this app does not implement the MCP
protocol itself. It configures remote MCP servers and passes that configuration
to the Anthropic connector, which performs the server-side connection and tool
resolution for this current path.

Client-side tools (`get_current_time`, `calculate`) work differently: the
model requests them, the app executes them locally, and the result is sent
back in a follow-up request.

## Known prototype limitations

- API key is in plain `SharedPreferences`; this is not an acceptable product security baseline.
- No durable conversation history or Mission/Task/Run state.
- No local privacy/egress policy engine or approval object.
- No verified evidence ledger.
- MCP is currently delegated to the Anthropic connector rather than implemented as an app-owned capability adapter.
- No model-provider abstraction beyond the current Anthropic client.
- No general capability scope/effect model beyond the demo ToolRegistry.

## Build

The repository includes `.github/workflows/build-apk.yml` for a hosted debug build. A normal local Android development environment can also build the project after dependency synchronization.
