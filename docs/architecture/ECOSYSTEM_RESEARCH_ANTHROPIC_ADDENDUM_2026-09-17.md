# Ecosystem Research Addendum — Anthropic — 2026-09-17

Status: **RESEARCH INPUT / NOT AN IMPLEMENTATION COMMITMENT**

This addendum attaches the current Anthropic findings to `ECOSYSTEM_RESEARCH_2026-09.md` without rewriting the historical ecosystem survey.

## External value classification

| Finding | Classification | Why |
|---|---|---|
| Agent/environment/session separation | ADOPT PATTERN | Matches our control-plane/runtime separation. |
| Brain/hands separation | ADOPT PATTERN | Reinforces `CapabilityExecutor` as the semantic execution boundary. |
| CredentialRef/Vault pattern | EXPERIMENT | Directly addresses an unresolved secret-use boundary. |
| Policy vs human approval vs auto evaluation | ADOPT PATTERN | Confirms our independent policy/approval semantics. |
| Environment containment | ADOPT PATTERN / EXPERIMENT | Important for future high-power capabilities; not for the current small core. |
| Persistent event history | EXPERIMENT | Useful for B4-B7 only if Run/Effect state proves insufficient. |
| Memory stores separate from session | ADOPT PATTERN | Avoids conflating memory, execution history, and evidence. |
| MCP auth separation | ADAPTER CANDIDATE | Relevant when native MCP extraction starts. |
| Self-hosted execution plane | REFERENCE / FUTURE ADAPTER | Confirms control-plane/data-plane split without requiring cloud coupling. |
| Risk classifier | REFERENCE only | Useful advisory signal, not authoritative security boundary. |

## Primary sources

- https://www.anthropic.com/engineering/managed-agents
- https://platform.claude.com/docs/en/managed-agents/overview
- https://platform.claude.com/docs/en/managed-agents/events-and-streaming
- https://platform.claude.com/docs/en/managed-agents/permission-policies
- https://platform.claude.com/docs/en/managed-agents/mcp-connector
- https://platform.claude.com/docs/en/api/beta/vaults/credentials
- https://platform.claude.com/docs/en/managed-agents/memory
- https://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes
- https://www.anthropic.com/engineering/how-we-contain-claude
- https://www.anthropic.com/engineering/claude-code-auto-mode
- https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/

## Local mapping

Detailed mapping: `ANTHROPIC_REFERENCE_REVIEW_2026-09-17.md`.

Current implementation/gate state: `ANTHROPIC_ADOPTION_DELTAS_2026-09-17.md` and `ARCHITECTURE_STATE_SYNC_2026-09-17.md`.

Decision authority: `DECISION_REGISTER_v0.2.md`.

The external source does not upgrade local claims. All local claims remain subject to repository code and executed experiments.
