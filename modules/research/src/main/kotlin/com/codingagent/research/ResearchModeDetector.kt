package com.codingagent.research

object ResearchModeDetector {
    fun detect(query: String): ResearchMode {
        val normalized = query.lowercase()
        return when {
            containsAny(normalized, "benchmark", "latency", "throughput", "performance", "measure", "evaluation", "compare") -> ResearchMode.EMPIRICAL
            containsAny(normalized, "formal", "theory", "theoretical", "model", "rfc", "specification", "proof", "semantics") -> ResearchMode.THEORETICAL
            containsAny(normalized, "experimental", "experiment", "novel", "unconventional", "prototype", "research-style", "creative hack") -> ResearchMode.EXPERIMENTAL
            else -> ResearchMode.BROAD
        }
    }

    private fun containsAny(value: String, vararg terms: String): Boolean = terms.any(value::contains)
}
