# Phase 2 — File-by-file audit ledger

## Audit baseline
- Repository: `thepotatoninjahost/Coding-Agent`
- Branch audited: `main`
- Exact baseline commit: `5910ac4841c893de5a5dca25d95b5f9f3591bbce`
- Source of inventory: recursive Git tree API at that commit
- Tree truncated: `false`
- Tracked file entries: `153`
- Directories: `33`

## Status rules
- `In progress`: path and Git metadata are inventoried; full dedicated file review and behavioral verification are not evidenced yet.
- `Prior inspection recorded`: prior conversation lists the component as inspected, but this ledger does not have a preserved finding-by-finding review record or per-file verification evidence. Reconcile before calling verified.
- `Blocked`: content could not be inspected. No file is marked `Verified` based solely on tree metadata or a passing CI report.

## Coverage and limitation
This ledger enumerates every blob in the non-truncated tree. The Git tree supplies exact path, byte size, blob SHA and file mode, but not line counts or content. Line counts, full content review, dependency/caller tracing, findings, and behavioral evidence are explicitly pending rather than fabricated. The Phase 2 exit criterion is therefore **not yet met**. No production files were changed.

## Ledger fields
Every entry includes path, type, size, line-count status, purpose, dependency status, review method, findings, action, verification, and state.

### `.gitattributes`

- **Path:** `.gitattributes`
- **Type:** Build/configuration or Android resource
- **Size:** 88 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `eab5770fb6e0346d32ca27628f96528fc10befa9`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (.gitattributes); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `.github/workflows/android-build.yml`

- **Path:** `.github/workflows/android-build.yml`
- **Type:** Build/configuration or Android resource
- **Size:** 3010 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `6095ce37a8850a76d6f7ed16e4819283c02edbe4`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (android-build.yml); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `.gitignore`

- **Path:** `.gitignore`
- **Type:** Build/configuration or Android resource
- **Size:** 149 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `1ad1c3be71ba4801b2d199a72499eacfb9deb994`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (.gitignore); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `README.md`

- **Path:** `README.md`
- **Type:** Documentation
- **Size:** 12741 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `8e52cf139e39d2b48896a6079e3f372bcb972df6`
- **File mode:** `100644`
- **Purpose:** Project documentation (README.md); implementation claims and workflow accuracy pending reconciliation.
- **Dependencies:** Cross-check claims against implementation and supported workflows
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/build.gradle.kts`

- **Path:** `app/build.gradle.kts`
- **Type:** Build/configuration or Android resource
- **Size:** 2589 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `16c9283b91ed5da927422bde9258312cbbaca61e`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (build.gradle.kts); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/AndroidManifest.xml`

