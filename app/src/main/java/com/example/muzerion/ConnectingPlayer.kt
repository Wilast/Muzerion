package com.example.muzerion

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.SimpleBasePlayer

/** Lets the library render before the remote session is ready; has no decoder or service. */
internal class ConnectingPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var snapshot = State.Builder().setAvailableCommands(Player.Commands.EMPTY).build()
    override fun getState(): State = snapshot
    fun reportInitializationError(error: Exception) {
        snapshot = snapshot.buildUpon().setPlayerError(PlaybackException(tr(R.string.msg_ae08c44ddc), error, PlaybackException.ERROR_CODE_UNSPECIFIED)).build()
        invalidateState()
    }
}
