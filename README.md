# Coding Agent

A modular Android coding workbench for a Galaxy S25-class device (API 35+, arm64-v8a). The project keeps a typed `ChangeSet` transaction boundary with checksums, atomic writes, fail-closed apply, and rollback. The twelve AgentConstitution rules remain non-negotiable; code changes require owner-visible review and two owner approvals.

## Current status

This is an active remediation build, not a finished coding product. The current increment has closed the most dangerous audit paths:

- The model cannot write files directly, run shell commands, or approve its own changes.
- Model edits use one bounded `propose_changes` call containing typed operations for multiple files.
- External research is mandatory and fail-closed before non-trivial model coding. Fetched source text is untrusted evidence, not instructions.
- Project import uses a temporary directory, cleanup on failure, path-name checks, and 10,000-file/512 MB quotas.
- Chat and Review show proposal file summaries and bounded unified diffs.
- Stop requests cancellation from the agent and terminal layers, not only the Compose job.
- The app uses a remote OpenAI-compatible gateway as its only model path. Provider URL, model ID, and API-key reference are configurable; the transaction and approval contract is provider-independent.

The current product path is intentionally fail-closed: non-trivial coding requires a configured model gateway and a successful external research session before the model can propose changes. Model tool calls cannot write files, approve changes, or run shell commands. File mutations use typed multi-file ChangeSets, remain checksum guarded, and require two owner approvals in the UI. Approval runs post-apply verification; use the Review surface to inspect the actual unified diff. The app does not ship or initialize an on-device model; serious coding requires the configured remote gateway.

The following remain incomplete and must not be described as shipped: durable transaction and proposal recovery, bounded cancellable web research with remaining SSRF hardening, durable undo across process restart, complete streaming assistant-message state, and physical-device validation. Physical-device validation remains required for release. Physical-device UI verification and release installation are also owner-side steps.

## Safe coding flow

1. Import a project. Import is staged into app-private storage and finalized only after the copy succeeds.
2. Submit a coding request. The agent indexes the project, performs mandatory external research, and reads relevant project evidence.
3. The model proposes a typed multi-file ChangeSet. No model tool can apply or approve it.
4. Review the actual files and bounded unified diffs in Chat or Review.
5. Complete the visible review/acknowledgement gate, then give the first and second owner approvals. The policy layer remains authoritative.
6. The checksum-guarded transaction applies only after the policy permits it.

## Model replacement

The app has a first-run Remote Model setup dialog with HTTPS/loopback validation, connection probing, a model-ID field, and an encrypted API-key field. Remote API keys are encrypted with Android Keystore and are never written to the settings JSON. `modules/model` owns the `ModelGateway` contract and OpenAI-compatible transport. The configured server may host any model, including a 400B+ coding model, provided it exposes the OpenAI-compatible chat-completions contract with reliable structured tool calls or strict JSON, streaming if available, and enough context for project evidence. Provider code must not bypass the typed proposal or policy boundaries.

## Build and verification

Standalone JVM module tests can run without Android SDK:

```bash
./gradlew :modules:domain:test :modules:intake:test :modules:workspace:test :modules:research:test :modules:model:test :modules:orchestration:test :modules:terminal:test :modules:persistence:test --no-daemon --console=plain
```

The Android app requires an Android SDK configured through `local.properties` or `ANDROID_HOME`.

The main build workflow is `.github/workflows/android-build.yml`. The old write-capable restore workflow was removed because it could overwrite `main` from a hard-coded historical commit.

## Repository structure

- `app`: Android UI and platform adapters.
- `modules/domain`: shared data contracts.
- `modules/policy`: AgentConstitution and approval ledger rules.
- `modules/workspace`: file indexing, typed transactions, checksums, and verification.
- `modules/research`: web search, bounded extraction, and persisted research sessions.
- `modules/model`: model gateway, streaming, tool protocol, and provider settings.
- `modules/orchestration`: intake, planning, research gate, model loop, proposal staging, and task results.
- `modules/terminal`: controlled project-root command execution and cancellation.

The supplied audit remains the historical defect baseline; this README states only the behavior verified in the current tree.
