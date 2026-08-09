# Coding Agent

A modular Android coding workbench designed for phone-only use. The project is organized as replaceable core modules for intake, planning, project indexing, knowledge retrieval, code synthesis, workspace mutation, verification, repair, live updates, and local learning.

The runtime keeps a degraded offline knowledge path, but non-trivial coding requests now fail closed unless a web research provider is configured and returns evidence. The model-driven autonomous path uses an OpenAI-compatible gateway with tool calling; the gateway also supports streamed server-sent-event deltas. Configure the gateway via the in-app Model settings screen (base URL, model id, API key).

## Current product status

The repository contains the modular backend and an Android workbench with a persistent Chat workspace. Core agent loop, evidence gates, and static verification are implemented and hardened.

The current APK supports:
- Project import via Storage Access Framework and indexing
- Source search and local knowledge search
- Autonomous model loop with real tool calling (list_files, read_file, search_project, verify, mutations with dual approval, etc.)
- Always-on static verification (TODO/FIXME/stub scan) — never reports a fake pass
- Evidence requirement: inspect/error/analyze requests must actually read or search project files before a final answer is accepted
- Model settings UI for any OpenAI-compatible provider (Groq, SambaNova, OpenRouter, local, etc.)
- Transactional file changes with checksum-backed rollback
- Persisted chat history and task journal

Remaining product polish (not blockers for basic use):
- Multi-file diff staging UI refinements
- Broader document ingestion beyond the example asset
- Extended physical-device verification of terminal cancellation and long streaming sessions

## Recommended remote provider (as of 2026-08)

SambaNova has shown rate-limit (429) and occasional Cloudflare blocks. Prefer **Groq** for reliability:

- Base URL: `https://api.groq.com/openai/v1`
- Model: `llama-3.3-70b-versatile`
- API key: from console.groq.com

Any other OpenAI-compatible endpoint works the same way.

## Architecture

The core execution path is modular:

1. `TaskIntakeParser` interprets a request into a typed goal contract and operation.
2. `AgentPlanner` creates the execution plan.
3. `ProjectIndexer` inventories project files, languages, imports, symbols, and checksums.
4. `AgentKnowledge` supplies local evidence while `WebResearchProvider` supplies mandatory internet evidence for non-trivial runtime work.
5. `CodeSynthesisEngine` creates a proposal when the request does not contain an explicit operation.
6. `ProjectWorkspace` applies edits through typed transactions.
7. `VerificationReport` records static and command-check evidence.
8. `CompilerTestRepairCycle` diagnoses failures, applies bounded repair attempts, and rolls back failed work.
9. `AgentJournal`, lessons, and local stores preserve task evidence for later work.

The main production modules are under `app/src/main/java/com/codingagent/core/`. Unit tests are under `app/src/test/java/com/codingagent/core/`.

## Current capabilities

- Import a project directory with Android's Storage Access Framework.
- Copy imported project files into app-private storage.
- Index files, languages, imports, symbols, line counts, and SHA-256 checksums.
- Search project source.
- Search the imported coding reference offline.
- Keep knowledge retrieval behind the `AgentKnowledge` interface so additional providers can be added modularly.
- Parse explicit create, replace, append, and remove operations.
- Generate language-specific starter files for supported create requests.
- Apply workspace mutations through typed `ChangeSet` transactions.
- Record each `ChangeRecord` with its operation, before/after content, reason, and checksums.
- Write file changes atomically and persist transaction metadata under `.coding-agent/transactions/`.
- Roll back one or more committed transactions only when current content still matches the recorded after-checksum.
- Reject rollback when another change has modified the file, preventing silent data loss.
- Run explicit verification commands with bounded timeouts.
- Persist task, document, and lesson records locally.
- Persist task, document, lesson, and Chat workspace messages locally in app-private JSONL records.
- Include prior Chat workspace messages in subsequent agent requests so follow-up work has conversation context.
- Store versioned live modules and local model files outside the APK.
- Reload changed modules and model bytes without rebuilding the Android host.

