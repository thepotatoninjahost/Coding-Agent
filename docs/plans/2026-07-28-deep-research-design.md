# Deep research and durable learning

## Decision

Research means collecting and retaining evidence, not displaying one search snippet. A coding research session will expand a request into five focused query lanes: primary documentation, implementation examples, community solutions, standards/papers, and failure modes. It will collect up to 50 distinct URLs across multiple domains, fetch each page with bounded concurrency, extract readable text and code blocks, and persist a provenance-bearing report under the project’s `.coding-agent/research/` directory.

The report is the learning artifact. Each fetched source records its URL, title, domain, query lane, status, word count, extracted text, and code examples. The session also stores normalized searchable chunks, so later tasks can retrieve prior research without repeating the crawl. A session is considered useful only when it has enough successful sources and extracted content; snippets alone do not satisfy the gate.

## Runtime behavior

`WebResearchProvider.deepResearch()` is the explicit deep-research boundary. `CodingAgentRuntime` calls it before non-trivial execution, saves the report, and records source/chunk counts in the task journal. `AutonomousAgent` performs the same deep phase before model turns and injects the bounded evidence context into the model prompt. Existing `search()` remains for quick lookup and UI previews.

The Android research surface starts a 50-source session, shows progress/result counts, lists the learned sources, and reports failures separately. Network work runs off the main thread. Source extraction is capped per page and bounded by a fixed worker pool. Existing transactional workspace behavior remains unchanged.

## Verification

Add pure tests for query-lane expansion, HTML extraction, source deduplication, report persistence/search, and the runtime’s research gate. Run unit tests, lint, and debug APK assembly after implementation.
