package com.example.muzerion

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.math.roundToInt
import kotlinx.coroutines.delay


@Composable
internal fun PlaybackQueueView(
    player: Player,
    modifier: Modifier = Modifier,
    onScrollActivity: (Boolean) -> Unit = {},
    header: @Composable ColumnScope.() -> Unit
) {
    var revision by remember(player) { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) revision++
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val rows = remember(player, revision) { playbackOrder(player).map { Pair(it, player.getMediaItemAt(it)) } }
    var preview by remember(player) { mutableStateOf(rows) }
    var draggedId by remember(player) { mutableStateOf<String?>(null) }
    var animateReorder by remember(player) { mutableStateOf(false) }
    var reorderGeneration by remember(player) { mutableIntStateOf(0) }
    LaunchedEffect(reorderGeneration,draggedId) {
        if(draggedId==null && animateReorder) {delay(700);animateReorder=false}
    }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var dragStartOrdinal by remember { mutableIntStateOf(0) }
    val latestRows by rememberUpdatedState(rows)
    val rowStepPx = with(LocalDensity.current) { 80.dp.toPx() }
    LaunchedEffect(rows) {
        if(draggedId == null || rows.map { it.second.mediaId }.toSet() != preview.map { it.second.mediaId }.toSet()) {
            preview=rows
            draggedId=null
            dragDistance=0f
        }
    }
    val current = remember(player, revision) { player.currentMediaItemIndex }
    val currentOrdinal = rows.indexOfFirst { it.first == current }
    val currentId=rows.firstOrNull {it.first==current}?.second?.mediaId
    LaunchedEffect(currentId) {animateReorder=false}
    val queueScroll = rememberLazyListState()
    val reportScroll by rememberUpdatedState(onScrollActivity)
    LaunchedEffect(queueScroll) {
        snapshotFlow {queueScroll.isScrollInProgress}.collect {reportScroll(it)}
    }
    var showHistory by remember(player) { mutableStateOf(false) }
    val firstVisibleOrdinal = if(showHistory) 0 else currentOrdinal.coerceAtLeast(0)
    val latestFirstVisible by rememberUpdatedState(firstVisibleOrdinal)
    val visibleRows = preview.drop(firstVisibleOrdinal)
    LazyColumn(modifier,state=queueScroll,verticalArrangement=Arrangement.spacedBy(4.dp),
        contentPadding=PaddingValues(bottom=28.dp)) {
        item(key="player-header",contentType="player-header") {
            Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,content=header)
        }
        item(key="queue-heading",contentType="queue-heading") {
        Column(Modifier.fillMaxWidth().padding(top=28.dp)) {
        HorizontalDivider(color=MuzerionStyle.text.copy(alpha=0.12f))
        Row(Modifier.fillMaxWidth().padding(top=20.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
            MuzerionIcon("music",MuzerionStyle.accent)
            Text(tr(R.string.msg_aec93b16ba),color=MuzerionStyle.text,fontSize=21.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(start=12.dp).weight(1f))
            Text(tr(R.string.msg_6337af1839, (rows.size-currentOrdinal-1).coerceAtLeast(0)),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)
        }
        Text(if(player.shuffleModeEnabled) tr(R.string.msg_7e006cbdfe) else tr(R.string.msg_aac420e073),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)
        Text(tr(R.string.msg_9a191a5aa5),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=11.sp,modifier=Modifier.padding(top=4.dp,bottom=12.dp))
        if(currentOrdinal>0) PlayerTextButton(onClick={showHistory=!showHistory}) {
            Text(if(showHistory) tr(R.string.msg_366d374ab0) else tr(R.string.msg_d1d92e48e7, currentOrdinal))
        }
        }
        }
        if(rows.isEmpty()) item(key="empty-queue") {Text(tr(R.string.msg_6e1a1d08e3),color=MuzerionStyle.text)}
        itemsIndexed(visibleRows,key={ _,row -> row.second.mediaId },contentType={_,_->"queue-track"}) { visibleOrdinal,row ->
                val ordinal=firstVisibleOrdinal+visibleOrdinal
                var menu by remember(row.second.mediaId) { mutableStateOf(false) }
                val dragging = draggedId == row.second.mediaId
                val dragY = if(dragging) dragStartOrdinal*rowStepPx + dragDistance - ordinal*rowStepPx else 0f
                Row(Modifier.animateItem(
                    fadeInSpec=null,fadeOutSpec=null,
                    placementSpec=if(dragging || !animateReorder) null else spring(stiffness=Spring.StiffnessMediumLow)
                ).zIndex(if(dragging) 1f else 0f)
                    .fillMaxWidth().height(76.dp).graphicsLayer {
                        translationY=dragY
                        shadowElevation=if(dragging) 10.dp.toPx() else 0f
                        shape=RoundedCornerShape(12.dp)
                    }
                    .background(if(row.first==current) MuzerionStyle.accent.copy(alpha=0.13f).compositeOver(MuzerionStyle.panel) else MuzerionStyle.panel,RoundedCornerShape(12.dp))
                    .clickable(enabled=draggedId==null) {
                        if(row.first==player.currentMediaItemIndex) togglePlayback(player)
                        else { player.seekTo(row.first,0L);player.play() }
                    }.padding(start=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    MuzerionArtwork(row.second.mediaMetadata.artworkUri,
                        Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),selected=row.first==current)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.second.mediaMetadata.title?.toString() ?: tr(R.string.msg_32b74a3c47),color=if(row.first==current)MuzerionStyle.accent else MuzerionStyle.text,
                            maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=14.sp)
                        Text(row.second.mediaMetadata.artist?.toString().orEmpty(),color=MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp)
                        Text(when {row.first==current -> tr(R.string.msg_0a110db57a);ordinal<currentOrdinal -> tr(R.string.msg_e2064b04cf);else -> tr(R.string.msg_6c554541fc, ordinal-currentOrdinal)},
                            color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=10.sp)
                    }
                    Box {
                        // Foundation clickable keeps accessibility semantics on this node.
                        // Material3 ChildSemantics can outlive a removed LazyColumn row during a seek.
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))
                            .semantics {contentDescription=tr(R.string.msg_a76d166649)}
                            .clickable(enabled=draggedId==null,role=Role.Button) {menu=true},
                            contentAlignment=Alignment.Center) {Text("⋮",color=MuzerionStyle.text,fontSize=24.sp)}
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                            DropdownMenuItem(text={Text(tr(R.string.msg_870427fc5c))},onClick={
                                menu=false;animateReorder=true;reorderGeneration++
                                player.currentMediaItem?.let { moveQueuedTrack(player,row.second.mediaId,it.mediaId,true) }
                            },enabled=row.first!=current)
                            DropdownMenuItem(text={Text(tr(R.string.msg_17e44239e9))},onClick={menu=false;animateReorder=true;reorderGeneration++;moveQueuedTrack(player,row.second.mediaId,rows[ordinal-1].second.mediaId,false)},enabled=ordinal>0)
                            DropdownMenuItem(text={Text(tr(R.string.msg_e0f1717450))},onClick={menu=false;animateReorder=true;reorderGeneration++;moveQueuedTrack(player,row.second.mediaId,rows[ordinal+1].second.mediaId,true)},enabled=ordinal<rows.lastIndex)
                            DropdownMenuItem(text={Text(tr(R.string.msg_f98ccec353))},onClick={menu=false;player.removeMediaItem(row.first)})
                        }
                    }
                    Box(Modifier.size(40.dp).semantics { contentDescription=tr(R.string.msg_40faba460d) }
                        .pointerInput(row.second.mediaId,player,rowStepPx) {
                            detectDragGesturesAfterLongPress(
                                onDragStart={
                                    animateReorder=true
                                    reorderGeneration++
                                    preview=latestRows
                                    dragStartOrdinal=preview.indexOfFirst { it.second.mediaId==row.second.mediaId }
                                    dragDistance=0f
                                    draggedId=row.second.mediaId
                                },
                                onDrag={change,amount ->
                                    change.consume()
                                    if(draggedId==row.second.mediaId) {
                                        dragDistance+=amount.y
                                        val source=preview.indexOfFirst { it.second.mediaId==row.second.mediaId }
                                        val target=(dragStartOrdinal+(dragDistance/rowStepPx).roundToInt()).coerceIn(latestFirstVisible.coerceAtMost(preview.lastIndex),preview.lastIndex)
                                        if(source>=0 && source!=target) {
                                            preview=preview.toMutableList().apply { add(target,removeAt(source)) }
                                        }
                                    }
                                },
                                onDragCancel={animateReorder=false;preview=latestRows;draggedId=null;dragDistance=0f},
                                onDragEnd={
                                    val original=latestRows
                                    val source=original.indexOfFirst { it.second.mediaId==row.second.mediaId }
                                    val target=preview.indexOfFirst { it.second.mediaId==row.second.mediaId }
                                    if(source>=0 && target>=0 && source!=target) {
                                        moveQueuedTrack(player,row.second.mediaId,original[target].second.mediaId,target>source)
                                    }
                                    draggedId=null
                                    dragDistance=0f
                                })
                        },contentAlignment=Alignment.Center) { Text("≡",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=23.sp) }
                }
            }
    }
}
