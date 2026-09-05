# Capability + Verification Contract v0.3

Status: Proposed semantic baseline
Date: 2026-09-05
Scope: Capability invocation, observation, verification, evidence, and promotion gates.

## 1. Purpose

This contract closes an architectural gap identified in the 2026-09-05 audit: the current runtime has a useful execution boundary, but the capability result can still carry a self-attested `postcondition` that materially influences success.

The intended v0.3 semantic boundary is:

```text
Action
  -> planned CapabilityInvocations
  -> authorization / approval / egress / budget checks
  -> CapabilityExecutor
  -> raw Observations
  -> independent Verifier
  -> VerificationResult
  -> Evidence
  -> terminal Run
```

The contract is a design baseline. It does not claim that the current implementation already satisfies every rule below.

## 2. Capability contract

Every application-owned capability should be describable by an explicit contract containing, at minimum:

```text
id
version
effect_class
scope_model
input_schema
output_schema
observation_schema
network_requirements
approval_requirements
idempotency/replay semantics
provenance
```

The executor is an effect boundary, not the authorization authority. A capability must not widen its own declared scope or approval requirements.

## 3. Invocation contract

A concrete invocation is the unit on which authorization is eventually evaluated.

Required identity/context:

```text
run_id
invocation_id
capability_id + version
action_id + version
requester/principal identity
activation source
concrete scope
parameters or parameter reference
stable effect identity when applicable
correlation/causation context
```

The Action declaration limits what may be invoked; it does not grant blanket authority over every possible parameter value.

## 4. Observation contract

Capability execution should report facts, not final semantic success.

An observation should identify enough information to support independent verification. Typical classes include:

```text
state_before
operation_attempted
external/local result
state_after
artifact/hash/reference
error
resource usage
```

Sensitive values should be minimized or represented by references where possible.

## 5. Verification contract

Verification is a separate semantic responsibility.

Input:

```text
expected postcondition / verification specification
observations
relevant artifacts or state references
```

Output:

```text
passed
verifier_id
verifier_version
reason/code
expected_predicate or predicate reference
observed-facts reference
verification timestamp
```

A capability's own claim of success must not be the only source used to produce a terminal `SUCCEEDED` state.

## 6. Evidence contract

Evidence must connect the complete decision chain:

```text
Activation
 -> authorization
 -> approval (if any)
 -> planned Action
 -> concrete Invocation(s)
 -> effect identity
 -> Observations
 -> VerificationResult
 -> terminal Run
```

Evidence should be reconstructable without relying on model prose. A future hardening phase may add hashes, immutable snapshots, or tamper-evident chaining; those are not required by this contract yet.

## 7. Immutable semantic provenance

A persisted Run must not reconstruct historical executable semantics by looking up only the current Action ID.

At minimum, durable provenance should contain:

```text
action_id
action_version
action_definition_hash or immutable snapshot id
```

The same rule applies to verifier definitions and, where relevant, capability definitions.

## 8. Promotion gate

A new capability is not considered runtime-ready merely because its happy path succeeds.

Promotion requires evidence appropriate to its effect class covering:

1. narrow declared scope;
2. invocation-level authorization;
3. explicit effect classification;
4. stable idempotency/effect semantics where retry is possible;
5. independent verification;
6. restart/recovery behavior;
7. evidence/provenance completeness;
8. resource and cancellation limits;
9. privacy/minimization review;
10. external reconciliation when effects cross a device boundary.

## 9. Current implementation gap

The current runtime still exposes:

```text
CapabilityExecution.postcondition
ActionExecution.postcondition
```

and uses the propagated value to construct `Verification`. This is intentionally retained as a known transition state while the semantic split is implemented and tested.

Therefore:

```text
Current implementation = provisional
Contract target         = independent observation + verification
```

## 10. Required experiments

The following experiments are required before promoting the contract from proposed to accepted:

- E-12 Normalize direct Action planning failures.
- E-13 Approval -> execution crash recovery.
- E-14 Concurrent journal/approval races.
- E-15 Immutable Action provenance.
- E-16 Invocation/source-aware authorization.
- E-17 Independent verifier.
- E-18 Tool-loop budget and cancellation.
- E-19 Correlation/provenance chain.
- E-20 MCP identity and validation.
- E-21 Secret-free settings state.
- E-22 Backup/restore lifecycle.
- E-23 Release/dependency integrity.

Experiment E-12 is now implemented by the direct planning-failure regression test; the contract itself remains proposed until the remaining semantic and runtime gates are evidenced.
