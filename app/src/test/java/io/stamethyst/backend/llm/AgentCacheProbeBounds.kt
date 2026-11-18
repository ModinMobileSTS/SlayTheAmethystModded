package io.stamethyst.backend.llm

/** Test report only: unknown cache reads are an interval, never an assumed zero or a hidden cold cost. */
internal data class AgentCacheProbeBounds(val lower: Double?, val upper: Double?) {
    companion object {
        fun from(usages: List<AgentCacheUsage>): AgentCacheProbeBounds {
            if (usages.any { it.input == null }) return AgentCacheProbeBounds(null, null)
            val input = usages.sumOf { requireNotNull(it.input) }
            if (input == 0L) return AgentCacheProbeBounds(null, null)
            val confirmedRead = usages.sumOf { it.read ?: 0 }
            val unknownInput = usages.filter { it.read == null }.sumOf { requireNotNull(it.input) }
            return AgentCacheProbeBounds(confirmedRead.toDouble() / input,
                (confirmedRead + unknownInput).toDouble() / input)
        }
    }
}
