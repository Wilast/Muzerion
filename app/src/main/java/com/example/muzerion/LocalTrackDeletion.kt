package com.example.muzerion

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class TrackDeletionResult(val deleted: Set<String>, val message: String)

/** Called only by the destructive confirmation button. No preview, scan or test invokes this. */
internal suspend fun deleteLocalTracks(context: Context, folder: String?, targets: List<Track>): TrackDeletionResult = withContext(Dispatchers.IO) {
    val deleted = linkedSetOf<String>()
    val errors = mutableListOf<String>()
    val resolver = context.contentResolver
    val granted=context.contentResolver.persistedUriPermissions.filter {it.isReadPermission}.map {it.uri.toString()}.toSet()
    val allowed=AppOptions.folders(folder).filter {it in granted}
    for (track in targets.distinctBy { it.uri }) {
        try {
            val tree=allowed.map(Uri::parse).firstOrNull {candidate->runCatching {
                candidate.authority==track.uri.authority && DocumentsContract.getTreeDocumentId(candidate)==DocumentsContract.getTreeDocumentId(track.uri)
            }.getOrDefault(false)}
            val treeId=tree?.let {DocumentsContract.getTreeDocumentId(it)}
            // Limit the operation to individual documents inside the user's selected tree.
            require(tree != null && treeId != null && DocumentsContract.isDocumentUri(context, track.uri) &&
                DocumentsContract.isTreeUri(track.uri) && track.uri.authority == tree.authority &&
                DocumentsContract.getTreeDocumentId(track.uri) == treeId) { tr(R.string.msg_699ec24ecb) }
            val columns = arrayOf(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_MIME_TYPE)
            resolver.query(track.uri, columns, null, null, null)?.use { cursor ->
                check(cursor.moveToFirst()) { tr(R.string.msg_a1d61d40a7) }
                check(cursor.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR) { tr(R.string.msg_426432a821) }
                check(cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0) { tr(R.string.msg_0aed5dccb9) }
            } ?: error(tr(R.string.msg_3640790d67))
            check(DocumentsContract.deleteDocument(resolver, track.uri)) { tr(R.string.msg_09ab712810) }
            deleted += track.uri.toString()
        } catch (_: SecurityException) {
            errors += tr(R.string.msg_819c006642, track.title)
        } catch (e: Exception) {
            errors += "${track.title}: ${e.message ?: tr(R.string.msg_83c837dcf9)}"
        }
    }
    var cacheWarning = false
    if (folder != null && deleted.isNotEmpty()) {
        try { allowed.forEach {forgetDeletedTracks(context, it, deleted)} } catch (_: Exception) { cacheWarning = true }
    }
    val message = buildString {
        append(tr(R.string.msg_799bd27248, deleted.size, targets.size))
        if (errors.isNotEmpty()) append("\n${errors.first()}${if (errors.size > 1) tr(R.string.msg_4144270885, errors.size - 1) else ""}")
        if (cacheWarning) append(tr(R.string.msg_6b7dae6063))
    }
    TrackDeletionResult(deleted, message)
}
