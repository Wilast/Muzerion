package com.example.muzerion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject

data class Track(
    val name: String,
    val uri: Uri,
    val title: String = name.substringBeforeLast('.'),
    val artist: String = "",
    val album: String = "",
    val artworkUri: Uri? = null,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val addedAt: Long = 0L,
    val albumArtist: String = "",
    val trackNumber: Int = 0,
    val discNumber: Int = 0
)

data class TrackListState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val error: String? = null
)

private data class LibraryEntry(
    val track: Track,
    val modified: Long,
    val size: Long,
    val metadataVersion: Int = 3
)

// Fast startup migration for installations that previously saved only one track.
fun readCachedMusicTracks(context: Context, folder: String): List<Track> {
    return try {
        readLibraryCache(AtomicFile(File(context.filesDir, "library_${hashText(folder)}.json")))
            .orEmpty().map { it.track }
    } catch (_: Exception) { emptyList() }
}

private val librarySnapshots = ConcurrentHashMap<String, TrackListState>()
fun rememberedLibrarySnapshot(folder: String): TrackListState = librarySnapshots[folder] ?: TrackListState()

fun observeMusicLibrary(
    context: Context,
    folderUri: Uri,
    deferScan: Boolean = true
): Flow<TrackListState> = flow {
    val appContext = context.applicationContext

    val cacheFile = AtomicFile(
        File(
            appContext.filesDir,
            "library_${hashText(folderUri.toString())}.json"
        )
    )

    val cachedEntries = readLibraryCache(cacheFile)

    val cachedByUri = cachedEntries.orEmpty().associateBy {
        it.track.uri.toString()
    }

    var visibleTracks = cachedEntries.orEmpty().map {
        it.track
    }

    if (cachedEntries != null) {
        emit(
            TrackListState(
                loading = false,
                refreshing = false,
                tracks = visibleTracks
            ).also { librarySnapshots[folderUri.toString()] = it }
        )
        // Let cached rows and their thumbnails draw before the automatic provider scan.
        if (deferScan) delay(750)
    }

    try {
        val files = scanMusicFolder(appContext, folderUri)
        val now = System.currentTimeMillis()

        currentCoroutineContext().ensureActive()

        val entries = files.map { file ->
            val cached = cachedByUri[file.track.uri.toString()]

            if (cached != null && isCacheValid(cached, file)) {
                cached
            } else {
                val addedAt = cached?.track?.addedAt
                    ?.takeIf { it > 0L }
                    ?: if (cachedEntries != null) {
                        now
                    } else {
                        file.modified.takeIf { it > 0L } ?: now
                    }

                file.copy(
                    track = file.track.copy(
                        addedAt = addedAt
                    )
                )
            }
        }.toMutableList()

        visibleTracks = entries.map { it.track }

        emit(
            TrackListState(
                loading = false,
                refreshing = true,
                tracks = visibleTracks
            )
        )

        var changes = 0

        for (index in files.indices) {
            currentCoroutineContext().ensureActive()

            val file = files[index]
            val cached = cachedByUri[file.track.uri.toString()]

            if (cached != null && isCacheValid(cached, file)) {
                continue
            }

            val entry = entries[index]

            entries[index] = entry.copy(
                track = readMetadata(appContext, entry)
            )

            changes++

            if (changes >= 8) {
                visibleTracks = entries.map { it.track }

                emit(
                    TrackListState(
                        loading = false,
                        refreshing = true,
                        tracks = visibleTracks
                    )
                )

                changes = 0
            }
        }

        currentCoroutineContext().ensureActive()

        val sortedEntries = entries.sortedBy {
            it.track.title.lowercase(Locale.ROOT)
        }

        visibleTracks = sortedEntries.map { it.track }

        var cacheError: String? = null

        try {
            writeLibraryCache(cacheFile, sortedEntries)
        } catch (_: IOException) {
            cacheError =
                tr(R.string.msg_fbbc29b3e6)
        }

        emit(
            TrackListState(
                loading = false,
                tracks = visibleTracks,
                error = cacheError
            ).also { librarySnapshots[folderUri.toString()] = it }
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: SecurityException) {
        emit(
            TrackListState(
                loading = false,
                tracks = visibleTracks,
                error = tr(R.string.msg_34b77fbd15)
            )
        )
    } catch (_: IOException) {
        emit(
            TrackListState(
                loading = false,
                tracks = visibleTracks,
                error = tr(R.string.msg_a469d23560)
            )
        )
    }
}.flowOn(Dispatchers.IO)

private fun isCacheValid(
    cached: LibraryEntry,
    file: LibraryEntry
): Boolean {
    val sameFile =
        cached.track.name == file.track.name &&
                cached.modified == file.modified &&
                cached.size == file.size

    val hasFingerprint =
        file.modified > 0L && file.size >= 0L

    val coverExists = cached.track.artworkUri?.let { uri ->
        uri.scheme == "file" &&
                uri.path?.let { File(it).isFile } == true
    } ?: true

    return cached.metadataVersion >= 3 &&
            sameFile &&
            hasFingerprint &&
            coverExists
}

private suspend fun scanMusicFolder(
    context: Context,
    folderUri: Uri
): List<LibraryEntry> {
    val childrenUri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            folderUri,
            DocumentsContract.getTreeDocumentId(folderUri)
        )

    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE
    )

    val result = mutableListOf<LibraryEntry>()
    val extensions = setOf("mp3", "flac", "m4a")

    val cursor = context.contentResolver.query(
        childrenUri,
        columns,
        null,
        null,
        null
    ) ?: throw IOException(tr(R.string.msg_2be5de6157))

    cursor.use {
        val idIndex = it.getColumnIndexOrThrow(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID
        )
        val nameIndex = it.getColumnIndexOrThrow(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        val mimeIndex = it.getColumnIndexOrThrow(
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val modifiedIndex = it.getColumnIndex(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val sizeIndex = it.getColumnIndex(
            DocumentsContract.Document.COLUMN_SIZE
        )

        while (it.moveToNext()) {
            currentCoroutineContext().ensureActive()

            val name = it.getString(nameIndex) ?: continue

            if (
                it.getString(mimeIndex) ==
                DocumentsContract.Document.MIME_TYPE_DIR
            ) {
                continue
            }

            val extension = name
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)

            if (extension !in extensions) continue

            val uri = DocumentsContract.buildDocumentUriUsingTree(
                folderUri,
                it.getString(idIndex)
            )

            val modified = if (
                modifiedIndex >= 0 && !it.isNull(modifiedIndex)
            ) {
                it.getLong(modifiedIndex)
            } else {
                -1L
            }

            val size = if (
                sizeIndex >= 0 && !it.isNull(sizeIndex)
            ) {
                it.getLong(sizeIndex)
            } else {
                -1L
            }

            result.add(
                LibraryEntry(
                    track = Track(
                        name = name,
                        uri = uri,
                        sizeBytes = size.coerceAtLeast(0L),
                        addedAt = modified.coerceAtLeast(0L)
                    ),
                    modified = modified,
                    size = size
                )
            )
        }
    }

    return result.sortedBy {
        it.track.name.lowercase(Locale.ROOT)
    }
}

private fun readMetadata(
    context: Context,
    entry: LibraryEntry
): Track {
    val track = entry.track
    val retriever = MediaMetadataRetriever()

    try {
        retriever.setDataSource(context, track.uri)

        val title = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_TITLE
        )?.trim()?.takeIf { it.isNotEmpty() } ?: track.title

        val artist = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_ARTIST
        )?.trim()?.takeIf { it.isNotEmpty() }
            ?: retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST
            )?.trim().orEmpty()

        val album = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_ALBUM
        )?.trim().orEmpty()

        val duration = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

        val artworkUri = try {
            retriever.embeddedPicture?.let { bytes ->
                saveCover(context, entry, bytes)
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }

        return track.copy(
            title = title,
            artist = artist,
            album = album,
            albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim().orEmpty(),
            trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
            discNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
            artworkUri = artworkUri,
            durationMs = duration
        )
    } catch (_: IOException) {
        return track
    } catch (_: RuntimeException) {
        return track
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
            // Продолжаем обработку остальных файлов.
        }
    }
}

