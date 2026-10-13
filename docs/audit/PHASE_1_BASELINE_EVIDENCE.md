# Phase 1 — Baseline and recovery-lifecycle evidence

## Scope and immutable baseline

- Repository: `thepotatoninjahost/Coding-Agent`
- Target branch: `main`
- Snapshot status: historical baseline, not current `main` as of 2026-10-13. The immutable baseline below is retained for reproducibility; current-main reconciliation is recorded separately below.
- Audited commit: `83002089d1a0a7b356e3dbf81a70cfb35c40721a`
- Commit: `Complete self-evolution snapshot recovery lifecycle (#73)`
- Recursive Git tree SHA: `c108371fe02cdf9c07c472bcfd17ac1af18f0c17`
- Current recursive tree: 189 entries = 155 tracked files + 34 directory entries; tree response was not truncated. The full path-by-path capture is in `docs/audit/PHASE_1_RECURSIVE_TREE.txt`.
- Previous inventory baseline: commit `5910ac4841c893de5a5dca25d95b5f9f3591bbce`, tree `81cf30532b85357715520eaa6d92326b1abf58b6`, 186 entries = 153 tracked files + 33 directory entries.
- Reconciliation: the prior 153-file count was correct for the earlier commit. Comparing that commit to the current baseline shows four commits ahead and two added tracked files: `app/src/test/java/com/codingagent/core/SelfEvolutionTest.kt` and `docs/audit/FILE_BY_FILE_AUDIT_LEDGER.md`. Current count is therefore 155, not 153.
- Uploaded source archive `Coding-Agent-main.zip` contains 154 files and does not include `app/src/test/java/com/codingagent/core/SelfEvolutionTest.kt`; it is not a complete mirror of the audited Git tree. Use the commit SHA above as the source of truth.

## Current-main reconciliation (checked 2026-10-13)

The immutable audit snapshot above is commit `83002089d1a0a7b356e3dbf81a70cfb35c40721a`; it must not be described as the current main branch.

