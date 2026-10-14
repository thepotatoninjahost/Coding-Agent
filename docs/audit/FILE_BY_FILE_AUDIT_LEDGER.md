# File-by-file audit ledger

- Repository: `thepotatoninjahost/Coding-Agent`
- Snapshot type: historical baseline inventory, not current `main`.
- Current code audit reconciled against the 2026-10-14 code snapshot recorded in the final section below. The Phase 1 evidence file retains historical snapshots and is not the current inventory.
- Baseline ref: `main`
- Baseline commit: `83002089d1a0a7b356e3dbf81a70cfb35c40721a`
- Inventory tree SHA: `c108371fe02cdf9c07c472bcfd17ac1af18f0c17`
- Inventory: 189 recursive tree entries = 155 tracked files + 34 directories.
- Ledger scope: every tracked blob in the recursive Git tree at the baseline commit.
- Important: inventory is not inspection. Rows start `in progress`; no file is represented as reviewed or verified until evidence is recorded.

| Path | Type | Size (bytes) | Line count | Purpose | Dependencies / dependents | Review method | Findings | Action | Verification evidence | Status |
|---|---|---:|---:|---|---|---|---|---|---|---|
| `.gitattributes` | configuration/build | 88 | 5 | Defines Git text normalization and LF endings for shell, Kotlin, Kotlin DSL, and Markdown files. | Git checkout/commit behavior; affects repository text files. | Full-file line-by-line configuration inspection. | F-2.3-01: no explicit eol rule for XML/YAML/properties; generic text=auto still applies. No functional defect established. | retain; consider explicit rules only if cross-platform line-ending drift is observed. | Content inspected; Git normalization behavior not exercised in a clone on multiple platforms. | reviewed |
| `.github/workflows/android-build.yml` | configuration/build | 3010 | 97 | Runs Android CI on pushes/PRs to main and manual dispatch; installs JDK/SDK; performs signing/browser-impersonation checks, compiles/tests, lint, debug/release APK builds, and uploads artifacts. | GitHub Actions runners and third-party actions; JDK 17; Android SDK 35; Gradle wrapper; app tests/build. | Full-file line-by-line workflow and shell-command inspection. | F-2.3-07: find expression combines -o predicates without grouping, so exclusions apply asymmetrically; the first *.keystore branch can match excluded .git/build paths and create false positives. It may also scan beyond the intended source tree. The check only detects selected filename extensions, not all signing-secret formats. | fix shell predicate grouping and scope; test against positive/negative fixture paths. | Workflow YAML and commands inspected; workflow execution/logs and shell fixture tests not checked. | reviewed |
| `.gitignore` | configuration/build | 149 | 12 | Excludes local Gradle/build/IDE state, local.properties, app signing artifacts, and agent workspace state from normal Git adds. | Git ignore matching; local developer and CI workspace. | Full-file line-by-line pattern review. | F-2.3-02: ignores debug keystore names and .b64 signing file, but ignore rules do not prevent already-tracked secrets; repository history/tracked-file scan not performed in this step. | retain; separately verify tracked/history secret exposure. | All patterns read; no git check-ignore or tracked-file audit run here. | reviewed |
| `README.md` | documentation | 12741 | 223 | Explains product scope, architecture, mutation/rollback semantics, terminal limits, development, CI, and source packaging. | Cross-package architecture and behavior; Gradle workflow and scripts; app features. | Full-file line-by-line documentation review against the baseline source tree and workflow descriptions. | F-2.3-09: documentation contains stale/internally inconsistent statements: it says internet-backed retrieval remains future work while WebResearch/deep-research providers exist; package/component names in architecture prose do not consistently match the source tree. Several verification-coverage claims need alignment with the actual workflow. | reconcile capabilities, component names, and verification claims with implementation and CI; do not describe unverified device behavior as proven. | 223 lines read; statements compared with relevant source inventory and workflow; full cross-reference of every capability not yet performed. | in progress |
| `app/build.gradle.kts` | configuration/build | 2589 | 68 | Defines Android application module, SDK levels, versioning, Java/Kotlin 17, Compose, release shrinking, packaging, and dependencies. | Root plugin declarations; Android SDK 35; Gradle environment GITHUB_RUN_NUMBER; Compose, Activity, DocumentFile, Biometric, Coroutines, Tink, JUnit, org.json. | Full-file line-by-line build/dependency inspection. | F-2.3-03: dependency versions are declared directly but no dependency lock/verification metadata is present in the inventoried tree; reproducibility relies on pinned direct versions and transitive resolution. Whether this is acceptable needs project policy. | investigate dependency locking and supply-chain verification policy; do not add lockfiles without confirming intended Gradle setup. | Declarations inspected; dependency graph and resolved artifacts not obtained in this review. | reviewed |
| `app/src/main/AndroidManifest.xml` | configuration/build | 901 | 21 | Declares INTERNET permission, application icon/label/theme/data-extraction rules, and exported launcher MainActivity with configuration-change handling. | Android package manager/runtime; MainActivity; @drawable/ic_launcher; @xml/data_extraction_rules; @style/Theme.CodingAgent. | Full-file line-by-line manifest inspection. | F-2.3-06: INTERNET permission is broad but consistent with an app with network features; exported launcher activity is expected. Config-change handling needs runtime lifecycle verification. | retain; verify network use and activity recreation behavior in implementation/runtime review. | Manifest inspected; merged manifest and runtime behavior not checked. | reviewed |
| `app/src/main/assets/knowledge/coding-for-dummies.txt` | knowledge/asset | 408460 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/AgentCommandPolicy.kt` | application source | 8239 | 173 | Restricts autonomous model-invoked shell commands and validates executable, shell syntax, path operands, find, grep/rg, and git subcommands. | Called by autonomous run_command path; interacts with project root and filesystem canonicalization. | Full-file line-by-line policy and path-boundary inspection. | F-2.3-10: policy is allowlist-based and rejects common shell escape syntax; filesystem operand validation applies only to selected executables, while allowed git inspection commands are not path-confined by this helper. Verify that git operates only within the imported project repository and cannot expose host or agent-private state. | trace all call sites and validate working directory/repository boundaries; add adversarial tests for git paths and unusual option parsing if the runner can target a non-project cwd. | 173 lines inspected; static review only; adversarial execution tests not run in this review. | in progress |
| `app/src/main/java/com/codingagent/agent/AgentConstitution.kt` | application source | 5066 | 125 | Defines action categories, twelve constitution rules, blocking checks, approval expiry, and an in-memory approval ledger. | MutationCoordinator and action authorization flows; approval records and verification reports. | Full-file line-by-line authorization logic inspection. | F-2.3-11: READ_ONLY actions return no violations after the lockdown guard, bypassing owner/permission/approval rules by design; callers must classify actions correctly. ApprovalLedger is in-memory, so durable authorization evidence must be maintained elsewhere. | trace all action-category construction sites and ensure no mutating operation can be misclassified READ_ONLY; confirm persisted proposal approvals remain authoritative after restart. | 125 lines inspected; static logic review; call-site-wide category audit remains pending. | in progress |
| `app/src/main/java/com/codingagent/agent/AgentDirectLanes.kt` | application source | 8374 | 183 | Handles local greeting, listing, status, direct read, inspect, and agent-meta requests without a model loop. | ProjectWorkspace.verify; ProjectFileService; AgentRequestKind; LocalFileEvidence. | Full-file review, direct-lane call trace, new regression test, and full CI. | F-2.3-23: agent-meta responses claimed passed verification without a project scan; direct reads claimed passed without invoking verification. | Fixed in PR #80: meta response records not verified; direct read runs workspace.verify() instead of fabricating a pass. | PR #80 CI run 38221135507 succeeded; OperationalAgentTest.directReadVerifiesProjectButAgentMetaDoesNotClaimVerification. | reviewed |
| `app/src/main/java/com/codingagent/agent/AgentOfflineMutation.kt` | application source | 4579 | 100 | Stages explicit/offline mutation proposals and returns approval, needs-input, or failure outcomes. | CodeSynthesisEngine; MutationCoordinator; PendingChangeProposal; VerificationReport; AutonomousAgent. | Full-file review and end-to-end offline proposal/clarification tests. | F-2.3-24: needs-input claimed verification passed; needs-approval discarded proposal.verification and substituted a fabricated passed report. | Fixed in PR #80: needs-input is not marked verified; approval task preserves proposal.verification. | PR #80 CI run 38221135507 succeeded; OperationalAgentTest.underspecifiedOfflineEditDoesNotClaimVerificationPassed and offline proposal verification-consistency assertion. | reviewed |
| `app/src/main/java/com/codingagent/agent/AgentPrompt.kt` | application source | 7092 | 146 | Builds the model system prompt, repository map, and evidence-based fallback summaries. | AutonomousAgent; TaskIntake; ProjectFileService; ProjectWorkspace; LessonContext; LogicReasoning. | Full-file line-by-line prompt and evidence-budget inspection. | F-2.3-14: `repoMap(maxPaths)` and prompt/evidence methods accept caller-provided limits without clamping negative values; negative limits can throw in Kotlin `take`. Prompt mandates a tool call per turn and uses source-only indexing, which should remain aligned with actual dispatch. | clamp public limit arguments and verify prompt rules against tool-dispatch behavior; retain evidence-budget caps. | Source read; static review only; caller limits and prompt/dispatch integration not exhaustively traced. | in progress |
| `app/src/main/java/com/codingagent/agent/AgentRequestFocus.kt` | application source | 408 | 13 | Strips a conversation wrapper to return the most recent `Current request:` suffix. | AutonomousAgent request normalization and wrapped conversation input. | Full-file line-by-line wrapper parsing review. | F-2.3-15: uses the last case-insensitive marker and returns the suffix; if the wrapper contains a later unrelated occurrence of this literal, earlier request context is discarded. Confirm wrapper format is unambiguous. | add tests for nested/repeated marker text and malformed wrappers if model/user content can contain the marker. | 13 lines inspected; static review only; wrapper call sites not fully traced. | in progress |
| `app/src/main/java/com/codingagent/agent/AgentRequestKind.kt` | application source | 5834 | 121 | Classifies greetings, listings, status/meta requests, whole-project reviews, explicit paths, and marker-only inspections. | AgentDirectLanes dispatch; intake/request handling and direct read/inspect flows. | Full-file line-by-line classifier and regex review. | F-2.3-16: heuristic regex routing can classify short generic requests containing review/analyze terms as whole-project reviews; path extraction trims leading `/`, intentionally converting absolute-looking paths to project-relative names rather than honoring host paths. Add regression cases for ambiguous short requests. | exercise precedence across listing/status/whole-project review/explicit path classifiers; ensure ambiguous inputs do not silently route to the wrong lane. | 121 lines inspected; static review only; classifier test coverage not fully mapped. | in progress |
| `app/src/main/java/com/codingagent/agent/AgentRuntime.kt` | application source | 631 | 14 | Defines typed runtime outcomes for completed, input-needed, approval-needed, and failed tasks. | AutonomousAgent execution spine; AgentTask result model; chat UI and tests. | Full-file line-by-line result-vocabulary inspection. | No standalone logic defect found; the type model itself does not enforce status invariants beyond its sealed variants. | retain; validate construction sites and UI rendering against the typed outcome semantics. | 14 lines inspected; static review only; all construction sites not exhaustively enumerated. | in progress |
| `app/src/main/java/com/codingagent/agent/AgentSupport.kt` | application source | 4127 | 88 | Defines knowledge search interface, planning step/contract models, and append-only task journal with recent-record retrieval. | AutonomousAgent; TaskIntakeParser; ProjectWorkspace; KnowledgeHit; AgentTask; `.coding-agent/tasks.tsv`. | Full-file line-by-line planning and journal persistence inspection. | F-2.3-17: task journal uses tab-separated fields with only tabs/newlines replaced, but does not escape pipe characters in the joined event field; parsing must preserve event semantics. `recent()` reads raw bytes backward and assumes UTF-8 line boundaries; safe for newline-delimited UTF-8 in current usage but should be tested with multibyte content and partial final lines. | add tests for Unicode and malformed/truncated journal lines; confirm task/event fields are never parsed as trusted commands. | 88 lines inspected; static review only; journal recovery/encoding tests not run here. | in progress |
| `app/src/main/java/com/codingagent/agent/AgentTaskBuilders.kt` | application source | 1989 | 59 | Builds terminal/near-terminal AgentTask outcomes. | AutonomousAgent and ToolCallOutcomeHandler task completion/failure paths; AgentJournal and ExperienceRecorder persist verification.passed. | Full-file review plus call-site trace and regression test. | F-2.3-18: failed task construction set `VerificationReport(passed=true, issues=[])`, causing failures to be persisted as successful verification evidence. | Fixed on current main by PR #78: failed outcomes now set passed=false; retain the regression test. | `AgentTaskBuildersTest.failedTaskDoesNotClaimVerificationPassed`; PR #78 CI run 38218160247 succeeded (test compilation, unit tests, acceptance tests, lint, debug/release APK builds). | reviewed |
| `app/src/main/java/com/codingagent/agent/AgentToolDispatch.kt` | application source | 8004 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/AgentTools.kt` | application source | 5021 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/AutonomousAgent.kt` | application source | 41704 | 840 | Orchestrates intake, direct lanes, offline staging, model execution, and durable task recording. | AgentDirectLanes; AgentOfflineStager; ModelGateway; MutationCoordinator; AgentJournal. | Full-file review of needs-input branches and recordTask dataflow; full unit/acceptance/lint/build CI. | F-2.3-25: both clarification-needed and missing-model outcomes persisted verification.passed=true despite no verification run. | Fixed in PR #80: both needs-input outcomes record passed=false; other verified execution paths unchanged. | PR #80 CI run 38221135507 succeeded; ChatWorkspaceTest clarification regression plus full OperationalAgentTest. | reviewed |
| `app/src/main/java/com/codingagent/agent/AutonomousAgentConfig.kt` | application source | 373 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/AutonomousAgentEvent.kt` | application source | 1138 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/ChatApproval.kt` | application source | 2156 | 59 | Routes chat approval phrases to the authenticated review flow without granting authority from chat text. | AutonomousAgent.pendingProposals; proposal verification; ChatWorkspace result rendering. | Full-file review, proposal/task dataflow trace, regression test, and full CI. | F-2.3-22: approval-prompt task always recorded passed verification, including ambiguous approval and cases where proposal verification differed. | Fixed in PR #80: ambiguous task defaults to not verified; waiting-owner-authentication task carries the proposal's actual verification report. | PR #80 CI run 38221135507 succeeded; OperationalAgentTest.chatApprovalPhraseCannotGrantOwnerAuthority asserts verification consistency. | reviewed |
| `app/src/main/java/com/codingagent/agent/ChatWorkspace.kt` | application source | 17880 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/DegenerateOutput.kt` | application source | 4892 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/ExperienceRecorder.kt` | application source | 1187 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/LessonContext.kt` | application source | 679 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/LessonSynthesizer.kt` | application source | 2632 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/LocalFileEvidence.kt` | application source | 3160 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/LogicReasoning.kt` | application source | 5121 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/LoopControl.kt` | application source | 1907 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/ModelCallWithRetry.kt` | application source | 2637 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/ModelFailure.kt` | application source | 3551 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/PendingWorkResume.kt` | application source | 6297 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/PlanningLoop.kt` | application source | 8437 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/RepairCycle.kt` | application source | 4997 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/ResearchGate.kt` | application source | 1311 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/ResponseQualityRules.kt` | application source | 1706 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/SelfEvolution.kt` | application source | 9425 | 210 | Stages and promotes versioned source snapshots, and prepares integrity-checked restore candidates. | MutationCoordinator records promoted source; AgentConstitution checks action authorization; restore candidates are routed through MutationCoordinator proposals. | Full-file review, call-site trace, SelfEvolutionTest, and full CI. | F-2.3-19: source promotion trusted caller-supplied action category; READ_ONLY bypasses AgentConstitution approval checks. | Fixed in PR #79: require CODE_CHANGE for source promotion. Existing snapshot checksum/path checks retained. | PR #79 CI run 38219905908 succeeded; SelfEvolutionTest.rejectsReadOnlyActionEvenWhenEvaluationPasses plus existing snapshot/restore tests. | reviewed |
| `app/src/main/java/com/codingagent/agent/SelfRepair.kt` | application source | 4810 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/StructureScan.kt` | application source | 1520 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/ToolCallOutcomeHandler.kt` | application source | 19186 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/ToolPurpose.kt` | application source | 1765 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/ToolSelection.kt` | application source | 9239 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/agent/UserMemory.kt` | application source | 8295 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/core/BuiltInModules.kt` | application source | 985 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/core/DeviceUserMemoryStore.kt` | application source | 3536 | 103 | Persists encrypted user memories; JSON decoding rejects unreadable values rather than substituting an empty list. | UserMemoryStore methods; KeystoreSecretStore.USER_MEMORY; UserMemoryJsonCodec; UserMemoryIndex. | Full-file review; traced remember/relevant/all/forget through load/save; malformed-data regression coverage. | AUD-008/F2-021: the previous parser returned an empty list on parse failure, allowing a later write to overwrite unreadable memories. | Fixed in PR #91: fail closed with IllegalStateException on malformed or partially invalid JSON; a failed load prevents writes. | PR-head CI run [#1477](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38641294653) and post-merge main CI run [#1478](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38641896973) passed. | reviewed |
| `app/src/main/java/com/codingagent/core/KeystoreSecretStore.kt` | application source | 9067 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/core/LiveModuleRuntime.kt` | application source | 4713 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/core/LiveModuleStore.kt` | application source | 6619 | 135 | Installs, versions, loads, and rolls back live-module source. | BuiltInModules installs trusted default; LiveModuleRuntime loads active module and stages patches through store. | Full-file review, active/history integrity trace, LiveUpdateTest, and full CI. | F-2.3-20: install trusted a READ_ONLY action category; active() did not compare source bytes with the recorded checksum, allowing tampered module source to load. | Fixed in PR #79: require CODE_CHANGE and verify active source checksum plus recorded kind/version. | PR #79 CI run 38219905908 succeeded; LiveUpdateTest.readOnlyActionCannotInstallModuleOrModel and activeModuleRejectsSourceChangedAfterInstall. | reviewed |
| `app/src/main/java/com/codingagent/core/LocalStore.kt` | application source | 5790 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/intake/CodeSynthesis.kt` | application source | 5683 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/intake/GoalInterpreter.kt` | application source | 8519 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/intake/TaskIntake.kt` | application source | 4174 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/knowledge/DocumentIngester.kt` | knowledge/asset | 3476 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/knowledge/KnowledgeBase.kt` | knowledge/asset | 2115 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/knowledge/KnowledgeIndex.kt` | knowledge/asset | 6721 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/knowledge/KnowledgeProvider.kt` | knowledge/asset | 242 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/model/AgentModelProtocol.kt` | application source | 7200 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/model/JsonModelResponseParser.kt` | application source | 2739 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/model/LiveModelRouter.kt` | application source | 792 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/model/LiveModelStore.kt` | application source | 7938 | 202 | Validates and stores optional local model files/packages and tracks the active model. | LiveModelRouter reads active model metadata and bytes; tests exercise single-file and package installation. | Full-file review, install/active checksum trace, LiveUpdateTest, and full CI. | F-2.3-21: install and installPackage trusted READ_ONLY action category; active() did not validate stored model bytes against recorded checksum. | Fixed in PR #79: require MODEL_CHANGE and validate checksum for both single-file models and manifest packages before activation. | PR #79 CI run 38219905908 succeeded; LiveUpdateTest.readOnlyActionCannotInstallModuleOrModel and activeModelRejectsPayloadChangedAfterInstall. | reviewed |
| `app/src/main/java/com/codingagent/model/ModelDownloadProgress.kt` | application source | 223 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/model/ModelGateway.kt` | application source | 115 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/model/ModelSettings.kt` | application source | 9550 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/model/ModelTypes.kt` | application source | 1291 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/model/RemoteHttpGateway.kt` | application source | 17320 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/model/RotatingModelGateway.kt` | application source | 4786 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/model/UnconfiguredModelGateway.kt` | application source | 421 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/research/ArticleExtractor.kt` | application source | 5611 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/research/DeepResearch.kt` | application source | 13934 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/research/PersonalResearchProvider.kt` | application source | 7775 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/research/QueryLanes.kt` | application source | 3983 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/research/ResearchBrief.kt` | application source | 2894 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/research/SourceQuality.kt` | application source | 9690 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/research/WebResearch.kt` | application source | 20145 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/ui/MainActivity.kt` | application source | 44763 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/ui/MainUiScreens.kt` | application source | 30119 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/ui/ReviewBinder.kt` | application source | 1187 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/ui/UiTheme.kt` | application source | 1738 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/AgentPlan.kt` | application source | 191 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/AgentStep.kt` | application source | 191 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/AtomicFileWriter.kt` | application source | 3820 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/ChangeDiff.kt` | application source | 8172 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/CommandRunner.kt` | application source | 5212 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/FileIntegrity.kt` | application source | 6017 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/MutationCoordinator.kt` | application source | 36292 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/OpenJobStore.kt` | application source | 8299 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/OwnerApprovalToken.kt` | application source | 1477 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/PendingProposalStore.kt` | application source | 6535 | 155 | Persists pending proposals in an integrity-authenticated envelope and reconstructs proposal/change/verification/approval state. | MutationCoordinator startup, owner review, approval and recovery; ProposalIntegrity and AtomicFileWriter. | Full-file review, malformed/missing verification regression tests, coordinator approval-path test, and exact-head CI. | AUD-005: missing `verificationPassed` previously defaulted to true; fixed to default false. | Fail closed on absent or malformed verification; reject approval when evaluation is false. | CI run #38533405506 passed unit tests, explicit acceptance tests, security baseline audit, Android lint, debug APK, and release APK on code commit `2461d6abd81b50d3357511969166c5ebaa7d7500`. | verified |
| `app/src/main/java/com/codingagent/workspace/ProjectFileService.kt` | application source | 2240 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/ProjectIndexer.kt` | application source | 5486 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/ProjectModels.kt` | application source | 3613 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/ProjectPaths.kt` | application source | 1216 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/ProjectWorkspace.kt` | application source | 16975 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/ProposalIntegrity.kt` | application source | 2383 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/StorageGuard.kt` | application source | 2557 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/java/com/codingagent/workspace/TerminalSession.kt` | application source | 3058 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/res/drawable/ic_launcher.xml` | configuration/build | 361 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/res/values/styles.xml` | configuration/build | 120 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/main/res/xml/data_extraction_rules.xml` | configuration/build | 585 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/agent/AgentCommandPolicyTest.kt` | test | 8288 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/agent/ToolCallOutcomeHandlerTest.kt` | test | 4059 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/AcceptancePathTest.kt` | test | 42098 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/AgentContextContinuityTest.kt` | test | 1972 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/AgentModelProtocolTest.kt` | test | 557 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/AgentPromptTest.kt` | test | 1438 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/AtomicFileWriterTest.kt` | test | 2901 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/AutonomousLoopTest.kt` | test | 11531 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ChangeDiffTest.kt` | test | 3224 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ChatWorkspaceTest.kt` | test | 14269 | 317 | Tests chat turn orchestration, clarification, recovery boundaries, and result rendering. | ChatWorkspace; AutonomousAgent; OpenJobStore; AgentRuntimeResult. | Full test inspection and full Android unit-test/acceptance/lint/build CI. | Missing assertion allowed clarification tasks to claim verification passed despite no check being run. | Added assertion that a needs-input clarification task has verification.passed=false in PR #80. | PR #80 CI run 38221135507 succeeded; ChatWorkspaceTest.clarificationRemainsNeedsInputInsteadOfCompleted. | reviewed |
| `app/src/test/java/com/codingagent/core/CodeSynthesisTest.kt` | test | 2161 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/DeepResearchTest.kt` | test | 7547 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/DocumentIngesterTest.kt` | test | 2841 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ExperienceRecorderTest.kt` | test | 890 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/FileIntegrityTest.kt` | test | 4919 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/GoalInterpreterTest.kt` | test | 6041 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/LessonSynthesizerTest.kt` | test | 1632 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/LiveUpdateTest.kt` | test | 7474 | 140 | Covers live-module and live-model install, reload, rollback, and authorization/integrity behavior. | LiveModuleStore, LiveModuleRuntime, LiveModelStore, LiveModelRouter. | Full test inspection and full Android unit-test/acceptance/lint/build CI. | Existing tests used CODE_CHANGE for model installation; regression gaps allowed READ_ONLY installs and did not test post-install content tampering. | Updated in PR #79: model tests use MODEL_CHANGE; added READ_ONLY rejection and module/model checksum-tamper regression tests. | PR #79 CI run 38219905908 succeeded: 263 unit tests, acceptance tests, lint, debug/release APK builds. | reviewed |
| `app/src/test/java/com/codingagent/core/LogicReasoningTest.kt` | test | 2415 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ModelFailureTest.kt` | test | 1683 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ModelGatewayTest.kt` | test | 6613 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ModelSettingsTest.kt` | test | 4217 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/OpenJobStoreTest.kt` | test | 6001 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/OperationalAgentTest.kt` | test | 11106 | 240 | Exercises AutonomousAgent execution, offline staging, approval boundaries, and direct lanes. | AutonomousAgent; ChatApproval; MutationCoordinator; ProjectWorkspace; ModelGateway. | Full test inspection and full Android unit-test/acceptance/lint/build CI. | Missing coverage for unperformed verification in direct/meta/needs-input tasks and for preservation of proposal verification in approval prompts. | Added direct-read/meta and offline needs-input regressions; asserted approval prompt uses proposal.verification in PR #80. | PR #80 CI run 38221135507 succeeded; full unit and acceptance tests passed. | reviewed |
| `app/src/test/java/com/codingagent/core/OwnerApprovalTokenTest.kt` | test | 1137 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/PlanningLoopTest.kt` | test | 2916 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ProjectFileServiceBoundaryTest.kt` | test | 3423 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ProjectImportPolicyTest.kt` | test | 1475 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ProjectWorkspaceTest.kt` | test | 6851 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/RepairCycleTest.kt` | test | 721 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ResearchModeTest.kt` | test | 8795 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ReviewBinderTest.kt` | test | 2302 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/SelfRepairTest.kt` | test | 3856 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/SelfEvolutionTest.kt` | test | 6085 | 144 | Tests source promotion, snapshot integrity, safe restore candidates, and restore proposals through dual approval. | SelfEvolution; MutationCoordinator; ProjectWorkspace; VerificationReport; JUnit temporary directories. | Full test inspection and full Android unit-test/acceptance/lint/build CI. | Missing regression for a passing evaluation paired with a READ_ONLY action; the authorization bypass was not covered. | Added rejectsReadOnlyActionEvenWhenEvaluationPasses in PR #79; existing failed-evaluation, managed-path, checksum, and approval-flow tests retained. | PR #79 CI run 38219905908 succeeded; full unit tests and acceptance tests passed. | reviewed |
| `app/src/test/java/com/codingagent/core/StorageGuardTest.kt` | test | 1553 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/TaskIntakeTest.kt` | test | 2263 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/TerminalCancelTest.kt` | test | 7825 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ToolPurposeTest.kt` | test | 1380 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/ToolSelectionTest.kt` | test | 4798 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `app/src/test/java/com/codingagent/core/UserMemoryTest.kt` | test | 5249 | 128 | Tests user-memory extraction, indexing, and strict JSON decoding. | UserMemoryExtractor; UserMemoryIndex; UserMemoryJsonCodec. | Reviewed all tests; added corruption and valid-data regression coverage. | AUD-008/F2-021: tests ensure malformed JSON and a partially corrupt list fail closed rather than returning empty/partial memories. | Fixed in PR #91 with tests for malformed JSON, a partially corrupt list, and valid saved data. | PR-head CI run [#1477](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38641294653) and post-merge main CI run [#1478](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38641896973) passed. | reviewed |
| `build.gradle.kts` | configuration/build | 215 | 5 | Pins Android Gradle Plugin 8.7.0 and Kotlin Android/Compose plugins 2.1.0 without applying them at root. | Gradle plugin resolution; app/build.gradle.kts. | Full-file line-by-line build configuration inspection. | none found in static inspection. | retain. | Versions and declarations inspected; plugin resolution/build not run here. | reviewed |
| `docs/ANTI_YES_MAN.md` | documentation | 2034 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `docs/OWNER_SYSTEM_PROMPT.md` | documentation | 614 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `docs/TOOL_QUEUE.md` | documentation | 1013 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `docs/audit/FILE_BY_FILE_AUDIT_LEDGER.md` | documentation/audit | 40619 | 169 | Tracks baseline inventory, per-file inspection, findings, actions, and verification evidence. | Phase 1 evidence document; recursive Git tree; review/verification workflow. | Self-review of ledger structure and comparison of baseline metadata against the recorded commit/tree. | F-2.3-08: ledger previously named commit 5910ac4/tree 81cf305 while Phase 1 baseline is commit 8300208/tree c108371; 2 tracked files were omitted from the ledger inventory. | align baseline metadata and include all 155 tracked files; leave uninspected rows explicitly in progress. | Commit/tree/count reconciled against the Phase 1 baseline evidence. | reviewed |
| `docs/plans/2026-07-28-deep-research-design.md` | documentation | 1955 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `gradle.properties` | configuration/build | 109 | 3 | Enables AndroidX and non-transitive R classes; sets Gradle JVM heap and file encoding. | Gradle and Android Gradle Plugin. | Full-file line-by-line settings inspection. | none found in static inspection. | retain. | Contents inspected; effective Gradle properties/build not executed in this review. | reviewed |
| `gradle/wrapper/gradle-wrapper.jar` | dependency/lock | 43453 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `gradle/wrapper/gradle-wrapper.properties` | configuration/build | 250 | 7 | Pins Gradle wrapper distribution URL to Gradle 8.9 binary distribution and configures cache locations, timeout, and URL validation. | gradlew; services.gradle.org distribution endpoint; Gradle wrapper. | Full-file line-by-line wrapper configuration inspection. | F-2.3-05: distribution URL has no distributionSha256Sum, so downloaded Gradle distribution integrity is not pinned by checksum in this file. | investigate and add a verified official distribution checksum if project policy requires integrity pinning. | Properties inspected; wrapper download and checksum verification not executed. | reviewed |
| `gradlew` | automation script | 8714 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `proguard-rules.pro` | configuration/build | 973 | 18 | Suppresses selected R8 warnings and keeps coroutine state fields, Compose runtime classes, and Tink classes for reflection/runtime behavior. | R8 release minification; Compose runtime; Kotlin coroutines; Tink. | Full-file line-by-line rule inspection. | F-2.3-04: broad keep rules for all Compose runtime and Tink classes may reduce shrinking; correctness and minimum required scope are unverified. | investigate against release build/R8 output; retain until rule necessity is validated. | Rules inspected; release minification and runtime smoke test not run here. | reviewed |
| `scripts/package-source.sh` | automation script | 2457 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `scripts/sync_to_github.sh` | automation script | 5061 | pending | pending content inspection | pending dependency mapping | pending | pending; no conclusion yet | investigate | pending | in progress |
| `settings.gradle.kts` | configuration/build | 331 | 18 | Configures plugin and dependency repositories, restricts project repositories, names root project, and includes app module. | Gradle settings lifecycle; Google, Maven Central, Gradle Plugin Portal; :app module. | Full-file line-by-line build configuration inspection. | none found in static inspection. | retain. | Settings read; repository resolution/build not run as part of this inspection. | reviewed |

