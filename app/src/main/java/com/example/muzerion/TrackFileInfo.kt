package com.example.muzerion

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class TrackFileDetails(val fields: List<Pair<String, String>>, val warning: String? = null)

internal fun readTrackFileDetails(context: Context, track: Track): TrackFileDetails {
    val fields = linkedMapOf<String,String>()
    fun field(label: String, value: String?) { if (!value.isNullOrBlank()) fields[label] = value }
    var name = track.name
    var size = track.sizeBytes
    var modified = 0L
    var readable = false
    try {
        context.contentResolver.query(track.uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                fun value(key: String): String? = c.getColumnIndex(key).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getString(it) }
                name = value(OpenableColumns.DISPLAY_NAME) ?: name
                size = value(OpenableColumns.SIZE)?.toLongOrNull() ?: size
                modified = value("last_modified")?.toLongOrNull()
                    ?: value("date_modified")?.toLongOrNull()?.times(1000L) ?: 0L
            }
        }
        context.contentResolver.openFileDescriptor(track.uri, "r")?.use {
            readable = true
            if (size <= 0) size = it.statSize
        }
    } catch (_: Exception) { }
    field(tr(R.string.msg_94b8df93b6), name)
    field(tr(R.string.msg_3de49828e8), track.title)
    field(tr(R.string.msg_2c5e7a631b), track.artist)
    field(tr(R.string.msg_065996461c), track.album)
    field(tr(R.string.msg_b9563c38ab), name.substringAfterLast('.', "").uppercase())
    field("MIME", runCatching { context.contentResolver.getType(track.uri) }.getOrNull())
    if (size > 0) field(tr(R.string.msg_98713e8814), tr(R.string.msg_b29bbe573e, Formatter.formatFileSize(context, size), size))
    if (track.durationMs > 0) field(tr(R.string.msg_d4168ace06), formatMuzerionTime(track.durationMs))
    if (modified > 0) field(tr(R.string.msg_440e2b5d80), DateFormat.getDateTimeInstance().format(Date(modified)))
    var bitrate = 0L
    var duration = track.durationMs
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, track.uri)
        fun tag(key: Int) = retriever.extractMetadata(key)
        field(tr(R.string.msg_3de49828e8), tag(MediaMetadataRetriever.METADATA_KEY_TITLE))
        field(tr(R.string.msg_2c5e7a631b), tag(MediaMetadataRetriever.METADATA_KEY_ARTIST))
        field(tr(R.string.msg_065996461c), tag(MediaMetadataRetriever.METADATA_KEY_ALBUM))
        field(tr(R.string.msg_fb95691afc), tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
        field(tr(R.string.msg_aa8ab25302), tag(MediaMetadataRetriever.METADATA_KEY_GENRE))
        field(tr(R.string.msg_03f8541f9a), tag(MediaMetadataRetriever.METADATA_KEY_YEAR))
        field(tr(R.string.msg_751a849393), tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
        field(tr(R.string.msg_ca040760a3), tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
        field(tr(R.string.msg_a139f9dd60), tag(MediaMetadataRetriever.METADATA_KEY_COMPOSER))
        duration = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: duration
        if (duration > 0) field(tr(R.string.msg_d4168ace06), formatMuzerionTime(duration))
        bitrate = tag(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
    } catch (_: Exception) { } finally { runCatching { retriever.release() } }
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, track.uri, null)
        for (index in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(index)
            val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
            if (!mime.startsWith("audio/")) continue
            fun integer(key: String): Int = if (f.containsKey(key)) runCatching { f.getInteger(key) }.getOrDefault(0) else 0
            field(tr(R.string.msg_3cd9eac36d), if (mime == "audio/raw" && name.substringAfterLast('.').equals("flac", ignoreCase=true)) "FLAC" else mime)
            integer(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 }?.let { field(tr(R.string.msg_4744b7836e), tr(R.string.msg_1217534f2f, it)) }
            integer(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 }?.let { field(tr(R.string.msg_88a8ecd3ff), when(it) {1 -> tr(R.string.msg_f656827bd7); 2 -> tr(R.string.msg_18c6e97c9a); else -> it.toString()}) }
            if (bitrate <= 0) bitrate = integer(MediaFormat.KEY_BIT_RATE).toLong()
            integer("bits-per-sample").takeIf { it > 0 }?.let { field(tr(R.string.msg_09c9e2a0ac), tr(R.string.msg_797f36ff09, it)) }
            break
        }
    } catch (_: Exception) { } finally { runCatching { extractor.release() } }
    if (bitrate > 0) field(tr(R.string.msg_5103b115af), tr(R.string.msg_febbdfd633, bitrate / 1000))
    else if (size > 0 && duration > 0) field(tr(R.string.msg_0059731b2f), tr(R.string.msg_5deb6157b8, size * 8 / duration))
    // Providers do not necessarily expose a real filesystem path. Keep the actual source URI.
    field(tr(R.string.msg_021a51a1ea), track.uri.toString())
    return TrackFileDetails(fields.toList(), if (readable) null else tr(R.string.msg_b9e1af6690))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackFileInfoSheet(track: Track, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val details by produceState<TrackFileDetails?>(null, track.uri) {
        value = withContext(Dispatchers.IO) { readTrackFileDetails(context.applicationContext, track) }
    }
    ModalBottomSheet(onDismissRequest=onDismiss, sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true), containerColor=MuzerionStyle.panel) {
        HighRefreshRateSheetWindow()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=32.dp)) {
            Text(tr(R.string.msg_cc52f76563), fontSize=22.sp, color=MuzerionStyle.text)
            Spacer(Modifier.height(20.dp))
            val info = details
            if (info == null) { CircularProgressIndicator(); Spacer(Modifier.height(24.dp)) }
            else {
                info.warning?.let { Text(it,color=MaterialTheme.colorScheme.error);Spacer(Modifier.height(16.dp)) }
                SelectionContainer {
                    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        info.fields.forEach { (label,value) ->
                            Column {
                                Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)
                                Text(value,color=MuzerionStyle.text,fontSize=15.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
