# Coding Agent

A phone-first **Coding-Agent**: an autonomous software-engineering system that plans, acts, observes, and iterates until the goal is completed.

It is not a chatbot that answers coding questions in one shot. It works like a careful senior developer with tool access — evidence-first, precise, and persistent. The agentic loop:

1. **Understands the goal** and decomposes it into ordered steps  
2. **Gathers real context** from the imported project (list / search / read — never invents paths or contents)  
3. **Plans** and revises the plan as new evidence appears  
4. **Acts** with exactly one tool per turn (files, search, terminal, verification, staged mutations)  
5. **Observes** the tool result; on failure, diagnoses and retries correctly  
6. **Verifies** with a bounded static unfinished-work scan; it does not automatically run imported project build/test scripts.  
7. **Hands over** a clear, evidence-based answer or a dual-approval change proposal  

It does not quit early out of convenience. It only stops early when a specific missing input from the user is required that tools cannot supply.

The runtime keeps a degraded offline knowledge path. Non-trivial coding requests that require external knowledge fail closed unless a web research provider is configured and returns evidence. The model-driven autonomous path uses an OpenAI-compatible gateway with tool calling and streamed server-sent-event deltas. Configure the gateway via the in-app Model settings screen (base URL, model id, API key).

## Current product status

The repository contains the modular backend and an Android workbench with a persistent Chat workspace. Core agent loop, evidence gates, and static verification are implemented and hardened.

The current APK supports:
- Project import via Storage Access Framework and indexing
- Source search and local knowledge search
- Autonomous model loop with real tool calling (list_files, read_file, search_project, verify, mutations with dual approval, etc.)
- Always-on static verification (unfinished-work marker scan) — never reports a fake pass
- Evidence requirement: inspect/error/analyze requests must actually read or search project files before a final answer is accepted
- Model settings UI for any OpenAI-compatible provider (Groq, SambaNova, OpenRouter, local, etc.)
- Transactional file changes with checksum-backed rollback
- Persisted chat history and task journal

Remaining product polish (not blockers for basic use):
- Multi-file diff staging UI refinements
- Broader document ingestion beyond the example asset
- Extended physical-device verification of long streaming sessions

Terminal behavior and limits are documented in **Terminal limitations**.

## Recommended remote provider (as of 2026-08)


- Base URL: `https://api.groq.com/openai/v1`
- Model: `llama-3.3-70b-versatile`
- API key: from console.groq.com

Any other OpenAI-compatible endpoint works the same way.

## Architecture

There is one execution spine: `AutonomousAgent` (`com.codingagent.agent`). Offline local lanes (hello, list, status, read, explicit synthesis edits) and the model-driven tool loop both run through that class. `AgentRuntime` holds shared result types only. There is no second orchestrator.

Production source is split by job under `app/src/main/java/com/codingagent/`:

| Package | Job |
|---|---|
| `agent` | Single spine (`AutonomousAgent`), constitution, tool dispatch (`AgentToolDispatch`), planning (`AgentPlanner`), chat workspace, journal |
| `intake` | Free text → typed goal (`TaskIntakeParser`, `GoalInterpreter`, `CodeSynthesisEngine`) |
| `workspace` | Project import, index (`ProjectIndexer`), files, diffs, dual-approval mutations, terminal, verification, checksum rollback (`ProjectWorkspace`) |
| `model` | User-configured OpenAI-compatible HTTP gateway (`RemoteHttpGateway`), settings, streamed SSE tool calls. No vendor is hardcoded. |
| `research` | Web evidence (`WebResearchProvider`, `DurableDeepResearchProvider`, `PersonalResearchProvider`, `SourceQuality`, `QueryLanes`) |
| `knowledge` | Local searchable chunks behind `AgentKnowledge` / `KnowledgeProvider` |
| `ui` | Compose workbench (`MainActivity`, screens, theme) |
| `core` | Residual device persistence (`LocalStore`). Not the agent loop. |

Execution path:

1. `TaskIntakeParser` interprets a request into a typed goal contract and operation.
2. `AutonomousAgent` owns the loop: gather evidence, plan, one tool per turn (queue extras), observe, verify, hand over.
3. `AgentPlanner` produces and revises the plan from evidence.
4. `ProjectIndexer` inventories project files, languages, imports, symbols, and checksums.
5. `AgentKnowledge` supplies local evidence. `WebResearchProvider` / deep-research providers supply internet evidence when the request requires it. Empty research fails closed.
6. `CodeSynthesisEngine` creates a proposal when the request does not contain an explicit operation.
7. `ProjectWorkspace` + `MutationCoordinator` apply edits through typed transactions. Dual owner approval is required before a write hits disk.
8. `VerificationReport` records bounded static checks. A static pass is not a compiler or test pass; imported project build/test scripts are not run automatically after approval.
9. Failed approved changes roll back when static verification fails, and a model-synthesized repair is staged for separate dual owner approval. Static verification does not prove that the project compiles or its tests pass.
10. `AgentJournal`, lessons, and `LocalStore` persist task evidence and chat for later work.

Unit tests currently live under `app/src/test/java/com/codingagent/core/` even though production code is package-split as above.

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
6. Web search and durable deep research are implemented as providers behind the same knowledge boundary; deep research retains source provenance and searchable chunks.