private fun saveCover(
    context: Context,
    entry: LibraryEntry,
    bytes: ByteArray
): Uri? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size, bounds
    )

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    var sample = 1

    while (
        bounds.outWidth / sample > 1024 ||
        bounds.outHeight / sample > 1024
    ) {
        sample *= 2
    }

    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
        }
    ) ?: return null

    var output = decoded

    try {
        val largestSide = maxOf(decoded.width, decoded.height)

        if (largestSide > 512) {
            val scale = 512f / largestSide

            output = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }

        val directory = File(context.cacheDir, "muzerion_covers")

        if (!directory.exists() && !directory.mkdirs()) {
            return null
        }

        val key = hashText(
            "${entry.track.uri}|${entry.modified}|${entry.size}"
        )

        val file = File(directory, "${key}.jpg")

        val success = file.outputStream().use { stream ->
            output.compress(
                Bitmap.CompressFormat.JPEG,
                90,
                stream
            )
        }

        return if (success) Uri.fromFile(file) else null
    } finally {
        if (output !== decoded) output.recycle()
        decoded.recycle()
    }
}

private fun readLibraryCache(
    file: AtomicFile
): List<LibraryEntry>? {
    return try {
        val root = JSONObject(
            file.readFully().toString(Charsets.UTF_8)
        )

        val version = root.optInt("version")

        if (version !in 1..3) return null

        val array = root.getJSONArray("tracks")
        val result = ArrayList<LibraryEntry>(array.length())

        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val artwork = item.optString("artwork", "")
            val modified = item.optLong("modified", -1L)
            val size = item.optLong("size", -1L)

            result.add(
                LibraryEntry(
                    track = Track(
                        name = item.getString("name"),
                        uri = Uri.parse(item.getString("uri")),
                        title = item.getString("title"),
                        artist = item.optString("artist", ""),
                        album = item.optString("album", ""),
                        albumArtist = item.optString("album_artist", ""),
                        trackNumber = item.optInt("track_number", 0),
                        discNumber = item.optInt("disc_number", 0),
                        artworkUri = artwork
                            .takeIf { it.isNotBlank() }
                            ?.let { Uri.parse(it) },
                        durationMs = item.optLong("duration", 0L),
                        sizeBytes = size.coerceAtLeast(0L),
                        addedAt = item.optLong(
                            "added_at",
                            modified.coerceAtLeast(0L)
                        )
                    ),
                    modified = modified,
                    size = size,
                    metadataVersion = version
                )
            )
        }

        result
    } catch (_: Exception) {
        null
    }
}

