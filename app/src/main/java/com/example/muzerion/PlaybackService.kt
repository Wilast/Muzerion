package com.example.muzerion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.text.Collator
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.*

private val playbackDiskDispatcher = Dispatchers.IO.limitedParallelism(1)
private val playbackDiskScope = CoroutineScope(SupervisorJob() + playbackDiskDispatcher)

class PlaybackService : MediaSessionService() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguage.wrap(newBase)) }
    override fun getResources(): android.content.res.Resources = AppLanguage.resources
    private val languageChanged: ()->Unit = { triggerNotificationUpdate() }
    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("muzerion_settings", Context.MODE_PRIVATE) }
    // Keep the potentially large playlist separate from the frequently saved position.
    private val queuePreferences by lazy { getSharedPreferences("muzerion_playback_queue", Context.MODE_PRIVATE) }
    private var lastQueueSnapshot: String? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var restoringQueue = true
    private var soundSettings: ServiceSoundSettings? = null
    private var headphoneCallback: android.media.AudioDeviceCallback? = null

    private val saveTask = object : Runnable {
        override fun run() {
            val player = mediaSession?.player ?: return
            saveLastTrack(player)
            if (player.playWhenReady) handler.postDelayed(this, 5000L)
        }
    }

    private val playbackListener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            val player = mediaSession?.player as? ExoPlayer ?: return
            if (shuffleModeEnabled) {
                // Enabling shuffle mid-track starts a new run without seeking/restarting audio.
                player.setShuffleOrder(AnchoredShuffleOrder.startingAt(player.mediaItemCount, player.currentMediaItemIndex))
            }
            preferences.edit().putBoolean("shuffle_enabled", shuffleModeEnabled).apply()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (restoringQueue && player.mediaItemCount == 0) return
            saveLastTrack(player)
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) saveQueue(player)
            handler.removeCallbacks(saveTask)
            if (player.playWhenReady) handler.postDelayed(saveTask, 5000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLanguage.listeners.add(languageChanged)
        AppOptions.init(this)
        val bootstrap = ConnectingPlayer()
        val session = MediaSession.Builder(this, bootstrap).setCallback(QueueSessionCallback()).build()
        mediaSession = session
        serviceScope.launch {
            var built: ExoPlayer? = null
            var attached = false
            try {
                // Decoder/renderers/track selector initialization must not block Compose/input.
                // Public Player calls and the session still use the main application looper.
                withContext(Dispatchers.IO) {
                    val renderers=object: androidx.media3.exoplayer.DefaultRenderersFactory(this@PlaybackService) {
                        override fun buildAudioSink(context: Context,enableFloatOutput: Boolean,enableAudioOutputPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink {
                            return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                                .setEnableFloatOutput(false)
                                .setEnableAudioOutputPlaybackParameters(false)
                                .setAudioProcessors(arrayOf(EqualizerAudioProcessor())).build()
                        }
                    }
                    built = ExoPlayer.Builder(this@PlaybackService,renderers).setLooper(Looper.getMainLooper()).build()
                }
                val player = checkNotNull(built)
                ensureActive()
                player.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                player.setHandleAudioBecomingNoisy(true)
                player.volume = 1f
                val savedSpeed=preferences.getFloat("sound_speed",1f).takeIf {it.isFinite()}?.coerceIn(.5f,2f) ?: 1f
                val savedPitch=preferences.getFloat("sound_pitch",1f).takeIf {it.isFinite()}?.coerceIn(.5f,2f) ?: 1f
                player.playbackParameters=PlaybackParameters(savedSpeed,savedPitch)
                player.setShuffleOrder(AnchoredShuffleOrder.startingAt(0, 0))
                player.repeatMode = if (preferences.getBoolean("repeat_one", false)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                player.shuffleModeEnabled = preferences.getBoolean("shuffle_enabled", false)
                if (mediaSession !== session) return@launch
                session.setPlayer(player)
                attached = true
                bootstrap.release()
                soundSettings=ServiceSoundSettings(this@PlaybackService,player)
                player.addListener(playbackListener)
                val audio=getSystemService(android.media.AudioManager::class.java)
                val registeredAt=android.os.SystemClock.elapsedRealtime()
                headphoneCallback=object: android.media.AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
                        if(android.os.SystemClock.elapsedRealtime()-registeredAt<1500L || !preferences.getBoolean("headphones_resume",false)) return
                        if(addedDevices.any {it.isSink && it.type in setOf(3,4,8,22,26)} && player.mediaItemCount>0) player.play()
                    }
                }.also {audio.registerAudioDeviceCallback(it,handler)}
                val restore = withContext(playbackDiskDispatcher) { readRestoration() }
                if (restore != null && mediaSession?.player === player && player.mediaItemCount == 0 &&
                    preferences.getString("music_folder", null) == restore.folder) {
                    restoringQueue = false
                    player.playWhenReady = false
                    player.setMediaItems(restore.items, restore.index, if(preferences.getBoolean("restore_position",true)) restore.position else 0L)
                    restore.order?.let { player.setShuffleOrder(it) }
                    player.prepare()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("MuzerionStartup", "Player initialization failed", error)
                if (!attached && mediaSession === session) bootstrap.reportInitializationError(error)
            } finally {
                if (attached) restoringQueue = false
                // Cancellation can arrive while the non-suspending constructor is running.
                // Release its result on the application looper unless the session owns it.
                if (!attached) withContext(NonCancellable + Dispatchers.Main.immediate) { built?.release() }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.let { saveLastTrack(it); saveQueue(it) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLanguage.listeners.remove(languageChanged)
        handler.removeCallbacks(saveTask)
        headphoneCallback?.let {getSystemService(android.media.AudioManager::class.java).unregisterAudioDeviceCallback(it)}
        soundSettings?.close();soundSettings=null
        serviceScope.cancel()
        mediaSession?.let { session ->
            val player = session.player
            saveLastTrack(player)
            saveQueue(player)
            player.removeListener(playbackListener)
            player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun snapshot(item: MediaItem): JSONObject {
        val metadata = item.mediaMetadata
        val uri = item.localConfiguration?.uri?.toString() ?: item.mediaId
        return JSONObject()
            .put("uri", uri)
            .put("media_id", item.mediaId.ifBlank { uri })
            .put("name", metadata.extras?.getString("file_name") ?: metadata.title?.toString() ?: tr(R.string.msg_32b74a3c47))
            .put("title", metadata.title?.toString() ?: tr(R.string.msg_32b74a3c47))
            .put("artist", metadata.artist?.toString().orEmpty())
            .put("explicit_queue", metadata.extras?.getBoolean("explicit_queue") == true)
            .put("album", metadata.albumTitle?.toString().orEmpty())
            .put("artwork", metadata.artworkUri?.toString().orEmpty())
            .put("duration_ms", metadata.extras?.getLong("duration_ms") ?: 0L)
            .put("size_bytes", metadata.extras?.getLong("size_bytes") ?: 0L)
            .put("added_at", metadata.extras?.getLong("added_at") ?: 0L)
    }

    private fun saveLastTrack(player: Player) {
        if (restoringQueue && player.mediaItemCount == 0) return
        val item = player.currentMediaItem
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration
        playbackDiskScope.launch {
            if (item == null) { preferences.edit().remove("last_track").apply(); return@launch }
            val saved = snapshot(item)
            if (saved.optString("uri").isBlank()) return@launch
            saved.put("position", position)
            if (duration > 0L) saved.put("duration_ms", duration)
            val text = saved.toString()
            if (preferences.getString("last_track", null) != text) preferences.edit().putString("last_track", text).apply()
        }
    }

    private fun saveQueue(player: Player) {
        if (restoringQueue && player.mediaItemCount == 0) return
        // Player methods stay on its application thread. Only immutable snapshots go to disk work.
        val captured = List(player.mediaItemCount) { player.getMediaItemAt(it) }
        val indices = if (player is ExoPlayer) buildList {
            val order = player.shuffleOrder
            var index = order.firstIndex
            repeat(player.mediaItemCount) {
                if (index != C.INDEX_UNSET) { add(index); index = order.getNextIndex(index) }
            }
        } else emptyList()
        playbackDiskScope.launch {
            val items = JSONArray()
            captured.forEach { items.put(snapshot(it)) }
            val text = items.toString()
            val shuffled = JSONArray()
            indices.forEach { shuffled.put(it) }
            val orderText = shuffled.toString()
            val combined = text + "\n" + orderText
            if (combined != lastQueueSnapshot) {
                lastQueueSnapshot = combined
                queuePreferences.edit().putString("items", text).putString("shuffle_order", orderText).apply()
            }
        }
    }

    private fun readItem(saved: JSONObject): MediaItem? {
        val uri = saved.optString("uri")
        if (uri.isBlank()) return null
        val extras = Bundle().apply {
            putBoolean("explicit_queue", saved.optBoolean("explicit_queue", false))
            putString("file_name", saved.optString("name", tr(R.string.msg_32b74a3c47)))
            putLong("duration_ms", saved.optLong("duration_ms", 0L))
            putLong("size_bytes", saved.optLong("size_bytes", 0L))
            putLong("added_at", saved.optLong("added_at", 0L))
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(saved.optString("title", tr(R.string.msg_32b74a3c47)))
            .setArtist(saved.optString("artist").takeIf { it.isNotBlank() })
            .setAlbumTitle(saved.optString("album").takeIf { it.isNotBlank() })
            .setArtworkUri(saved.optString("artwork").takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(saved.optString("media_id", uri).ifBlank { uri })
            .setUri(Uri.parse(uri)).setMediaMetadata(metadata).build()
    }

    private fun readQueue(): List<MediaItem> = try {
        val items = JSONArray(queuePreferences.getString("items", "[]") ?: "[]")
        buildList {
            for (index in 0 until items.length()) {
                val value = items.optJSONObject(index) ?: continue
                readItem(value)?.let { add(it) }
            }
        }
    } catch (_: Exception) { emptyList() }

    private fun cachedQueue(): List<MediaItem> {
        val folder = preferences.getString("music_folder", null) ?: return emptyList()
        val tracks = AppOptions.folders(folder).flatMap {readCachedMusicTracks(this, it)}.distinctBy {it.uri}
        val collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.PRIMARY }
        val alphabetical = Comparator<Track> { a, b ->
            val title = collator.compare(a.title, b.title)
            if (title != 0) title else {
                val artist = collator.compare(a.artist, b.artist)
                if (artist != 0) artist else a.uri.toString().compareTo(b.uri.toString())
            }
        }
        val ordered = if (preferences.getString("library_sort", "alphabetical") == "recent") {
            tracks.sortedWith(compareByDescending<Track> { it.addedAt }.then(alphabetical))
        } else tracks.sortedWith(alphabetical)
        return ordered.map { it.toMuzerionMediaItem() }
    }

    private data class RestoredPlayback(val items: List<MediaItem>, val index: Int, val position: Long,
        val order: AnchoredShuffleOrder?, val folder: String?)

    private fun readRestoration(): RestoredPlayback? {
        val folder = preferences.getString("music_folder", null)
        val text = preferences.getString("last_track", null) ?: return null
        val saved = try { JSONObject(text) } catch (_: Exception) { return null }
        val selected = readItem(saved) ?: return null
        val uri = selected.localConfiguration?.uri
        fun matches(item: MediaItem): Boolean = item.localConfiguration?.uri == uri
        val storedQueue = readQueue()
        var items = storedQueue
        // Migration from older versions, and recovery if a previous save was incomplete.
        if ((items.size < 2 && !saved.optBoolean("explicit_queue", false)) || items.none { matches(it) }) {
            val cached = cachedQueue()
            if (cached.any { matches(it) }) items = cached
        }
        var index = items.indexOfFirst { matches(it) }
        if (index < 0) { items = listOf(selected); index = 0 }
        val restored = items.toMutableList().apply { this[index] = selected }
        val order = if (restored.map { it.mediaId } == storedQueue.map { it.mediaId }) {
            try {
                val indices = JSONArray(queuePreferences.getString("shuffle_order", "[]") ?: "[]")
                AnchoredShuffleOrder.restored(IntArray(indices.length()) { indices.getInt(it) }, restored.size)
            } catch (_: Exception) { null }
        } else null
        return RestoredPlayback(restored, index, saved.optLong("position", 0L).coerceAtLeast(0L),
            order, folder)
    }
}
