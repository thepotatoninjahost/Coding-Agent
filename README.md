# Coding Agent

Coding Agent is an Android application written in Kotlin using Jetpack Compose. It is intended to help inspect a selected project, research technical questions, propose code changes, and run verification steps through an agent workflow.

**Status: active development. Production readiness is not established.** The presence of a class, UI surface, test, or CI step is not proof that every end-to-end scenario works. Check CI for the exact commit you intend to use, and test the app on the target device and model provider.

## Current verified build configuration

From `app/build.gradle.kts`:

- Application ID and namespace: `com.codingagent`
- Compile SDK: 35
- Target SDK: 35
- Minimum SDK: 34
- Java source/target compatibility: 17
- Kotlin JVM target: 17
- Android Gradle Plugin: 8.7.0
- Kotlin and Compose compiler plugins: 2.1.0
- Gradle wrapper distribution: Gradle 8.9
- Release build: code shrinking enabled through R8/ProGuard rules
- Release signing: no dedicated production signing configuration is declared in this module

The app's declared minimum Android version is API 34. Do not assume older devices are supported. A successful release APK assembly is not evidence that the artifact is production-signed, installable on every target, or correct at runtime.

## User-facing areas

The Compose workbench declares these surfaces in `UiTheme.kt`:

- **Chat** — interact with the agent and view progress.
- **Files** — browse and inspect files in the selected project.
- **Review** — review pending proposed changes and approval actions.
- **Terminal** — access the app's terminal/command-runner integration.
- **Research** — view research functionality.

The actual capabilities and constraints of each surface depend on the implementation, device environment, project, and configured model. In particular, the presence of a terminal surface does not mean that every desktop shell command or development tool is available on Android.

## Source tree

Production Kotlin source is under `app/src/main/java/com/codingagent/`.

| Package | Responsibility represented in source |
| --- | --- |
| `agent/` | Autonomous execution loop, request classification, planning, tool selection and dispatch, progress events, journaling, response-quality checks, retry/repair support, user memory, and self-evolution/self-repair support |
| `core/` | Local app storage, encrypted secret/settings storage, migration, user-memory persistence, and live-module bootstrap/runtime support |
| `intake/` | Goal interpretation, task-intake parsing, typed operations, and code-synthesis proposals |
| `knowledge/` | Document ingestion, local knowledge indexing/search, and knowledge-provider interfaces |
| `model/` | Model request/response types, model settings, JSON response parsing, remote HTTP gateway, streaming, cancellation, and model rotation |
| `research/` | Search providers, source relevance/quality checks, article extraction, research modes, progress, and durable research sessions |
| `ui/` | Main Android activity, Compose screens, review binding, theme, and UI status mapping |
| `workspace/` | Project indexing and file services, path checks, command execution, staged changes, approval coordination, integrity checks, atomic writes, persistence, and rollback |

Other repository areas:

- `app/src/main/AndroidManifest.xml` — app declaration and Android permissions.
- `app/src/main/res/` — launcher artwork, theme resources, and data-extraction rules.
- `app/src/main/assets/knowledge/coding-for-dummies.txt` — bundled knowledge text asset.
- `app/src/test/java/com/codingagent/agent/` and `app/src/test/java/com/codingagent/core/` — JVM unit and acceptance-path tests.
- `docs/` — owner instructions, tool-queue notes, anti-yes-man protocol, and a research design note. Some documents describe intended or historical behavior; compare them with current code before treating them as authoritative.
- `scripts/package-source.sh` — source ZIP packaging and SHA-256 output.
- `scripts/sync_to_github.sh` — a specialized sync helper with external script-path assumptions; it is not a general-purpose build or deployment command.
- Root Gradle files and `gradle/wrapper/` — build configuration and wrapper.

These are responsibility summaries, not claims that every workflow is complete.

## Model configuration and network behavior

The settings dialog currently exposes a **remote HTTP model backend** with a base URL, model ID, API key, and optional extra HTTP headers. The gateway targets an OpenAI-compatible `/chat/completions` endpoint and implements both non-streaming requests and Server-Sent Events (SSE) response parsing, including streamed tool-call argument assembly. The `ModelSettings` data class also contains `rotationModels` and `systemPrompt` fields, but the current settings dialog does not expose those fields. Code search found no call site for `effectiveSystemPrompt()`; agent requests use the built-in `AgentModelProtocol.SYSTEM` prompt. Therefore, custom system-prompt editing is **not currently wired up as a usable UI feature**.

Important constraints:

- Compatibility depends on the specific provider and model. An OpenAI-compatible URL does not guarantee compatible tool calling, streaming, or response formatting.
- Remote non-loopback endpoints are required by the endpoint policy to use HTTPS. Plain HTTP is permitted only for recognized localhost/loopback endpoints.
- The source supports model rotation when fallback model IDs are present in settings data, but the current settings dialog does not expose a fallback-model field. Rotation is not automatic failover to unrelated providers.
- The `systemPrompt` data field is not currently connected to the agent request path. Do not assume the owner can customize the active system prompt through the UI.
- The application source contains encrypted secret/settings storage backed by Android Keystore mechanisms and a legacy-preferences migration path. This does not remove the need to protect the device, backups, logs, and any credentials supplied to a provider.
- Never commit API keys, provider credentials, signing material, local SDK paths, or private project data.