- **Path:** `app/src/main/AndroidManifest.xml`
- **Type:** Build/configuration or Android resource
- **Size:** 901 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `1e0ccb857729537aa97368f90f5ffd7477c46c35`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (AndroidManifest.xml); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/assets/knowledge/coding-for-dummies.txt`

- **Path:** `app/src/main/assets/knowledge/coding-for-dummies.txt`
- **Type:** Knowledge asset
- **Size:** 408460 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `561a59c4edd1aca437f2b6440b712a1f806a98f3`
- **File mode:** `100644`
- **Purpose:** Bundled knowledge text; provenance, licensing, quality, indexing, and model/user exposure pending review.
- **Dependencies:** Consumers, provenance and license not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/AgentCommandPolicy.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AgentCommandPolicy.kt`
- **Type:** Application source
- **Size:** 8239 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `5ee40b04d957ec61c6f6f4afe5bf2d2f2b9ba2d3`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentCommandPolicy.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/agent/AgentConstitution.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AgentConstitution.kt`
- **Type:** Application source
- **Size:** 5066 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `5419da862201ec1a40da4956fc9779fbeffa24a6`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentConstitution.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/AgentDirectLanes.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AgentDirectLanes.kt`
- **Type:** Application source
- **Size:** 8374 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `893f918b6a8ae77b3e2718d7c9e131c88fa34dda`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentDirectLanes.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/AgentOfflineMutation.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AgentOfflineMutation.kt`
- **Type:** Application source
- **Size:** 4579 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `3a27cdf0bcd40b907bffc79c883b74ce3279fb5c`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentOfflineMutation.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/AgentPrompt.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AgentPrompt.kt`
- **Type:** Application source
- **Size:** 7092 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `866050ac0b4e0a537b5035d7fdca186851240497`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentPrompt.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/AgentRequestFocus.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AgentRequestFocus.kt`
- **Type:** Application source
- **Size:** 408 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `c17a876d7f5b4b8c2d43bf8a36e7382dafcb720d`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentRequestFocus.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/AgentRequestKind.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AgentRequestKind.kt`
- **Type:** Application source
- **Size:** 5834 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `0c98a9bad53ba7d9a8f9c9bb58510250a080f616`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentRequestKind.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/AgentRuntime.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AgentRuntime.kt`
- **Type:** Application source
- **Size:** 631 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `8ee564c5663eef9ba8f39420426743c7d3d40ccc`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentRuntime.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/AgentSupport.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AgentSupport.kt`
- **Type:** Application source
- **Size:** 4127 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `4caeb8911e5d2b383758b38cf542c548d9efbc15`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentSupport.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/AgentTaskBuilders.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AgentTaskBuilders.kt`
- **Type:** Application source
- **Size:** 1989 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `82ad079addb9311b1bc032565fcf691a5dd7e295`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentTaskBuilders.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/AgentToolDispatch.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AgentToolDispatch.kt`
- **Type:** Application source
- **Size:** 8004 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `40c0cf02ad499a3a7702f4ce8130f583359326c1`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentToolDispatch.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/agent/AgentTools.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AgentTools.kt`
- **Type:** Application source
- **Size:** 5021 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `2cd9ec01369c4711d815d47590d4fb63952b70fa`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentTools.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/AutonomousAgent.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AutonomousAgent.kt`
- **Type:** Application source
- **Size:** 41704 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `26f4480ecefeedd567b9b8b7d98165493e280716`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AutonomousAgent.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/agent/AutonomousAgentConfig.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AutonomousAgentConfig.kt`
- **Type:** Application source
- **Size:** 373 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `a77a020881f6f8b8a32cafac93420d163607a1f4`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AutonomousAgentConfig.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/AutonomousAgentEvent.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/AutonomousAgentEvent.kt`
- **Type:** Application source
- **Size:** 1138 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `8ccde0301cb16f9ef1c24db4da24d22ab9c43c82`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AutonomousAgentEvent.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/ChatApproval.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/ChatApproval.kt`
- **Type:** Application source
- **Size:** 2156 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `fadc861cded245962bd6b2f6301c082250b0afff`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ChatApproval.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/ChatWorkspace.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/ChatWorkspace.kt`
- **Type:** Application source
- **Size:** 17880 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `2f29572d90167dffd46204171c0362a3227cafc8`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ChatWorkspace.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/agent/DegenerateOutput.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/DegenerateOutput.kt`
- **Type:** Application source
- **Size:** 4892 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `d0f0e44f8849d9f360810c3a2d62881d66a43297`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component DegenerateOutput.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/ExperienceRecorder.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/ExperienceRecorder.kt`
- **Type:** Application source
- **Size:** 1187 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `6b6870b2ad219aec3e858b1205cedc7abae8f917`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ExperienceRecorder.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/LessonContext.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/LessonContext.kt`
- **Type:** Application source
- **Size:** 679 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `b53d366501990c952a2b2a46c9af17900b804125`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component LessonContext.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/LessonSynthesizer.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/LessonSynthesizer.kt`
- **Type:** Application source
- **Size:** 2632 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `7b3b4e38f5e052b6547b667dd4081f2e864c4638`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component LessonSynthesizer.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/LocalFileEvidence.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/LocalFileEvidence.kt`
- **Type:** Application source
- **Size:** 3160 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `b821f11494f6430701c46395fd92d256cff7335c`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component LocalFileEvidence.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/LogicReasoning.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/LogicReasoning.kt`
- **Type:** Application source
- **Size:** 5121 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `568c852f01ba781fb68f301546bc0e62b7160278`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component LogicReasoning.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/LoopControl.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/LoopControl.kt`
- **Type:** Application source
- **Size:** 1907 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `ceb821c3c622dabf1bb7d8a9d7779a0a2fd23f3b`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component LoopControl.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/ModelCallWithRetry.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/ModelCallWithRetry.kt`
- **Type:** Application source
- **Size:** 2637 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `32332bda73dcf787ca28894929ac3ffb2994b4b6`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ModelCallWithRetry.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/ModelFailure.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/ModelFailure.kt`
- **Type:** Application source
- **Size:** 3551 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `cb04722b343ffc7c014b2332ba97be6d404d872d`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ModelFailure.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/PendingWorkResume.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/PendingWorkResume.kt`
- **Type:** Application source
- **Size:** 6297 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `e718020c3040dded5be6ee10f3eef69a7762d31c`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component PendingWorkResume.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/PlanningLoop.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/PlanningLoop.kt`
- **Type:** Application source
- **Size:** 8437 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `adc090e34a059f992190f086f34796ad63697cbd`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component PlanningLoop.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/RepairCycle.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/RepairCycle.kt`
- **Type:** Application source
- **Size:** 4997 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `c1de7d18aadd53b8108da4a8773ab976915916ff`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component RepairCycle.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/ResearchGate.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/ResearchGate.kt`
- **Type:** Application source
- **Size:** 1311 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `e0a05bd70e359d063fcf213839bbb67589a37b64`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ResearchGate.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/ResponseQualityRules.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/ResponseQualityRules.kt`
- **Type:** Application source
- **Size:** 1706 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `f20e9f1c7aa254bb836d7d105986b763ce883392`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ResponseQualityRules.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/SelfEvolution.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/SelfEvolution.kt`
- **Type:** Application source
- **Size:** 3573 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `ed7b8d77d824efcbc216e1432044dc10fe69d081`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component SelfEvolution.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/SelfRepair.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/SelfRepair.kt`
- **Type:** Application source
- **Size:** 4810 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `f3aef6fa4a098cedad0b1ca2ae0a10c89106233b`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component SelfRepair.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/StructureScan.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/StructureScan.kt`
- **Type:** Application source
- **Size:** 1520 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `c3bc3bca6ce5370b5eebe1fdb18d37fa3bda6425`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component StructureScan.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/ToolCallOutcomeHandler.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/ToolCallOutcomeHandler.kt`
- **Type:** Application source
- **Size:** 19186 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `cdc9d3f8d9080e811e61fad147c114d92ef9b2c3`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ToolCallOutcomeHandler.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/ToolPurpose.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/ToolPurpose.kt`
- **Type:** Application source
- **Size:** 1765 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `9a776f1ed29535412e6cf187fdab91d560a6a47a`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ToolPurpose.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/ToolSelection.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/ToolSelection.kt`
- **Type:** Application source
- **Size:** 9239 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `9a1dd0c8d94991d69db70c03d2a1662f0adbcc49`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ToolSelection.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/agent/UserMemory.kt`

- **Path:** `app/src/main/java/com/codingagent/agent/UserMemory.kt`
- **Type:** Application source
- **Size:** 8295 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `9426287edb522081df8867cdf48c0d7dd8affb1e`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component UserMemory.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/core/BuiltInModules.kt`