The current implementation includes the local knowledge example, document-ingestion/indexing components, web search, and durable deep-research providers. The exact end-user ingestion workflow remains limited by the UI and supported input paths; this section is not a claim that every document format or ingestion path is supported.

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

## Terminal limitations

The Terminal tab and the agent `run_command` tool use the same underlying runner:

- Command: `sh -c <your text>`
- Working directory: the imported project copy in app-private storage
- Timeout: 180 seconds (Stop sends `destroy` / `destroyForcibly`)
- Output: stdout and stderr, each capture capped at 256 KiB

The **Terminal tab is the owner-controlled terminal** and remains unrestricted. The **agent `run_command` tool is restricted** to project inspection and standard verification commands. Model commands cannot delete or modify files, chain shell commands, redirect output, access parent/absolute paths, use network tools, or change global Gradle configuration.

This is the stock Android `sh` (toybox/toolbox on current devices). It is not bash, not a login shell, and not Termux. Typical available commands are basic Unix utilities already on the device (`ls`, `pwd`, `cat`, `echo`, limited `grep`). There is usually **no** JDK, **no** Gradle, **no** `git`, and **no** package manager. A command such as `./gradlew testDebugUnitTest` will fail on a normal phone unless those binaries are already on `PATH`.

A passing shell command is not a file write. Source mutations still go through dual owner approval and checksum-backed transactions.

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
- Project indexing and exact mutation behavior
- Typed transaction records, checksum-backed rollback, and owner-approved repair staging
- Live module and live model updates
- Android lint
- Debug APK assembly

The Android unit tests are JVM tests and run with:

```bash
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
```

## Build on GitHub

The repository includes `.github/workflows/android-build.yml`. That file is the source of truth for CI behavior.

### When the workflow runs

| Trigger | Behavior |
|--------|----------|
| Push to `main` | Runs, unless the commit only changes ignored paths (see below). |
| Pull request targeting `main` | Runs, unless the PR only changes ignored paths (see below). |
| Manual **Run workflow** (`workflow_dispatch`) | Always runs, even if the latest commit was docs-only. |

**Paths ignored for automatic push/PR runs** (no automatic CI for these alone):

- `**/*.md` (all Markdown files, including this README)
- `LICENSE`
- `.gitignore`

If a push or PR changes only those paths, GitHub does **not** start the build. Change any other tracked file (Kotlin, Gradle, XML, the workflow itself, etc.) and CI runs as usual. Use **Actions → Android build → Run workflow** when you need a build after a docs-only change.

### Concurrency

Only one `Android build` run is active per branch/ref at a time:

- Group: `android-build-${{ github.workflow }}-${{ github.ref }}`
- `cancel-in-progress: true`

A newer push to the same branch cancels the older in-progress run so the APK and status you see match the latest commit, not a stale job.

### Runner

- Image: `ubuntu-24.04`
- Job timeout: 90 minutes (the job is cancelled if it runs longer)
- Default shell for `run` steps: `bash`
- One job (`build`), sequential steps — debug APK is assembled and uploaded before the emulator step so a later failure does not depend on skipping the APK upload

### What the job does

1. Checks out source (read-only permissions; single-commit shallow clone, `fetch-depth: 1` — full git history is not required for this build).
2. Sets up JDK 17 (Temurin) and the Android SDK (API 35 / build-tools 35.0.0). Android SDK licenses are accepted explicitly for headless CI; packages installed: platform-tools, platforms;android-35, build-tools;35.0.0.
3. Runs the security baseline audit: blocks tracked keystores/`.jks` files; blocks browser/device impersonation User-Agents in `app/src/main`; blocks common hardcoded secret shapes in `app/src/main` (private keys, `sk-…`, `ghp_…`, AWS `AKIA…`). Unit-test fixtures under `app/src/test` are excluded so redaction tests can still use fake secrets.
4. Verifies the Gradle Wrapper JAR SHA-256 against a pinned official checksum from [gradle.org/release-checksums](https://gradle.org/release-checksums/) (`cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8`). The project downloads the **Gradle 8.9** distribution (`distributionUrl` / `distributionSha256Sum` in `gradle-wrapper.properties`). When upgrading Gradle, update the properties, the wrapper JAR, and this pinned hash together or CI fails on purpose.
5. Compiles unit-test sources, then runs the full JVM unit suite (`testDebugUnitTest`), which already includes the acceptance-path classes (`AcceptancePathTest`, `StorageGuardTest`, `AutonomousLoopTest`). On unit-test failure, uploads unit test reports.
6. Runs Android lint (`lintDebug`).
7. Assembles the **debug** APK and uploads it as the workflow artifact **`coding-agent-debug-apk`** (14-day retention).
8. Assembles the release APK (not uploaded as an artifact).
9. Starts an API 35 emulator and runs instrumentation tests (`connectedDebugAndroidTest`).
10. On instrumentation failure, uploads Android test reports.

Permissions: `contents: read` only.

### Download the debug APK

1. Open the repository on GitHub.
2. Open **Actions**.
3. Select **Android build**.
4. Open a completed run for the commit you want (or start one with **Run workflow**).
5. Under **Artifacts**, download **`coding-agent-debug-apk`**.

The debug APK upload step is configured to run even when a later step fails, so a usable debug APK can still appear when unit tests, lint, and `assembleDebug` succeeded but a later step (for example emulator tests) failed. Confirm the artifact exists on that run before installing.

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