Test the exact provider URL, model ID, authentication, extra headers, streaming behavior, and tool-calling task that you intend to use.

## Project changes and safety boundaries

Source code implements a proposal/review path and includes typed file operations, project-path checks, pre-apply content checksums, atomic file-writing/recovery helpers, post-write integrity checks, and rollback routines. The mutation coordinator includes approval records and constitution/policy checks. Command execution has a separate policy layer.

These are safeguards, not a formal security guarantee. The repository still needs end-to-end validation for path/symlink edge cases, multi-file edits, concurrent changes, interruption during writes, failed rollback, command execution, imported projects, and hostile or malformed model output. Do not assume a change is safe solely because a proposal was generated or a unit test passed. Review the diff and verify the resulting project state before relying on a change.

The research source code includes a composite search stack that can query GitHub, Stack Overflow, public Searx instances, MDN, and DuckDuckGo, with source filtering and relevance ranking. Public search services can be unavailable, rate-limited, or return incomplete results. Research output must be checked against the cited pages and the user's actual question; the code does not guarantee exhaustive web coverage.

The bundled knowledge asset and local knowledge index are distinct from live web research. Do not assume that an indexed document is current unless its provenance and date have been checked.

## Build locally

Requirements:

- JDK 17
- Android SDK platform 35 and build tools 35.0.0
- Android SDK platform-tools
- Network access for Gradle dependency resolution, unless dependencies are already cached

Set `sdk.dir` in an untracked `local.properties` file to the SDK installation on your machine. For example, replace the path below with your actual SDK path:

```properties
sdk.dir=/opt/android-sdk
```

Then run from the repository root:

```bash
chmod +x ./gradlew
./gradlew --version
./gradlew :app:compileDebugUnitTestKotlin --no-daemon --console=plain
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew :app:lintDebug --no-daemon --console=plain
./gradlew :app:assembleDebug --no-daemon --console=plain
./gradlew :app:assembleRelease --no-daemon --console=plain
```

Expected APK locations after successful assembly:

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

Confirm the actual output files after the build. The Gradle configuration shown here does not declare a production release-signing setup. Do not distribute an unsigned or otherwise unverified release artifact as a production release.

## CI workflow

Workflow file: `.github/workflows/android-build.yml`.

Triggers: pushes to `main`, pull requests targeting `main`, and manual dispatch. Its job installs JDK 17 and Android SDK packages, performs a limited security-baseline check, compiles test sources, runs the JVM unit-test task, explicitly reruns selected acceptance-path test classes, runs Android lint, assembles debug and release APKs, and uploads the debug APK artifact.

The security-baseline step checks for certain repository-stored signing files and specific browser/device-impersonation strings. **It is not a comprehensive secret scan, dependency audit, penetration test, or full security review.** The workflow does not upload the release APK in its current configuration.

Read the result for the exact commit. A workflow in progress is not a pass; a successful workflow establishes only that the listed jobs completed successfully in that CI environment. It does not establish physical-device behavior, provider compatibility, production signing, accessibility, or release readiness.

## Tests

Tests are under `app/src/test/java/com/codingagent/`, in both the `agent` and `core` packages. They cover selected behavior such as command policy, tool-call outcomes, acceptance paths, agent-loop handling, local persistence, project/workspace integrity, model settings and parsing, research, storage guards, terminal cancellation, approval tokens, and tool selection.

The CI workflow explicitly selects these test classes in its acceptance-path step:

- `com.codingagent.core.AcceptancePathTest`
- `com.codingagent.core.StorageGuardTest`
- `com.codingagent.core.AutonomousLoopTest`

The general `:app:testDebugUnitTest` task also runs the configured JVM unit tests. Test names and counts are not a substitute for inspecting assertions, and passing JVM tests do not prove end-to-end correctness.

## Source archive helper

Run `scripts/package-source.sh [output.zip]` to create a source archive outside the repository tree. It excludes selected directories and file types such as Git metadata, local agent metadata, build/cache directories, `local.properties`, APKs, AABs, and class files, then prints a SHA-256 checksum.

The script's `forbidden` list is currently initialized empty and is not populated with secret-detection findings. **The script is not a comprehensive credential or sensitive-data scanner.** Inspect the archive contents before sharing it; verify the checksum separately if you need integrity assurance.

## Production-readiness checklist

Before claiming the app is production-ready, obtain and record evidence for all relevant items:

- [ ] Clean build and all CI steps pass on the exact release commit.
- [ ] Release signing is configured and verified, and the release APK is installed and exercised on supported physical devices.
- [ ] The intended real model provider completes representative tasks, including streamed tool calls, errors, rate limits, cancellation, and malformed responses.
- [ ] Project import, listing, indexing/search, file reads, proposed diffs, approvals, apply, verification, and rollback work end to end.
- [ ] Multi-file operations survive interruption, concurrent edits, storage failures, and rollback failures without silent data loss.
- [ ] Path and symlink boundaries, terminal restrictions, permissions, network/TLS handling, and sensitive-data logging are reviewed.
- [ ] Lifecycle interruption, persistence recovery, long-running work, accessibility, and UI behavior are tested on target devices.
- [ ] Source archives and distributed artifacts are inspected for credentials and private data.

Keep this README aligned with the code and reproducible evidence. Mark unverified behavior as unverified, and do not describe planned or partially implemented behavior as completed.
