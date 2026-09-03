# Privacy & Security Invariants v0.1

Date: 2026-09-03  
Status: **Foundational security baseline**

This document defines properties that must remain true regardless of model provider, capability adapter, UI, or deployment profile.

## 1. Authority invariant

The Android application is the local authority for:

- identity;
- policy;
- capability admission;
- user consent/approval;
- data-egress authorization;
- durable mission/task/run state;
- evidence and audit references.

A remote provider may execute reasoning or an admitted action, but it cannot redefine these local decisions.

## 2. Model non-authority invariant

```text
model output != authorization
model output != policy
model output != evidence
model output != identity
```

A model may request an operation. The control plane must independently determine whether that operation is permitted.

## 3. Least-authority invariant

A capability invocation must expose only the minimum scope required for the requested task.

Scope may cover, depending on the capability:

- filesystem paths;
- Android app/function identifiers;
- network destinations;
- remote services;
- data classes;
- operation types;
- time window;
- execution mode.

Broad capabilities such as unrestricted filesystem access or arbitrary shell execution are not acceptable defaults for public/product profiles.

## 4. Egress invariant

No protected data may leave the device unless a local policy authorizes the destination, purpose, and execution context.

A remote model request is an egress event.

```text
protected data
   -> classify
   -> minimize/redact where possible
   -> destination check
   -> policy decision
   -> invocation
```

## 5. Secret isolation invariant

Secrets must never be treated as ordinary agent content.

Secrets include:

- model/API keys;
- OAuth access/refresh tokens;
- MCP credentials;
- remote service credentials;
- encryption keys or other credential material.

Secrets must not be copied into Mission text, Task descriptions, prompts, evidence payloads, logs, analytics, or crash reports unless an explicit low-level protocol requires a protected representation.

The current prototype stores the Anthropic key in ordinary `SharedPreferences`; this is a known prototype limitation and is not an accepted production state.

## 6. Credential storage invariant

Credential protection must use Android's cryptographic facilities and a dedicated credential boundary rather than ordinary settings persistence.

The intended separation is:

```text
Settings       -> DataStore / ordinary preferences
Secrets        -> Keystore-backed credential store
```

The exact storage implementation remains a technology choice, but plaintext secret persistence in normal settings storage is not an accepted product baseline.

## 7. Backup/export invariant

Local data must be reviewed as a privacy surface even when no network call is made by the application.

The design must explicitly decide what happens to:

- device backup;
- device-to-device transfer;
- exported files;
- share actions;
- debugging artifacts;
- crash/diagnostic data.

"Stored locally" must never be interpreted as "cannot leave the device."

## 8. Activation invariant

All execution entry points must converge on the same activation/control path.

```text
Chat
Quick Action
Widget
Notification
Automation
External invocation
App Function
   |
   v
ActivationRequest
   |
   v
Policy / Privacy / Approval
```

An alternate UI path must not create an alternate authorization path.

## 9. Approval invariant

Approval is bound to a specific proposed operation and context. An approval must not silently authorize:

- a different capability;
- a wider scope;
- a different destination;
- a different data set;
- an unrelated later operation.

Approval representation must eventually include enough context to prevent replay or scope widening.

## 10. Effect classification invariant

Every capability has an effect class, at minimum:

```text
READ_ONLY
REVERSIBLE
DESTRUCTIVE / HIGH_IMPACT
```

Policy may require explicit approval for non-read-only or high-impact effects.

The effect classification must be evaluated before execution, not inferred from the natural-language prompt after the effect occurs.

## 11. Evidence invariant

Only attributable observations, executor results, artifacts, tests, or verification outputs can directly establish evidence.

```text
"I did it" from a model
        != evidence

executor result + artifact hash + verification
        = candidate evidence
```

Evidence records must retain sufficient attribution to answer:

```text
who / what requested it?
who / what authorized it?
which capability?
which invocation?
what actually happened?
what was verified?
when?
```

## 12. Terminal-state invariant

A settled/terminal execution cannot be turned into another outcome by a late callback, retry, or stale UI event.

Examples:

```text
cancelled -> cannot become succeeded
failed    -> cannot become succeeded through stale completion
succeeded -> cannot become executing
```

This invariant applies to Mission, Task, and Run implementations according to their respective lifecycles.

## 13. Durable recovery invariant

After process death, the system must prefer durable state over assumptions in memory.

Recovery must be designed to prevent duplicate external effects. Where an effect can be retried, the system should use stable effect identity/idempotency semantics appropriate to that capability.

## 14. Network security invariant

Remote endpoints must not be accepted as trusted merely because they were configured by a model or discovered dynamically.

Endpoint trust, authentication, authorization, and data-egress policy are separate checks.

Static URLs, OAuth credentials, bearer tokens, and dynamically discovered destinations must never be conflated into one trust decision.

## 15. Logging invariant

Logs and diagnostics must be treated as data egress surfaces.

Do not log:

- API keys;
- OAuth tokens;
- raw Authorization headers;
- private prompts unless explicitly opted in;
- unrestricted capability arguments containing sensitive data.

Structured diagnostics should prefer identifiers, classes, hashes, and bounded metadata over raw content.

## 16. Public/Product profile invariant

A product/public profile may restrict capabilities and data compared with a personal/developer profile, but it may not bypass foundational security boundaries.

Security must not depend on a Compose switch being hidden, disabled, or absent from the UI.

## 17. External capability invariant

An external project/service is untrusted until its integration contract specifies at least:

```text
identity
endpoint/transport
capabilities
required credentials
trust level
data accepted
side effects
evidence returned
verification expectations
health/failure semantics
```

This is why a general ProjectRegistry/ProjectAdapter federation is deferred until real consumers demonstrate a shared contract.

## 18. Android background invariant

Background execution must obey current Android lifecycle and foreground-service restrictions. The application must not assume that a persistent unrestricted agent daemon can run indefinitely.

Appropriate mechanisms include user-triggered foreground work, notifications, and OS-supported persistent/deferred work such as WorkManager when the workload fits its contract.

## 19. Security review gates

Before enabling a new high-impact capability, review at least:

1. identity and authorization source;
2. exact scope;
3. data accessed;
4. network destinations;
5. effect classification;
6. approval requirement;
7. rollback/idempotency behavior;
8. verification method;
9. evidence produced;
10. logging/redaction behavior;
11. recovery behavior after process death;
12. public/product exposure profile.

A capability that cannot answer these questions is not ready for general exposure.

## 20. Current prototype gaps

The repository baseline is intentionally small. The following are recognized gaps, not hidden assumptions:

- API key is stored in SharedPreferences;
- chat history is in memory only;
- MCP is currently delegated to Anthropic's connector rather than implemented as a local MCP client;
- capability control is currently a simple ToolRegistry;
- there is no durable Mission/Task/Run store;
- there is no approval object or local egress policy engine;
- there is no verified evidence ledger.

These are the first architecture work items only after this security baseline is accepted.

## 21. Reference platform facts

The project will track current platform behavior rather than hard-coding assumptions from old tutorials.

As of 2026-08-26, Jetpack App Functions is available as `1.0.0-alpha11` and is still experimental.  
Reference: https://developer.android.com/jetpack/androidx/releases/appfunctions

The MCP specification released 2026-07-28 moved the protocol core to stateless request/response operation, added Tasks as an extension, and strengthened authorization.  
Reference: https://blog.modelcontextprotocol.io/posts/2026-07-28/

These technologies are integration surfaces, not substitutes for the local security/control model described here.
