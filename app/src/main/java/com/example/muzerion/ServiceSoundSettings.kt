package com.example.muzerion

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

internal class ServiceSoundSettings(context: Context,private val player: Player) {
    private val prefs=context.getSharedPreferences("muzerion_settings",0)
    private var applying=false
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key in setOf("sound_speed","sound_pitch")) apply()
    }
    init {prefs.registerOnSharedPreferenceChangeListener(listener)}
    private fun apply() {
        if(applying)return
        val speed=prefs.getFloat("sound_speed",1f).takeIf {it.isFinite()}?.coerceIn(.5f,2f) ?: 1f
        val pitch=prefs.getFloat("sound_pitch",1f).takeIf {it.isFinite()}?.coerceIn(.5f,2f) ?: 1f
        val params=PlaybackParameters(speed,pitch)
        if(player.playbackParameters!=params) {applying=true;try {player.playbackParameters=params} finally {applying=false}}
    }
    fun close() {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
}
