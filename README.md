# LLM Chat (Android)

A native Android chat client (Kotlin + Jetpack Compose) that talks directly
to the Anthropic Messages API: streaming chat, client-side tool calling, and
MCP server support via Anthropic's MCP connector.

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
package registries, api.anthropic.com, a few others). Building an Android
APK needs Google's Maven repo (`maven.google.com`) for AndroidX/Compose and
Gradle's own distribution server (`services.gradle.org`) — I checked directly
and both are unreachable from here (`host_not_allowed`), along with
`dl.google.com` and even plain Maven Central. So I could write and reason
about every file, but I could not run `./gradlew assembleDebug` to produce
the actual `.apk` — see "Finishing the build" below. This isn't a problem
with the code; it's that the last, mechanical step needs a normal internet
connection, which any Android Studio install already has.

## Architecture

```
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
  combination I'm confident is internally consistent. While researching the
  CI workflow I confirmed the Android Gradle Plugin has since moved to the
  9.x line (9.3.0 as of July 2026), which changed some core DSL/Kotlin
  integration behavior. I deliberately didn't chase that: rewriting for a
  major version I can't compile-test myself risked trading a known-good
  baseline for new, unverified breakage. Android Studio will likely offer
  an Upgrade Assistant prompt when you open this — safe to accept or
  decline, the project builds either way.

None of these are hard — they're just not what a first working version needs.

## Finishing the build

You need Android Studio (Ladybird/2024.x or newer) or the command-line
Android SDK + a real internet connection — i.e., what any Android
developer already has. Three ways to get the actual `.apk`:

1. **Android Studio (simplest):** open this folder as a project. It will
   offer to regenerate the Gradle wrapper it's missing (see below), sync
   dependencies from Google's/Maven's real servers, and then
   Build → Build App Bundle(s)/APK(s) → Build APK(s).
2. **Command line**, once you have a system Gradle or a working wrapper:
   `./gradlew assembleDebug` — output lands in
   `app/build/outputs/apk/debug/app-debug.apk`.
3. **GitHub Actions (no Android Studio needed at all)** — see below.

Note on the Gradle wrapper: `gradle/wrapper/gradle-wrapper.properties` is
included, but not `gradle-wrapper.jar` itself (a small binary normally
fetched from `services.gradle.org` — also unreachable from this sandbox).
Android Studio detects this and regenerates it automatically on first open;
from the command line, `gradle wrapper` (if you have any Gradle installed)
does the same.

## Building via GitHub Actions (no local Android setup needed)

`.github/workflows/build-apk.yml` builds a debug APK on GitHub's own
runners — which have completely normal internet access — and attaches it
to the workflow run as a downloadable artifact. It deliberately doesn't
rely on the (missing) local Gradle wrapper: it installs Gradle 8.9 directly
via `gradle/actions/setup-gradle` and invokes `gradle` rather than
`./gradlew`. Verified with `actionlint` (GitHub's own workflow schema, not
just generic YAML) before being included here — 0 issues.

To use it:

```bash
cd llm-mcp-client        # this folder becomes the repo root
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<you>/<repo-name>.git
git push -u origin main
```

Then, on GitHub: **Actions tab → Build debug APK → this run → Artifacts**
(bottom of the page) → download `llm-chat-debug-apk.zip`, which contains
the `.apk`. It also reruns automatically on every future push, and can be
triggered manually any time from the Actions tab ("Run workflow").

One thing I couldn't do for you: create the GitHub repo and push this
myself — I don't have a connector for that available right now, so the
`git` commands above are on you (or ask me again after connecting one, if
that becomes available).