## Completion rule

Do not change a row to `reviewed` until the file's contents have been inspected and the purpose, dependencies, findings, and action have been documented. Do not change it to `verified` until the verification evidence is recorded. A blank or pending field is an outstanding audit task, not a clean result.

## Phase 2 finding reconciliation against current main (2026-10-14)

- Current source commit: 7f425a89eca1a4b870c6b0188146b1867c34b23d; recursive tree: 32ff9b1fe718610cb2ea583e82ee8c53cdfeef1e; 163 tracked blobs and 34 directories; response not truncated.
- The original Phase 2 ledger used baseline 5910ac4841c893de5a5dca25d95b5f9f3591bbce and recorded 153 tracked files; its branch head was 372c46f. Current main is 113 commits beyond that baseline, so its findings cannot be treated as current without revalidation.
- Current main CI: [Android build run 38495535414](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38495535414) succeeded for the exact source commit above. This is GitHub Actions evidence, not a local build.
- This section reconciles the Phase 2 finding register; it does not claim the repository-wide file-by-file audit is complete. Unreviewed rows remain outstanding.

| Finding | Current-main disposition and evidence |
|---|---|
| F2-001 — multiple tool calls | **Resolved on current main.** PR #89 rejects streamed responses containing additional tool-call indexes rather than silently dropping them and adds regression coverage. Exact PR-head CI run [#1470](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38628279216) passed; merged commit `33dd91acecbc202b61fdba02c73e935ec7abf380` passed post-merge main CI run [#1471](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38629133182). |
| F2-002 / F2-019 — failed task verification | **Resolved.** AgentTaskBuilders.failed creates VerificationReport(false, ...); AgentTaskBuildersVerificationTest and AgentTaskBuildersTest cover this. Do not list as a current defect. |
| F2-003 — missing verificationPassed defaults true | **Resolved.** `PendingProposalStore` now uses `optBoolean("verificationPassed", false)`. Regression tests cover missing and malformed values and prove approval is rejected without changing the file; exact-head CI run [#38533405506](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38533405506) passed. |
| F2-004 / F2-023 — model package validation | **Partially resolved.** LiveModelStore.active checks installed content against recorded checksums. installPackage checks manifest presence and non-empty payload files but does not validate manifest semantics against payload contents. |
| F2-005 — public workspace mutation APIs | **Defense-in-depth concern, not a demonstrated exploit.** Public write APIs exist, but the inspected autonomous tool path stages changes through MutationCoordinator and owner approval. Complete caller tracing before describing an approval bypass. |
| F2-006 — package-source forbidden list | **Not confirmed.** scripts/package-source.sh initializes an empty forbidden list and skips configured local/generated paths. Intent is ambiguous; the unreachable error path is misleading, but no security bypass is established without defining expected exclusion semantics. |
| F2-007 — repair rollback | **Resolved in current main.** RepairCycle accumulates existing change sets and each applied repair change set before rollback; RepairLifecycleAcceptanceTest and RepairCycleTest exist. |
| F2-008 — terminal timeout ignored | **Resolved.** AgentTools.terminal passes timeoutSeconds to TerminalSession.executeArgs. |
| F2-009 — research DNS rebinding | **Resolved on current main.** OkHttp performs destination validation in its connection-time DNS resolver and uses only the returned checked addresses; private/reserved answers are rejected, redirects/retries/system proxies are disabled, and regression tests cover private and mixed DNS answers. Full CI passed on code commit `4a5efd70c3eddfd1db36971451e3c24107c42f80` (run #38536858203). |
| F2-010 — owner review truncation | **Resolved on current main.** PR #87 removes silent character caps from the owner review, includes the complete request/reason and before/after file contents with checksums and an end marker, and makes the review scrollable. Regression tests cover long requests and large replacements. Merged as `491b44ce759ba5a9226bb9badfbcc9b8ad85c2d9`; post-merge main CI run #1465 passed: https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38597801445. |
| F2-011 — reserved metadata case sensitivity | **Original claim not confirmed.** ProjectIndexer has a case-sensitive ignored-name set, but ProjectPaths.isSensitivePath checks .coding-agent and .git case-insensitively. |
| F2-012 — persisted research word count | **Open consistency issue.** DeepResearch and PersonalResearchProvider persist content capped at 12,000 characters while retaining the original content's word count. |
| F2-013 — model probe accepts any text | **Resolved in PR #92.** ModelConnectionProbe rejects empty and whitespace-only text responses; regression tests cover blank, whitespace-only, and valid text. PR-head CI #1481 and post-merge main CI #1482 passed. |
| F2-014 — knowledge index replacement | **Resolved on current main.** PR #90 journals the prior chunks and metadata, restores both on startup when a transaction is interrupted, and uses the same recoverable transaction for document removal. Regression tests cover replacement, removal, interrupted replacement, and interrupted first-time indexing. Exact PR-head CI run [#1474](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38637630350) passed; merged commit `cf4cc0e4249d256a90abd7bc721b046fa63c03d2` passed post-merge main CI run [#1475](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38638619886). |
| F2-015 — project-controlled verification commands | **Partially resolved / caller trace required.** MutationCoordinator's post-approval path avoids automatic build/test commands, but RepairCycle.execute can call workspace.runChecks(plan.checks, ...). Trace all entry points and plan provenance before closing the broader finding. |
| F2-016 — research cancellation | **Cancellation chain exists.** AutonomousAgent.cancel calls research.cancel; DeepResearch.cancel calls personalProvider.cancel. Verify UI call sites and active-request tests before claiming every manual cancellation path is covered. |
| F2-017 — editable system prompt unused | **Resolved.** MainActivity sets the agent prompt using ModelSettings.effectiveSystemPrompt(). |
| F2-018 — wrapper checksum / mutable action tags | **Open supply-chain hardening.** gradle-wrapper.properties has no distributionSha256Sum; CI uses major-version action tags rather than immutable commit SHAs. Do not guess checksum values. |
| F2-020 — Git pathspec exposure | **Resolved for the inspected autonomous path.** AgentCommandPolicy rejects Git commands; reopen only if another reachable path invokes Git on model-controlled input. |
| F2-021 — corrupt user-memory JSON | **Resolved in PR #91.** Corrupt or partially invalid saved memory JSON now raises an explicit error; load does not return an empty list on parse failure, so remember/forget cannot overwrite unreadable data. PR-head CI #1477 and post-merge main CI #1478 passed. |
| F2-022 — import permission preservation | **Unverified.** This pass did not establish whether import/export preserves executable bits on target Android storage providers. |
| F2-024 — ambiguous local file resolution | **Original claim not reproduced.** AgentTools resolves explicit project-relative paths and performs case-insensitive segment matching, not recursive arbitrary-basename selection. Reopen only with a concrete reproducer. |

### Reconciliation exit state

The register distinguishes open findings, partial fixes, resolved historical findings, and unconfirmed claims. It does not complete the whole repository audit: the existing ledger still has unreviewed rows, and the current-main inventory is 163 tracked files. No application behavior was changed by this documentation reconciliation.


---

# Current-main project-wide audit — 2026-10-14

## Audit snapshot and completion rule

- **Audited source snapshot:** `2f9ad127f755f5e54cbc3b0097e33ab67df86c59`
- **Git tree:** `28bd43129c23006f090895c7dd6d9ce2c0c36b07`
- **Recursive tree:** 197 entries, 163 tracked blobs, 34 directory objects; GitHub returned `truncated=false`.
- **Coverage:** every tracked blob was inventoried. All 162 text files received a content-based static pass; security-sensitive execution, approval, persistence, network, import, packaging, and workflow paths received deeper review. The wrapper JAR was hashed and compared with Gradle's published checksum list. This is a static/source audit; it does not replace device-level testing or prove runtime behavior on every device.
- **Scope discipline:** no application/security behavior was changed during this audit. Two demonstrably stale documentation claims were corrected in separate documentation-only commits: multi-tool queue behavior and the status of web research. Security remediation remains tracked below and is not silently bundled into the audit.
- **CI at audit snapshot:** the workflow for `2f9ad127f755f5e54cbc3b0097e33ab67df86c59` was still running when this record was prepared. Do not treat it as green until a terminal result is checked. The preceding main commit `119ac501fc1c71616076e3f8408d470274ccbb91` passed workflow run [#38497475234](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38497475234).

## Findings register

| ID | Severity | Finding and evidence | Status |
|---|---|---|---|
| AUD-001 | Critical | The former approval path accepted two generic device-authentication callbacks and a software-minted internal token. The current production UI path requires owner-created typed and spoken passphrases, a fresh four-word spoken challenge, and an auth-per-use Android Keystore signature bound to the exact proposal and approval ordinal. `MutationCoordinator.approve()` validates the signed capability but does not independently attest that the phrase checks ran; that defense-in-depth boundary is tracked under AUD-020. | **Implemented on the reachable production UI path at `90d6943`; current-main CI run #38516802952 passed; device-level testing still required** |
| AUD-002 | High | Historical snapshot contained `app/src/main/assets/knowledge/coding-for-dummies.txt`, a commercial book with restrictive reproduction language and no recorded license. That path is absent from the current `main` tree, so the current tracked-source package no longer includes the asset. | **Closed for current main: asset is not tracked at `f9f63d9`; retain this as a historical finding** |
| AUD-003 | High | Research URL validation alone previously had a DNS time-of-check/time-of-use gap. The current implementation applies a connection-time OkHttp DNS policy, rejects any private/reserved A/AAAA answer, and passes only those validated addresses to the connection. Redirects, automatic retries, and system proxies are disabled. Regression tests cover connection-time private answers, mixed public/private answers, and reserved IPv4/IPv6 ranges. | **Resolved on current main; full CI run #38536858203 passed** |
| AUD-004 | High | The Gradle 8.9 distribution is now pinned by its official SHA-256; the Gradle 8.7 wrapper JAR is checked against Gradle's published SHA-256 in CI; Android build workflow actions are pinned to full commit SHAs. | **Resolved — PR #88 merged as `09059fc4bdb705dfc146ea242ea15964ec058e1f`; PR CI run [#1467](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38621327186) and post-merge `main` CI run [#1468](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38623506348) succeeded on the respective exact commits.** |
| AUD-005 | Medium | Pending proposal deserialization previously defaulted a missing verification flag to true. The parser now defaults missing or malformed `verificationPassed` to false; regression coverage confirms an unverified persisted proposal is rejected before file mutation. This was defensive-deserialization hardening, not a demonstrated unauthenticated bypass because the proposal MAC limits tampering. | **Resolved in code; CI verified** — commit `2461d6abd81b50d3357511969166c5ebaa7d7500`, run [#38533405506](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38533405506). |
| AUD-006 | Medium | The response model represents one tool call. Non-streaming parsing rejects multiple calls, while streaming parsing previously retained only the first indexed call. | **Resolved** — PR #89 rejects extra streamed tool-call indexes; exact PR-head CI run [#1470](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38628279216) and post-merge main CI run [#1471](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38629133182) passed. Merge commit `33dd91acecbc202b61fdba02c73e935ec7abf380`. |
| AUD-007 | Medium | Knowledge replacement previously updated chunks and metadata in separate writes, risking partial state after interruption. | **Resolved** — PR #90 journals and restores both files, and applies the same recoverable transaction to removals. Four regression tests passed with the full workflow on exact PR head run [#1474](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38637630350); post-merge main run [#1475](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38638619886) passed on merge commit `cf4cc0e4249d256a90abd7bc721b046fa63c03d2`. |
| AUD-008 | Medium | Corrupt encrypted user-memory JSON was treated as an empty list, allowing a later write to overwrite unreadable memory data. | **Resolved — PR #91 merged as `955bcd093c1468d580595fcdc8cb34a347148a08`; exact PR-head CI run [#1477](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38641294653) and post-merge main CI run [#1478](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38641896973) passed. Regression tests cover malformed JSON, partial corruption, and valid data.** |
| AUD-009 | Medium | The model connection probe previously reported `ModelResponse.Text` as success even when the body was blank (`empty body`). | **Resolved — PR #92 merged as `8297c13f929921c563b091a4993f4e01ee490f71`; exact PR-head CI run [#1481](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38644050634) and post-merge main CI run [#1482](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38644684180) passed. Empty and whitespace-only responses now fail; regression tests also confirm nonblank text succeeds.** |
| AUD-010 | High | Owner review text is truncated by a hard character cap after generating the diff, without a visible truncation warning or an explicit “review incomplete” state. The owner may not see every proposed change before confirming. | **Open — approval-review integrity** |
| AUD-011 | Medium | `scripts/sync_to_github.sh` defines a remote-verification helper path but does not execute that helper itself; when authenticated, it updates files one at a time, so a multi-file sync can partially apply. | **Open — maintenance-tool reliability; avoid using as an atomic sync** |
| AUD-012 | Low | Deep-research persistence caps stored extracted text while retaining word-count metadata derived from the uncapped source, so displayed counts can disagree with the persisted text. | **Open — metadata consistency** |
| AUD-013 | Medium | CI proves JVM tests, lint, and APK assembly for the checked commit, but there are no Android instrumentation/UI tests in the tracked tree. Biometric prompt interaction, actual import UI, and device lifecycle behavior are not proven by the JVM suite. | **Open — test coverage limitation** |
| AUD-014 | Medium | Chat/task/lesson/document logs are written as JSONL in app-private storage rather than encrypted by `KeystoreSecretStore`. Android backup/transfer exclusions are configured, and app sandboxing reduces exposure; still, logs can contain user code or secrets if the app/device is compromised. | **Risk noted — confirm intended at-rest protection** |
| AUD-015 | Low | Credential redaction in personal logs is pattern-based and tests cover only representative API-key, bearer-token, password, and token formats. It cannot guarantee that every provider-specific secret format is removed. | **Risk noted — do not treat redaction as a security boundary** |
| AUD-016 | Low | `docs/TOOL_QUEUE.md` incorrectly claimed multiple model tool calls are queued. It was corrected in commit `b405b8f7a8bdda0db433e1d6cabdff7ecceef6ad` to document the actual single-call behavior. | **Fixed — documentation only** |
| AUD-017 | Low | `README.md` incorrectly said internet research remained future work despite current web/deep-research providers. It was corrected in commit `2f9ad127f755f5e54cbc3b0097e33ab67df86c59`. | **Fixed — documentation only** |
| AUD-018 | Low | Earlier audit sections use historical tree snapshots and leave many file rows marked in progress. This current-main section is the authoritative coverage register for the snapshot above; historical evidence remains historical. | **Reconciled by this register** |

| AUD-019 | Medium | Live model package validation checks for a manifest and non-empty payload files, then checks installed bytes against a recorded checksum; it does not validate manifest semantics against the payload or model format. | **Open — package validation depth** |
| AUD-020 | Medium | Public `ProjectWorkspace` write APIs (`transaction`, `replace`, `create`, `append`, `remove`, `applyApproved`) can mutate project files without the coordinator. Current code search found no production callsite from the autonomous tool path; that path stages changes through `MutationCoordinator`. In addition, `MutationCoordinator.approve()` accepts a signed token but does not independently prove the typed/spoken phrase checks occurred; the current production UI performs those checks before creating the token. No reachable production bypass was demonstrated, but these are defense-in-depth trust-boundary concerns. | **Open concern — keep direct-write APIs test-only/internal where possible; preserve the current production caller trace** |
| AUD-021 | Low | `scripts/package-source.sh` declares a `forbidden` accumulator but never populates it; configured generated/local paths are skipped instead. The dead error path is misleading, but no package-exclusion bypass was demonstrated. | **Unconfirmed — clarify expected behavior before changing** |
| AUD-022 | Low | `CompilerTestRepairCycle.run` can execute `plan.checks`, but repository code search found no production caller of `.run`; the current production path uses `prepareRepair` and the mutation coordinator's static verifier. | **Resolved for current reachable production path; re-audit if wired into runtime** |
| AUD-023 | Low | Cancellation is wired from the UI through `ChatWorkspace.cancel()` to `AutonomousAgent.cancel()`, which cancels the terminal, research provider, and model gateway. Existing tests cover terminal and research cancellation paths. | **Resolved for inspected cancellation chain** |
| AUD-024 | Medium | SAF import restores executable permission for a root-level `gradlew`, but no end-to-end Android import test verifies mode preservation across document providers. | **Partially addressed — integration verification missing** |
| AUD-025 | Medium | On-device speech recognition checks the recognized transcript, not speaker identity. A fresh four-word challenge reduces straightforward replay, but synthesized or spliced audio could still match it; typed passphrase and a fresh device-authenticated signature remain mandatory. | **Residual limitation — not voice biometrics; device-level testing pending** |

### Official supply-chain references checked

- Gradle security best practices: https://docs.gradle.org/current/userguide/best_practices_security.html
- Gradle Wrapper verification: https://docs.gradle.org/current/userguide/gradle_wrapper.html
- Gradle published wrapper/distribution checksums: https://gradle.org/release-checksums/
- GitHub Actions secure-use guidance: https://docs.github.com/en/actions/reference/security/secure-use

These official documents recommend verifying the Gradle distribution checksum, validating the wrapper JAR, and pinning GitHub Actions to full commit SHAs where immutability is required. The remediation and exact-head CI evidence are recorded below.

## Phase 2 finding disposition crosswalk

This crosswalk prevents historical findings from disappearing when the current-main tracker is updated.

| Historical finding | Current disposition |
|---|---|
| F2-001 — multiple tool calls | Resolved; see AUD-006 and PR #89 / CI runs #1470 and #1471. |
| F2-002 / F2-019 — failed task verification | Resolved; failure/stopped task builders return verification false and tests cover both. |
| F2-003 — missing `verificationPassed` default | Resolved in code and verified by unit/acceptance tests and full CI; see AUD-005 and run [#38533405506](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38533405506). |
| F2-004 / F2-023 — model package validation | Partially resolved; installed checksum verification exists, manifest semantics remain open; see AUD-019. |
| F2-005 — public workspace mutation APIs | Defense-in-depth concern; no bypass demonstrated; see AUD-020. |
| F2-006 — package-source forbidden list | Not confirmed as a security defect; dead error path remains; see AUD-021. |
| F2-007 — repair rollback | Resolved; repair lifecycle tests cover rollback and recovery. |
| F2-008 — terminal timeout | Resolved; timeout is passed to the terminal runner and tested. |
| F2-009 — research DNS rebinding | Resolved; see AUD-003 and verification note below. |
| F2-010 — owner review truncation | Open; see AUD-010. |
| F2-011 — reserved metadata case sensitivity | Original claim not reproduced; case-insensitive checks exist in `ProjectPaths.isSensitivePath`. |
| F2-012 — persisted research word count | Open consistency issue; see AUD-012. |
| F2-013 — model probe accepts any text | Resolved; see AUD-009 and PR #92 / CI runs #1481 and #1482. |
| F2-014 — knowledge index replacement | Resolved; see AUD-007 and PR #90 / CI runs #1474 and #1475. |
| F2-015 — project-controlled verification commands | Resolved for the current reachable production path: `CompilerTestRepairCycle.run` has no production caller; the production mutation path uses static verification. Reopen if `.run` becomes reachable; see AUD-022. |
| F2-016 — research cancellation | Resolved for the inspected UI-to-agent cancellation chain; see AUD-023. |
| F2-017 — editable system prompt unused | Resolved; `MainActivity` uses `ModelSettings.effectiveSystemPrompt()`. |
| F2-018 — wrapper checksum / mutable action tags | Resolved; see AUD-004 remediation verification below. |
| F2-020 — Git pathspec exposure | Resolved for the autonomous command path; `AgentCommandPolicy` rejects Git commands. |
| F2-021 — corrupt user-memory JSON | Resolved; see AUD-008 and PR #91 / CI runs #1477 and #1478. |
| F2-022 — import permission preservation | Partially addressed by setting executable permission on root `gradlew`; end-to-end SAF verification is missing; see AUD-024. |
| F2-024 — ambiguous local file resolution | Original claim not reproduced; explicit project-relative paths and case-insensitive segment matching are used. |

## Per-file audit tracker

Status means the file was included in the current snapshot's static review; “Deep review” identifies files whose security-sensitive logic was additionally examined in detail. Finding IDs link the file to the register above. A dash means no finding was assigned to that file during this pass; it is not a guarantee that the file can never contain a defect.

| Tracked file | Audit status | Finding |
|---|---|---|
| `.gitattributes` | Static scan complete | — |
| `.github/workflows/android-build.yml` | Deep review + static scan complete | AUD-004,AUD-013 |
| `.gitignore` | Static scan complete | — |
| `README.md` | Static scan complete | AUD-017 |
| `app/build.gradle.kts` | Deep review + static scan complete | — |
| `app/src/main/AndroidManifest.xml` | Deep review + static scan complete | AUD-001 |
| `app/src/main/java/com/codingagent/agent/AgentCommandPolicy.kt` | Deep review + static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AgentConstitution.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AgentDirectLanes.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AgentOfflineMutation.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AgentPrompt.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AgentRequestFocus.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AgentRequestKind.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AgentRuntime.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AgentSupport.kt` | Static scan complete | AUD-014 |
| `app/src/main/java/com/codingagent/agent/AgentTaskBuilders.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AgentToolDispatch.kt` | Deep review + static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AgentTools.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AutonomousAgent.kt` | Deep review + static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AutonomousAgentConfig.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/AutonomousAgentEvent.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/ChatApproval.kt` | Deep review + static scan complete | — |
| `app/src/main/java/com/codingagent/agent/ChatWorkspace.kt` | Deep review + static scan complete | AUD-023 |
| `app/src/main/java/com/codingagent/agent/DegenerateOutput.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/ExperienceRecorder.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/LessonContext.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/LessonSynthesizer.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/LocalFileEvidence.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/LogicReasoning.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/LoopControl.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/ModelCallWithRetry.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/ModelFailure.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/PendingWorkResume.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/PlanningLoop.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/RepairCycle.kt` | Deep review + static scan complete | AUD-022 |
| `app/src/main/java/com/codingagent/agent/ResearchGate.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/ResponseQualityRules.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/SelfEvolution.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/SelfRepair.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/StructureScan.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/ToolCallOutcomeHandler.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/ToolPurpose.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/ToolSelection.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/agent/UserMemory.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/core/BuiltInModules.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/core/DeviceUserMemoryStore.kt` | Deep review + static scan complete | AUD-008 — resolved in PR #91; malformed data fails closed. |
| `app/src/main/java/com/codingagent/core/KeystoreSecretStore.kt` | Deep review + static scan complete | — |
| `app/src/main/java/com/codingagent/core/LiveModuleRuntime.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/core/LiveModuleStore.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/core/LocalStore.kt` | Deep review + static scan complete | AUD-014 |
| `app/src/main/java/com/codingagent/core/OwnerApprovalPhraseStore.kt` | Deep review + static scan complete | AUD-001,AUD-025 |
| `app/src/main/java/com/codingagent/intake/CodeSynthesis.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/intake/GoalInterpreter.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/intake/TaskIntake.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/knowledge/DocumentIngester.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/knowledge/KnowledgeBase.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/knowledge/KnowledgeIndex.kt` | Deep review + static scan complete | AUD-007 |
| `app/src/main/java/com/codingagent/knowledge/KnowledgeProvider.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/model/AgentModelProtocol.kt` | Deep review + static scan complete | — |
| `app/src/main/java/com/codingagent/model/JsonModelResponseParser.kt` | Deep review + static scan complete | AUD-006 |
| `app/src/main/java/com/codingagent/model/LiveModelRouter.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/model/LiveModelStore.kt` | Deep review + static scan complete | AUD-019 |
| `app/src/main/java/com/codingagent/model/ModelDownloadProgress.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/model/ModelGateway.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/model/ModelSettings.kt` | Deep review + static scan complete | AUD-009 |
| `app/src/main/java/com/codingagent/model/ModelTypes.kt` | Deep review + static scan complete | AUD-006 |
| `app/src/main/java/com/codingagent/model/RemoteHttpGateway.kt` | Deep review + static scan complete | AUD-006 |
| `app/src/main/java/com/codingagent/model/RotatingModelGateway.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/model/UnconfiguredModelGateway.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/research/ArticleExtractor.kt` | Deep review + static scan complete | AUD-003 |
| `app/src/main/java/com/codingagent/research/DeepResearch.kt` | Deep review + static scan complete | AUD-003,AUD-012 |
| `app/src/main/java/com/codingagent/research/PersonalResearchProvider.kt` | Deep review + static scan complete | AUD-003,AUD-012 |
| `app/src/main/java/com/codingagent/research/QueryLanes.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/research/ResearchBrief.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/research/SourceQuality.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/research/WebResearch.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/ui/MainActivity.kt` | Deep review + static scan complete | AUD-001 |
| `app/src/main/java/com/codingagent/ui/MainUiScreens.kt` | Deep review + static scan complete | AUD-001,AUD-010,AUD-024 |
| `app/src/main/java/com/codingagent/ui/ReviewBinder.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/ui/UiTheme.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/workspace/AgentPlan.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/workspace/AgentStep.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/workspace/AtomicFileWriter.kt` | Deep review + static scan complete | — |
| `app/src/main/java/com/codingagent/workspace/ChangeDiff.kt` | Deep review + static scan complete | AUD-010 |
| `app/src/main/java/com/codingagent/workspace/CommandRunner.kt` | Deep review + static scan complete | — |
| `app/src/main/java/com/codingagent/workspace/FileIntegrity.kt` | Deep review + static scan complete | — |
| `app/src/main/java/com/codingagent/workspace/MutationCoordinator.kt` | Deep review + static scan complete | AUD-001,AUD-005 |
| `app/src/main/java/com/codingagent/workspace/OpenJobStore.kt` | Deep review + static scan complete | — |
| `app/src/main/java/com/codingagent/workspace/OwnerApprovalKey.kt` | Deep review + static scan complete | AUD-001 |
| `app/src/main/java/com/codingagent/workspace/OwnerApprovalToken.kt` | Deep review + static scan complete | AUD-001 |

| `app/src/main/java/com/codingagent/workspace/PendingProposalStore.kt` | Deep review + static scan complete | AUD-005 |
| `app/src/main/java/com/codingagent/workspace/ProjectFileService.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/workspace/ProjectIndexer.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/workspace/ProjectMetadataBoundary.kt` | Deep review + static scan complete | — |
| `app/src/main/java/com/codingagent/workspace/ProjectModels.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/workspace/ProjectPaths.kt` | Deep review + static scan complete | AUD-020 |
| `app/src/main/java/com/codingagent/workspace/ProjectWorkspace.kt` | Deep review + static scan complete | AUD-020 |
| `app/src/main/java/com/codingagent/workspace/ProposalIntegrity.kt` | Deep review + static scan complete | AUD-005 |
| `app/src/main/java/com/codingagent/workspace/StorageGuard.kt` | Static scan complete | — |
| `app/src/main/java/com/codingagent/workspace/TerminalSession.kt` | Deep review + static scan complete | — |
| `app/src/main/res/drawable/ic_launcher.xml` | Static scan complete | — |
| `app/src/main/res/values/styles.xml` | Static scan complete | — |
| `app/src/main/res/xml/data_extraction_rules.xml` | Static scan complete | — |
| `app/src/test/java/com/codingagent/agent/AgentCommandPolicyTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/agent/AgentTaskBuildersTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/agent/ResearchGateTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/agent/ToolCallOutcomeHandlerTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/AcceptancePathTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/AgentContextContinuityTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/AgentJournalTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/AgentModelProtocolTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/AgentPromptTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/AgentTaskBuildersVerificationTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/AtomicFileWriterTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/AutonomousLoopTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/ChangeDiffTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/ChatWorkspaceTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/CodeSynthesisTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/DeepResearchTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/DocumentIngesterTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/ExperienceRecorderTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/FileIntegrityTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/GoalInterpreterTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/LessonSynthesizerTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/LiveUpdateTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/LogicReasoningTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/ModelFailureTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/ModelGatewayTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/ModelSettingsTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/OpenJobStoreTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/OperationalAgentTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/OwnerApprovalPhraseStoreTest.kt` | Deep review + static scan complete | AUD-001,AUD-025 |
| `app/src/test/java/com/codingagent/core/OwnerApprovalTokenTest.kt` | Deep review + static scan complete | AUD-001 |
| `app/src/test/java/com/codingagent/core/PlanningLoopTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/ProjectFileServiceBoundaryTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/ProjectImportPolicyTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/ProjectWorkspaceTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/RepairCycleTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/RepairLifecycleAcceptanceTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/ResearchModeTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/ReviewBinderTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/SelfEvolutionTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/SelfRepairTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/StorageGuardTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/TaskIntakeTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/TerminalCancelTest.kt` | Deep review + static scan complete | — |
| `app/src/test/java/com/codingagent/core/ToolPurposeTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/ToolSelectionTest.kt` | Static scan complete | — |
| `app/src/test/java/com/codingagent/core/UserMemoryTest.kt` | Deep review + regression tests complete | AUD-008 |
| `build.gradle.kts` | Static scan complete | — |
| `docs/ANTI_YES_MAN.md` | Static scan complete | — |
| `docs/OWNER_SYSTEM_PROMPT.md` | Static scan complete | — |
| `docs/TOOL_QUEUE.md` | Static scan complete | AUD-016 |
| `docs/audit/FILE_BY_FILE_AUDIT_LEDGER.md` | Static scan complete | AUD-018 |
| `docs/audit/PHASE_1_BASELINE_EVIDENCE.md` | Static scan complete | AUD-018 |
| `docs/audit/PHASE_1_RECURSIVE_TREE.txt` | Static scan complete | AUD-018 |
| `docs/plans/2026-07-28-deep-research-design.md` | Static scan complete | — |
| `gradle.properties` | Static scan complete | — |
| `gradle/wrapper/gradle-wrapper.jar` | Hash verified against official Gradle 8.7 wrapper checksum | AUD-004 |
| `gradle/wrapper/gradle-wrapper.properties` | Static scan complete | AUD-004 |
| `gradlew` | Static scan complete | — |
| `proguard-rules.pro` | Static scan complete | — |
| `scripts/package-source.sh` | Deep review + static scan complete | AUD-002,AUD-021 |
| `scripts/sync_to_github.sh` | Static scan complete | AUD-011 |
| `settings.gradle.kts` | Static scan complete | — |

## Audit conclusion

The project-wide static audit was complete for the prior 163-file snapshot. The current-main reconciliation below supersedes that count and accounts for all 165 tracked files at the current commit. The audit is **not a claim that every finding is fixed**: AUD-001 through AUD-015 and AUD-019 through AUD-024 remain open, partially resolved, or noted as specified above. In particular, the spoken-plus-typed owner approval safeguard is not implemented, and the bundled copyrighted reference asset needs rights confirmation before redistribution. Do not mark these as resolved merely because CI passes.


---

## Approval security remediation tracker — 2026-10-14

- **Code snapshot:** `90d6943dac2d3ff7cb37df3bfe503178dddf707c`
- **Exact-head CI:** [run #38501501736 — success](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38501501736). Unit tests, acceptance-path tests, Android lint, debug APK assembly, and release APK assembly all passed.
- **Scope:** owner passphrase enrollment/verification, fresh spoken challenge, auth-per-use Keystore signature, and exact proposal/approval-step binding.
- **Owner-created credentials:** the user supplies both distinct passphrases; no passphrase is generated or stored in plaintext. Verifiers use per-phrase salts and PBKDF2-HMAC-SHA256, persisted through the encrypted local settings store. No arbitrary minimum passphrase length is enforced.
- **Replay control:** each speech attempt requires a fresh four-word challenge; prior full recordings normally fail against the new challenge. The challenge is ephemeral and is not a credential.
- **Token control:** Android production cannot use the synthetic-token factory. The coordinator checks a Keystore signature bound to proposal ID, approval ordinal, issue time, and nonce; each signature requires fresh owner authentication. The key accepts strong biometrics or device credential, matching Android's documented auth-per-use flow.
- **Key material:** Android Keystore creates a non-exportable signing key on first owner approval; it is not a user passphrase and is never exported. The user remains the sole creator of both passphrases.
- **Fail-closed behavior:** no on-device speech recognizer, denied microphone permission, phrase mismatch, malformed signature, expired token, wrong proposal, or wrong approval ordinal means no approval.
- **Residual limitation (AUD-025):** speech-to-text is not speaker identification. A sophisticated attacker could synthesize or splice audio that matches the current challenge; that still does not authorize a mutation without the typed phrase and fresh device-authenticated signature. The workflow does not run device/emulator instrumentation tests, so actual microphone, biometric, and Keystore integration remains unverified.

### Files re-reviewed for this remediation

| File | Review record |
|---|---|
| `app/src/main/java/com/codingagent/ui/MainActivity.kt` | Setup and approval UX, on-device-only speech path, four-word challenge freshness, recognizer lifecycle, biometric CryptoObject flow; no transcript logging |
| `app/src/main/java/com/codingagent/core/LocalStore.kt` | Secure-store integration; no phrase plaintext API |
| `app/src/main/java/com/codingagent/core/OwnerApprovalPhraseStore.kt` | Salted phrase verifiers, typed case sensitivity, transcript/challenge matching, fail-closed storage errors |
| `app/src/main/java/com/codingagent/workspace/OwnerApprovalKey.kt` | Auth-per-use Keystore signing and exact challenge binding |
| `app/src/main/java/com/codingagent/workspace/OwnerApprovalToken.kt` | Signature validation, one-time consumption, expiry, proposal and step binding; synthetic tokens test-only |
| `app/src/main/java/com/codingagent/workspace/MutationCoordinator.kt` | Coordinator checks the expected approval ordinal before accepting the capability |
| `app/src/main/AndroidManifest.xml` | Runtime microphone permission and speech-recognition service visibility query |
| `app/src/test/java/com/codingagent/core/OwnerApprovalPhraseStoreTest.kt` | Salted-storage, wrong phrase, typed case sensitivity, challenge suffix, and distinct phrase tests |
| `app/src/test/java/com/codingagent/core/OwnerApprovalTokenTest.kt` | One-time, expiry, proposal binding, approval-step replay, and malformed-signature tests |

### Official Android references checked

- Speech recognizer availability, on-device factory, and required lifecycle cleanup: https://developer.android.com/reference/android/speech/SpeechRecognizer
- Auth-per-use keys and `BiometricPrompt.CryptoObject`: https://developer.android.com/identity/sign-in/biometric-auth
- Keystore authentication parameters and authentication-type flags: https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder


## F2-009 implementation note — 2026-10-14

- Research fetches now use OkHttp's request API with a custom `Dns` implementation. The custom resolver checks the exact addresses returned for the connection and passes only those same addresses back to OkHttp, so a later unchecked DNS lookup is not used for the socket destination.
- Every answer is checked, including mixed public/private answers; special-use IPv4 ranges, IPv6 ULA/link-local/documentation/translation ranges, and IPv4-mapped IPv6 addresses are rejected. Redirects, automatic connection retries, and system proxies are disabled.
- Regression tests cover a private connection-time answer, mixed public/private answers, and reserved address ranges. Full CI passed on code commit `4a5efd70c3eddfd1db36971451e3c24107c42f80`: [run #38536858203](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38536858203). Security baseline audit, test compilation, unit tests, acceptance-path tests, Android lint, debug APK, and release APK all passed.

## Current-main reconciliation — 2026-10-14

- **Code snapshot audited:** `f9f63d9a9bb90108cf9c37ffa38cdc9b3211a1e8` — [commit](https://github.com/thepotatoninjahost/Coding-Agent/commit/f9f63d9a9bb90108cf9c37ffa38cdc9b3211a1e8). The subsequent change that reconciles this ledger is documentation-only; application code is unchanged.
- **Audited code tree:** `d7cc65b2bb10b5fe3c78eaf62a1f23c3aad349b5`; recursive Git tree API returned 197 entries = 165 tracked files + 32 directories, not truncated.
- **Exact-head CI:** [Android build run #38516802952 — success](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38516802952). Unit tests, explicit acceptance-path tests, security baseline audit, lint, debug APK assembly, and release APK assembly passed.
- **Reconciliation against prior 163-file audit register:** one previously inventoried asset path is no longer tracked (`app/src/main/assets/knowledge/coding-for-dummies.txt)); three approval-security files were missing from the register and are now added (`OwnerApprovalPhraseStore.kt`, `OwnerApprovalKey.kt`, and `OwnerApprovalPhraseStoreTest.kt`). Net current coverage is 165 tracked files. Existing approval-path changes in `MainActivity.kt`, `MutationCoordinator.kt`, and `OwnerApprovalToken.kt` were re-reviewed against current main.
- **Approval path verified from source:** owner creates both passphrases; typed phrase and on-device speech transcript are checked in the UI; speech must include a fresh challenge; Android Keystore signs a token bound to proposal ID, approval ordinal, issue time, nonce, and the exact proposal fingerprint; the coordinator consumes that token once and rejects mismatches. This is not speaker identification. JVM tests use synthetic test-only tokens and do not prove real microphone, biometric, or Keystore behavior on device.
- **Open findings remain open:** AUD-004 through AUD-015 where not already marked resolved, AUD-019 through AUD-021, AUD-024, and AUD-025 remain as described in the register. AUD-002 is closed for the current snapshot because the referenced asset is absent. Do not interpret a green build as closure of those findings.
- **Official source references rechecked for this reconciliation:** Android biometric/auth-per-use key guidance (https://developer.android.com/identity/sign-in/biometric-auth), Android Keystore (https://developer.android.com/privacy-and-security/keystore), AndroidX BiometricPrompt authenticator compatibility (https://developer.android.com/reference/androidx/biometric/BiometricPrompt.PromptInfo.Builder), GitHub Git Trees API (https://docs.github.com/en/rest/git/trees), Gradle wrapper verification (https://docs.gradle.org/current/userguide/gradle_wrapper.html), and GitHub Actions secure-use guidance (https://docs.github.com/en/actions/reference/security/secure-use).


## F2-003 remediation verification — 2026-10-14

- **Code commit:** `2461d6abd81b50d3357511969166c5ebaa7d7500` ([commit](https://github.com/thepotatoninjahost/Coding-Agent/commit/2461d6abd81b50d3357511969166c5ebaa7d7500)).
- **Tree SHA:** `83964f7388e431316a9129c146aa400b00836a9a`; the inventory paths are unchanged.
- **Exact-head CI:** [run #38533405506 — success](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38533405506). Security baseline audit, unit-test compilation, unit tests, explicit acceptance-path tests, Android lint, debug APK assembly, and release APK assembly all passed.
- **Behavioral change:** missing or non-coercible `verificationPassed` values deserialize as false. The acceptance test removes the field from a correctly authenticated test payload, recomputes the test MAC, reloads the proposal, and confirms the coordinator rejects approval without modifying the target file. It also checks a malformed value.
- **Official API reference checked:** Android `JSONObject.optBoolean(name, fallback)` returns the supplied fallback when the value is absent or cannot be coerced to a boolean: https://developer.android.com/reference/org/json/JSONObject.html.


- **Code snapshot audited:** `f9f63d9a9bb90108cf9c37ffa38cdc9b3211a1e8` — [commit](https://github.com/thepotatoninjahost/Coding-Agent/commit/f9f63d9a9bb90108cf9c37ffa38cdc9b3211a1e8). The subsequent change that reconciles this ledger is documentation-only; application code is unchanged.
- **Audited code tree:** `d7cc65b2bb10b5fe3c78eaf62a1f23c3aad349b5`; recursive Git tree API returned 197 entries = 165 tracked files + 32 directories, not truncated.
- **Exact-head CI:** [Android build run #38516802952 — success](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38516802952). Unit tests, explicit acceptance-path tests, security baseline audit, lint, debug APK assembly, and release APK assembly passed.
- **Reconciliation against prior 163-file audit register:** one previously inventoried asset path is no longer tracked (`app/src/main/assets/knowledge/coding-for-dummies.txt)); three approval-security files were missing from the register and are now added (`OwnerApprovalPhraseStore.kt`, `OwnerApprovalKey.kt`, and `OwnerApprovalPhraseStoreTest.kt`). Net current coverage is 165 tracked files. Existing approval-path changes in `MainActivity.kt`, `MutationCoordinator.kt`, and `OwnerApprovalToken.kt` were re-reviewed against current main.
- **Approval path verified from source:** owner creates both passphrases; typed phrase and on-device speech transcript are checked in the UI; speech must include a fresh challenge; Android Keystore signs a token bound to proposal ID, approval ordinal, issue time, nonce, and the exact proposal fingerprint; the coordinator consumes that token once and rejects mismatches. This is not speaker identification. JVM tests use synthetic test-only tokens and do not prove real microphone, biometric, or Keystore behavior on device.
- **Open findings remain open:** AUD-003 through AUD-015 where not already marked resolved, AUD-019 through AUD-021, AUD-024, and AUD-025 remain as described in the register. AUD-002 is closed for the current snapshot because the referenced asset is absent. Do not interpret a green build as closure of those findings.
- **Official source references rechecked for this reconciliation:** Android biometric/auth-per-use key guidance (https://developer.android.com/identity/sign-in/biometric-auth), Android Keystore (https://developer.android.com/privacy-and-security/keystore), AndroidX BiometricPrompt authenticator compatibility (https://developer.android.com/reference/androidx/biometric/BiometricPrompt.PromptInfo.Builder), GitHub Git Trees API (https://docs.github.com/en/rest/git/trees), Gradle wrapper verification (https://docs.gradle.org/current/userguide/gradle_wrapper.html), and GitHub Actions secure-use guidance (https://docs.github.com/en/actions/reference/security/secure-use).


## F2-003 remediation verification — 2026-10-14

- **Code commit:** `2461d6abd81b50d3357511969166c5ebaa7d7500` ([commit](https://github.com/thepotatoninjahost/Coding-Agent/commit/2461d6abd81b50d3357511969166c5ebaa7d7500)).
- **Tree SHA:** `83964f7388e431316a9129c146aa400b00836a9a`; the inventory paths are unchanged.
- **Exact-head CI:** [run #38533405506 — success](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38533405506). Security baseline audit, unit-test compilation, unit tests, explicit acceptance-path tests, Android lint, debug APK assembly, and release APK assembly all passed.
- **Behavioral change:** missing or non-coercible `verificationPassed` values deserialize as false. The acceptance test removes the field from a correctly authenticated test payload, recomputes the test MAC, reloads the proposal, and confirms the coordinator rejects approval without modifying the target file. It also checks a malformed value.
- **Official API reference checked:** Android `JSONObject.optBoolean(name, fallback)` returns the supplied fallback when the value is absent or cannot be coerced to a boolean: https://developer.android.com/reference/org/json/JSONObject.html.


## AUD-004 remediation verification — 2026-10-14

- **PR:** [#88 — Fix AUD-004: harden build supply-chain verification](https://github.com/thepotatoninjahost/Coding-Agent/pull/88), merged.
- **Merged commit:** `09059fc4bdb705dfc146ea242ea15964ec058e1f`.
- **PR exact-head CI:** [run #1467 — success](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38621327186), on head `1fbf537901d2a5c1a15865ab90c1acbac8d0e36e`.
- **Post-merge main CI:** [run #1468 — success](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38623506348), on merged `main` commit `09059fc4bdb705dfc146ea242ea15964ec058e1f`. The workflow completed successfully, including the wrapper JAR checksum step, tests, Android lint, and debug/release APK assembly.
- **Changes verified:** configured the official Gradle 8.9 binary distribution SHA-256; pinned checkout, setup-java, setup-android, and upload-artifact actions to full commit SHAs; added CI verification of the tracked Gradle Wrapper JAR against Gradle's published checksum.
- **Official references:** https://docs.gradle.org/current/userguide/gradle_wrapper.html ; https://gradle.org/release-checksums/ ; https://docs.github.com/en/actions/reference/security/secure-use
- **Disposition:** AUD-004 and crosswalk finding F2-018 resolved for the audited build workflow. This does not imply that other open audit findings are resolved.


## AUD-009 remediation verification — 2026-10-14

- **PR:** [#92 — Fix AUD-009: reject blank model connection probes](https://github.com/thepotatoninjahost/Coding-Agent/pull/92), merged.
- **Merged commit:** `8297c13f929921c563b091a4993f4e01ee490f71`.
- **Exact PR-head CI:** [run #1481 — success](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38644050634), on head `ca923363879bc9a21cc4d9d2d6672413eddcb54a`.
- **Post-merge main CI:** [run #1482 — success](https://github.com/thepotatoninjahost/Coding-Agent/actions/runs/38644684180), on merge commit `8297c13f929921c563b091a4993f4e01ee490f71`.
- **Behavioral change:** empty or whitespace-only `ModelResponse.Text` now returns a failed probe result instead of success. Tests cover empty text, whitespace-only text, and a valid nonblank response.
- **Official reference:** Kotlin `isBlank()` is true for empty or whitespace-only character sequences: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/is-blank.html.
