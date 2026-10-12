# Phase 1 — Baseline and recovery-lifecycle evidence

## Scope and immutable baseline

- Repository: `thepotatoninjahost/Coding-Agent`
- Target branch: `main`
- Audited commit: `83002089d1a0a7b356e3dbf81a70cfb35c40721a`
- Commit: `Complete self-evolution snapshot recovery lifecycle (#73)`
- Recursive Git tree SHA: `c108371fe02cdf9c07c472bcfd17ac1af18f0c17`
- Current recursive tree: 189 entries = 155 tracked files + 34 directory entries; tree response was not truncated.
- Previous inventory baseline: commit `5910ac4841c893de5a5dca25d95b5f9f3591bbce`, tree `81cf30532b85357715520eaa6d92326b1abf58b6`, 186 entries = 153 tracked files + 33 directory entries.
- Reconciliation: the prior 153-file count was correct for the earlier commit. Comparing that commit to the current baseline shows four commits ahead and two added tracked files: `app/src/test/java/com/codingagent/core/SelfEvolutionTest.kt` and `docs/audit/FILE_BY_FILE_AUDIT_LEDGER.md`. Current count is therefore 155, not 153.
- Uploaded source archive `Coding-Agent-main.zip` contains 154 files and does not include `app/src/test/java/com/codingagent/core/SelfEvolutionTest.kt`; it is not a complete mirror of the audited Git tree. Use the commit SHA above as the source of truth.

## Current file-type inventory (155 tracked files)

| Type | Count |
|---|---:|
| Kotlin source/test (`.kt`) | 131 |
| Markdown (`.md`) | 6 |
| XML (`.xml`) | 4 |
| Kotlin DSL (`.kts`) | 3 |
| Properties (`.properties`) | 2 |
| Shell scripts (`.sh`) | 2 |
| YAML (`.yml`) | 1 |
| Text asset (`.txt`) | 1 |
| Java archive (`.jar`) | 1 |
| ProGuard rules (`.pro`) | 1 |
| Gradle wrapper script (no extension) | 1 |
| `.gitattributes` | 1 |
| `.gitignore` | 1 |
| **Total** | **155** |

## Verification evidence

### Merged baseline

- GitHub Actions run #1271, ID `38208556052`, for the exact baseline commit completed with conclusion `success`: https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38208556052
- The workflow ran the security baseline audit, unit-test source compilation, unit tests, explicit acceptance-path tests, Android lint, debug APK assembly, release APK assembly, and debug APK artifact upload.
- Relevant workflow commands:
  - `./gradlew :app:compileDebugUnitTestKotlin --no-daemon --stacktrace`
  - `./gradlew :app:testDebugUnitTest --no-daemon --stacktrace`
  - `./gradlew :app:testDebugUnitTest --tests "com.codingagent.core.AcceptancePathTest" --tests "com.codingagent.core.StorageGuardTest" --tests "com.codingagent.core.AutonomousLoopTest" --no-daemon --stacktrace`
  - `./gradlew :app:lintDebug --no-daemon --stacktrace`
  - `./gradlew :app:assembleDebug --no-daemon --stacktrace`
  - `./gradlew :app:assembleRelease --no-daemon --stacktrace`
- Code/test inspection confirms coverage for rollback after a failed repair attempt, rejection of corrupted snapshot checksums, and restore changes remaining unapplied until dual approval.

### Follow-up defect found; not merged

- A review found that `promoteSource` on the baseline used an empty-string fallback when recovery-target metadata was absent. That could mark a snapshot active even though no safe recovery target could be restored.
- Fix and regression tests are isolated in PR #74: https://github.com/thepotatoninjahost/Coding-Agent/pull/74
- PR head: `2ae101043ed8de05d6a04b8db95da8f4d8e63202`
- Added checks reject missing/unsafe project-relative recovery targets and unknown version IDs; restore tests assert the file is unchanged before the second approval.
- PR CI run #1272, ID `38210516271`: https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38210516271
- At the time this report was prepared, the rerun had passed unit-test source compilation, unit tests, acceptance-path tests, and Android lint; debug APK assembly was still in progress. Do not treat the PR as fully validated until the workflow concludes successfully.

## Phase 1 status — not complete

Completed: immutable baseline identified; recursive tree and tracked-file counts reconciled; file types counted; merged-commit CI success recorded; several recovery/rollback safety tests inspected.

Remaining before claiming Phase 1 complete:
1. Confirm PR #74 CI reaches a successful terminal conclusion; fix any failure and rerun.
2. Complete an explicit end-to-end repair path test proving failure detection → repair proposal → required approvals → apply → verification, rather than treating separate tests as proof of the whole path.
3. Confirm rollback after a forced post-apply verification failure and record the exact test/result.
4. Confirm dual-owner approval enforcement for the recovery path and no target-file modification before the second approval.
5. Recheck the final commit/tree and update this evidence after any merged fix.
6. Continue the file-by-file ledger; its existing rows explicitly distinguish inventory from completed review, and the full repository audit is not complete.

No claim is made here that the entire Phase 1 audit or all end-to-end behavior is 100% complete.
