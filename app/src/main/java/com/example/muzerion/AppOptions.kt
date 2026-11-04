package com.example.muzerion

import android.content.Context
import androidx.compose.runtime.*
import org.json.JSONObject
import org.json.JSONArray

internal object AppOptions {
    private lateinit var context: Context
    var revision by mutableIntStateOf(0)
        private set
    var libraryRevision by mutableIntStateOf(0)
    fun init(c: Context) {context=c.applicationContext;context.getSharedPreferences("muzerion_settings",0).edit().remove("track_sound").remove("sound_per_track").remove("sound_presets").apply();EqualizerState.load(context);revision++}
    private val prefs get()=context.getSharedPreferences("muzerion_settings",0)
    fun bool(key: String,default: Boolean=false): Boolean {revision;return prefs.getBoolean(key,default)}
    fun number(key: String,default: Float): Float {revision;return prefs.getFloat(key,default)}
    fun text(key: String,default: String=""): String {revision;return prefs.getString(key,default) ?: default}
    fun put(key: String,value: Any) {
        prefs.edit().also {e->when(value) {is Boolean->e.putBoolean(key,value);is Float->e.putFloat(key,value);is String->e.putString(key,value)}}.apply()
        revision++
        if(key.startsWith("library_") || key=="extra_folders") libraryRevision++
    }
    fun refresh() {EqualizerState.load(context);revision++;libraryRevision++;MuzerionStyle.load(context)}
    fun folders(primary: String?): List<String> = buildList {
        primary?.let {add(it)}
        val array=runCatching {JSONArray(text("extra_folders","[]"))}.getOrDefault(JSONArray())
        for(i in 0 until array.length()) add(array.getString(i))
    }.distinct()
    fun backup(): String {
        val values=JSONObject()
        prefs.all.forEach {(key,value)->if(allowedKey(key) && value!=null) values.put(key,value)}
        return JSONObject().put("format","muzerion-settings").put("version",1).put("settings",values).toString(2)
    }
    fun restore(raw: String) {
        require(raw.length<=1024*1024) {tr(R.string.msg_2453e775fb)}
        val root=JSONObject(raw)
        require(root.optString("format")=="muzerion-settings" && root.optInt("version")==1) {tr(R.string.msg_2579d3eb91)}
        val data=root.getJSONObject("settings")
        // Validate the complete file before changing any setting. Never import folder grants or credentials.
        val entries=data.keys().asSequence().map {it to data.get(it)}.filterNot {it.first in setOf("sound_per_track","track_sound","sound_presets")}.toList()
        val booleanKeys=setOf("appearance_system","appearance_dark","appearance_monochrome","compact_list","restore_position","headphones_resume","previous_restarts","library_auto","library_hide_short","library_filenames","gesture_cover","gesture_dismiss","haptic","sound_linked")
        val ranges=mapOf("player_background_visibility" to 0f..1f,"player_background_blur" to 0f..1f,"text_scale" to .85f..1.2f,"cover_size" to 36f..64f,"sound_speed" to .5f..2f,"sound_pitch" to .5f..2f,"appearance_accent" to 0f..5f)
        entries.forEach {(k,v)->
            require(allowedKey(k)) {tr(R.string.msg_3334b9b7a4, k)}
            when {
                k in booleanKeys -> require(v is Boolean)
                k in ranges -> require(v is Number && v.toFloat().isFinite() && v.toFloat() in ranges.getValue(k))
                k=="equalizer_state" -> {require(v is String);EqualizerState.decode(JSONObject(v))}
                k=="equalizer_presets" -> {require(v is String);EqualizerState.validatePresets(JSONObject(v))}
                k=="library_sort" -> require(v in listOf("alphabetical","recent"))
                else -> {
                    require(v is String && v.length<500000)
                    val map=JSONObject(v)
                    require(map.length()<=10000)
                    map.keys().forEach {id->val p=map.getJSONObject(id)
                        require(p.getDouble("speed").isFinite() && p.getDouble("speed") in .5..2.0)
                        require(p.getDouble("pitch").isFinite() && p.getDouble("pitch") in .5..2.0)
                    }
                }
            }
        }
        val edit=prefs.edit()
        entries.forEach {(k,v)->when(v) {is Boolean->edit.putBoolean(k,v);is Number->if(k=="appearance_accent")edit.putInt(k,v.toInt().coerceIn(0,5)) else edit.putFloat(k,v.toFloat());is String->edit.putString(k,v)}}
        edit.apply();refresh()
    }
    private fun allowedKey(k: String)=k in setOf("appearance_system","appearance_dark","appearance_accent","appearance_monochrome",
        "player_background_visibility","player_background_blur","text_scale","compact_list","cover_size","restore_position","headphones_resume","previous_restarts",
        "library_auto","library_hide_short","library_filenames","library_sort","gesture_cover","gesture_dismiss","haptic","sound_speed","sound_pitch","sound_linked","equalizer_state","equalizer_presets")
}
