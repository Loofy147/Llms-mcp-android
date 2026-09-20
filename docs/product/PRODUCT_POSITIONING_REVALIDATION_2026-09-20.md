# Product Positioning Revalidation — 2026-09-20

Status: Strategic hypothesis record
Scope: Personal and commercial pursuit after architecture/Q1 review
Evidence boundary: This document does not claim product-market fit.

## 1. Core conclusion

The project remains worth pursuing, but the strongest thesis is not "another AI assistant."

Primary positioning hypothesis:

    User-Owned Agent Runtime / Control Plane

with a possible commercial form:

    Agent Control Plane / Execution-Control SDK

The project should remain model/provider-neutral and should preserve local authority over policy, approval, capability admission, execution state, recovery, verification, and evidence.

## 2. Personal-use conclusion

### Primary proving ground

Personal developer workflows remain the strongest early proving ground because:
- repository state is observable;
- diffs/hashes are inspectable;
- tests/builds provide independent evidence;
- local effects can be narrowly scoped;
- many useful actions can be deterministic without an LLM.

Initial capability family:

    dev.workspace.inspect
    dev.file.read
    dev.file.hash
    dev.git.status
    dev.git.diff

The proving ground is not a product identity commitment to a general coding agent.

### Personal mobile direction

Later Android-native capabilities may use the same runtime boundary:

    Action
      -> Policy
      -> Approval
      -> Capability
      -> Observation
      -> Verification
      -> Evidence

The model must not receive direct unrestricted device authority.

## 3. Generic assistant conclusion

A universal Android assistant is not the primary bet.

This is a strategic hypothesis, not a claim that such a product cannot succeed.

The current Android platform is itself developing agentic capability exposure through AppFunctions. Google documents AppFunctions as an Android mechanism that lets apps expose discrete functions to agents and assistants for discovery and execution on-device; the API is currently experimental and evolving.

References:
- https://developer.android.com/ai/intelligence-system
- https://developer.android.com/ai/appfunctions
- https://developer.android.com/blog/posts/build-intelligent-android-apps-integrate-into-android-s-intelligence-system-using-app-functions

Implication for this project:

    compete on control / ownership / execution semantics
    rather than compete on system-assistant distribution.

## 4. Commercial hypotheses

### H1 — Agent Control Plane

A model/provider-neutral control layer could sit between reasoning systems and consequential capabilities:

    Model / Agent
          |
      Tool exposure
          |
       Action
          |
       Policy
          |
      Approval
          |
    Capability
          |
      Observation
          |
      Verification
          |
       Evidence

Potential customers:
- developers embedding agents into applications;
- teams operating multiple model providers;
- products exposing consequential local or remote tools;
- environments requiring explicit authorization and auditability.

Status: HYPOTHESIS / needs customer interviews and competitive validation.

### H2 — Embedded runtime/SDK

Instead of replacing a model vendor, the product could provide:
- execution admission;
- capability scoping;
- human approval;
- durable Run state;
- recovery;
- evidence/provenance;
- model/provider-neutral integration.

Status: HYPOTHESIS.

### H3 — Personal/local-first product

A BYOK, local-first runtime for developers and power users could provide:
- any compatible model;
- user-owned credentials;
- local execution;
- capability-scoped tools;
- durable approval and recovery.

Status: HYPOTHESIS.

## 5. Why the problem appears real

NIST published a 2026 concept paper focused specifically on identity and authorization for software and AI agents, including identification, authentication, authorization, least privilege, delegation, auditing, non-repudiation, and prompt-injection risk. This validates that the control problem is an active technical/security concern.

References:
- https://www.nist.gov/news-events/news/2026/02/new-concept-paper-identity-and-authority-software-agents
- https://www.nccoe.nist.gov/projects/software-and-ai-agent-identity-and-authorization

This is evidence that the problem exists, not evidence that this repository has product-market fit.

## 6. Commercial non-goals

Unless direct evidence creates a reason:
- do not market as a replacement for major general assistants;
- do not compete primarily on base-model quality;
- do not build a generic workflow engine before measured need;
- do not build a broad multi-agent platform merely because the category exists;
- do not claim enterprise readiness before authorization, recovery, verification, and evidence are demonstrated.

## 7. Validation plan

Before large commercial expansion, validate:

    1. Who has a concrete execution-control problem?
    2. What are they using today?
    3. Which failure modes remain unsolved?
    4. Is model/provider neutrality valuable enough to pay for?
    5. Is local/user-owned authority a requirement or merely a preference?
    6. Which control-plane capability is the minimum paid wedge?
    7. Can one narrow capability save measurable engineering/security cost?
    8. Does the buyer want an SDK, hosted control plane, or end-user application?

Required status until answered:

    Commercial PMF = UNKNOWN

## 8. Decision boundary

Continue building the runtime because it has independent personal utility and a technically coherent control-plane thesis.

Do not assume that technical coherence implies commercial viability.

The commercial decision should be made from customer/competitive evidence after Q1-Q5 semantic stabilization produces a credible executable substrate.
