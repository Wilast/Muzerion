package com.example.muzerion

import java.util.Locale

internal fun collectionIdentity(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

internal fun artistNames(value: String): List<String> = value.split(Regex("\\s*(?:,|;|\\s+feat\\.?\\s+|\\s+featuring\\s+)\\s*", RegexOption.IGNORE_CASE))
    .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::collectionIdentity)
    .ifEmpty { listOf(tr(R.string.msg_42a1e6c35f)) }

// Length prefixes prevent collisions even when tags contain punctuation or delimiters.
internal fun albumIdentity(album: String, artist: String): String {
    val a = collectionIdentity(album)
    val b = collectionIdentity(artist)
    return "${a.length}:${a}${b.length}:${b}"
}