## Knowledge and learning boundary

`Coding For Dummies` is an example reference asset used to exercise the local knowledge pipeline. It is not the product's knowledge limit or a hardcoded coding strategy.

The intended ingestion workflow is:

1. The user supplies documents, source files, reference material, or other supported input.
2. An ingestion module extracts and normalizes the content.
3. A knowledge module creates searchable chunks with source provenance.
4. The agent retrieves relevant material during planning and synthesis.
5. Lessons and verification evidence are stored locally for later tasks.
6. Internet research will be added as another provider behind the same knowledge boundary.

The current implementation has the local knowledge example and the provider interfaces. General multi-file ingestion and internet-backed retrieval remain implementation work.

## Transaction and rollback behavior

Workspace mutations are typed as `ChangeOperation` values: `CREATE`, `REPLACE`, `APPEND`, and `REMOVE`. A `ChangeSet` groups the records created by one transaction.

Each `ChangeRecord` stores:

- Project-relative path
- Operation type
- Previous content, when a file previously existed
- New content
- Reason for the change
- SHA-256 checksum before the change
- SHA-256 checksum after the change

Rollback is fail-closed. It returns `RollbackResult.Restored` only when every affected file still matches the expected after-checksum. If a file was changed externally, rollback returns `RollbackResult.Rejected` and leaves the conflicting file untouched.

## Local development

Install the Android SDK command-line tools and packages required by the project, then create an untracked SDK configuration file:

```bash
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew :app:lintDebug --no-daemon --console=plain
./gradlew :app:assembleDebug --no-daemon --console=plain
```

The generated debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

`local.properties`, `.gradle/`, and `app/build/` are machine-local or generated state. They must not be committed.

## Verification coverage

The project currently verifies:

- Goal interpretation and task intake
- Code-synthesis proposals
- Planning and tool-selection loops
- Project indexing and exact mutation behavior
- Typed transaction records and checksum-backed rollback
- Repair-cycle rollback behavior
- Live module and live model updates
- Android lint
- Debug APK assembly

The Android unit tests are JVM tests and run with:

```bash
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
```

## Build on GitHub

The repository includes `.github/workflows/android-build.yml`. GitHub Actions runs automatically on pushes to `main`, pull requests, and manual workflow dispatch. It installs the Android SDK, runs the unit tests, assembles the debug APK, and publishes the APK as the `coding-agent-debug-apk` workflow artifact.

To run it manually:

1. Open the repository on GitHub.
2. Open **Actions**.
3. Select **Android build**.
4. Select **Run workflow**.
5. Choose `main` and run it.
6. Open the completed run and download `coding-agent-debug-apk` under **Artifacts**.

## Supported device contract

This build targets the Samsung Galaxy S25 class of devices: Android API 35 or newer, `arm64-v8a`, and 64-bit ARM. The APK intentionally does not claim x86_64 or 32-bit ARM support.

## Reproducible source packaging

The repository is the canonical source tree. Do not commit Android SDK paths, Git metadata, Gradle caches, build outputs, APKs, AABs, class files, or local configuration.

Create a clean source archive with:

```bash
./scripts/package-source.sh ../Coding-Agent-source.zip
```

The packager:

- Ignores Git metadata and `.coding-agent/` runtime data.
- Rejects generated or machine-local files instead of silently hiding them.
- Rejects `.gradle/`, `.idea/`, `build/`, `app/build/`, `local.properties`, APKs, AABs, and class files.
- Orders entries by UTF-8 path bytes.
- Normalizes ZIP timestamps and file metadata.
- Prints the archive SHA-256 checksum.

Two runs from the same clean source tree must produce byte-identical archives. Regenerate the checksum after changing source files; it is intentionally not hardcoded here because the README itself is part of the archive.
