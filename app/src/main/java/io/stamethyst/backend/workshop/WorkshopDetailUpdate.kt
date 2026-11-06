package io.stamethyst.backend.workshop

/** A transient snapshot; only the completed details belong in the reusable detail cache. */
internal data class WorkshopDetailUpdate(
    val details: WorkshopItemDetails,
    val metadataLoading: Boolean,
    val communityLoading: Boolean,
    val dependenciesLoading: Boolean,
    val resolvedDependencyIds: Set<ULong> = emptySet(),
    val metadataErrorMessage: String? = null,
) {
    val descriptionLoading: Boolean
        get() = details.summary.description.isBlank() && (metadataLoading || communityLoading)

    val galleryLoading: Boolean
        get() = details.previewMedia.isEmpty() && details.previewImageUrls.isEmpty() &&
            (metadataLoading || communityLoading)

    val commentContextLoading: Boolean
        get() = details.commentThreadContext == null && details.commentCount == null &&
            (metadataLoading || communityLoading)

    /** Revalidation must not replace already-visible cache content with fresh skeletons. */
    fun retainVisibleCache(cached: WorkshopItemDetails?): WorkshopDetailUpdate {
        if (cached == null || cached.summary.appId != details.summary.appId ||
            cached.summary.publishedFileId != details.summary.publishedFileId
        ) return this
        val keepMetadata = (metadataLoading || metadataErrorMessage != null) && cached.jsonMetadata.isNotBlank()
        val keepGallery = communityLoading && cached.previewMedia.isNotEmpty()
        return copy(
            details = details.copy(
                fileUrl = if (keepMetadata) cached.fileUrl else details.fileUrl,
                hcontentFile = if (keepMetadata) cached.hcontentFile else details.hcontentFile,
                depotId = if (keepMetadata) cached.depotId else details.depotId,
                jsonMetadata = if (keepMetadata) cached.jsonMetadata else details.jsonMetadata,
                previewMedia = if (keepGallery) cached.previewMedia else details.previewMedia,
                previewImageUrls = if (keepGallery) cached.previewImageUrls else details.previewImageUrls,
            ),
            metadataLoading = metadataLoading && !keepMetadata,
            resolvedDependencyIds = resolvedDependencyIds + cached.dependencies.map { it.publishedFileId },
        )
    }
}