private fun writeLibraryCache(
    file: AtomicFile,
    entries: List<LibraryEntry>
) {
    val array = JSONArray()

    for (entry in entries) {
        val track = entry.track

        array.put(
            JSONObject()
                .put("name", track.name)
                .put("uri", track.uri.toString())
                .put("title", track.title)
                .put("artist", track.artist)
                .put("album", track.album)
                .put("album_artist", track.albumArtist)
                .put("track_number", track.trackNumber)
                .put("disc_number", track.discNumber)
                .put("artwork", track.artworkUri?.toString() ?: "")
                .put("duration", track.durationMs)
                .put("added_at", track.addedAt)
                .put("modified", entry.modified)
                .put("size", entry.size)
        )
    }

    val root = JSONObject()
        .put("version", 3)
        .put("tracks", array)

    val stream = file.startWrite()

    try {
        stream.write(
            root.toString().toByteArray(Charsets.UTF_8)
        )

        file.finishWrite(stream)
    } catch (error: Exception) {
        file.failWrite(stream)
        throw error
    }
}

private fun hashText(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") {
            (it.toInt() and 0xff)
                .toString(16)
                .padStart(2, '0')
        }
}
// Keep successful deletions out of the startup snapshot and persisted metadata cache.
internal fun forgetDeletedTracks(context: Context, folder: String, deleted: Set<String>) {
    val file = AtomicFile(File(context.filesDir, "library_${hashText(folder)}.json"))
    readLibraryCache(file)?.let { entries ->
        writeLibraryCache(file, entries.filterNot { it.track.uri.toString() in deleted })
    }
    librarySnapshots.computeIfPresent(folder) { _, state ->
        state.copy(tracks = state.tracks.filterNot { it.uri.toString() in deleted })
    }
}