- **Path:** `app/src/main/java/com/codingagent/core/BuiltInModules.kt`
- **Type:** Application source
- **Size:** 985 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `f2dc662d7f305ca310f6c645ec0fdacdfda6aec0`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component BuiltInModules.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/core/DeviceUserMemoryStore.kt`

- **Path:** `app/src/main/java/com/codingagent/core/DeviceUserMemoryStore.kt`
- **Type:** Application source
- **Size:** 3152 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `d13f3f0ad9af9fdf099d9b442ccf01a634622e16`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component DeviceUserMemoryStore.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/core/KeystoreSecretStore.kt`

- **Path:** `app/src/main/java/com/codingagent/core/KeystoreSecretStore.kt`
- **Type:** Application source
- **Size:** 9067 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `1aea6721bda74bfb0160d1f21185b95ac3683fc0`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component KeystoreSecretStore.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/core/LiveModuleRuntime.kt`

- **Path:** `app/src/main/java/com/codingagent/core/LiveModuleRuntime.kt`
- **Type:** Application source
- **Size:** 4713 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `31a421f44b6d192465954abbe5e21b5a00881b3c`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component LiveModuleRuntime.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/core/LiveModuleStore.kt`

- **Path:** `app/src/main/java/com/codingagent/core/LiveModuleStore.kt`
- **Type:** Application source
- **Size:** 6619 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `03b43208f499ec337b8e48915058316db98a4c15`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component LiveModuleStore.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/core/LocalStore.kt`

- **Path:** `app/src/main/java/com/codingagent/core/LocalStore.kt`
- **Type:** Application source
- **Size:** 5790 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `a5f8a38b5c98159b6e3541df039f972b7800d814`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component LocalStore.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/intake/CodeSynthesis.kt`

- **Path:** `app/src/main/java/com/codingagent/intake/CodeSynthesis.kt`
- **Type:** Application source
- **Size:** 5683 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `a00ea4f3faae4c7fb100048c08c73e515e553f6b`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component CodeSynthesis.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/intake/GoalInterpreter.kt`

- **Path:** `app/src/main/java/com/codingagent/intake/GoalInterpreter.kt`
- **Type:** Application source
- **Size:** 8519 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `d2fc0e05b6b720203c212f8431c369b3f191f99f`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component GoalInterpreter.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/intake/TaskIntake.kt`

- **Path:** `app/src/main/java/com/codingagent/intake/TaskIntake.kt`
- **Type:** Application source
- **Size:** 4174 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `b2aa011c42f3bc30dd5ec649a6f908b643dcb239`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component TaskIntake.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/knowledge/DocumentIngester.kt`

- **Path:** `app/src/main/java/com/codingagent/knowledge/DocumentIngester.kt`
- **Type:** Application source
- **Size:** 3476 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `e2ce71becad36176dfd684847555ad2f38776224`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component DocumentIngester.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/knowledge/KnowledgeBase.kt`

- **Path:** `app/src/main/java/com/codingagent/knowledge/KnowledgeBase.kt`
- **Type:** Application source
- **Size:** 2115 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `99c51323f679e5e444e89c488bb45d625e7a62bb`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component KnowledgeBase.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/knowledge/KnowledgeIndex.kt`

- **Path:** `app/src/main/java/com/codingagent/knowledge/KnowledgeIndex.kt`
- **Type:** Application source
- **Size:** 6721 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `976ba832ea6d8f4ea9b90aff9295ff740e76cbe2`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component KnowledgeIndex.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/knowledge/KnowledgeProvider.kt`

- **Path:** `app/src/main/java/com/codingagent/knowledge/KnowledgeProvider.kt`
- **Type:** Application source
- **Size:** 242 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `10190167ad6153adf373bf5c761690fbd8f7e9d9`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component KnowledgeProvider.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/model/AgentModelProtocol.kt`

- **Path:** `app/src/main/java/com/codingagent/model/AgentModelProtocol.kt`
- **Type:** Application source
- **Size:** 7200 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `b1d09f97af38e00e6ea347b8bf9be63e408cbf88`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentModelProtocol.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/model/JsonModelResponseParser.kt`

