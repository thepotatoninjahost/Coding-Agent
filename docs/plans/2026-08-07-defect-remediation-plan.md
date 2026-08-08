# Coding Agent Defect Remediation Plan

Date: 2026-08-07
Status: active

## Scope

Remediate the audit defects while preserving the twelve constitution rules, the typed ChangeSet/checksum/rollback foundation, mandatory research for coding, and a replaceable model provider. The local Qwen3-4B package remains a provider option, not a product assumption.

## Completed in this increment

- Removed model-facing direct mutation and approval tools from the protocol.
- Added `propose_changes` for bounded typed multi-file proposals.
- Made external research mandatory and fail-closed before model coding execution.
- Made model-generated proposal paths reject absolute, traversal, and backslash paths.
- Unified the app's shared `MutationCoordinator` and research provider across runtime construction.
- Wired the visible Stop action to cancel the autonomous agent and terminal session.
- Added bounded, staged project import with cleanup on failure and file/byte quotas.
- Added proposal file summaries and unified diff rendering to Chat and Review surfaces.
- Added a three-step approval UI gate: review, acknowledge, then owner approval; the existing policy still enforces two approvals.
- Removed the dangerous write-capable restore workflow.
- Replaced the README's stale claims with current safety/model/research status.

## Next protected increment

- Run post-apply verification and expose its result in the task state.
- Persist transaction snapshots, pending proposals, and approval records.
- Add encrypted model-secret storage and a model settings/onboarding surface.
- Add bounded research cancellation, SSRF protection, and source quotas.
- Finish canonical-runtime migration, streaming assistant message state, real project verification, and durable undo.

## Verification requirement

Do not call the project fully operational until the modular JVM tests, Android build/tests, archive checks, cross-module sweep, root-cause verification, and static-warning audit all pass. Android verification is currently blocked if no Android SDK is configured in `local.properties` or `ANDROID_HOME`.
