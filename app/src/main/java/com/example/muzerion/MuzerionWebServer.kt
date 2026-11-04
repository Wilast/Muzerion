package com.example.muzerion

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MUZERION_WEB_PORT = 8080

private const val LOCAL_NETWORK_PERMISSION =
    "android.permission.ACCESS_LOCAL_NETWORK"

private data class WebAudioFile(
    val uri: Uri,
    val mimeType: String,
    val size: Long
)

private data class WebLibrarySnapshot(
    val revision: String,
    val json: String,
    val covers: Map<String, File>,
    val audioFiles: Map<String, WebAudioFile>
)

private data class WebByteRange(
    val start: Long,
    val end: Long
)

private class InvalidWebRange : Exception()

class MuzerionWebServer(
    context: Context
) : NanoHTTPD(MUZERION_WEB_PORT) {

    private val appContext = context.applicationContext

    val accessKey: String = UUID.randomUUID()
        .toString()
        .replace("-", "")

    private var cachedSnapshot: WebLibrarySnapshot? = null

    override fun serve(session: IHTTPSession): Response {
        if (
            session.method != Method.GET &&
            session.method != Method.HEAD
        ) {
            return finishResponse(
                textResponse(
                    Response.Status.METHOD_NOT_ALLOWED,
                    tr(R.string.msg_27b3814126)
                )
            )
        }

        if (session.uri == "/health") {
            return finishResponse(
                textResponse(
                    Response.Status.OK,
                    "Muzerion OK"
                )
            )
        }

        val suppliedKey = session.parameters["key"]
            ?.firstOrNull()
            .orEmpty()

        val authorized = MessageDigest.isEqual(
            suppliedKey.toByteArray(Charsets.UTF_8),
            accessKey.toByteArray(Charsets.UTF_8)
        )

        if (!authorized) {
            return finishResponse(
                textResponse(
                    Response.Status.FORBIDDEN,
                    tr(R.string.msg_0f224cc57e)
                )
            )
        }

        return try {
            val response = when {
                session.uri == "/" -> {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html; charset=utf-8",
                        libraryPage()
                    )
                }

                session.uri == "/api/library" -> {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json; charset=utf-8",
                        readLibrarySnapshot().json
                    )
                }

                session.uri.startsWith("/api/cover/") -> {
                    serveCover(
                        session.uri.removePrefix("/api/cover/")
                    )
                }

                session.uri.startsWith("/api/audio/") -> {
                    serveAudio(
                        session = session,
                        id = session.uri.removePrefix("/api/audio/")
                    )
                }

                else -> {
                    textResponse(
                        Response.Status.NOT_FOUND,
                        tr(R.string.msg_b8a9604777)
                    )
                }
            }

            finishResponse(response)
        } catch (_: SecurityException) {
            finishResponse(
                textResponse(
                    Response.Status.FORBIDDEN,
                    tr(R.string.msg_f070401d4a)
                )
            )
        } catch (_: Exception) {
            finishResponse(
                textResponse(
                    Response.Status.INTERNAL_ERROR,
                    tr(R.string.msg_edcfe025bf)
                )
            )
        }
    }

    private fun textResponse(
        status: Response.Status,
        text: String
    ): Response {
        return newFixedLengthResponse(
            status,
            "text/plain; charset=utf-8",
            text
        )
    }

    private fun finishResponse(
        response: Response
    ): Response {
        response.addHeader(
            "Cache-Control",
            "no-store"
        )

        response.addHeader(
            "X-Content-Type-Options",
            "nosniff"
        )

        response.addHeader(
            "Referrer-Policy",
            "no-referrer"
        )

        return response
    }

    private fun serveCover(
        id: String
    ): Response {
        val file = readLibrarySnapshot().covers[id]

        if (file == null || !file.isFile) {
            return textResponse(
                Response.Status.NOT_FOUND,
                tr(R.string.msg_726226201e)
            )
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            file.inputStream(),
            file.length()
        )
    }

    private fun serveAudio(
        session: IHTTPSession,
        id: String
    ): Response {
        val track = readLibrarySnapshot().audioFiles[id]
            ?: return textResponse(
                Response.Status.NOT_FOUND,
                tr(R.string.msg_072ef7bcba)
            )

        val descriptor = appContext.contentResolver
            .openAssetFileDescriptor(track.uri, "r")
            ?: return textResponse(
                Response.Status.NOT_FOUND,
                tr(R.string.msg_83f5f2dee8)
            )

        var stream: InputStream? = null

        try {
            val descriptorLength = descriptor.length

            val length = when {
                descriptorLength >= 0L -> descriptorLength
                track.size > 0L -> track.size
                else -> -1L
            }

            if (session.method == Method.HEAD) {
                descriptor.close()

                return if (length >= 0L) {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        track.mimeType,
                        ByteArrayInputStream(ByteArray(0)),
                        length
                    ).apply {
                        addHeader(
                            "Accept-Ranges",
                            "bytes"
                        )
                    }
                } else {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        track.mimeType,
                        ""
                    ).apply {
                        addHeader(
                            "Accept-Ranges",
                            "none"
                        )
                    }
                }
            }

            if (length < 0L) {
                val input = descriptor.createInputStream()
                stream = input

                return newChunkedResponse(
                    Response.Status.OK,
                    track.mimeType,
                    input
                ).apply {
                    addHeader(
                        "Accept-Ranges",
                        "none"
                    )
                }
            }

            val rangeHeader = session.headers["range"]

            val range = if (
                rangeHeader != null &&
                session.headers["if-range"] == null
            ) {
                try {
                    parseRange(
                        header = rangeHeader,
                        length = length
                    )
                } catch (_: InvalidWebRange) {
                    descriptor.close()

                    return textResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE,
                        tr(R.string.msg_28158028e1)
                    ).apply {
                        addHeader(
                            "Content-Range",
                            "bytes */${length}"
                        )

                        addHeader(
                            "Accept-Ranges",
                            "bytes"
                        )
                    }
                }
            } else {
                null
            }

            val input = descriptor.createInputStream()
            stream = input

            val start = range?.start ?: 0L
            val end = range?.end ?: (length - 1L)

            val responseLength = if (length == 0L) {
                0L
            } else {
                end - start + 1L
            }

            skipFully(
                input = input,
                bytes = start
            )

            return newFixedLengthResponse(
                if (range == null) {
                    Response.Status.OK
                } else {
                    Response.Status.PARTIAL_CONTENT
                },
                track.mimeType,
                input,
                responseLength
            ).apply {
                addHeader(
                    "Accept-Ranges",
                    "bytes"
                )

                if (range != null) {
                    addHeader(
                        "Content-Range",
                        "bytes ${start}-${end}/${length}"
                    )
                }
            }
        } catch (failure: Exception) {
            try {
                stream?.close()
            } catch (_: Exception) {
            }

            try {
                descriptor.close()
            } catch (_: Exception) {
            }

            throw failure
        }
    }

    private fun parseRange(
        header: String,
        length: Long
    ): WebByteRange? {
        if (header.contains(',')) {
            return null
        }

        val match = Regex(
            """bytes=(\d*)-(\d*)""",
            RegexOption.IGNORE_CASE
        ).matchEntire(header.trim()) ?: return null

        val startText = match.groupValues[1]
        val endText = match.groupValues[2]

        if (
            startText.isEmpty() &&
            endText.isEmpty()
        ) {
            return null
        }

        if (length <= 0L) {
            throw InvalidWebRange()
        }

        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull()
                ?: throw InvalidWebRange()

            if (suffix <= 0L) {
                throw InvalidWebRange()
            }

            return WebByteRange(
                start = (length - suffix).coerceAtLeast(0L),
                end = length - 1L
            )
        }

        val start = startText.toLongOrNull()
            ?: throw InvalidWebRange()

        val requestedEnd = if (endText.isEmpty()) {
            length - 1L
        } else {
            endText.toLongOrNull()
                ?: throw InvalidWebRange()
        }

        if (
            start >= length ||
            requestedEnd < start
        ) {
            throw InvalidWebRange()
        }

        return WebByteRange(
            start = start,
            end = requestedEnd.coerceAtMost(length - 1L)
        )
    }

    private fun skipFully(
        input: InputStream,
        bytes: Long
    ) {
        var remaining = bytes

        while (remaining > 0L) {
            val skipped = input.skip(remaining)

            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() == -1) {
                    throw IOException(
                        tr(R.string.msg_054256fed9)
                    )
                }

                remaining--
            }
        }
    }

    @Synchronized
    private fun readLibrarySnapshot(): WebLibrarySnapshot {
        val preferences = appContext.getSharedPreferences(
            "muzerion_settings",
            Context.MODE_PRIVATE
        )

        val folder = preferences.getString(
            "music_folder",
            null
        ) ?: return pendingSnapshot(
            tr(R.string.msg_809205c551)
        )

        val folderHash = webHash(folder)

        val libraryFile = File(
            appContext.filesDir,
            "library_${folderHash}.json"
        )

        if (!libraryFile.isFile) {
            return pendingSnapshot(
                tr(R.string.msg_e820e8426e)
            )
        }

        val revision =
            "${folderHash}:${libraryFile.lastModified()}:${libraryFile.length()}"

        cachedSnapshot?.let { snapshot ->
            if (snapshot.revision == revision) {
                return snapshot
            }
        }

        return try {
            val source = JSONObject(
                libraryFile.readText(Charsets.UTF_8)
            )

            val sourceTracks = source.getJSONArray("tracks")
            val tracks = JSONArray()

            val covers = mutableMapOf<String, File>()
            val audioFiles = mutableMapOf<String, WebAudioFile>()

            val coversDirectory = File(
                appContext.cacheDir,
                "muzerion_covers"
            ).canonicalFile

            var totalBytes = 0L

            for (index in 0 until sourceTracks.length()) {
                val item = sourceTracks.getJSONObject(index)
                val uri = Uri.parse(item.getString("uri"))

                if (uri.scheme != "content") {
                    continue
                }

                val id = webHash(uri.toString())

                val name = item.optString(
                    "name",
                    tr(R.string.msg_32b74a3c47)
                )

                val title = item.optString(
                    "title",
                    ""
                ).ifBlank {
                    name.substringBeforeLast('.')
                }

                val size = item.optLong(
                    "size",
                    0L
                ).coerceAtLeast(0L)

                val extension = name
                    .substringAfterLast('.', "")
                    .lowercase(Locale.ROOT)

                val mimeType = when (extension) {
                    "mp3" -> "audio/mpeg"
                    "flac" -> "audio/flac"
                    "m4a", "mp4" -> "audio/mp4"
                    "aac" -> "audio/aac"
                    "ogg", "oga", "opus" -> "audio/ogg"
                    "wav" -> "audio/wav"
                    "3gp", "3gpp" -> "audio/3gpp"

                    else -> {
                        appContext.contentResolver.getType(uri)
                            ?: "application/octet-stream"
                    }
                }

                audioFiles[id] = WebAudioFile(
                    uri = uri,
                    mimeType = mimeType,
                    size = size
                )

                totalBytes += size

                val artwork = item.optString(
                    "artwork",
                    ""
                )

                val cover = try {
                    val parsed = Uri.parse(artwork)

                    if (parsed.scheme != "file") {
                        null
                    } else {
                        parsed.path?.let { path ->
                            File(path).canonicalFile.takeIf {
                                it.parentFile == coversDirectory &&
                                        it.isFile
                            }
                        }
                    }
                } catch (_: Exception) {
                    null
                }

                if (cover != null) {
                    covers[id] = cover
                }

                tracks.put(
                    JSONObject()
                        .put("id", id)
                        .put("title", title)
                        .put(
                            "artist",
                            item.optString("artist", "")
                        )
                        .put(
                            "album",
                            item.optString("album", "")
                        )
                        .put(
                            "duration",
                            item.optLong("duration", 0L)
                        )
                        .put("size", size)
                        .put(
                            "addedAt",
                            item.optLong(
                                "added_at",
                                item.optLong("modified", 0L)
                            )
                        )
                        .put(
                            "format",
                            extension.uppercase(Locale.ROOT)
                        )
                        .put(
                            "hasCover",
                            cover != null
                        )
                )
            }

            val payload = JSONObject()
                .put("revision", revision)
                .put("loading", false)
                .put("message", "")
                .put("count", tracks.length())
                .put("totalBytes", totalBytes)
                .put("tracks", tracks)

            WebLibrarySnapshot(
                revision = revision,
                json = payload.toString(),
                covers = covers,
                audioFiles = audioFiles
            ).also {
                cachedSnapshot = it
            }
        } catch (_: Exception) {
            cachedSnapshot
                ?.takeIf {
                    it.revision.startsWith("${folderHash}:")
                }
                ?: pendingSnapshot(
                    tr(R.string.msg_ff74edc85e)
                )
        }
    }

    private fun pendingSnapshot(
        message: String
    ): WebLibrarySnapshot {
        return WebLibrarySnapshot(
            revision = "pending",

            json = JSONObject()
                .put("revision", "pending")
                .put("loading", true)
                .put("message", message)
                .put("count", 0)
                .put("totalBytes", 0)
                .put("tracks", JSONArray())
                .toString(),

            covers = emptyMap(),
            audioFiles = emptyMap()
        )
    }

    private fun libraryPage(): String = """
        <!DOCTYPE html>
        <html lang="ru">
        <head>
            <meta charset="utf-8">
            <meta name="viewport"
                  content="width=device-width, initial-scale=1">
            <title>Muzerion</title>

            <style>
                * {
                    box-sizing: border-box;
                }

                :root {
                    color-scheme: dark;
                    --bg: #101014;
                    --panel: #1d1c24;
                    --accent: #b9a0ff;
                    --text: #f4f1fa;
                    --muted: #96939f;
                }

                body {
                    margin: 0;
                    min-height: 100vh;
                    background:
                        radial-gradient(
                            ellipse at top left,
                            #2b203b 0,
                            transparent 55%
                        ),
                        var(--bg);
                    color: var(--text);
                    font-family: system-ui, sans-serif;
                }

                main {
                    width: min(100%, 1100px);
                    margin: auto;
                    padding: 30px 24px 210px;
                }

                header {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 16px;
                    margin-bottom: 32px;
                }

                .brand {
                    color: var(--accent);
                    font-size: 32px;
                    font-weight: 800;
                    letter-spacing: -1px;
                }

                .badge {
                    border: 1px solid #45374f;
                    border-radius: 100px;
                    padding: 9px 15px;
                    color: #c8b9de;
                    font-size: 13px;
                }

                h1 {
                    margin: 0 0 8px;
                    font-size: 32px;
                }

                .muted {
                    color: var(--muted);
                    font-size: 14px;
                }

                .toolbar {
                    display: flex;
                    gap: 12px;
                    margin-top: 24px;
                }

                input, select {
                    min-height: 50px;
                    border: 1px solid #39313f;
                    border-radius: 16px;
                    background: var(--panel);
                    color: var(--text);
                    font: inherit;
                }

                input {
                    flex: 1;
                    min-width: 0;
                    padding: 0 18px;
                }

                select {
                    padding: 0 14px;
                    max-width: 210px;
                }

                :focus-visible {
                    outline: 2px solid var(--accent);
                    outline-offset: 3px;
                }

                .status {
                    min-height: 22px;
                    margin: 14px 0;
                    color: var(--muted);
                    font-size: 13px;
                }

                .track {
                    width: 100%;
                    display: grid;
                    grid-template-columns: 56px minmax(0, 1fr) auto;
                    align-items: center;
                    gap: 16px;
                    padding: 12px;
                    margin-bottom: 4px;
                    border: 0;
                    border-radius: 18px;
                    background: transparent;
                    color: inherit;
                    font: inherit;
                    text-align: left;
                    cursor: pointer;
                    content-visibility: auto;
                    contain-intrinsic-size: auto 80px;
                }

                .track:hover {
                    background: #ffffff08;
                }

                .track.active {
                    background: #b9a0ff15;
                    box-shadow: inset 0 0 0 1px #b9a0ff35;
                }

                .track.active .title {
                    color: var(--accent);
                }

                .cover {
                    position: relative;
                    width: 56px;
                    height: 56px;
                    border-radius: 14px;
                    overflow: hidden;
                    display: grid;
                    place-items: center;
                    background: linear-gradient(
                        135deg,
                        #453452,
                        #26232e
                    );
                    color: var(--accent);
                    font-size: 26px;
                    flex-shrink: 0;
                }

                .cover img {
                    position: absolute;
                    inset: 0;
                    width: 100%;
                    height: 100%;
                    object-fit: cover;
                }

                .info {
                    min-width: 0;
                }

                .title, .artist {
                    display: block;
                    overflow: hidden;
                    white-space: nowrap;
                    text-overflow: ellipsis;
                }

                .title {
                    font-size: 15px;
                    font-weight: 600;
                }

                .artist {
                    margin-top: 6px;
                    color: var(--muted);
                    font-size: 13px;
                }

                .duration {
                    color: var(--muted);
                    font-size: 13px;
                    font-variant-numeric: tabular-nums;
                }

                .empty {
                    padding: 60px 20px;
                    text-align: center;
                    color: var(--muted);
                }

                .player {
                    position: fixed;
                    z-index: 10;
                    bottom: 18px;
                    left: 50%;
                    transform: translateX(-50%);
                    width: min(1060px, calc(100% - 32px));
                    padding: 16px 20px;
                    border: 1px solid #52415e;
                    border-radius: 24px;
                    background: #221c2cf5;
                    box-shadow: 0 12px 60px #0008;
                }

                .player[hidden] {
                    display: none;
                }

                .player-top {
                    display: flex;
                    align-items: center;
                    gap: 14px;
                }

                .player-info {
                    flex: 1;
                    min-width: 0;
                }

                .player-buttons {
                    display: flex;
                    gap: 8px;
                }

                .skip {
                    width: 44px;
                    height: 44px;
                    display: grid;
                    place-items: center;
                    border: 1px solid #51415e;
                    border-radius: 14px;
                    background: #342a41;
                    color: var(--text);
                    font-size: 20px;
                    cursor: pointer;
                }

                .skip:hover {
                    background: #483758;
                }

                .skip:disabled {
                    opacity: .35;
                    cursor: default;
                }

                audio {
                    display: block;
                    width: 100%;
                    height: 42px;
                    margin-top: 12px;
                }

                .player-message {
                    color: #d8c8ef;
                    font-size: 12px;
                    margin-top: 8px;
                }

                .player-message:empty {
                    display: none;
                }

                @media (max-width: 600px) {
                    main {
                        padding: 22px 14px 230px;
                    }

                    header {
                        margin-bottom: 26px;
                    }

                    .badge {
                        font-size: 11px;
                        padding: 8px 10px;
                    }

                    h1 {
                        font-size: 26px;
                    }

                    .toolbar {
                        flex-direction: column;
                    }

                    select {
                        max-width: none;
                    }

                    .track {
                        gap: 12px;
                        padding: 10px 5px;
                    }

                    .player {
                        bottom: 10px;
                        padding: 14px;
                    }

                    .player-top {
                        gap: 10px;
                    }

                    .player-buttons {
                        gap: 5px;
                    }

                    .skip {
                        width: 38px;
                        height: 38px;
                    }
                }
            </style>
        </head>

        <body>
            <main>
                <header>
                    <div class="brand">muzerion</div>
                    <div class="badge">Музыка со смартфона</div>
                </header>

                <h1>Библиотека</h1>

                <div id="summary" class="muted">
                    Подключаемся…
                </div>

                <div class="toolbar">
                    <input
                        id="search"
                        type="search"
                        placeholder="Название или исполнитель"
                        autocomplete="off"
                        aria-label="Поиск музыки">

                    <select id="sort" aria-label="Сортировка">
                        <option value="alphabetical">
                            По алфавиту
                        </option>
                        <option value="recent">
                            Сначала новые
                        </option>
                    </select>
                </div>

                <div id="status"
                     class="status"
                     role="status"></div>

                <div id="tracks"></div>
            </main>

            <section id="player"
                     class="player"
                     hidden
                     aria-label="Музыкальный плеер">

                <div class="player-top">
                    <div id="player-cover" class="cover">♪</div>

                    <div class="player-info">
                        <div id="player-title" class="title"></div>
                        <div id="player-artist" class="artist"></div>
                    </div>

                    <div class="player-buttons">
                        <button id="previous"
                                class="skip"
                                type="button"
                                aria-label="Предыдущий трек"
                                title="Предыдущий трек">⏮</button>

                        <button id="next"
                                class="skip"
                                type="button"
                                aria-label="Следующий трек"
                                title="Следующий трек">⏭</button>
                    </div>
                </div>

                <audio id="audio"
                       controls
                       preload="none"></audio>

                <div id="player-message"
                     class="player-message"
                     role="status"></div>
            </section>

            <script>
                const key = new URL(location.href)
                    .searchParams.get("key") || "";

                const search = document.getElementById("search");
                const sort = document.getElementById("sort");
                const status = document.getElementById("status");
                const summary = document.getElementById("summary");
                const list = document.getElementById("tracks");

                const player = document.getElementById("player");
                const audio = document.getElementById("audio");

                const playerCover =
                    document.getElementById("player-cover");

                const playerTitle =
                    document.getElementById("player-title");

                const playerArtist =
                    document.getElementById("player-artist");

                const playerMessage =
                    document.getElementById("player-message");

                const previous =
                    document.getElementById("previous");

                const next =
                    document.getElementById("next");

                let tracks = [];
                let visibleTracks = [];
                let queue = [];
                let queueIndex = -1;
                let current = null;
                let revision = null;
                let busy = false;
                let selectionVersion = 0;

                function apiUrl(path) {
                    return path +
                        "?key=" +
                        encodeURIComponent(key);
                }

                function normalize(value) {
                    return String(value || "")
                        .toLocaleLowerCase("ru")
                        .replaceAll("ё", "е")
                        .trim();
                }

                function countLabel(count) {
                    let word = "треков";

                    if (!(count % 100 >= 11 && count % 100 <= 14)) {
                        if (count % 10 === 1) {
                            word = "трек";
                        } else if (
                            count % 10 >= 2 &&
                            count % 10 <= 4
                        ) {
                            word = "трека";
                        }
                    }

                    return count + " " + word;
                }

                function formatSize(bytes) {
                    const units = ["Б", "КБ", "МБ", "ГБ", "ТБ"];

                    let value = Math.max(
                        0,
                        Number(bytes) || 0
                    );

                    let unit = 0;

                    while (
                        value >= 1000 &&
                        unit < units.length - 1
                    ) {
                        value /= 1000;
                        unit++;
                    }

                    return new Intl.NumberFormat("ru", {
                        maximumFractionDigits: 1
                    }).format(value) + " " + units[unit];
                }

                function formatDuration(milliseconds) {
                    const total = Math.floor(
                        Math.max(
                            0,
                            Number(milliseconds) || 0
                        ) / 1000
                    );

                    if (!total) return "—:—";

                    const seconds = String(total % 60)
                        .padStart(2, "0");

                    const minutes = Math.floor(total / 60);

                    if (minutes >= 60) {
                        return Math.floor(minutes / 60) + ":" +
                            String(minutes % 60).padStart(2, "0") +
                            ":" + seconds;
                    }

                    return minutes + ":" + seconds;
                }

                function fillCover(element, track, lazy) {
                    element.replaceChildren(
                        document.createTextNode("♪")
                    );

                    if (!track.hasCover) return;

                    const image = document.createElement("img");

                    image.alt = "";
                    image.decoding = "async";

                    if (lazy) {
                        image.loading = "lazy";
                    }

                    image.src = apiUrl(
                        "/api/cover/" +
                        encodeURIComponent(track.id)
                    );

                    image.addEventListener("error", function() {
                        image.remove();
                    });

                    element.append(image);
                }

                function updateActiveRows() {
                    list.querySelectorAll(".track")
                        .forEach(function(row) {
                            const active = !!current &&
                                row.dataset.id === current.id;

                            row.classList.toggle("active", active);

                            row.setAttribute(
                                "aria-pressed",
                                String(active && !audio.paused)
                            );
                        });
                }

                function updateButtons() {
                    previous.disabled = queueIndex <= 0;

                    next.disabled =
                        queueIndex < 0 ||
                        queueIndex >= queue.length - 1;
                }

                async function resumeAudio() {
                    const version = selectionVersion;

                    playerMessage.textContent = "";

                    try {
                        await audio.play();
                    } catch (error) {
                        if (
                            version !== selectionVersion ||
                            error.name === "AbortError"
                        ) {
                            return;
                        }

                        playerMessage.textContent =
                            error.name === "NotAllowedError"
                                ? "Нажми кнопку воспроизведения внизу."
                                : "Не удалось запустить трек. Проверь связь с телефоном.";
                    }
                }

                function selectTrack(track) {
                    selectionVersion++;

                    audio.pause();

                    current = track;

                    player.hidden = false;

                    playerTitle.textContent = track.title;
                    playerTitle.title = track.title;

                    playerArtist.textContent =
                        track.artist ||
                        track.format ||
                        "Локальный файл";

                    fillCover(
                        playerCover,
                        track,
                        false
                    );

                    document.title =
                        track.title + " — Muzerion";

                    playerMessage.textContent =
                        "Подключаем трек…";

                    audio.src = apiUrl(
                        "/api/audio/" +
                        encodeURIComponent(track.id)
                    );

                    audio.load();

                    updateButtons();
                    updateActiveRows();

                    resumeAudio();
                }

                function clickTrack(track) {
                    if (
                        current &&
                        current.id === track.id
                    ) {
                        if (audio.paused) {
                            if (audio.ended) {
                                audio.currentTime = 0;
                            }

                            resumeAudio();
                        } else {
                            audio.pause();
                        }

                        return;
                    }

                    queue = visibleTracks.slice();

                    queueIndex = queue.findIndex(function(item) {
                        return item.id === track.id;
                    });

                    selectTrack(track);
                }

                function moveTrack(direction) {
                    const target = queueIndex + direction;

                    if (
                        target < 0 ||
                        target >= queue.length
                    ) {
                        return;
                    }

                    queueIndex = target;

                    selectTrack(queue[queueIndex]);
                }

                function render() {
                    const terms = normalize(search.value)
                        .split(/\s+/)
                        .filter(Boolean);

                    visibleTracks = tracks.filter(function(track) {
                        const haystack = normalize(
                            track.title + " " + track.artist
                        );

                        return terms.every(function(term) {
                            return haystack.includes(term);
                        });
                    });

                    visibleTracks.sort(function(first, second) {
                        if (sort.value === "recent") {
                            const date =
                                Number(second.addedAt) -
                                Number(first.addedAt);

                            if (date) return date;
                        }

                        return first.title.localeCompare(
                            second.title,
                            "ru",
                            { sensitivity: "base" }
                        );
                    });

                    const fragment =
                        document.createDocumentFragment();

                    if (!visibleTracks.length) {
                        const empty =
                            document.createElement("div");

                        empty.className = "empty";

                        empty.textContent = terms.length
                            ? "Ничего не найдено"
                            : "В библиотеке пока нет треков";

                        fragment.append(empty);
                    }

                    visibleTracks.forEach(function(track) {
                        const row =
                            document.createElement("button");

                        row.type = "button";
                        row.className = "track";
                        row.dataset.id = track.id;

                        row.setAttribute(
                            "aria-pressed",
                            "false"
                        );

                        const cover =
                            document.createElement("span");

                        cover.className = "cover";

                        fillCover(cover, track, true);

                        const info =
                            document.createElement("span");

                        info.className = "info";

                        const title =
                            document.createElement("span");

                        title.className = "title";
                        title.textContent = track.title;
                        title.title = track.title;

                        const artist =
                            document.createElement("span");

                        artist.className = "artist";

                        artist.textContent =
                            track.artist ||
                            (track.format + " · Локальный файл");

                        info.append(title, artist);

                        const duration =
                            document.createElement("span");

                        duration.className = "duration";

                        duration.textContent =
                            formatDuration(track.duration);

                        row.append(
                            cover,
                            info,
                            duration
                        );

                        row.addEventListener("click", function() {
                            clickTrack(track);
                        });

                        fragment.append(row);
                    });

                    list.replaceChildren(fragment);

                    updateActiveRows();

                    status.textContent = terms.length
                        ? "Найдено: " + visibleTracks.length
                        : "";
                }

                async function refreshLibrary() {
                    if (busy) return;

                    busy = true;

                    try {
                        const response = await fetch(
                            apiUrl("/api/library"),
                            {
                                cache: "no-store",
                                signal: AbortSignal.timeout(10000)
                            }
                        );

                        if (!response.ok) {
                            throw new Error(
                                response.status === 403
                                    ? "Ссылка устарела. Открой новую ссылку с телефона."
                                    : "Телефон не смог передать библиотеку."
                            );
                        }

                        const data = await response.json();

                        if (data.loading) {
                            status.textContent =
                                data.message ||
                                "Библиотека обновляется…";

                            return;
                        }

                        summary.textContent =
                            countLabel(data.count) +
                            " · " +
                            formatSize(data.totalBytes);

                        if (revision !== data.revision) {
                            revision = data.revision;
                            tracks = data.tracks || [];

                            render();
                        } else {
                            status.textContent =
                                search.value.trim()
                                    ? "Найдено: " +
                                      visibleTracks.length
                                    : "";
                        }
                    } catch (error) {
                        status.textContent =
                            error.name === "TimeoutError"
                                ? "Телефон не отвечает. Проверь Wi-Fi и Muzerion."
                                : error.message ||
                                  "Связь с телефоном потеряна.";
                    } finally {
                        busy = false;
                    }
                }

                search.addEventListener("input", render);
                sort.addEventListener("change", render);

                previous.addEventListener("click", function() {
                    moveTrack(-1);
                });

                next.addEventListener("click", function() {
                    moveTrack(1);
                });

                audio.addEventListener(
                    "play",
                    updateActiveRows
                );

                audio.addEventListener(
                    "pause",
                    updateActiveRows
                );

                audio.addEventListener("playing", function() {
                    playerMessage.textContent = "";
                    updateActiveRows();
                });

                audio.addEventListener("waiting", function() {
                    playerMessage.textContent =
                        "Буферизация…";
                });

                audio.addEventListener("seeking", function() {
                    playerMessage.textContent =
                        "Перематываем…";
                });

                audio.addEventListener("seeked", function() {
                    playerMessage.textContent = "";
                });

                audio.addEventListener("canplay", function() {
                    playerMessage.textContent = "";
                });

                audio.addEventListener("ended", function() {
                    if (queueIndex < queue.length - 1) {
                        moveTrack(1);
                    } else {
                        playerMessage.textContent =
                            "Очередь закончилась";

                        updateActiveRows();
                    }
                });

                audio.addEventListener("error", function() {
                    const code = audio.error
                        ? audio.error.code
                        : 0;

                    if (code === 1) return;

                    playerMessage.textContent =
                        code === 3 || code === 4
                            ? "Браузер не смог воспроизвести файл. Проверь другой трек: возможно, кодек не поддерживается."
                            : "Не удалось получить звук с телефона. Проверь соединение и открой трек снова.";
                });

                updateButtons();
                refreshLibrary();

                setInterval(
                    refreshLibrary,
                    4000
                );
            </script>
        </body>
        </html>
    """ .let { AppLanguage.webPage(it) }.trimIndent()
}

