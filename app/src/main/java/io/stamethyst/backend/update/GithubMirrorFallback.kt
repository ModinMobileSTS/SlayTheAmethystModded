package io.stamethyst.backend.update

import java.io.IOException

data class GithubMirrorFallbackFailure(
    val source: UpdateSource,
    val error: Throwable
)

data class GithubMirrorFallbackSuccess<T>(
    val source: UpdateSource,
    val value: T
)

class GithubMirrorFallbackException(
    val failures: List<GithubMirrorFallbackFailure>
) : IOException(
    failures.joinToString(separator = " | ") { failure ->
        "${failure.source.displayName}: ${GithubMirrorFallback.summarizeSingleError(failure.error)}"
    }.ifBlank { "No GitHub mirror fallback candidates succeeded." },
    failures.lastOrNull()?.error
)

object GithubMirrorFallback {
    inline fun <T> run(
        preferredUserSource: UpdateSource,
        block: (UpdateSource) -> T
    ): GithubMirrorFallbackSuccess<T> {
        return run(UpdateSource.githubResourceFallbackCandidates(preferredUserSource), block)
    }

    inline fun <T> run(
        sources: List<UpdateSource>,
        block: (UpdateSource) -> T
    ): GithubMirrorFallbackSuccess<T> {
        val failures = ArrayList<GithubMirrorFallbackFailure>()
        sources.forEach { source ->
            try {
                return GithubMirrorFallbackSuccess(
                    source = source,
                    value = block(source)
                )
            } catch (error: Throwable) {
                failures += GithubMirrorFallbackFailure(
                    source = source,
                    error = error
                )
            }
        }
        throw GithubMirrorFallbackException(failures)
    }

    fun summarize(error: Throwable): String {
        return when (error) {
            is GithubMirrorFallbackException -> error.message
            else -> summarizeSingleError(error)
        }.orEmpty()
    }

    internal fun summarizeSingleError(error: Throwable): String {
        return error.message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error.javaClass.simpleName
    }
}
