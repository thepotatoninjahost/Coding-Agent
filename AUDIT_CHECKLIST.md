# File-by-file audit checklist

This checklist is the current audit. `docs/audit/FILE_BY_FILE_AUDIT_LEDGER.md` is an older ledger and was not treated as truth.

Every path below was read through its last line in this audit. `gradle/wrapper/gradle-wrapper.jar` is a binary; it was checked as a zip and by SHA-256, not decompiled. A file is not marked yes from a partial read.

| File | Read in full | Issues found | Issues fixed | Issues left and why |
|---|---|---|---|---|
| `.gitattributes` | yes | Catch-all text=auto could rewrite the wrapper JAR as text. | Marked *.jar, *.png, and *.webp as binary. | None. |
| `.github/workflows/android-build.yml` | yes | CI replaces the wrapper JAR before the build, so a poisoned tracked JAR would not be what CI runs. Docs-only pushes skip CI. | None in this file. The tracked JAR was replaced separately so local and CI hashes match. | Docs-only pushes still skip CI by design. Release assemble is a compile check without a release signing config. Left because signing needs an owner keystore. |
| `.gitignore` | yes | None. | None. | None. |
| `README.md` | yes | Said extra tool calls are queued; they are rejected. Said terminal output is capped at 256 KiB; CommandRunner keeps full output. Said the APK refuses x86_64; Gradle sets no ABI filter. Called the setup screen Model settings. Described Coding For Dummies as a current example asset that is not in the tree. Recommended Groq as the helper. | Corrected the tool-call, terminal-output, ABI, and Setup wording. The default helper is now OpenRouter's free model, and the secret password is typed at runtime. Added the computer script that reads OPENROUTER_API_KEY from the environment. | The Coding For Dummies asset is still absent. The README still describes product behavior that was not proven with a live model call. No OpenRouter key was available in this environment. |
| `app/build.gradle.kts` | yes | androidTest Compose UI test library had no BOM, so the version resolved empty. Biometric dependency comment was stale. | Added the Compose BOM to androidTestImplementation and corrected the biometric comment. core-ktx pinned to 1.13.1. The OpenRouter runner is excluded from the normal unit-test run unless -PrunOpenRouter is set. | No release signing config and no ABI filter. Left: signing needs an owner keystore, and there is no native code that needs an ABI filter. |
| `app/src/androidTest/java/com/codingagent/ui/MainActivitySmokeTest.kt` | yes | Assertions still looked for old setup wording. | Updated to the current plain-English strings, including 'Your new project is ready.' | Not run on an emulator in this environment. The first Cancel on screen is still what the test taps. |
| `app/src/main/AndroidManifest.xml` | yes | allowBackup was not explicitly false. No network security config, so cleartext policy was the platform default. | allowBackup=false and networkSecurityConfig pointing at the new XML. | RECORD_AUDIO remains because spoken approval needs the microphone. Left on purpose. |
| `app/src/main/java/com/codingagent/agent/AgentCommandPolicy.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/AgentConstitution.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/AgentDirectLanes.kt` | yes | Owner-facing failure and status text used internal wording. | Rewrote those replies in plain English and added plainIntent. | None. |
| `app/src/main/java/com/codingagent/agent/AgentOfflineMutation.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/AgentOwnerContext.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/AgentPrompt.kt` | yes | repoMap ignored maxPaths and dumped every path. build() still takes maxEvidenceChars and does not cut the evidence. | repoMap now honors maxPaths and says when the list is cut. | maxEvidenceChars stays unused. AgentPromptTest.promptPreservesEvidenceBeyondFormerCharacterLimits requires the full evidence even when the passed limit is smaller. Cutting it would hide owner and tool evidence from the model. |
| `app/src/main/java/com/codingagent/agent/AgentRequestFocus.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/AgentRequestKind.kt` | yes | A short sentence containing the word help, such as 'help me fix the bug', was classified as a status question and never reached coding. | Status-help now ignores sentences that also ask to fix, write, change, or debug. | None. |
| `app/src/main/java/com/codingagent/agent/AgentRuntime.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/AgentSupport.kt` | yes | The journal tail reader was suspected of splitting UTF-8 characters. | None. | Not a bug. It walks backward to newline bytes, then decodes each whole line. A newline cannot sit inside a UTF-8 character, so a line is not split. |
| `app/src/main/java/com/codingagent/agent/AgentTaskBuilders.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/AgentToolDispatch.kt` | yes | search_knowledge asked for every knowledge hit at once. | The tool now returns at most 30 hits and says when more exist. | None. |
| `app/src/main/java/com/codingagent/agent/AgentTools.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/AutonomousAgent.kt` | yes | A failed personal-log write returned a non-terminal event list, and execute() then threw. A later log failure only set cancel and the loop could keep going. A change request could be marked done after the turn limit with no saved change. Policy notes could attach to the wrong file when only the file name matched. Several phase messages were jargon. The missing-helper message pointed at Groq. | A personal-log failure now records Failed and the loop returns on the next check, including during research. A change, create, refactor, or debug request that never stages a change fails instead of completing when the turn limit runs out. Path matching requires the full relative path. Phase text is plain English. The missing-helper message now tells the owner to paste the OpenRouter key. | Chat and task JSONL under the project stay unencrypted. Left for the owner. Constitution rules TRANSPARENCY_LOG and SAFETY_BOUNDARY are still declared and not emitted. |
| `app/src/main/java/com/codingagent/agent/AutonomousAgentConfig.kt` | yes | commandTimeoutSeconds is not what the terminal runner uses. | None. | Left. The terminal timeout lives on TerminalSession. Changing which timeout wins would change behavior without a failing test. |
| `app/src/main/java/com/codingagent/agent/AutonomousAgentEvent.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/ChatApproval.kt` | yes | Approval summaries used protocol wording. | Rewritten in plain English. | None. |
| `app/src/main/java/com/codingagent/agent/ChatWorkspace.kt` | yes | openJob!! could crash. Status text said Streaming and dumped model tokens, and said a check was 'ok'. Personal-log footer claimed notes were shortened. | Null-safe goal. Status text is plain English and no longer dumps tokens. Footer no longer claims shortening. | If run() ends with a non-terminal event that is not a personal-log failure, send() still calls execute() once more. That path is now only for a genuinely missing terminal event. |
| `app/src/main/java/com/codingagent/agent/DegenerateOutput.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/ExperienceRecorder.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/LessonContext.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/LessonSynthesizer.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/LocalFileEvidence.kt` | yes | A policy note on one file could be attached to another file with the same name. | Match is the exact path or a path that ends with /name. | None. |
| `app/src/main/java/com/codingagent/agent/LogicReasoning.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/LoopControl.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/ModelCallWithRetry.kt` | yes | Phase strings were internal. | Plain English. | None. |
| `app/src/main/java/com/codingagent/agent/ModelFailure.kt` | yes | humanize showed raw provider wording such as worker pool. A rejected key and a 429 were easy to miss in the raw JSON. | Plain English, including 'too busy' and Setup. A rejected secret password names openrouter.ai/keys. A 429 says the free limit may be used up and to wait. | The raw message still contains the HTTP status so retry logic can see 429. The sentence the owner sees does not. |
| `app/src/main/java/com/codingagent/agent/PendingWorkResume.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/PlanningLoop.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/RepairCycle.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/ResearchGate.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/ResponseQualityRules.kt` | yes | Told the model that a passing verify() was the definition of done. | Says verify() is only a text check and is not proof the program runs. | None. |
| `app/src/main/java/com/codingagent/agent/SelfEvolution.kt` | yes | parentFile could be null at three write sites. | Null-safe parent handling. | Self-change still needs the owner's two approvals. The phone cannot push the GitHub repo; sync_to_github.sh now refuses. |
| `app/src/main/java/com/codingagent/agent/SelfRepair.kt` | yes | On a project that is not this app's source, repair proposes a toy src/SelfRepair.kt seed. | None. | Left. Replacing that seed with a real self-fix would invent a repair the owner did not describe. |
| `app/src/main/java/com/codingagent/agent/StructureScan.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/ToolCallOutcomeHandler.kt` | yes | A successful read_file ended the run when the request merely contained the letters read, show, or open, so "already" counted as a read. The early answer included the tool wire format. The repeat hint said files were searched when it was counting reads. Abort messages still name the tool. | Early completion now requires a real read request, using whole words, and skips requests that also ask to explain or change something. The answer shows the file text. The hint counts reads and searches separately. | Abort messages still name the tool. They steer the model, and tests lock the repeat-stop behavior. |
| `app/src/main/java/com/codingagent/agent/ToolPurpose.kt` | yes | Unused local. | Removed the unused reason local. | None. |
| `app/src/main/java/com/codingagent/agent/ToolResultPager.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/ToolSelection.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/agent/UserMemory.kt` | yes | maxEntries is unused. compact() only sorts. sameDirectiveTopic and directiveTopic are unused. | None. | Left. Enforcing the cap would delete the owner's saved memories. That choice is the owner's. |
| `app/src/main/java/com/codingagent/core/BuiltInModules.kt` | yes | installDefault builds a fake dual-approval action. | None. | Left. Nothing in the app calls installDefault, so it does not install itself. Deleting it would remove a test/helper entry point without a product decision. |
| `app/src/main/java/com/codingagent/core/DeviceUserMemoryStore.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/core/KeystoreSecretStore.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/core/LiveModuleRuntime.kt` | yes | replace_exact does not edit; it returns PROPOSAL_REQUIRED. | None. | Left. A live module must not write without the approval path. The UI does not expose this runtime. |
| `app/src/main/java/com/codingagent/core/LiveModuleStore.kt` | yes | The module checksum used the platform character set, and install used plain writes that can leave a half-written file. | Checksums and reads use UTF-8. The module file, history, and active pointer are written with the atomic writer. | The workbench does not install live modules. This path is still unused by the phone screen. |
| `app/src/main/java/com/codingagent/core/LocalStore.kt` | yes | Companion constants KEY_PROJECT_PATH, KEY_LAST_RESEARCH, and KEY_MODEL_SETTINGS look unused. Real keys are on the secret store. | None. | Left. Removing them is cosmetic and risks a caller outside this tree. |
| `app/src/main/java/com/codingagent/core/OwnerApprovalPhraseStore.kt` | yes | Enrollment errors used jargon. | Plain English. | No minimum phrase length beyond non-blank and typed words different from spoken words. Left for the owner. |
| `app/src/main/java/com/codingagent/intake/CodeSynthesis.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/intake/GoalInterpreter.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/intake/TaskIntake.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/knowledge/DocumentIngester.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/knowledge/KnowledgeBase.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/knowledge/KnowledgeIndex.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/knowledge/KnowledgeProvider.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/model/AgentModelProtocol.kt` | yes | System prompt did not require everyday English for the phone owner. | Added that requirement without renaming the tool protocol. | None. |
| `app/src/main/java/com/codingagent/model/JsonModelResponseParser.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/model/LiveModelRouter.kt` | yes | reload() holds the whole model byte array. | None. | Left. The workbench does not call this. Remote HTTP is the path the phone uses. |
| `app/src/main/java/com/codingagent/model/LiveModelStore.kt` | yes | modelBytes concatenates every file with repeated array copies. | None. | Left. Not used by the phone UI. Rewriting the loader without a caller would not change what the owner can do. |
| `app/src/main/java/com/codingagent/model/ModelDownloadProgress.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/model/ModelGateway.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/model/ModelSettings.kt` | yes | Public DNS names that resolve to private or link-local addresses were not refused before the key was sent. Status text said Remote. Extra headers could contain control characters. Defaults left the web address and helper name empty, and Setup suggested Groq. | DNS refusal, control-character drop, and plain status text. Defaults are now https://openrouter.ai/api/v1 and openrouter/free. The secret password stays empty. A blank saved provider is filled the same way. A saved custom provider is left alone. A missing OpenRouter password says to paste the key from openrouter.ai/keys. Probe failures for 401 and 429 are rewritten in plain English. | None. |
| `app/src/main/java/com/codingagent/model/ModelTypes.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/model/RemoteHttpGateway.kt` | yes | Redirects were followed. Error bodies could be large and could echo the key. Success bodies were unbounded. OpenRouter error JSON was shown as a raw blob. | Redirects rejected without reading the body. Error snippets sanitized and capped. Success and stream bodies stop at 4_000_000 bytes by throwing. OpenRouter error JSON is reduced to its message, and the HTTP status stays in the text. Requests to openrouter.ai send HTTP-Referer and X-Title. The key is only the Bearer header. | None. |
| `app/src/main/java/com/codingagent/model/RotatingModelGateway.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/model/UnconfiguredModelGateway.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/research/ArticleExtractor.kt` | yes | A page larger than 2 MiB returns null, so the page is dropped with no separate 'too big' notice. | None. | Left. Returning a cut-off HTML page would look like the whole article. Dropping it keeps the agent from treating a fragment as the source. |
| `app/src/main/java/com/codingagent/research/DeepResearch.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/research/PersonalResearchProvider.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/research/QueryLanes.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/research/ResearchBrief.kt` | yes | A long brief could be cut with no notice. | truncated flag and an explicit notice, while the stored word count stays the full count. | None. |
| `app/src/main/java/com/codingagent/research/SourceQuality.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/research/WebResearch.kt` | yes | Search GETs could follow redirects and read an unbounded body. | Redirects disabled. Search bodies capped at 1_048_576 bytes. | Indentation is uneven. Left because it is valid and a reformat would hide the real diff. |
| `app/src/main/java/com/codingagent/ui/MainActivity.kt` | yes | Setup dialog and approval status used internal labels. Activity was not passed into the approval controller. The dialog told the owner to use Groq. | Plain-English setup dialog. It now says OpenRouter and openrouter/free are already filled in, and the secret password comes from openrouter.ai/keys. activityProvider wired. | None. |
| `app/src/main/java/com/codingagent/ui/MainUiScreens.kt` | yes | Status, chat, review, terminal, and research copy used jargon. New-project file list was a separate bug in the view model. | Owner-facing copy is plain English. Review button says Say yes. Busy line no longer says Agent. | None. |
| `app/src/main/java/com/codingagent/ui/OwnerApprovalController.kt` | yes | Dialog state was not snapshot state, so Compose could drop typed phrases. Signing was not tied to BiometricPrompt. Errors were raw exceptions. | mutableStateOf, BiometricPrompt with CryptoObject, four-word challenge, plain errors. | Speech checks the words, not the speaker's voice. Left for the owner (AUD-025). |
| `app/src/main/java/com/codingagent/ui/ReviewBinder.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/ui/UiTheme.kt` | yes | Phase labels were internal (MODEL, TOOL, RUNNING). | Plain labels. mapAgentPhase still maps the same enums. | None. |
| `app/src/main/java/com/codingagent/ui/WorkbenchViewModel.kt` | yes | Search was not wired to the free providers. Editor Save could write without the approval path. Stop did not cancel approval or research. New project reported an empty file list even though README.md was created. chat!! could crash. First-run drafts started empty. | Search uses CompositeWebResearchProvider. Save stages a proposal. Stop cancels those jobs. New project lists the files that were created. Chat rebuild failure is an error instead of a crash. Draft web address and helper name start as OpenRouter's free helper. The password box starts empty. Try connection shows the plain 401 and 429 sentences. | None. |
| `app/src/main/java/com/codingagent/workspace/AgentPlan.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/workspace/AgentStep.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/workspace/AtomicFileWriter.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/workspace/ChangeDiff.kt` | yes | Offline proposals could reach Review without the six plain-English headings. unified() takes maxLines and never uses it. | ensurePlainEnglishExplanation fills the six headings when they are missing. | maxLines stays unused because review text is supposed to show the whole change. Left so a later caller cannot silently clip the review. |
| `app/src/main/java/com/codingagent/workspace/CommandRunner.kt` | yes | readLimited does not limit. The old README claimed a 256 KiB cap. | README now says output is kept in full. | A noisy command can use a lot of memory. Left. Capping it would drop command output, which this file's comment says it must keep. |
| `app/src/main/java/com/codingagent/workspace/FileIntegrity.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/workspace/MutationCoordinator.kt` | yes | An expired proposal told the owner the agent would restage the same files. Recovery checksums followed a symlink. | The expiry message now says nothing was written. A symlink is treated as a mixed, untrusted disk state. | Other rejection reasons still name recovery-required and constitution rule names. Left where unit tests assert those exact sentences. |
| `app/src/main/java/com/codingagent/workspace/OpenJobStore.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/workspace/OwnerApprovalKey.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/workspace/OwnerApprovalToken.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/workspace/PendingProposalStore.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/workspace/ProjectFileService.kt` | yes | list() could include a symlink whose target is outside the project, and the relative path could fall back to the link name. | Symlinks are skipped, matching the indexer. | None. |
| `app/src/main/java/com/codingagent/workspace/ProjectIndexer.kt` | yes | Search keeps every matching line in memory. | None. | Left. Import is capped at 250 MiB, but a search of a huge text file can still be large. A silent result cap would hide matches. |
| `app/src/main/java/com/codingagent/workspace/ProjectMetadataBoundary.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/workspace/ProjectModels.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/workspace/ProjectPaths.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/workspace/ProjectWorkspace.kt` | yes | verify() read a path with readText, which follows a symlink. Rollback checked checksums once, then wrote without checking again. IMPLEMENT_ME matched any sentence that contained those letters. | verify() refuses a symlink or a path whose real location is outside the project. Rollback checks the checksum again immediately before each write and undoes earlier files in that batch if one no longer matches. IMPLEMENT_ME counts only in a comment or a call, same as TODO and FIXME. | None. |
| `app/src/main/java/com/codingagent/workspace/ProposalIntegrity.kt` | yes | None. | None. | None. |
| `app/src/main/java/com/codingagent/workspace/StorageGuard.kt` | yes | Adding the download size to the margin could overflow a Long and the check would say there was enough space. | Overflow is treated as not enough space. | None. |
| `app/src/main/java/com/codingagent/workspace/TerminalSession.kt` | yes | None. | None. | None. |
| `app/src/main/res/drawable/ic_launcher.xml` | yes | None. | None. | None. |
| `app/src/main/res/values/styles.xml` | yes | None. | None. | None. |
| `app/src/main/res/xml/data_extraction_rules.xml` | yes | None. | None. | None. Cloud backup and device transfer already exclude every domain. |
| `app/src/test/java/com/codingagent/agent/AgentCommandPolicyTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/agent/AgentTaskBuildersTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/agent/ResearchGateTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/agent/ToolCallOutcomeHandlerTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/AcceptancePathTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/AgentContextContinuityTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/AgentJournalTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/AgentModelProtocolTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/AgentPromptTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/AgentTaskBuildersVerificationTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/AtomicFileWriterTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/AutonomousLoopTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/ChangeDiffTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/ChatWorkspaceTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/CodeSynthesisTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/DeepResearchTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/DocumentIngesterTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/ExperienceRecorderTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/FileIntegrityTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/GoalInterpreterTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/KnowledgeIndexTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/LessonSynthesizerTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/LiveUpdateTest.kt` | yes | Did not cover a null parent path. | Added the null-parent checks. | None. |
| `app/src/test/java/com/codingagent/core/LogicReasoningTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/ModelFailureTest.kt` | yes | Still required the old worker-pool wording. | Asserts the plain 'too busy' and Setup wording. Also asserts a 401 and a 429 become plain sentences and the raw JSON is not shown. | None. |
| `app/src/test/java/com/codingagent/core/ModelGatewayTest.kt` | yes | Redirect test looked for the word redirect after the message was rewritten. | Asserts 'different web address' and that the redirect body is not copied into the error. Asserts OpenRouter error JSON keeps the status code, and that the key is only sent as the Bearer header. | None. |
| `app/src/test/java/com/codingagent/core/ModelSettingsTest.kt` | yes | Status assertion looked for Remote. No case for a public name that is a metadata address, or for a private literal that is allowed. An older test required an empty provider. | Updated the status assertion. Added the metadata and RFC1918 cases. Defaults now assert the OpenRouter free helper and an empty secret password. A saved custom provider is not replaced. | None. |
| `app/src/test/java/com/codingagent/core/OpenJobStoreTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/OpenRouterAgentRunner.kt` | yes | There was no owner-runnable path for the real agent loop against OpenRouter. | New runner. It is excluded from the normal test suite. It reads the key from the environment, writes a redacted result, and fails if the key is missing when someone opts in. | Not run in this audit. No OPENROUTER_API_KEY was present, and running it would be a live model call. |
| `app/src/test/java/com/codingagent/core/OperationalAgentTest.kt` | yes | Research failure assertion looked for 'no usable external sources' after the message was rewritten. | Asserts the plain 'could not get any' wording and that the model was not called. | plainEnglishDebugWithoutModelNeedsInputOrFailsClosed still accepts either Completed or Failed. Left because both are non-writing outcomes and the disk assertion is the safety check. |
| `app/src/test/java/com/codingagent/core/OwnerApprovalPhraseStoreTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/OwnerApprovalTokenTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/PlanningLoopTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/ProjectFileServiceBoundaryTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/ProjectImportPolicyTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/ProjectWorkspaceTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/RepairCycleTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/RepairLifecycleAcceptanceTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/ResearchModeTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/ReviewBinderTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/SelfEvolutionTest.kt` | yes | Did not cover a null parent path. | Added the null-parent checks. | None. |
| `app/src/test/java/com/codingagent/core/SelfRepairTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/StorageGuardTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/TaskIntakeTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/TerminalCancelTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/ToolPurposeTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/ToolResultPagerTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/ToolSelectionTest.kt` | yes | None. | None. | None. |
| `app/src/test/java/com/codingagent/core/UserMemoryTest.kt` | yes | Sets maxEntries = 2 and expects 4 memories, which documents that the cap is unused. | None. | Left with UserMemory.kt. The test matches the current choice not to drop memories. |
| `app/src/test/java/com/codingagent/workspace/DualModalityApprovalTest.kt` | yes | None. | None. | None. |
| `build.gradle.kts` | yes | None. | None. | None. |
| `docs/ANTI_YES_MAN.md` | yes | None. | None. | None. |
| `docs/OWNER_SYSTEM_PROMPT.md` | yes | None. | None. | None. |
| `docs/TOOL_QUEUE.md` | yes | None. It correctly says only the first tool call is used. | None. | None. README was the file that contradicted it. |
| `docs/audit/FILE_BY_FILE_AUDIT_LEDGER.md` | yes | Historical ledger. It disagrees with this tree (wrapper checksum, package-source.sh, sync script, instrumentation, approval design). | None. Not edited, so it stays as history. | Do not treat it as the current audit. This checklist is the current one. |
| `docs/audit/PHASE_1_BASELINE_EVIDENCE.md` | yes | Describes an older commit and an older approval path. | None. | Left as historical evidence. |
| `docs/audit/PHASE_1_RECURSIVE_TREE.txt` | yes | Tree snapshot of an older commit. Missing later files. | None. | Left as historical evidence. |
| `docs/plans/2026-07-28-deep-research-design.md` | yes | Names CodingAgentRuntime, which is not a class in this tree. | None. | Left. It is a design note, not the runtime. |
| `gradle.properties` | yes | None. | None. | None. |
| `gradle/wrapper/gradle-wrapper.jar` | yes | Tracked JAR SHA-256 was cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8, not the official Gradle 8.9 wrapper JAR CI verifies. | Replaced with the official JAR, SHA-256 498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17. Inspected as a zip (GradleWrapperMain present), not decompiled. | None. |
| `gradle/wrapper/gradle-wrapper.properties` | yes | None. Pins Gradle 8.9 and distributionSha256Sum. | None. | The old ledger said the checksum was missing. That note is stale. |
| `gradlew` | yes | File mode was not executable in git. | Mode set to executable. | None. CI also chmod +x. |
| `proguard-rules.pro` | yes | Keeps all of androidx.compose.runtime and all of Tink. | None. | Left. Release minify is not the phone install path (debug APK). Narrowing keep rules without a release-minify test can crash the keystore path. |
| `scripts/package-source.sh` | yes | Forbidden paths were skipped instead of failing the archive. | A forbidden path now fails the script. | None. |
| `scripts/run_openrouter_agent.sh` | yes | The owner had no way to run the real agent loop against OpenRouter from a computer. | New script. It refuses to run when OPENROUTER_API_KEY is unset, does not print the key, and does not pass the key on the command line. | A live run was not executed here because no key was in the environment. |
| `scripts/sync_to_github.sh` | yes | Updated one file at a time through the GitHub Contents API, which can leave a branch half-updated. Hard-coded helper path. | The script now refuses that update and exits 2. | It depends on /home/workdir/.grok/skills/safe-github-push, which is not in this repo, so it cannot push from a normal phone or a clean checkout. Left because pushing the agent's own repo needs the owner's GitHub credentials and a reviewed push path. |
| `settings.gradle.kts` | yes | None. | None. | None. |
| `app/src/main/res/xml/network_security_config.xml` | yes | Missing while the manifest required it, so a clean checkout could not build. | Added. Cleartext only for localhost and 127.0.0.1. | None. |
| `app/src/test/java/com/codingagent/core/AuditFixTest.kt` | yes | New tests were needed for the phase map, research truncation, and the help misroute. | Added those tests. | None. |
| `AUDIT_CHECKLIST.md` | yes | None. | None. | None. |

Files listed: 176.