- **Path:** `app/src/main/java/com/codingagent/model/JsonModelResponseParser.kt`
- **Type:** Application source
- **Size:** 2739 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `69a18a45a3efd66fcf30d98546d6820bbd0b09aa`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component JsonModelResponseParser.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/model/LiveModelRouter.kt`

- **Path:** `app/src/main/java/com/codingagent/model/LiveModelRouter.kt`
- **Type:** Application source
- **Size:** 792 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `7c68e4385bf359834072939706ca05a12df4933c`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component LiveModelRouter.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/model/LiveModelStore.kt`

- **Path:** `app/src/main/java/com/codingagent/model/LiveModelStore.kt`
- **Type:** Application source
- **Size:** 7938 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `0d9d8fd24ea0358f2e9f03e99bb12bb2d60b4c85`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component LiveModelStore.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/model/ModelDownloadProgress.kt`

- **Path:** `app/src/main/java/com/codingagent/model/ModelDownloadProgress.kt`
- **Type:** Application source
- **Size:** 223 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `b124be9c1da96b6fc0a74dda97e7aae30e0db1eb`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ModelDownloadProgress.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/model/ModelGateway.kt`

- **Path:** `app/src/main/java/com/codingagent/model/ModelGateway.kt`
- **Type:** Application source
- **Size:** 115 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `e3c1bbee8748928e49ef436ee3912f4e64a0c2fd`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ModelGateway.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/model/ModelSettings.kt`

- **Path:** `app/src/main/java/com/codingagent/model/ModelSettings.kt`
- **Type:** Application source
- **Size:** 9550 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `6f0dd92ad42a50f873b6821da241f84225ec10a6`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ModelSettings.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/model/ModelTypes.kt`

- **Path:** `app/src/main/java/com/codingagent/model/ModelTypes.kt`
- **Type:** Application source
- **Size:** 1291 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `b17a14a06b77cb8b216fb43f4a1f2e7aaa4e0bb9`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ModelTypes.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/model/RemoteHttpGateway.kt`

- **Path:** `app/src/main/java/com/codingagent/model/RemoteHttpGateway.kt`
- **Type:** Application source
- **Size:** 17320 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `619a0ae0353bef2672739be2b7b31ce202c959a0`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component RemoteHttpGateway.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/model/RotatingModelGateway.kt`

- **Path:** `app/src/main/java/com/codingagent/model/RotatingModelGateway.kt`
- **Type:** Application source
- **Size:** 4786 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `515dbe1cdfbd953f6a260ad0e126a38e76b71edf`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component RotatingModelGateway.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/model/UnconfiguredModelGateway.kt`

- **Path:** `app/src/main/java/com/codingagent/model/UnconfiguredModelGateway.kt`
- **Type:** Application source
- **Size:** 421 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `9842b69bd5440e69e75660be9afc138b9bf0dec5`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component UnconfiguredModelGateway.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/research/ArticleExtractor.kt`

- **Path:** `app/src/main/java/com/codingagent/research/ArticleExtractor.kt`
- **Type:** Application source
- **Size:** 5611 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `dbd0ce3091e774df28a17bc010a81990bf61b2d4`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ArticleExtractor.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/research/DeepResearch.kt`

- **Path:** `app/src/main/java/com/codingagent/research/DeepResearch.kt`
- **Type:** Application source
- **Size:** 13934 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `6697bea3707d8ad0bddc0df5aba54804923bb739`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component DeepResearch.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/research/PersonalResearchProvider.kt`

- **Path:** `app/src/main/java/com/codingagent/research/PersonalResearchProvider.kt`
- **Type:** Application source
- **Size:** 7775 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `638689bf6ad6b81f08f58290743e4998bbf6d0f0`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component PersonalResearchProvider.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/research/QueryLanes.kt`

- **Path:** `app/src/main/java/com/codingagent/research/QueryLanes.kt`
- **Type:** Application source
- **Size:** 3983 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `cb2d6d20a9dd7033ad02d3a02c1c90f6771c22d5`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component QueryLanes.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/research/ResearchBrief.kt`

- **Path:** `app/src/main/java/com/codingagent/research/ResearchBrief.kt`
- **Type:** Application source
- **Size:** 2894 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `18753f9a9e2ddea0096a5abdfad6f31e33c319ae`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ResearchBrief.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/research/SourceQuality.kt`

- **Path:** `app/src/main/java/com/codingagent/research/SourceQuality.kt`
- **Type:** Application source
- **Size:** 9690 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `3febae8bb9dea958614134d7c4baaa509c2dcf80`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component SourceQuality.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/research/WebResearch.kt`

- **Path:** `app/src/main/java/com/codingagent/research/WebResearch.kt`
- **Type:** Application source
- **Size:** 20145 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `ab7e848c6a72c9dd3e0670a4336009cc7b83fea3`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component WebResearch.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/ui/MainActivity.kt`

- **Path:** `app/src/main/java/com/codingagent/ui/MainActivity.kt`
- **Type:** Application source
- **Size:** 44763 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `142196593e0e73bf4981c0531107a09de90671fc`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component MainActivity.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/ui/MainUiScreens.kt`

