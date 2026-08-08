# Coding Agent Modularization Plan

Date: 2026-08-07
Status: active

## Rule

One responsibility has one owner. A module may expose contracts for its responsibility, but it must not reimplement another module's job. UI and Android platform code compose the modules; they do not own core business behavior.

## Ownership map

| Module | One job | May depend on |
|---|---|---|
| `domain` | Stable business types and pure policy types | none |
| `workspace` | Project files, checksums, typed transactions, apply, verification, rollback | `domain` |
| `research` | Internet search and source retrieval | `domain` |
| `model` | Model request/response protocol and provider adapters | `domain` |
| `terminal` | Process execution, streaming output, cancellation, and terminal history | `domain` |
| `persistence` | Durable events and conversation records | `domain` |
| `orchestration` | Workflow sequencing only: intake → plan → research → proposal → approval → apply → verify → repair | capability modules |
| `architecture` | Executable ownership and dependency contracts | none |
| `app` | Android lifecycle, Compose UI, and composition-root wiring | module contracts |

## Duplicate-job rule

These are separate jobs and must not be merged:

- `workspace` mutates project files; `terminal` runs commands. Verification may ask `terminal` to run a command, but verification does not become a second command executor.
- `research` obtains evidence; `model` reasons over a request. Research never calls a model, and the model never silently performs network research.
- `persistence` stores records; `orchestration` decides when records are written. Persistence never owns workflow policy.
- `orchestration` sequences capability calls; it does not own file I/O, HTTP transport, process execution, or Compose state.
- `ui` renders state and collects approval; it does not mutate files directly.

## Current verified state

The twelve registered modules compile. The Android app also compiles. App files that remain under `app/src/main/java/com/codingagent/core` are compatibility facades or Android-specific adapters; executable capability owners live in the modules. The migration is not declared complete until the remaining Android compatibility surface is reduced or explicitly retained as composition adapters.

## Completed increments

1. Added `:modules:architecture` with executable one-owner and dependency checks.
2. Added `:modules:domain` for shared contracts, including change sets, commands, research hits, and chat records.
3. Added `:modules:workspace` with path safety, atomic writes, checksum validation, transactional apply, fail-closed rollback, and tests.
4. Added `:modules:research`, `:modules:model`, `:modules:orchestration`, `:modules:terminal`, and `:modules:persistence` contracts/implementations.
5. Moved terminal execution behind `:modules:terminal`; the app uses the module's single `CommandExecutor` and `TerminalSession` implementation.
6. The workspace application facade now delegates indexing and verification to `:modules:workspace`; the legacy indexer implementation was deleted. The app facade still owns compatibility for transaction and lesson APIs until all callers migrate.
7. Added `:modules:knowledge`, `:modules:policy`, `:modules:intake`, and `:modules:live`; moved local indexing, approval policy, request intake, live modules, self-evolution, storage checks, and resumable downloads behind their owning modules.
8. Replaced app-side model settings, model provisioning, live module/model stores, self-evolution, storage guard, and resumable downloader implementations with module-backed facades or typealiases.

## Remaining work

1. Migrate Android tests and callers from compatibility names to module package names where that improves ownership clarity.
2. Keep only Android-specific model runtime (`NexaLocalModelGateway`), Android persistence (`LocalStore`), UI adapters, and composition wiring in `app`.
3. Add direct tests for the live module's canonical runtime and model-store adapters before deleting compatibility aliases.
4. Update the UI to expose model settings and end-to-end proposal staging; this is product work, not evidence that the module split is complete.

## Verification

Non-Android modules can be verified with:

```bash
./gradlew :modules:architecture:test :modules:domain:test :modules:workspace:test :modules:research:test :modules:model:test :modules:orchestration:test :modules:terminal:test :modules:persistence:test --no-daemon --console=plain
```

Android tasks additionally require an Android SDK configured through `local.properties` or `ANDROID_HOME`.
