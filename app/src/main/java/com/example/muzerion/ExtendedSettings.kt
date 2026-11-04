package com.example.muzerion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

@Composable
private fun OptionSwitch(title: String,key: String,default: Boolean=false,description: String="") {
    Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {Text(title,color=MuzerionStyle.text);if(description.isNotBlank())Text(description,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Switch(checked=AppOptions.bool(key,default),onCheckedChange={AppOptions.put(key,it)})
    }
}
@Composable
private fun OptionSlider(title: String,key: String,default: Float,range: ClosedFloatingPointRange<Float>,format: (Float)->String) {
    val saved=AppOptions.number(key,default)
    var draft by remember(saved) {mutableFloatStateOf(saved.coerceIn(range))}
    Text("${title} · ${format(draft)}",modifier=Modifier.padding(top=16.dp),color=MuzerionStyle.text)
    MuzerionSeekSlider(draft,{draft=it},{AppOptions.put(key,draft)},range,true)
}
@Composable
internal fun AppearanceOptions() {
    HorizontalDivider(Modifier.padding(vertical=16.dp))
    OptionSlider(tr(R.string.msg_b1e23e1ff0),"text_scale",1f,.85f..1.2f) {"${(it*100).toInt()}%"}
    OptionSwitch(tr(R.string.msg_4f30c70379),"compact_list")
    OptionSlider(tr(R.string.msg_6615431ca4),"cover_size",52f,36f..64f) {"${it.toInt()}"}
}
@Composable
internal fun PlaybackOptions() {
    OptionSwitch(tr(R.string.msg_1280fefc1a),"restore_position",true)
    OptionSwitch(tr(R.string.msg_c000ede895),"headphones_resume")
    OptionSwitch(tr(R.string.msg_f0a95a2bcb),"previous_restarts",true,tr(R.string.msg_f676c627fb))
}
@Composable
internal fun GestureOptions() {
    OptionSwitch(tr(R.string.msg_4fec828bcb),"gesture_cover",true)
    OptionSwitch(tr(R.string.msg_65d9a27202),"gesture_dismiss",true)
    OptionSwitch(tr(R.string.msg_836030b4bd),"haptic",false)
}
@Composable
internal fun LibraryOptions() {
    val context=LocalContext.current
    var status by remember {mutableStateOf("")}
    val primary=context.getSharedPreferences("muzerion_settings",0).getString("music_folder",null)
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri->
        if(uri!=null) try {
            try {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
            catch(_: SecurityException) {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            if(primary==null) {
                context.getSharedPreferences("muzerion_settings",0).edit().putString("music_folder",uri.toString()).apply()
                AppOptions.libraryRevision++
            } else {
                val extra=AppOptions.folders(null).plus(uri.toString()).distinct().filter {it!=primary}
                AppOptions.put("extra_folders",JSONArray(extra).toString())
            }
            status=tr(R.string.msg_b18c5c05ce)
        } catch(_: SecurityException) {status=tr(R.string.msg_3758c6338c)}
    }
    Text(tr(R.string.msg_ae22420fc1),fontSize=18.sp)
    Text(tr(R.string.msg_0bbf909d7a),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    AppOptions.folders(primary).forEach {folder->
        Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text(Uri.decode(folder.substringAfterLast('/')),maxLines=2,fontSize=14.sp);if(folder==primary)Text(tr(R.string.msg_66984540a1),fontSize=11.sp)}
            if(folder!=primary) PlayerIconButton({AppOptions.put("extra_folders",JSONArray(AppOptions.folders(null).filter {it!=folder}).toString())}) {MuzerionIcon("delete",MuzerionStyle.text)}
        }
    }
    PlayerTextButton({picker.launch(null)}) {Text(tr(R.string.msg_585b38ac20))}

    OptionSwitch(tr(R.string.msg_2c7295c565),"library_auto",true)
    OptionSwitch(tr(R.string.msg_2d5ba1ca0f),"library_hide_short")
    OptionSwitch(tr(R.string.msg_8b44c6b425),"library_filenames")
    if(status.isNotBlank())Text(status,fontSize=12.sp)
}
@Composable
internal fun DataOptions() {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var status by remember {mutableStateOf("")};var clear by remember {mutableStateOf(false)}
    var bytes by remember {mutableLongStateOf(0L)}
    suspend fun measure() {bytes=withContext(Dispatchers.IO) {File(context.cacheDir,"muzerion_covers").listFiles()?.sumOf {if(it.isFile)it.length() else 0L} ?: 0L}}
    LaunchedEffect(AppOptions.revision) {measure()}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->if(uri!=null)scope.launch {
        val raw=AppOptions.backup();status=withContext(Dispatchers.IO) {runCatching {context.contentResolver.openOutputStream(uri)?.use {it.write(raw.toByteArray())} ?: error(tr(R.string.msg_fabe7ed7c2));tr(R.string.msg_bd6392e026)}.getOrElse {tr(R.string.msg_b1980f4b88)}}
    }}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null)scope.launch {
        val result=withContext(Dispatchers.IO) {runCatching {context.contentResolver.openInputStream(uri)?.use {readSettingsBackup(it)} ?: error(tr(R.string.msg_fabe7ed7c2))}}
        status=runCatching {AppOptions.restore(result.getOrThrow());tr(R.string.msg_9de070e4dd)}.getOrElse {tr(R.string.msg_8a6ab1131e)}
    }}
    Text(tr(R.string.msg_6b5363eebf, android.text.format.Formatter.formatFileSize(context,bytes)),fontSize=18.sp)

    PlayerTextButton({clear=true}) {Text(tr(R.string.msg_2ac5e3f865))}
    if(clear) AlertDialog(onDismissRequest={clear=false},title={Text(tr(R.string.msg_86ec702586))},text={Text(tr(R.string.msg_2dc29a49f0))},confirmButton={TextButton({clear=false;scope.launch {
        withContext(Dispatchers.IO) {val dir=File(context.cacheDir,"muzerion_covers");check(dir.canonicalFile.parentFile==context.cacheDir.canonicalFile);dir.listFiles()?.filter {it.isFile}?.forEach {it.delete()}}
        clearArtworkMemoryCaches();AppOptions.libraryRevision++;measure();status=tr(R.string.msg_69969d27fa)
    }}) {Text(tr(R.string.msg_98b2073ed1))}},dismissButton={TextButton({clear=false}) {Text(tr(R.string.msg_0ec753be8d))}})
    HorizontalDivider(Modifier.padding(vertical=16.dp))
    Text(tr(R.string.msg_72f931bda2),fontSize=18.sp)
    Text(tr(R.string.msg_c29822ec54),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    PlayerTextButton({export.launch("Muzerion-settings.json")}) {Text(tr(R.string.msg_87ae19737e))}
    PlayerTextButton({import.launch(arrayOf("application/json","text/plain","application/octet-stream"))}) {Text(tr(R.string.msg_7e2c00e93f))}
    if(status.isNotBlank())Text(status,fontSize=13.sp)
}
@Composable
internal fun AboutOptions(player: Player,onUpdates: ()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();var status by remember {mutableStateOf("")}
    val report=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->if(uri!=null)scope.launch {
        val raw=JSONObject().put("application","Muzerion").put("version",BuildConfig.VERSION_NAME).put("android",Build.VERSION.RELEASE).put("api",Build.VERSION.SDK_INT)
            .put("playback_state",player.playbackState).put("playback_error",player.playerError?.errorCodeName ?: "none").put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("abi",System.getProperty("os.arch"))
            .put("settings",JSONObject(AppOptions.backup()).getJSONObject("settings").also {it.remove("track_sound");it.remove("sound_presets");it.remove("equalizer_presets")}).toString(2)
        status=withContext(Dispatchers.IO) {runCatching {context.contentResolver.openOutputStream(uri)?.use {it.write(raw.toByteArray())} ?: error(tr(R.string.msg_fabe7ed7c2));tr(R.string.msg_7c379e8e5c)}.getOrElse {tr(R.string.msg_8ff44cc92f)}}
    }}
    Text("Muzerion ${BuildConfig.VERSION_NAME}",fontSize=20.sp)
    Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\n${System.getProperty("os.arch")}",modifier=Modifier.padding(vertical=14.dp))
    PlayerTextButton(onUpdates) {Text(tr(R.string.msg_7b55599e7a))}
    PlayerTextButton({report.launch("Muzerion-diagnostic.json")}) {Text(tr(R.string.msg_f069478e29))}
    Text(tr(R.string.msg_7f8368a9ad),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider(Modifier.padding(vertical=16.dp))
    Text(tr(R.string.msg_b6685882b1),fontSize=18.sp)
    listOf(tr(R.string.msg_f9bf7b2387),tr(R.string.msg_c77857d3a9),"ZXing Android Embedded · Apache 2.0","NanoHTTPD · BSD 3-Clause").forEach {Text(it,modifier=Modifier.padding(vertical=8.dp),fontSize=13.sp)}
    PlayerTextButton({context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.apache.org/licenses/LICENSE-2.0")))}) {Text("Apache License 2.0")}
    PlayerTextButton({context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/NanoHttpd/nanohttpd/blob/master/LICENSE.md")))}) {Text("BSD 3-Clause · NanoHTTPD")}
    if(status.isNotBlank())Text(status,fontSize=13.sp)
}

private fun readSettingsBackup(input: java.io.InputStream): String {
    val output=java.io.ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    while(true) {val n=input.read(buffer);if(n<0)break;require(output.size()+n<=1024*1024) {tr(R.string.msg_2453e775fb)};output.write(buffer,0,n)}
    return output.toString("UTF-8")
}