- **Path:** `app/src/main/java/com/codingagent/ui/MainUiScreens.kt`
- **Type:** Application source
- **Size:** 30119 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `85fd7ee22c1258efad38eca5cfde89b91436b159`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component MainUiScreens.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/ui/ReviewBinder.kt`

- **Path:** `app/src/main/java/com/codingagent/ui/ReviewBinder.kt`
- **Type:** Application source
- **Size:** 1187 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `c339388f2134479be349f3b01e455bf2b42a7feb`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ReviewBinder.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/ui/UiTheme.kt`

- **Path:** `app/src/main/java/com/codingagent/ui/UiTheme.kt`
- **Type:** Application source
- **Size:** 1738 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `df8b1363820c590bd057cac79994a448ee4b072f`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component UiTheme.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/workspace/AgentPlan.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/AgentPlan.kt`
- **Type:** Application source
- **Size:** 191 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `aa4a0b9e7d8b039650fc97e6c6633f3c64db7942`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentPlan.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/workspace/AgentStep.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/AgentStep.kt`
- **Type:** Application source
- **Size:** 191 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `82dca3684cd3a894bb7bf56920dcee7ef7e8fa71`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AgentStep.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/workspace/AtomicFileWriter.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/AtomicFileWriter.kt`
- **Type:** Application source
- **Size:** 3820 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `f76eaa52d4219ccd84621b5f602203010fbb0f08`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component AtomicFileWriter.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/workspace/ChangeDiff.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/ChangeDiff.kt`
- **Type:** Application source
- **Size:** 8172 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `d8c522b2f544715d827fca7a525f0b5ee0f030bd`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ChangeDiff.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/workspace/CommandRunner.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/CommandRunner.kt`
- **Type:** Application source
- **Size:** 5212 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `64a568779679ef44fe94dcb6af50bbfb43129998`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component CommandRunner.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/workspace/FileIntegrity.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/FileIntegrity.kt`
- **Type:** Application source
- **Size:** 6017 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `8b4e0548a7bb4737ea0a6b0a8922a58567be7066`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component FileIntegrity.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/workspace/MutationCoordinator.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/MutationCoordinator.kt`
- **Type:** Application source
- **Size:** 36292 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `21e23418b40046863c585c5c18ef3d81af3c0c08`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component MutationCoordinator.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/workspace/OpenJobStore.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/OpenJobStore.kt`
- **Type:** Application source
- **Size:** 8299 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `9836fd2fed76612ebe678a2cfc15dd7506e0bba4`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component OpenJobStore.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/workspace/OwnerApprovalToken.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/OwnerApprovalToken.kt`
- **Type:** Application source
- **Size:** 1477 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `9816ba9a3489fb4cb16118c0d67e369cb4d0fdbe`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component OwnerApprovalToken.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/workspace/PendingProposalStore.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/PendingProposalStore.kt`
- **Type:** Application source
- **Size:** 6338 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `45d93c09b47e6c0f1fe19e610f3f4d3a6b0aa474`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component PendingProposalStore.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/workspace/ProjectFileService.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/ProjectFileService.kt`
- **Type:** Application source
- **Size:** 2240 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `5dab8ab331b4abe42b9bee194e855b8eb6602747`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ProjectFileService.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/workspace/ProjectIndexer.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/ProjectIndexer.kt`
- **Type:** Application source
- **Size:** 5486 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `0131c72143a86cb66e4d6277aac1ccb2babbb788`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ProjectIndexer.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/workspace/ProjectModels.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/ProjectModels.kt`
- **Type:** Application source
- **Size:** 3613 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `993e96844aabd4bd8cbae44c5193ba9fdff5a683`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ProjectModels.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/workspace/ProjectPaths.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/ProjectPaths.kt`
- **Type:** Application source
- **Size:** 1216 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `198a444b8bc068fcdfab1ae4b85ec2e86d375ece`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ProjectPaths.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/workspace/ProjectWorkspace.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/ProjectWorkspace.kt`
- **Type:** Application source
- **Size:** 16975 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `570ed2c66b76f638873b2abe5c9b1c5f5ddd246d`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ProjectWorkspace.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/workspace/ProposalIntegrity.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/ProposalIntegrity.kt`
- **Type:** Application source
- **Size:** 2383 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `8304ca29a0d2d0033c0213c3e56ec44294c6a566`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component ProposalIntegrity.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/java/com/codingagent/workspace/StorageGuard.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/StorageGuard.kt`
- **Type:** Application source
- **Size:** 2557 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `1fa247cf245332a2c5182f11bb20f109d0014365`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component StorageGuard.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Prior structured inspection reported; underlying notes/evidence not present in this ledger
- **Findings:** No finding IDs or disposition evidence preserved; do not infer none
- **Action:** Reconcile prior notes, findings and tests; retain only if supported
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** Prior inspection recorded

### `app/src/main/java/com/codingagent/workspace/TerminalSession.kt`

- **Path:** `app/src/main/java/com/codingagent/workspace/TerminalSession.kt`
- **Type:** Application source
- **Size:** 3058 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `c19737d677e6f1ef02a4306b5926dc58a3c64bac`
- **File mode:** `100644`
- **Purpose:** Production Kotlin component TerminalSession.kt; exact responsibilities and caller contracts pending source review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/res/drawable/ic_launcher.xml`