private class TestServerHolder {

    val server = AtomicReference<MuzerionWebServer?>(null)

    private val disposed = AtomicBoolean(false)

    fun attach(candidate: MuzerionWebServer) {
        server.set(candidate)

        if (disposed.get()) {
            stop()
        }
    }

    fun stop() {
        server.getAndSet(null)?.stop()
    }

    fun dispose() {
        disposed.set(true)
        stop()
    }
}

@Composable
fun PcConnectionPanel() {
    val context = LocalContext.current
    val appContext = context.applicationContext

    val scope = rememberCoroutineScope()

    val holder = remember {
        TestServerHolder()
    }

    var running by remember {
        mutableStateOf(false)
    }

    var starting by remember {
        mutableStateOf(false)
    }

    var addresses by remember {
        mutableStateOf<List<String>>(emptyList())
    }

    var error by remember {
        mutableStateOf<String?>(null)
    }

    DisposableEffect(holder) {
        onDispose {
            holder.dispose()
        }
    }

    fun startServer() {
        if (starting || running) return

        starting = true
        error = null

        scope.launch {
            try {
                val urls = withContext(Dispatchers.IO) {
                    val ips = findLocalAddresses(appContext)

                    if (ips.isEmpty()) {
                        throw IOException(
                            tr(R.string.msg_921fe6d827)
                        )
                    }

                    val server = MuzerionWebServer(appContext)

                    try {
                        server.start(
                            NanoHTTPD.SOCKET_READ_TIMEOUT,
                            true
                        )

                        holder.attach(server)
                    } catch (failure: Exception) {
                        server.stop()
                        throw failure
                    }

                    ips.map { ip ->
                        "http://${ip}:${MUZERION_WEB_PORT}/?key=${server.accessKey}"
                    }
                }

                addresses = urls

                running = holder.server.get() != null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                error =
                    tr(R.string.msg_376c1487e3)
            } catch (failure: Exception) {
                error = failure.message
                    ?: tr(R.string.msg_3899758874)
            } finally {
                starting = false
            }
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startServer()
            } else {
                error =
                    tr(R.string.msg_d65cdc5a7d)
            }
        }

    Surface(
        color = MuzerionStyle.panel,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr(R.string.msg_15fa1bcda6),
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp
                )

                TextButton(
                    enabled = !starting,
                    onClick = {
                        if (running) {
                            starting = true

                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        holder.stop()
                                    }

                                    running = false
                                    addresses = emptyList()
                                    error = null
                                } finally {
                                    starting = false
                                }
                            }
                        } else {
                            val needsPermission =
                                Build.VERSION.SDK_INT >= 37 &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            LOCAL_NETWORK_PERMISSION
                                        ) != PackageManager.PERMISSION_GRANTED

                            if (needsPermission) {
                                permissionLauncher.launch(
                                    LOCAL_NETWORK_PERMISSION
                                )
                            } else {
                                startServer()
                            }
                        }
                    }
                ) {
                    Text(
                        when {
                            starting -> tr(R.string.msg_577f71f48f)
                            running -> tr(R.string.msg_d4f447c130)
                            else -> tr(R.string.msg_124298ec71)
                        }
                    )
                }
            }

            if (running) {
                PcQrPairingButton(
                    accessKey = holder.server.get()?.accessKey
                )

                Text(
                    text = tr(R.string.msg_15d20423bd),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )

                addresses.forEach { address ->
                    SelectionContainer {
                        Text(
                            text = address,
                            color = MuzerionStyle.accent,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun findLocalAddresses(
    context: Context
): List<String> {
    val manager = context.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as ConnectivityManager

    return manager.allNetworks.flatMap { network ->
        val capabilities =
            manager.getNetworkCapabilities(network)

        val local = capabilities != null &&
                (
                        capabilities.hasTransport(
                            NetworkCapabilities.TRANSPORT_WIFI
                        ) ||
                                capabilities.hasTransport(
                                    NetworkCapabilities.TRANSPORT_ETHERNET
                                )
                        )

        if (!local) {
            emptyList()
        } else {
            manager.getLinkProperties(network)
                ?.linkAddresses
                .orEmpty()
                .mapNotNull { link ->
                    val address = link.address

                    if (
                        address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    ) {
                        address.hostAddress
                    } else {
                        null
                    }
                }
        }
    }.distinct()
}

private fun webHash(
    value: String
): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") {
            (it.toInt() and 0xff)
                .toString(16)
                .padStart(2, '0')
        }
}