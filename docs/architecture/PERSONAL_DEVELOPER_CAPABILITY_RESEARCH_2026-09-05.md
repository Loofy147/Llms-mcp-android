# Personal Developer Capability Research — 2026-09-05

Status: Research-backed capability proposal / not yet committed runtime catalog
Date: 2026-09-05

## 1. Thesis

The fastest path to useful personal-agent automation is not a general-purpose agent. It is a compact set of typed capabilities whose outputs are observable and independently verifiable.

For a developer-owned mobile Agent Runtime, this creates a useful ordering principle:

```text
Automation priority
  ∝
  observability × verifiability × reversibility × reuse
  ---------------------------------------------------
  ambiguity × consequence × external uncertainty
```

This is a heuristic for prioritization, not a scientific law. It must be validated with measured runs.

Current research supports the underlying direction. APEX-Agents reports only 24.0% Pass@1 for its best evaluated agent on long-horizon, cross-application professional tasks, while MobileWorld reports 20.9% end-to-end success for its best evaluated model on substantially harder mobile tasks. These benchmarks are not directly comparable, but both show the large reliability penalty of open-ended multi-step autonomy. citehttps://arxiv.org/abs/2601.14242https://aclanthology.org/2026.acl-long.278/

The arithmetic commonly used to illustrate compounding is also correct under an independence assumption: 0.85^10 ≈ 0.197 and 0.95^10 ≈ 0.599. It is a model of error accumulation, not a measured universal agent law. Multiple 2026 engineering discussions use the same calculation. 

## 2. Research findings to retain

### R-01 — Narrow domains are the correct first proving ground

Repeated real-world examples converge on bounded productivity domains such as calendar, tasks, email, documents, and search. PersonalAgents is an open-source implementation that separates email, calendar, and web exploration into specialized agents/functions. Google's current Gemini Spark product likewise combines calendar, Gmail, Drive, Docs, Tasks, and related connected applications for personal workflows.

Sources:
- PersonalAgents: https://github.com/JoelKong/PersonalAgents
- Gemini Spark: https://gemini.google/overview/agent/spark/

Classification: **CONVERGENT PRODUCT SIGNAL**, not proof that these are the only useful domains.

### R-02 — Verification is the critical accelerator

A capability becomes much easier to automate when its result has an independent success predicate:

```text
create event -> read event back -> compare fields
write file   -> hash/stat/read back -> compare expectations
run tests    -> process exit + test report -> evaluate predicate
build APK    -> artifact exists + checksum + install/launch smoke test
Git change   -> diff/status/test -> verify intended change
```

This maps directly to the runtime's `Observation -> Verification -> Evidence` contract.

### R-03 — Approval should attach to the effect, not the chat

The research examples support a practical distinction between read/summarize, draft/prepare, and externally visible/destructive actions. The runtime should therefore classify approval at the Action/Capability effect boundary.

### R-04 — Cross-domain value comes from narrow compositions

The useful combinations found in Gemini Spark and FinAgent are not a universal agent identity. They are bounded compositions such as:

```text
read invoices -> infer need -> create reminder
read transactions -> calculate budget -> propose meal plan
read repository -> run tests -> summarize failures
read issue -> inspect code -> prepare patch -> verify tests
```

The architectural unit remains a typed Action containing typed CapabilityInvocations.

### R-05 — Failure handling matters as much as model selection

A 2026 incident analysis of 73 production agent incidents from one provider found tool/schema drift to be the largest listed failure layer and reported materially faster repair where schema validation was present. This is useful operational evidence, but it is one organization's dataset and must not be generalized into a universal incident rate.

Source: https://www.sherlocks.ai/blog/why-ai-agents-fail-in-production

Classification: **SINGLE-SOURCE OPERATIONAL SIGNAL**.

## 3. Personal developer capability taxonomy

The following catalog is intentionally developer-centric: the phone is treated as a personal engineering control surface for inspecting, validating, preparing, and initiating work. High-consequence execution remains gated until stronger Android/device evidence exists.

### Tier A — Safe, deterministic, highly verifiable

