package com.example.muzerion

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal const val MOVE_QUEUE_COMMAND = "com.example.muzerion.MOVE_QUEUE"

internal fun playbackOrder(player: Player): List<Int> {
    val timeline = player.currentTimeline
    if (timeline.isEmpty) return emptyList()
    val order = mutableListOf<Int>()
    val seen = hashSetOf<Int>()
    var index = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
    while (index != C.INDEX_UNSET && index in 0 until player.mediaItemCount && seen.add(index)) {
        order += index
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
    }
    return order
}

internal fun moveQueuedTrack(player: Player, source: String, target: String, after: Boolean) {
    val args = Bundle().apply { putString("source", source); putString("target", target); putBoolean("after", after) }
    if (player is MediaController) player.sendCustomCommand(SessionCommand(MOVE_QUEUE_COMMAND, Bundle.EMPTY), args)
}

internal class QueueSessionCallback : MediaSession.Callback {
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        if (controller.packageName != "com.example.muzerion") return super.onConnect(session, controller)
        return MediaSession.ConnectionResult.AcceptedResultBuilder()
            .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(MOVE_QUEUE_COMMAND, Bundle.EMPTY)).build()).build()
    }

    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
        if (customCommand.customAction != MOVE_QUEUE_COMMAND || controller.packageName != "com.example.muzerion")
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        val player = session.player as? ExoPlayer
            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        val indices = (0 until player.mediaItemCount)
        val source = indices.firstOrNull { player.getMediaItemAt(it).mediaId == args.getString("source") }
        val target = indices.firstOrNull { player.getMediaItemAt(it).mediaId == args.getString("target") }
        if (source == null || target == null) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val changed = moveQueueEntry(playbackOrder(player), source, target, args.getBoolean("after"))
        if (player.shuffleModeEnabled) {
            AnchoredShuffleOrder.restored(changed.toIntArray(), player.mediaItemCount)?.let { player.setShuffleOrder(it) }
        } else player.moveMediaItem(source, changed.indexOf(source))
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
}
