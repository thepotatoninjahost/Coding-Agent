# Deep research and durable learning

**Status: implementation note. This document describes the current source structure at the time it was reviewed; it is not a promise of exhaustive research or complete end-to-end reliability.** Update it whenever the research pipeline changes.

## Current implementation map

- `WebResearchProvider` defines the search boundary.
- `CompositeWebResearchProvider` combines source-specific providers and applies source acceptability, query relevance, deduplication, and ranking rules. Current providers include GitHub, Stack Overflow, public Searx instances, MDN, and DuckDuckGo.
- `DeepResearchProvider` defines the deep-research API, including mode, target source count, cancellation, and progress callbacks.
- `DurableDeepResearchProvider` coordinates research sessions. Broad mode delegates to `PersonalResearchProvider`; specialized modes use the multi-lane research path.
- `ArticleExtractor` contains URL validation and page-text extraction helpers.
- `ResearchBriefBuilder` creates bounded evidence text from a research session.
- Research sessions are persisted by the research provider under its configured research root.
- `AgentToolDispatch.researchWeb()` calls the research provider and returns a bounded evidence brief to the agent. The tool path currently clamps its requested source count to 1–12; other entry points may use different limits.
- The Compose UI has a Research surface that starts a research session and displays progress/results.

These are code-path descriptions, not a guarantee that all public search services are available, that a requested number of sources will be found, or that every fetched page will be extracted successfully.

## Evidence and limitations

Research results can be incomplete, stale, duplicated, blocked, or irrelevant despite filtering. Search providers may be down or rate-limited. Extracted page text can omit context, dynamic content, tables, or code. Verify important claims against the original source pages and dates; do not treat the brief as a substitute for the sources.

The bundled knowledge text and local knowledge index are separate from live web research. An indexed document is not necessarily current.

## Verification

Relevant tests include `DeepResearchTest` and `ResearchModeTest`. They cover selected query-lane, extraction, and research-mode behavior; they do not establish exhaustive live-web coverage or provider availability. Use CI for the exact commit and perform an end-to-end run against the intended network environment before relying on research output.