- **Path:** `app/src/main/res/drawable/ic_launcher.xml`
- **Type:** Build/configuration or Android resource
- **Size:** 361 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `b1ad91375be6062333a39c4b114f83e2ecdb6a7a`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (ic_launcher.xml); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/res/values/styles.xml`

- **Path:** `app/src/main/res/values/styles.xml`
- **Type:** Build/configuration or Android resource
- **Size:** 120 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `4a704655bce4a9ddab568aaebb13d623ae60c50e`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (styles.xml); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/main/res/xml/data_extraction_rules.xml`

- **Path:** `app/src/main/res/xml/data_extraction_rules.xml`
- **Type:** Build/configuration or Android resource
- **Size:** 585 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `3f5900cef1f120332d63f6c4925e9051c7564463`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (data_extraction_rules.xml); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/agent/AgentCommandPolicyTest.kt`

- **Path:** `app/src/test/java/com/codingagent/agent/AgentCommandPolicyTest.kt`
- **Type:** Test
- **Size:** 8288 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `903fafece98763d7912f00da092f4216e5e1ffcd`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for AgentCommandPolicy; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/agent/ToolCallOutcomeHandlerTest.kt`

- **Path:** `app/src/test/java/com/codingagent/agent/ToolCallOutcomeHandlerTest.kt`
- **Type:** Test
- **Size:** 4059 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `996b5848ef96c3a899bfb503df72229c88ee01dd`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ToolCallOutcomeHandler; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/AcceptancePathTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/AcceptancePathTest.kt`
- **Type:** Test
- **Size:** 42098 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `4fb5ac4e42924c2fd61ecd48a31ca1c3b10f58b9`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for AcceptancePath; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/AgentContextContinuityTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/AgentContextContinuityTest.kt`
- **Type:** Test
- **Size:** 1972 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `11040cbfd23bde1935c60d58613a2c68c0ac8b7f`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for AgentContextContinuity; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/AgentModelProtocolTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/AgentModelProtocolTest.kt`
- **Type:** Test
- **Size:** 557 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `d236ae1a263331c4749c37ae134e5b49bd282c92`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for AgentModelProtocol; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/AgentPromptTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/AgentPromptTest.kt`
- **Type:** Test
- **Size:** 1438 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `b01d082d1ee6624f95a3595d705131060182b0f6`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for AgentPrompt; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/AtomicFileWriterTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/AtomicFileWriterTest.kt`
- **Type:** Test
- **Size:** 2901 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `118144bd87654df095a6d74b8cdfdf312aee8938`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for AtomicFileWriter; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/AutonomousLoopTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/AutonomousLoopTest.kt`
- **Type:** Test
- **Size:** 11531 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `d705ccfb44330cf9acfc21556f62e66705001a51`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for AutonomousLoop; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ChangeDiffTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ChangeDiffTest.kt`
- **Type:** Test
- **Size:** 3224 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `8e3a70da43473adb97614c44f7f8c2c6f1201de0`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ChangeDiff; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ChatWorkspaceTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ChatWorkspaceTest.kt`
- **Type:** Test
- **Size:** 14269 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `38ed49ab98350b49c8f47b38c797648a7edd1207`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ChatWorkspace; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/CodeSynthesisTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/CodeSynthesisTest.kt`
- **Type:** Test
- **Size:** 2161 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `d3bd110593cb79209b439a613c1cbb6e238a309a`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for CodeSynthesis; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/DeepResearchTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/DeepResearchTest.kt`
- **Type:** Test
- **Size:** 7547 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `eb575c3005b40f295deba805cecc26510c430b0a`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for DeepResearch; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/DocumentIngesterTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/DocumentIngesterTest.kt`
- **Type:** Test
- **Size:** 2841 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `da0d7759974b3e6799ed2839f926b045ae927bdb`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for DocumentIngester; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ExperienceRecorderTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ExperienceRecorderTest.kt`
- **Type:** Test
- **Size:** 890 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `40b756fde90e026a72ef3ed3b479ca0e3129fc50`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ExperienceRecorder; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/FileIntegrityTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/FileIntegrityTest.kt`
- **Type:** Test
- **Size:** 4919 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `9f288c4d224d744e9e46c9892eefed85d959b4d0`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for FileIntegrity; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/GoalInterpreterTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/GoalInterpreterTest.kt`
- **Type:** Test
- **Size:** 6041 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `81422c6dec0a2762ea3cfc6f57f9505c8980e6d9`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for GoalInterpreter; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/LessonSynthesizerTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/LessonSynthesizerTest.kt`
- **Type:** Test
- **Size:** 1632 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `c8c6d09abeaf4136893b788631671a3cd3bcd33a`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for LessonSynthesizer; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/LiveUpdateTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/LiveUpdateTest.kt`
- **Type:** Test
- **Size:** 7474 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `50fdc9614b64dbfc530bb221e46e10e50c039aec`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for LiveUpdate; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/LogicReasoningTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/LogicReasoningTest.kt`
- **Type:** Test
- **Size:** 2415 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `bbc0c1c0fa0e63645c579200a5844ac184c08771`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for LogicReasoning; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ModelFailureTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ModelFailureTest.kt`
- **Type:** Test
- **Size:** 1683 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `231f5d88bb5d924234260c517ea5898288b580ba`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ModelFailure; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ModelGatewayTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ModelGatewayTest.kt`
- **Type:** Test
- **Size:** 6613 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `aed2844e8f4d0f743503d2d0f72ce5047f53c94c`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ModelGateway; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ModelSettingsTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ModelSettingsTest.kt`
- **Type:** Test
- **Size:** 4217 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `d6e082c798c2932b8262459ca3ec75719f4622cb`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ModelSettings; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/OpenJobStoreTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/OpenJobStoreTest.kt`
- **Type:** Test
- **Size:** 6001 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `37e76e825b58d48381513909fc7431dc21de8de1`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for OpenJobStore; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/OperationalAgentTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/OperationalAgentTest.kt`
- **Type:** Test
- **Size:** 11106 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `808e8dd8ba73731283211d290636d5b17d15a708`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for OperationalAgent; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/OwnerApprovalTokenTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/OwnerApprovalTokenTest.kt`
- **Type:** Test
- **Size:** 1137 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `8200e340ed435639ea6c7dd51f1386749c93d7b2`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for OwnerApprovalToken; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/PlanningLoopTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/PlanningLoopTest.kt`
- **Type:** Test
- **Size:** 2916 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `0a31d7fb0cc0e99dd7a9315b8a21f36126d382ce`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for PlanningLoop; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ProjectFileServiceBoundaryTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ProjectFileServiceBoundaryTest.kt`
- **Type:** Test
- **Size:** 3423 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `2d40f9a9a2582e4054dadd91340634e777cfb5c5`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ProjectFileServiceBoundary; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ProjectImportPolicyTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ProjectImportPolicyTest.kt`
- **Type:** Test
- **Size:** 1475 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `6981fc7d4ea3908b88d8bbc004e1c43c088d82b0`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ProjectImportPolicy; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ProjectWorkspaceTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ProjectWorkspaceTest.kt`
- **Type:** Test
- **Size:** 6851 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `6f39c8888c151eb3de043a5f529349e9f96ba61c`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ProjectWorkspace; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/RepairCycleTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/RepairCycleTest.kt`
- **Type:** Test
- **Size:** 721 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `a5359593037f52e510b66e4f25d088af74e01881`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for RepairCycle; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ResearchModeTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ResearchModeTest.kt`
- **Type:** Test
- **Size:** 8795 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `87a90441bb42a51ec63f5da0e8b5bf1fd1b1dbc5`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ResearchMode; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ReviewBinderTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ReviewBinderTest.kt`
- **Type:** Test
- **Size:** 2302 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `ebbedb6c0c9776849521182de25e91a21db72d6c`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ReviewBinder; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/SelfRepairTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/SelfRepairTest.kt`
- **Type:** Test
- **Size:** 3856 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `edae495c1ba95e6a6d72e24511e18cd3501b2eef`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for SelfRepair; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/StorageGuardTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/StorageGuardTest.kt`
- **Type:** Test
- **Size:** 1553 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `3f820a50582addafab48cc11aa315367a0eb07f4`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for StorageGuard; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/TaskIntakeTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/TaskIntakeTest.kt`
- **Type:** Test
- **Size:** 2263 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `9ef9532adf4ba1b58f805c12658a90efa0b54d62`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for TaskIntake; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/TerminalCancelTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/TerminalCancelTest.kt`
- **Type:** Test
- **Size:** 7825 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `e9b9053959b26dff22f9eb230068fd748ef962e2`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for TerminalCancel; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ToolPurposeTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ToolPurposeTest.kt`
- **Type:** Test
- **Size:** 1380 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `6e7323d2c0841eba2fae2aef7f981d0cecf912ef`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ToolPurpose; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/ToolSelectionTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/ToolSelectionTest.kt`
- **Type:** Test
- **Size:** 4798 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `ee1dd029b195b046250b929089f7c0cf3f74835a`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for ToolSelection; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `app/src/test/java/com/codingagent/core/UserMemoryTest.kt`