| Capability | Effect | Typical verification | Automation priority |
|---|---|---|---|
| `dev.workspace.inspect` | READ_ONLY | paths, metadata, expected files | P0 |
| `dev.file.read` | READ_ONLY | exact bytes/text/hash | P0 |
| `dev.file.hash` | READ_ONLY | deterministic digest | P0 |
| `dev.directory.list` | READ_ONLY | expected entry set | P0 |
| `dev.git.status` | READ_ONLY | repository status snapshot | P0 |
| `dev.git.diff` | READ_ONLY | exact diff / patch | P0 |
| `dev.git.log` | READ_ONLY | commit identity/order | P0 |
| `dev.test.run` | READ_ONLY | process result + structured report | P0 |
| `dev.build.run` | REVERSIBLE/LOCAL | process result + artifact | P0 |
| `dev.artifact.inspect` | READ_ONLY | size/hash/type/installability checks | P0 |
| `dev.config.validate` | READ_ONLY | schema/invariant report | P0 |

These should form the first empirical capability family because their outcomes can usually be checked without asking a model whether the task succeeded.

### Tier B — Prepared changes with strong verification

| Capability | Effect | Typical verification | Approval |
|---|---|---|---|
| `dev.file.write` | REVERSIBLE | read-back + hash/diff | configurable |
| `dev.file.patch` | REVERSIBLE | exact expected diff | configurable |
| `dev.git.branch.create` | REVERSIBLE | branch exists + points to expected base | optional |
| `dev.git.commit.prepare` | REVERSIBLE | staged diff + message/policy checks | yes before commit |
| `dev.test.fix` (Action composition) | REVERSIBLE | patch + test suite | yes for write |
| `dev.build.reproduce` | LOCAL | build output + hash comparison | no for safe local build |
| `dev.documentation.sync` | REVERSIBLE | generated diff + link/heading checks | optional |

The key pattern is **prepare → inspect → verify → commit/apply**, not direct autonomous mutation.

### Tier C — External or consequential developer effects

| Capability | Effect | Verification | Approval |
|---|---|---|---|
| `dev.git.push` | REVERSIBLE/EXTERNAL | remote ref comparison + server response | REQUIRED |
| `dev.pull_request.open` | EXTERNAL | PR metadata/diff | REQUIRED |
| `dev.issue.create` | EXTERNAL | issue identity/body snapshot | REQUIRED initially |
| `dev.release.publish` | HIGH_IMPACT | release metadata + artifact references | REQUIRED |
| `dev.deploy` | HIGH_IMPACT | deployment status + health check | REQUIRED |
| `dev.secret.rotate` | HIGH_IMPACT | provider confirmation + credential test | REQUIRED |

These should not be the first proof capability because local verification is insufficient to establish the final external effect under network/process uncertainty.

### Tier D — Mobile/device interaction

| Capability | Effect | Why later |
|---|---|---|
| `device.notification.post` | REVERSIBLE | platform integration required |
| `device.clipboard.read` | READ_ONLY | privacy/sensitive-data review |
| `device.clipboard.write` | REVERSIBLE | leakage policy required |
| `device.file.pick` | USER_INTERACTION | Android permission/UI integration |
| `device.screen.capture` | REVERSIBLE | media/privacy permissions + lifecycle |
| `device.app.launch` | EXTERNAL/UI | platform policy + verification |
| `device.accessibility.act` | HIGH_IMPACT | broad authority and weak independent verification |

The runtime should not make Tier D the first proving ground merely because it is attractive. Real-device authority and lifecycle semantics are still open gates.

## 4. High-value composite Actions for one developer

The best personal developer experiences are small compositions of the above primitives.

### A-01 — Diagnose failing build

```text
Activation
  -> dev.build.run OR dev.test.run
  -> dev.artifact.inspect / report collection
  -> structured observations
  -> verification
  -> evidence
  -> optional model summary
```

The model explains; deterministic tooling establishes whether the build/test actually succeeded.

### A-02 — Review local change

```text
Activation
  -> dev.git.status
  -> dev.git.diff
  -> dev.test.run
  -> verification
  -> evidence
  -> model-generated review summary (derived)
```

