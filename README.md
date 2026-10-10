# Coding Agent

Coding Agent is an Android application written in Kotlin with a Jetpack Compose interface. The repository includes an autonomous agent loop, task-intake and tool-dispatch code, an OpenAI-compatible HTTP chat-completions gateway, and a project workspace for file inspection and proposed changes.

**Status: active development. Production readiness has not been established.** This README records source and CI facts, not aspirations. Check the current [GitHub Actions runs](../../actions) for the status of the exact commit you plan to use.

## Android build configuration

The app module declares application ID `com.codingagent`, compile SDK 35, target SDK 35, minimum SDK 34, and Java/Kotlin target 17. Release builds enable code shrinking. A successful APK assembly alone does not prove signing, distribution readiness, or runtime correctness.

## Source layout

Production source is under `app/src/main/java/com/codingagent/`.

- `agent`: autonomous execution loop, task planning support, tool dispatch, chat workspace, journaling
- `model`: model protocol and OpenAI-compatible HTTP gateway with streamed response parsing
- `workspace`: project files, indexing/search, transactional edits, checksums, verification, rollback
- `intake`: task interpretation and typed operation data
- `research`: research provider interfaces and implementations
- `ui`: Android/Compose interface
- `core`: supporting local persistence

These package names describe source organization; they do not imply that every workflow is complete.

## Build and test

Install JDK 17 and Android SDK platform/build tools 35. Create an untracked `local.properties` with the correct SDK path, then run:

```bash
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
chmod +x ./gradlew
./gradlew :app:compileDebugUnitTestKotlin --no-daemon --console=plain
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew :app:lintDebug --no-daemon --console=plain
./gradlew :app:assembleDebug --no-daemon --console=plain
./gradlew :app:assembleRelease --no-daemon --console=plain
```

Replace `/opt/android-sdk` with the actual SDK path. Debug APK output is `app/build/outputs/apk/debug/app-debug.apk`; release APK output is `app/build/outputs/apk/release/app-release.apk`.

## CI

`.github/workflows/android-build.yml` runs on pushes and pull requests targeting `main`, and on manual dispatch. It compiles unit-test sources, runs JVM unit tests and selected acceptance-path tests, runs Android lint, assembles debug and release APKs, and uploads the debug APK artifact. A result is valid only for the commit and steps actually completed. CI does not prove physical-device behavior or production readiness.

## Implemented safety mechanisms in source

The workspace includes typed file operations, pre-change checks, atomic writes, checksum verification, and rollback logic. The agent includes a mutation-approval flow. These mechanisms still require end-to-end testing across multi-file changes, interruption, concurrent edits, and failure recovery; do not infer that every case is safe from the existence of the code alone.

The model gateway uses an OpenAI-compatible `/chat/completions` endpoint and supports streamed server-sent-event parsing. Provider compatibility varies. Test the exact endpoint/model and a complete tool-calling task. Never commit API keys, credentials, signing material, SDK paths, or private project data.

## Production-readiness gates

Before describing a build as production-ready, obtain current evidence for:

- Full end-to-end tasks against the intended real model provider, including streamed tool calls and provider failures
- Project import, indexing, file reads/search, proposal review, approval, apply, verification, and rollback
- Cancellation, timeout, lifecycle interruption, and persistence recovery
- Storage boundaries, network/TLS behavior, permissions, and sensitive-data logging
- Release signing and installation of the release build on target physical devices
- Long-running streaming and accessibility/lifecycle behavior

Tests live under `app/src/test/java/com/codingagent/core/`. CI explicitly runs `AcceptancePathTest`, `StorageGuardTest`, and `AutonomousLoopTest` in addition to the unit-test task. Passing these checks is useful evidence, not a substitute for the release gates above.

The repository includes `scripts/package-source.sh` for source archives. Inspect its behavior and verify its output/checksum for the specific source tree being packaged.

Keep this file aligned with source and reproducible test evidence. Document unverified behavior as unverified, and do not describe planned functionality as implemented.
