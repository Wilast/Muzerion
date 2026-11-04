package com.example.muzerion

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray

internal object EqualizerState {
    @Volatile var current=EqConfig()
        private set
    fun load(context: Context) {current=runCatching {decode(JSONObject(context.getSharedPreferences("muzerion_settings",0).getString("equalizer_state","{}") ?: "{}"))}.getOrDefault(EqConfig())}
    fun save(config: EqConfig) {current=config;AppOptions.put("equalizer_state",encode(config).toString())}
    fun encode(c: EqConfig)=JSONObject().put("enabled",c.enabled).put("bands",JSONArray(c.bands)).put("preamp",c.preamp).put("auto",c.autoLevel).put("name",c.name)
    fun decode(o: JSONObject): EqConfig {
        val a=o.optJSONArray("bands") ?: JSONArray(List(10){0})
        require(a.length()==10)
        val values=(0..9).map {a.getDouble(it)}
        return EqConfig(o.optBoolean("enabled",false),values,o.optDouble("preamp",0.0),o.optBoolean("auto",true),o.optString("name","Ровно").take(40))
    }
    fun validatePresets(o: JSONObject) {
        require(o.length()<=50)
        o.keys().forEach {require(it.isNotBlank() && it.length<=40);decode(o.getJSONObject(it))}
    }
    fun presets(): JSONObject=runCatching {JSONObject(AppOptions.text("equalizer_presets","{}"))}.getOrDefault(JSONObject())
}
