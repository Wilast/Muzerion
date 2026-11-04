package com.example.muzerion

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.media3.common.Player
import kotlinx.coroutines.delay

@Composable
internal fun largeArtworkTarget(): Int =
    (LocalConfiguration.current.screenWidthDp*LocalDensity.current.density).toInt().coerceAtMost(1024)

@Composable
internal fun PrefetchAdjacentArtwork(player: Player,scrolling: Boolean) {
    val context=LocalContext.current.applicationContext
    val target=largeArtworkTarget()
    var revision by remember(player) { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener=object : Player.Listener {
            override fun onEvents(player: Player,events: Player.Events) {
                if(events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                    events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) revision++
            }
        }
        player.addListener(listener)
        onDispose {player.removeListener(listener)}
    }
    val adjacent=remember(player,revision) {
        listOf(player.nextMediaItemIndex,player.previousMediaItemIndex)
            .filter {it in 0 until player.mediaItemCount}
            .mapNotNull {player.getMediaItemAt(it).mediaMetadata.artworkUri}
            .distinct()
    }
    LaunchedEffect(adjacent,target,scrolling) {
        if(scrolling) return@LaunchedEffect
        // Let the current transition finish before preparing at most two neighbours.
        delay(600)
        for(uri in adjacent) loadArtworkBitmap(context,uri,target)
    }
}
