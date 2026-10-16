package com.jmreader.data.local

import com.jmreader.data.dto.ComicBriefDto

/** Fill missing metadata against the current stored item, without overwriting concurrent edits. */
internal fun mergeComicMetadata(base: ComicBriefDto, incoming: ComicBriefDto): ComicBriefDto {
    if (base.id != incoming.id || base === incoming) return base
    return base.copy(
        name = base.name.takeIf { it.isNotBlank() } ?: incoming.name,
        author = base.author?.takeIf { it.isNotBlank() } ?: incoming.author?.takeIf { it.isNotBlank() },
        tags = base.tags.ifEmpty { incoming.tags },
        cover = base.cover?.takeIf { it.isNotBlank() } ?: incoming.cover?.takeIf { it.isNotBlank() },
        likes = base.likes?.takeIf { it.isNotBlank() } ?: incoming.likes?.takeIf { it.isNotBlank() },
        views = base.views?.takeIf { it.isNotBlank() } ?: incoming.views?.takeIf { it.isNotBlank() },
        page_count = base.page_count ?: incoming.page_count,
        publishTime = base.publishTime?.takeIf { it.isNotBlank() } ?: incoming.publishTime?.takeIf { it.isNotBlank() },
    )
}

internal fun ComicBriefDto.needsMetadataEnrichment(): Boolean =
    name.isBlank() || tags.isEmpty() || author.isNullOrBlank() || publishTime.isNullOrBlank()