### A-03 — Prepare a safe patch

```text
Activation
  -> inspect relevant files
  -> dev.file.patch
  -> dev.test.run
  -> verify expected diff + tests
  -> WAITING_APPROVAL / commit policy
```

### A-04 — Reproduce a repository state

```text
Activation
  -> dev.git.log/status
  -> dev.config.validate
  -> dev.build.reproduce
  -> artifact hash
  -> evidence
```

### A-05 — Documentation consistency check

```text
Activation
  -> inspect docs + source symbols
  -> detect stale claims
  -> generate proposed patch
  -> validate links/headings/reference SHAs
  -> present diff
```

This is particularly aligned with the current project's recurring requirement to keep architecture documentation synchronized with implementation evidence.

## 5. What should NOT become a Capability yet

Do not introduce broad primitives such as:

- `dev.shell.execute(any_command)`;
- `device.control(any_app)`;
- `browser.do(anything)`;
- `git.force_push` as a normal capability;
- autonomous credential extraction;
- unrestricted file-system mutation;
- unconstrained package installation.

These collapse many authorization decisions into one opaque capability and make independent verification weaker.

Prefer typed operations whose parameters expose the actual authority being granted.

## 6. Verification contracts

Each candidate Capability should define a verification predicate before it is exposed broadly.

```text
CapabilityContract {
    id
    version
    effectClass
    scope
    inputSchema
    outputSchema
    verification
    evidence
    idempotency
    failureMode
    recoveryMode
}
```

Verification should be expressed as an independent function of observed state where possible:

```text
verify(observation, expectedPostcondition) -> pass/fail + reason
```

A model may produce an expected outcome, but it must not be the sole verifier of that outcome.

## 7. Automation-readiness score

For experiments, assign each candidate capability measurable dimensions rather than a single subjective score:

- **O — Observability:** can the post-state be inspected?
- **V — Verifiability:** is there an independent success predicate?
- **R — Reversibility:** can the effect be undone safely?
- **I — Idempotency:** can retry be made safe?
- **E — Evidence quality:** can the result be attributed and reconstructed?
- **S — Scope clarity:** is authority narrowly bounded?
- **C — Consequence:** how bad is an incorrect effect?
- **X — External uncertainty:** how much depends on systems outside the runtime?

A capability should move earlier in the roadmap when O/V/R/I/E/S are high and C/X are low.

## 8. Proposed first capability experiment

The strongest first real developer capability is **not a remote service**. It is a local repository/test capability family:

```text
dev.workspace.inspect
+
 dev.git.status
+
 dev.git.diff
+
 dev.test.run
+
 dev.artifact.inspect
```

Why:

1. It matches the user's actual development loop.
2. Results are machine-observable.
3. Most verification can be deterministic.
4. The effects are initially READ_ONLY.
5. It exercises Action → CapabilityInvocation → Executor → Observation → Verification → Evidence without immediately introducing irreversible external effects.
6. It creates the evidence needed before adding write/remote/device capabilities.

This should be treated as a **capability family experiment**, not a decision to build a general coding agent.

## 9. Benchmark design

For each capability family, run at least 20 independent cases across at least 3 distinct repository/project states before promoting the capability beyond experimental status.

Record:

```text
success_rate
verification_precision
verification_recall
false_success_rate
false_failure_rate
mean_latency
p95_latency
retry_rate
recovery_rate
human_intervention_rate
duplicate_effect_rate
```

For model-mediated runs additionally record token/latency cost and compare against the deterministic direct Action path.

The critical metric is not only task success:

```text
verified_success = correct outcome AND verifier agrees
```

A high task-completion number with weak verification should not promote the capability.

## 10. Research conclusion

The research supports a stronger project thesis:

> **Build the runtime around verifiable effects, not around the apparent intelligence of the model.**

Personal developer use is an unusually good proving ground because repository state, test output, diffs, hashes, build artifacts, and many configuration properties provide objective observations.

The next architecture work should therefore deepen the Capability/Verification contract and prove a small local developer capability family before introducing broad Android automation or a general workflow engine.