- **Path:** `app/src/test/java/com/codingagent/core/UserMemoryTest.kt`
- **Type:** Test
- **Size:** 4061 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `a554c0428c6d92d24a406c01551ec28464ef349e`
- **File mode:** `100644`
- **Purpose:** Regression/unit tests for UserMemory; assertions, fixtures, mocks and false-positive paths pending review.
- **Dependencies:** Imports, callers, reverse dependencies and runtime wiring not yet mapped
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `build.gradle.kts`

- **Path:** `build.gradle.kts`
- **Type:** Build/configuration or Android resource
- **Size:** 215 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `955a84c29dda8167317326cb704e793b89897329`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (build.gradle.kts); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `docs/ANTI_YES_MAN.md`

- **Path:** `docs/ANTI_YES_MAN.md`
- **Type:** Documentation
- **Size:** 2034 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `25e7d163918c8bc68737f1be7483c79f33d0cce9`
- **File mode:** `100644`
- **Purpose:** Project documentation (ANTI_YES_MAN.md); implementation claims and workflow accuracy pending reconciliation.
- **Dependencies:** Cross-check claims against implementation and supported workflows
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `docs/OWNER_SYSTEM_PROMPT.md`

- **Path:** `docs/OWNER_SYSTEM_PROMPT.md`
- **Type:** Documentation
- **Size:** 614 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `80c75d6e46745bc4fc790cd84b8b49926144165d`
- **File mode:** `100644`
- **Purpose:** Project documentation (OWNER_SYSTEM_PROMPT.md); implementation claims and workflow accuracy pending reconciliation.
- **Dependencies:** Cross-check claims against implementation and supported workflows
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `docs/TOOL_QUEUE.md`

