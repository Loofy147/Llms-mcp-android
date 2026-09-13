# Developer Capability Gate v0.1

Status: implemented vertical slice; device verification still required

## Current capabilities

```text
dev.file.read
dev.file.hash
```

Both are deterministic, read-only Actions executed through the existing `AgentRuntime` and `CapabilityExecutor`.

## Authority boundary

Developer filesystem access is not based on raw filesystem paths.

```text
Android user grant
    -> WorkspaceGrant
    -> WorkspaceAuthority
    -> operation-specific authorization
    -> normalized relative path
    -> SAF document URI
    -> CapabilityExecutor
```

The workspace grant must identify:

- stable local grant identity;
- Android SAF tree URI;
- explicitly granted operations;
- persisted authorization state;
- user-visible display identity.

Revoked or unavailable authority fails closed.

## Path safety

Relative workspace paths:

- reject absolute paths;
- reject `.` and `..` segments;
- normalize repeated separators;
- normalize `\\` to `/`;
- reject NUL characters.

No capability accepts an arbitrary filesystem root.

## Read bounds

`dev.file.read` is limited to 64 KiB and requires strict UTF-8.

`dev.file.hash` is limited to 16 MiB and streams SHA-256 without returning file content.

These limits are resource-safety bounds, not security claims about every possible Android `DocumentsProvider` implementation.

## Verification

The current unit evidence covers:

- path normalization;
- path traversal rejection;
- NUL rejection;
- strict UTF-8 rejection;
- read-size bounding;
- deterministic SHA-256 output;
- hash-size bounding.

Required device evidence before promotion:

1. user grants a workspace through the Android document picker;
2. a file inside the workspace is read and hashed;
3. traversal and outside-root attempts fail;
4. the grant is revoked and later access fails closed;
5. the app restarts and the persisted grant behaves consistently;
6. a provider or document that becomes unavailable fails closed.

## Deliberate non-goals

Not part of this gate:

- shell/process execution;
- Termux;
- PRoot/LinuxOnAndroid;
- git mutation;
- model exposure of these capabilities;
- remote workspace authority;
- generic filesystem abstraction.

`dev.directory.list` remains deferred until its information-disclosure and model-egress semantics are reviewed.
