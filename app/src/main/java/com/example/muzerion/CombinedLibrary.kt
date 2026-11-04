package com.example.muzerion

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect

internal fun observeCombinedLibrary(context: Context,primary: String,deferScan: Boolean,manual: Boolean)=flow {
    val folders=AppOptions.folders(primary)
    val states=folders.associateWith {TrackListState(loading=false,tracks=readCachedMusicTracks(context,it))}.toMutableMap()
    fun combined()=TrackListState(loading=false,refreshing=states.values.any {it.refreshing||it.loading},
        tracks=states.values.flatMap {it.tracks}.distinctBy {track->
            runCatching {"${track.uri.authority}:${android.provider.DocumentsContract.getDocumentId(track.uri)}"}.getOrDefault(track.uri.toString())
        },error=states.values.mapNotNull {it.error}.distinct().joinToString("\n").ifBlank {null})
    emit(combined())
    if(!AppOptions.bool("library_auto",true) && !manual && states.values.any {it.tracks.isNotEmpty()}) return@flow
    for(folder in folders) {
        observeMusicLibrary(context,Uri.parse(folder),deferScan).collect {states[folder]=it;emit(combined())}
    }
}.flowOn(Dispatchers.IO)