- **Path:** `docs/TOOL_QUEUE.md`
- **Type:** Documentation
- **Size:** 1013 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `31c4c8075c5da48b7f6968c5b2a61f00495ce7a2`
- **File mode:** `100644`
- **Purpose:** Project documentation (TOOL_QUEUE.md); implementation claims and workflow accuracy pending reconciliation.
- **Dependencies:** Cross-check claims against implementation and supported workflows
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `docs/plans/2026-07-28-deep-research-design.md`

- **Path:** `docs/plans/2026-07-28-deep-research-design.md`
- **Type:** Documentation
- **Size:** 1955 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `e2d18ba10f0d752cfab5e518b9413b0bd373feb9`
- **File mode:** `100644`
- **Purpose:** Project documentation (2026-07-28-deep-research-design.md); implementation claims and workflow accuracy pending reconciliation.
- **Dependencies:** Cross-check claims against implementation and supported workflows
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `gradle.properties`

- **Path:** `gradle.properties`
- **Type:** Build/configuration or Android resource
- **Size:** 109 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `bae9034674974f1b207c165701b2d54450b276c1`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (gradle.properties); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `gradle/wrapper/gradle-wrapper.jar`

- **Path:** `gradle/wrapper/gradle-wrapper.jar`
- **Type:** Binary dependency/tool
- **Size:** 43453 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `e6441136f3d4ba8a0da8d277868979cfbc8ad796`
- **File mode:** `100644`
- **Purpose:** Gradle wrapper JAR; provenance, integrity, version and license require binary-capable inspection.
- **Dependencies:** Binary provenance, integrity and consumer path pending specialized tooling
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `gradle/wrapper/gradle-wrapper.properties`

- **Path:** `gradle/wrapper/gradle-wrapper.properties`
- **Type:** Build/configuration or Android resource
- **Size:** 250 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `09523c0e5490700d1a647ecf95309b5f7f9a8199`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (gradle-wrapper.properties); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `gradlew`

- **Path:** `gradlew`
- **Type:** Automation script
- **Size:** 8714 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `97de990bb40fb164c0a4375b3817be2ad053d68a`
- **File mode:** `100644`
- **Purpose:** Developer/build automation (gradlew); inputs, commands, permissions and failure handling pending review.
- **Dependencies:** Invocation chain, inputs, outputs, permissions and failure paths pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `proguard-rules.pro`

- **Path:** `proguard-rules.pro`
- **Type:** Build/configuration or Android resource
- **Size:** 973 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `89fd26036c86db3d612665ca674726755a0892fa`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (proguard-rules.pro); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `scripts/package-source.sh`

- **Path:** `scripts/package-source.sh`
- **Type:** Automation script
- **Size:** 2457 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `2913c019a23e53713b35e597a843cb9c86df61ad`
- **File mode:** `100644`
- **Purpose:** Developer/build automation (package-source.sh); inputs, commands, permissions and failure handling pending review.
- **Dependencies:** Invocation chain, inputs, outputs, permissions and failure paths pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `scripts/sync_to_github.sh`

- **Path:** `scripts/sync_to_github.sh`
- **Type:** Automation script
- **Size:** 5061 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `19b4b773ffbecee67c6e0c11e13893cb8e2e7a29`
- **File mode:** `100644`
- **Purpose:** Developer/build automation (sync_to_github.sh); inputs, commands, permissions and failure handling pending review.
- **Dependencies:** Invocation chain, inputs, outputs, permissions and failure paths pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress

### `settings.gradle.kts`

- **Path:** `settings.gradle.kts`
- **Type:** Build/configuration or Android resource
- **Size:** 331 bytes
- **Line count:** Pending content retrieval/counting
- **Git blob SHA:** `2174e191ca924024a1b499b46332df596d56701a`
- **File mode:** `100644`
- **Purpose:** Build, CI, Android resource or repository configuration (settings.gradle.kts); effective values and runtime/security impact pending review.
- **Dependencies:** Effective configuration, consumers and overrides pending
- **Review method:** Tree metadata classification only; whole-file inspection pending
- **Findings:** Not determined; not equivalent to “none found”
- **Action:** Review full content, callers/consumers, boundary/error paths; decide fix/retain/remove/replace
- **Verification:** Inventory verified against Git tree at baseline; behavioral verification and content-level findings not yet evidenced.
- **Status:** In progress