- Current `main` commit: `74f1c18f2fbee310eb4a6e26b6d677544502ca99` (PR #69 merge).
- Current main tree SHA: `117a1a659affc22e6813977d83233b7eb58617d0`.
- Recursive tree API response: not truncated; 195 entries = 161 tracked files + 34 directories.
- Android build for that exact main commit: run `38410597602`, conclusion `success`: https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38410597602.
- PR #74, “Reject unsafe self-evolution recovery targets,” is **merged** at `4ab3e4117e17fda89325f92a246a411131f05b14`.
- PR #76, “Test the complete repair approval and verification lifecycle,” is **merged** at `8b0fa217811ccdecbf827a28beb251f7a7a08b88`.
- PR #78, #79, and #80 are also merged; the ledger entries below that cite their fixes are historical findings with subsequent fixes recorded, not open-PR status claims.
- PR #69 is merged at the current main commit above.
- PR #77, “Require spoken phrase and exact typed confirmation for mutations,” is **closed without merge**. Its last reported head is `abc3d5555eb0c9ba42d59e370fe797db62246fc2`; its change is not part of main.

### Dual-modality approval remains an implementation blocker on current main

Current-main source inspection confirms the required spoken-phrase-plus-exact-typed-confirmation safeguard is still **not implemented**:

- `MainActivity.approvePendingProposal` creates `OwnerApprovalToken.authenticated(id)` after Android biometric/device-credential authentication.
- `MutationCoordinator.approve` accepts that generic token and records the actor as `device-owner`; it does not validate a speech transcript or typed phrase.
- `OwnerApprovalToken` contains no speech transcript, typed phrase, or modality field.
- `AcceptancePathTest` still constructs approvals through the generic `OwnerApprovalToken.authenticated` helper.

Therefore the two recorded approvals are not the owner's required two distinct modalities. Do not claim the approval safeguard is satisfied or verified. This finding is based on current-main source and test inspection, not on the closed PR #77 proposal.

## Historical file-type inventory (155 tracked files)

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

### Recovery-target validation fix — PR #74, merged

- A review found that `promoteSource` on the baseline used an empty-string fallback when recovery-target metadata was absent. That could mark a snapshot active even though no safe recovery target could be restored.
- Fix and regression tests were merged through PR #74: https://github.com/thepotatoninjahost/Coding-Agent/pull/74 (merge commit `4ab3e4117e17fda89325f92a246a411131f05b14`).
- PR head: `2ae101043ed8de05d6a04b8db95da8f4d8e63202`
- Added checks reject missing/unsafe project-relative recovery targets and unknown version IDs; restore tests assert the file is unchanged before the second approval.
- PR CI run #1272, ID `38210516271`: https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38210516271
- Run conclusion: `success`. Test compilation, full unit tests, explicit acceptance-path tests, Android lint, debug APK assembly, release APK assembly, and debug APK upload all passed.

### End-to-end repair lifecycle — PR #76, merged

- PR: https://github.com/thepotatoninjahost/Coding-Agent/pull/76 (merge commit `8b0fa217811ccdecbf827a28beb251f7a7a08b88`).
- Head: `10a231863b57d9743fd0cba8280536a4ac67b8c4`
- Added `RepairLifecycleAcceptanceTest.kt` exercises this sequence: a broken replacement fails verification; approval one leaves disk unchanged; approval two permits application; failed verification rolls the change back; a repair proposal is staged; the first repair approval leaves the file unchanged; the second approval applies the repair; post-apply verification passes and the result is `Applied`.
- CI run #1274, ID `38211267466`: https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38211267466
- Verified terminal conclusion: `success`. Security baseline audit, test-source compilation, full unit tests (including the new lifecycle test), explicit acceptance-path tests, Android lint, debug APK assembly, release APK assembly, and debug APK upload all passed.

### Required dual-approval semantics — confirmed by owner

The required dual approval is **two different modalities from the same owner**, not two different people and not two biometric prompts:

1. The owner speaks the displayed confirmation word or phrase through a live speech-recognition interaction.
2. The owner separately types that exact word or phrase into the app.
3. Both events must bind to the same pending proposal and be independently validated before any write is authorized.
4. The agent must not be able to create, skip, replay, or substitute either event. A missing or mismatched spoken transcript or typed phrase must leave the project files unchanged.

### Current implementation fails this acceptance criterion

- `MainActivity.approvePendingProposal` currently requests Android biometric/device-credential authentication on each confirmation.
- `OwnerApprovalToken.authenticated` represents only a generic authenticated event; it carries no speech transcript, typed phrase, or approval modality.
- `MutationCoordinator.approve` accepts two such generic events and records both under the literal actor label `device-owner`.
- Existing tests manufacture both tokens using `OwnerApprovalToken.authenticated`; they do not exercise speech recognition or typed phrase matching.
- Therefore the current implementation **does not meet the owner's stated dual-approval requirement**. This is a confirmed implementation blocker, not an unresolved interpretation of “dual-owner approval.” Do not merge this as a completed audit or claim the approval safeguard is verified until the implementation and tests enforce spoken phrase + exact typed confirmation.

### Spoken phrase + exact typed confirmation — PR #77, closed without merge

- PR: https://github.com/thepotatoninjahost/Coding-Agent/pull/77
- Branch: `security/spoken-typed-approval`
- Last head recorded by GitHub: `abc3d5555eb0c9ba42d59e370fe797db62246fc2`. GitHub reports this PR closed without merge; none of its proposed implementation is included in current main.
- Replaces generic approval events with ordered, proposal-bound channels: a recognized spoken challenge phrase first, then an exact typed match. The coordinator records channel-specific labels and rejects channel substitution, replay, cross-proposal use, expiry, and legacy generic approval state.
- Adds acceptance coverage for skipped channels, wrong phrases, replay, and legacy approval state. The Chat and Review surfaces display the challenge phrase.
- Earlier PR CI runs are historical evidence only; they do not establish that the closed PR was merged or that current main enforces this flow. Current main was inspected directly and still uses generic biometric/device-credential approval tokens.
- Limitation: the Android speech-recognition activity returns a transcript; this enforces the requested interaction flow but is not a cryptographic guarantee against a compromised device or a malicious recognition provider.

## Phase 1 status — evidence improved; audit not yet complete

Completed:
- Exact baseline commit and recursive Git tree identified.
- Recursive tree captured path by path; tracked-file count and previous 153-file count reconciled.
- Current tracked file types counted; uploaded ZIP mismatch recorded.
- Baseline CI and both follow-up PR CI runs confirmed successful, including all configured build steps.
- Recovery-target validation (PR #74) and end-to-end repair/approval/rollback lifecycle coverage (PR #76) are merged; current main has a successful build run recorded above.

Still outstanding:
1. The baseline inventory is intentionally historical; current main is `74f1c18f2fbee310eb4a6e26b6d677544502ca99` with tree `117a1a659affc22e6813977d83233b7eb58617d0` and 161 tracked files. Refresh this current-state addendum whenever main changes materially.
2. PR #74 and #76 are merged; PR #77 is closed without merge. PR #75 remains open until its documentation changes are reviewed and merged.
3. Finish the file-by-file audit ledger. An inventory or a test suite passing does not prove every repository file has been reviewed.
4. Implement and test the owner's confirmed dual-modality approval: live spoken confirmation of the proposal-bound phrase followed by exact typed confirmation of that phrase; reject missing, mismatched, replayed, expired, or cross-proposal confirmations without changing files.
5. Document the complete acceptance matrix with exact test names/results for corrupted checksums, unsafe paths, invalid snapshot versions, approval sequencing, failed-verification rollback, and repair verification.

Therefore, the historical baseline/follow-up runs #1271, #1272, and #1274 were green, and current main's run #38410597602 is green. That does **not** mean Phase 1 is 100% complete: the file-by-file audit and acceptance-criteria reconciliation remain open, and the required spoken-plus-typed approval is still missing from current main.
