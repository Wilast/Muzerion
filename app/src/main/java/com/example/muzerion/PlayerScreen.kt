package com.example.muzerion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private val PlayerAccent get() = MuzerionStyle.color(0xFFB9A0FF)
private val PlayerMuted get() = MuzerionStyle.color(0xFF96939F)

private val artworkCache = object : LruCache<String, Bitmap>(
    16 * 1024 * 1024
) {
    override fun sizeOf(
        key: String,
        value: Bitmap
    ): Int {
        return value.allocationByteCount
    }
}

fun togglePlayback(player: Player) {
    if (player.playWhenReady) {
        player.pause()
        return
    }

    if (player.playbackState == Player.STATE_ENDED) {
        player.seekTo(0L)
    }

    if (player.playbackState == Player.STATE_IDLE) {
        player.prepare()
    }

    player.play()
}

fun playQueue(
    player: Player,
    tracks: List<Track>,
    selectedTrack: Track
) {
    if (
        player.currentMediaItem?.mediaId ==
        selectedTrack.uri.toString()
    ) {
        togglePlayback(player)
        return
    }

    val startIndex = tracks.indexOfFirst {
        it.uri == selectedTrack.uri
    }

    if (startIndex < 0) return

    val items = tracks.map { it.toMuzerionMediaItem() }

    player.setMediaItems(items, startIndex, 0L)
    player.prepare()
    player.play()
}

fun Track.toMuzerionMediaItem(): MediaItem {
    val track = this
        val extras = Bundle().apply {
            putBoolean("explicit_queue", true)
            putString("file_name", track.name)
            putLong("duration_ms", track.durationMs)
            putLong("size_bytes", track.sizeBytes)
            putLong("added_at", track.addedAt)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(
                track.artist.takeIf { it.isNotBlank() }
            )
            .setAlbumTitle(
                track.album.takeIf { it.isNotBlank() }
            )
            .setArtworkUri(track.artworkUri)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(track.uri.toString())
            .setUri(track.uri)
            .setMediaMetadata(metadata)
            .build()
    }


// Repair the old one-track restore after the library is available, preserving playback.
fun restoreMissingQueue(player: Player, tracks: List<Track>) {
    if (player.mediaItemCount != 1 || tracks.size < 2) return
    val current = player.currentMediaItem ?: return
    if (current.mediaMetadata.extras?.getBoolean("explicit_queue") == true) return
    val uri = current.localConfiguration?.uri?.toString() ?: current.mediaId
    val index = tracks.indexOfFirst { it.uri.toString() == uri }
    if (index < 0) return
    val position = player.currentPosition.coerceAtLeast(0L)
    val playing = player.playWhenReady
    player.setMediaItems(tracks.map { it.toMuzerionMediaItem() }, index, position)
    player.prepare()
    player.playWhenReady = playing
}

private val artworkDecoders = Semaphore(2)

// Shared thumbnails are decoded off the UI thread and reused by the ambient backdrop.
internal suspend fun loadArtworkBitmap(context: android.content.Context, uri: Uri, target: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        val key="${uri}@${target}"
        artworkCache.get(key) ?: artworkDecoders.withPermit {
            artworkCache.get(key) ?: try {
                val resolver=context.contentResolver
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
                var sample=1
                while(bounds.outWidth/(sample*2)>=target || bounds.outHeight/(sample*2)>=target) sample*=2
                val decoded=resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample })
                }
                val bitmap=decoded?.let { image ->
                    val edge=maxOf(image.width,image.height)
                    if(edge>target) {
                        val scale=target.toFloat()/edge
                        Bitmap.createScaledBitmap(image,(image.width*scale).toInt().coerceAtLeast(1),
                            (image.height*scale).toInt().coerceAtLeast(1),true).also {if(it!==image) image.recycle()}
                    } else image
                }
                if(bitmap!=null) {
                    coroutineContext.ensureActive()
                    bitmap.prepareToDraw()
                    artworkCache.put(key,bitmap)
                }
                bitmap
            } catch (_: IOException) { null }
            catch (cancelled: CancellationException) {throw cancelled}
            catch (_: RuntimeException) { null }
        }
    }


@Composable
fun MuzerionArtwork(
    artworkUri: Uri?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    large: Boolean = false
) {
    val context = LocalContext.current
    // Decode to an actual bounded size, not merely the nearest inSampleSize power of two.
    val target = if(large) largeArtworkTarget() else 160
    val cacheKey = artworkUri?.let { "${it}@${target}" }

    val bitmap by produceState<Bitmap?>(
        initialValue = cacheKey?.let {
            artworkCache.get(it)
        },
        key1 = artworkUri,
        key2 = target
    ) {
        value = cacheKey?.let {
            artworkCache.get(it)
        }

        if (artworkUri != null && value == null) {
            value = loadArtworkBitmap(context, artworkUri, target)
        }
    }

    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                if (selected) {
                    listOf(
                        MuzerionStyle.color(0xFF7860B0),
                        MuzerionStyle.color(0xFF44315F)
                    )
                } else {
                    listOf(
                        MuzerionStyle.color(0xFF393043),
                        MuzerionStyle.color(0xFF24232D)
                    )
                }
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap

        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = "♪",
                    color = PlayerAccent,
                    fontSize = (maxWidth.value * 0.42f)
                        .coerceIn(24f, 110f).sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
fun PlayerTransitionHost(
    player: Player,
    track: Track?,
    library: @Composable (
        openPlayer: () -> Unit,
        hideMiniCover: Boolean,
        freezeLibraryPlayback: Boolean,
        onMiniCoverBounds: (Rect) -> Unit
    ) -> Unit
) {
    var visible by remember {
        mutableStateOf(false)
    }

    SideEffect { MuzerionNav.playerExpanded = visible }
    DisposableEffect(Unit) { onDispose { MuzerionNav.playerExpanded = false } }

    var targetProgress by remember {
        mutableFloatStateOf(0f)
    }

    var dragging by remember {
        mutableStateOf(false)
    }

    val miniBounds = remember { mutableStateOf(Rect.Zero) }
    val largeBounds = remember { mutableStateOf(Rect.Zero) }
    val rootOrigin = remember { mutableStateOf(Offset.Zero) }
    val coverReady by remember {
        derivedStateOf(structuralEqualityPolicy()) {
            miniBounds.value.width>0f && miniBounds.value.height>0f &&
                largeBounds.value.width>0f && largeBounds.value.height>0f
        }
    }

    var swipeDirection by remember {
        mutableIntStateOf(1)
    }

    var lastSwitchTime by remember {
        mutableLongStateOf(0L)
    }

    val progress = animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = if (dragging) {
            snap()
        } else {
            tween(
                durationMillis = 420,
                easing = FastOutSlowInEasing
            )
        },
        label = "playerExpansion"
    )

    val showCoverInline by remember {
        derivedStateOf(structuralEqualityPolicy()) {
            visible && !dragging && targetProgress>=1f && progress.value>=0.999f
        }
    }

    /*
     * Убираем слой, когда:
     * 1. палец уже отпущен;
     * 2. выбран закрытый режим;
     * 3. обложка дошла до маленького размера.
     *
     * Это работает и тогда, когда прогресс достиг нуля
     * ещё до отпускания пальца.
     */
    LaunchedEffect(Unit) {
        snapshotFlow {
            visible &&
                    !dragging &&
                    targetProgress <= 0f &&
                    progress.value <= 0.001f
        }.collect { shouldHide ->
            if (shouldHide) {
                visible = false
            }
        }
    }

    val closePlayer: () -> Unit = {
        targetProgress = 0f
        dragging = false
    }

    val switchTrack: (Int) -> Unit = { direction ->
        val now = SystemClock.elapsedRealtime()

        val available = if (direction > 0) {
            player.hasNextMediaItem()
        } else {
            player.hasPreviousMediaItem()
        }

        if (
            available &&
            now - lastSwitchTime >= 300L
        ) {
            lastSwitchTime = now
            swipeDirection = direction

            if (direction > 0) {
                player.seekToNextMediaItem()
            } else {
                player.seekToPreviousMediaItem()
            }
        }
    }

    val latestSwitchTrack by rememberUpdatedState(switchTrack)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                if (
                    reason ==
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason ==
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                ) {
                    swipeDirection = 1
                }
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    BackHandler(enabled = visible) {
        closePlayer()
    }

    LaunchedEffect(track == null) {
        if (track == null) {
            dragging = false
            targetProgress = 0f
            visible = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                rootOrigin.value = it.positionInRoot()
            }
    ) {
        val density = LocalDensity.current

        val dragDistance = maxOf(
            with(density) { 240.dp.toPx() },
            constraints.maxHeight * 0.35f
        )

        val swipeThreshold = with(density) {
            56.dp.toPx()
        }

        // Keep layout and cover bounds for the closing transition, but do not draw
        // the library under an opaque, fully expanded player.
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha=if(visible && progress.value>=0.999f) 0f else 1f
        }) {
        library(
            {
                if (track != null) {
                    dragging = false
                    visible = true
                    targetProgress = 1f
                }
            },
            visible,
            showCoverInline,
            { bounds ->
                miniBounds.value = bounds
            }
        )
        }

        val selectedTrack = track

        if (visible && selectedTrack != null) {
            val coverGesture = Modifier
                .onGloballyPositioned {
                    largeBounds.value = Rect(
                        it.positionInRoot(),
                        Size(
                            it.size.width.toFloat(),
                            it.size.height.toFloat()
                        )
                    )
                }
                .pointerInput(swipeThreshold,AppOptions.bool("gesture_cover",true)) {
                    if(!AppOptions.bool("gesture_cover",true)) return@pointerInput
                    var distance = 0f

                    detectHorizontalDragGestures(
                        onDragStart = {
                            distance = 0f
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            distance += amount
                        },
                        onDragEnd = {
                            if (
                                visible &&
                                progress.value >= 0.95f
                            ) {
                                when {
                                    distance < -swipeThreshold -> {
                                        latestSwitchTrack(1)
                                    }

                                    distance > swipeThreshold -> {
                                        latestSwitchTrack(-1)
                                    }
                                }
                            }

                            distance = 0f
                        },
                        onDragCancel = {
                            distance = 0f
                        }
                    )
                }

            val dismissConnection = remember(dragDistance) {
                object : NestedScrollConnection {
                    fun settle(velocity: Float = 0f) {
                        if (dragging) {
                            targetProgress = if (targetProgress < 0.72f || velocity > 1200f) 0f else 1f
                            dragging = false
                        }
                    }
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source == NestedScrollSource.UserInput && dragging) {
                            targetProgress = (targetProgress - available.y / dragDistance).coerceIn(0f, 1f)
                            return Offset(0f, available.y)
                        }
                        return Offset.Zero
                    }
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (source == NestedScrollSource.UserInput && available.y > 0f) {
                            if (!dragging) { targetProgress = progress.value; dragging = true }
                            targetProgress = (targetProgress - available.y / dragDistance).coerceIn(0f, 1f)
                            return Offset(0f, available.y)
                        }
                        return Offset.Zero
                    }
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (!dragging) return Velocity.Zero
                        settle(available.y)
                        return Velocity(0f, available.y)
                    }
                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                        settle(available.y)
                        return Velocity.Zero
                    }
                }
            }

            // Native stretch deforms title and artist independently from the floating cover.
            // Keep normal scrolling and our explicit pull-to-close gesture without stretching.
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
            ExpandedPlayer(
                player = player,
                dismissModifier = if(AppOptions.bool("gesture_dismiss",true)) Modifier.nestedScroll(dismissConnection) else Modifier,
                track = selectedTrack,
                progress = progress,
                coverModifier = coverGesture,
                showCoverInline = showCoverInline,
                coverDirection = swipeDirection,
                onClose = closePlayer,
                onPrevious = {
                    if (AppOptions.bool("previous_restarts",true) && player.currentPosition > 3000L) {
                        player.seekTo(0L)
                    } else if (player.hasPreviousMediaItem()) {
                        switchTrack(-1)
                    } else {
                        player.seekTo(0L)
                    }
                },
                onNext = {
                    switchTrack(1)
                }
            )
            }

            if (coverReady && !showCoverInline) {
                FloatingCover(
                    track=selectedTrack,
                    miniBounds=miniBounds,
                    largeBounds=largeBounds,
                    rootOrigin=rootOrigin,
                    progress=progress,
                    direction=swipeDirection
                )
            }
        }
    }
}

@Composable
private fun FloatingCover(
    track: Track,
    miniBounds: State<Rect>,
    largeBounds: State<Rect>,
    rootOrigin: State<Offset>,
    progress: State<Float>,
    direction: Int
) {
    // Position changes are read only by graphicsLayer. Only real size changes
    // remeasure the cover; scrolling no longer recomposes the entire player.
    val coverSize by remember(largeBounds) { derivedStateOf(structuralEqualityPolicy()) { largeBounds.value.size } }
    val density = LocalDensity.current

    val width = with(density) {
        coverSize.width.toDp()
    }

    val height = with(density) {
        coverSize.height.toDp()
    }

    AnimatedCoverArtwork(track,direction,
        modifier = Modifier
            .size(width, height)
            .graphicsLayer {
                val origin=rootOrigin.value
                val correction=Offset(-origin.x,-origin.y)
                val mini=miniBounds.value.translate(correction)
                val large=largeBounds.value.translate(correction)
                val p = progress.value.coerceIn(0f, 1f)

                val currentWidth = mix(
                    mini.width,
                    large.width,
                    p
                )

                val currentHeight = mix(
                    mini.height,
                    large.height,
                    p
                )

                val horizontalScale =
                    currentWidth / large.width

                transformOrigin = TransformOrigin(0f, 0f)
                translationX = mix(mini.left, large.left, p)
                translationY = mix(mini.top, large.top, p)
                scaleX = horizontalScale
                scaleY = currentHeight / large.height

                shape = RoundedCornerShape(
                    (
                            mix(14f, 0f, p) /
                                    horizontalScale.coerceAtLeast(0.01f)
                            ).dp
                )

                clip = true
            }
    )
}
@Composable
private fun AnimatedCoverArtwork(track: Track,direction: Int,modifier: Modifier) {
    AnimatedContent(
        targetState = track,
        contentKey = {
            it.uri.toString()
        },
        transitionSpec = {
            (
                    slideInHorizontally(
                        animationSpec = tween(280)
                    ) { fullWidth ->
                        fullWidth * direction
                    } + fadeIn(tween(220))
                    ).togetherWith(
                    slideOutHorizontally(
                        animationSpec = tween(280)
                    ) { fullWidth ->
                        -fullWidth * direction
                    } + fadeOut(tween(220))
                )
        },
        label = "coverTrackChange",
        modifier=modifier
    ) { displayedTrack ->
        MuzerionArtwork(displayedTrack.artworkUri,Modifier.fillMaxSize(),selected=true,large=true)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedPlayer(
    player: Player,
    dismissModifier: Modifier,
    track: Track,
    progress: State<Float>,
    coverModifier: Modifier,
    showCoverInline: Boolean,
    coverDirection: Int,
    onClose: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    var playerScrolling by remember(player) {mutableStateOf(false)}
    PrefetchAdjacentArtwork(player,playerScrolling)
    val context = LocalContext.current
    var fileMenu by remember { mutableStateOf(false) }
    var soundPanel by remember { mutableStateOf(false) }
    var equalizerPanel by remember {mutableStateOf(false)}
    if(equalizerPanel) EqualizerSheet {equalizerPanel=false}
    if(soundPanel) PlaybackSoundSheet(player) {soundPanel=false}
    var showFileInfo by remember(track.uri) { mutableStateOf(false) }
    if (showFileInfo) TrackFileInfoSheet(track) { showFileInfo=false }

    var isPlaying by remember(player) {
        mutableStateOf(player.isPlaying)
    }

    var shuffleEnabled by remember(player) {
        mutableStateOf(player.shuffleModeEnabled)
    }

    var hasNext by remember(player) {
        mutableStateOf(player.hasNextMediaItem())
    }

    var errorMessage by remember {
        mutableStateOf<String?>(null)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events
            ) {
                isPlaying = player.isPlaying
                shuffleEnabled = player.shuffleModeEnabled
                hasNext = player.hasNextMediaItem()

                if (
                    events.contains(
                        Player.EVENT_MEDIA_ITEM_TRANSITION
                    )
                ) {
                    errorMessage = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMessage =
                    tr(R.string.msg_bab4cc4e1d, error.errorCodeName)
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(dismissModifier)
            .graphicsLayer {
                alpha = progress.value.coerceIn(0f, 1f)
            }
            .background(MuzerionStyle.background)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {})
            }
    ) {
        AmbientArtwork(track.artworkUri,maxWidth.value/maxHeight.value,Modifier.matchParentSize())
        val coverSize = minOf(
            maxWidth - 56.dp,
            420.dp,
            maxHeight * 0.43f
        ).coerceAtLeast(80.dp)

        PlaybackQueueView(
            player=player,
            onScrollActivity={playerScrolling=it},
            modifier=Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=28.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = 8.dp,
                        bottom = 24.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr(R.string.msg_d49a9545fb),
                    color = PlayerMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )

                PlayerIconButton(onClick={equalizerPanel=true},modifier=Modifier.semantics {contentDescription=tr(R.string.msg_8005fc4871)}) {
                    MuzerionIcon("tune",PlayerAccent)
                }
                PlayerIconButton(onClick={soundPanel=true},modifier=Modifier.semantics {contentDescription=tr(R.string.msg_007be3a0e3)}) {
                    MuzerionIcon("speed",PlayerAccent)
                }
                PlayerIconButton(onClick=onClose,modifier=Modifier.semantics {contentDescription=tr(R.string.msg_590b94553f)}) {
                    MuzerionIcon("down",PlayerAccent)
                }
            }

            Box(
                modifier = Modifier
                    .size(coverSize)
                    .then(coverModifier)
            ) {
                if(showCoverInline) AnimatedCoverArtwork(track,coverDirection,Modifier.fillMaxSize())
            }

            Spacer(Modifier.height(28.dp))

            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(
                text = track.title,
                fontSize = 27.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .basicMarquee(iterations = Int.MAX_VALUE)
            )

                Box {
                    PlayerIconButton(onClick={fileMenu=true},modifier=Modifier.semantics { contentDescription=tr(R.string.msg_2022790464) }) {
                        Text("⋮",fontSize=28.sp,color=MuzerionStyle.text)
                    }
                    DropdownMenu(expanded=fileMenu,onDismissRequest={fileMenu=false}) {
                        DropdownMenuItem(text={Text(tr(R.string.msg_cc52f76563))},leadingIcon={MuzerionIcon("info",MuzerionStyle.text)},
                            onClick={fileMenu=false;showFileInfo=true})
                    }
                }
            }

            Text(
                text = track.artist.ifBlank {
                    tr(R.string.msg_42a1e6c35f)
                },
                fontSize = 16.sp,
                color = PlayerMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            Spacer(Modifier.height(24.dp))

            PlayerProgress(player,track)

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RepeatTrackButton(player)

                PlayerIconButton(
                    onClick = {
                        errorMessage = null
                        onPrevious()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription =
                                tr(R.string.msg_8e1abb9475)
                        }
                ) {
                    MuzerionIcon("previous",PlayerAccent,Modifier.size(30.dp))
                }

                PlayerIconButton(
                    onClick = {
                        errorMessage = null
                        togglePlayback(player)
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .semantics {
                            contentDescription = if (isPlaying) {
                                tr(R.string.msg_03498e395a)
                            } else {
                                tr(R.string.msg_edf6515d2e)
                            }
                        },
                    containerColor = PlayerAccent,
                    contentColor = MuzerionStyle.color(0xFF211337)
                ) {
                    Text(
                        text = if (isPlaying) "Ⅱ" else "▶",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                PlayerIconButton(
                    enabled = hasNext,
                    onClick = {
                        errorMessage = null
                        onNext()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription =
                                tr(R.string.msg_c97fa8b29b)
                        }
                ) {
                    MuzerionIcon("next",if(hasNext) PlayerAccent else MuzerionStyle.color(0xFF49434F),Modifier.size(30.dp))
                }
                PlayerToggleButton(
                    checked = shuffleEnabled,
                    onCheckedChange = { enabled ->
                        player.shuffleModeEnabled = enabled
                        shuffleEnabled = enabled

                        context.getSharedPreferences(
                            "muzerion_settings",
                            android.content.Context.MODE_PRIVATE
                        ).edit()
                            .putBoolean("shuffle_enabled", enabled)
                            .apply()
                    },
                    modifier = Modifier
                        .padding(0.dp)
                        .size(44.dp)
                        .semantics {
                            contentDescription = tr(R.string.msg_d576eef442)
                        }
                ) {
                    ShuffleIcon(
                        tint = if (shuffleEnabled) {
                            PlayerAccent
                        } else {
                            PlayerMuted
                        }
                    )
                }
            }


            errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

        }
    }
}

@Composable
private fun PlayerProgress(player: Player, track: Track) {
    var position by remember(player) {
        mutableStateOf(
            player.currentPosition.coerceAtLeast(0L)
        )
    }

    var duration by remember(player) {
        mutableStateOf(
            player.duration.coerceAtLeast(0L)
        )
    }

    var isSeeking by remember {
        mutableStateOf(false)
    }

    var seekPosition by remember {
        mutableFloatStateOf(0f)
    }

    DisposableEffect(player) {
        val listener=object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                position=player.currentPosition.coerceAtLeast(0L)
                duration=player.duration.coerceAtLeast(0L)
                if(events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) isSeeking=false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(300)
        }
    }

    val effectiveDuration = duration
        .takeIf { it > 0L }
        ?: track.durationMs

    val maximum = effectiveDuration
        .coerceAtLeast(1L)
        .toFloat()

    val displayedPosition = if (isSeeking) {
        seekPosition
    } else {
        position.toFloat()
    }.coerceIn(0f, maximum)

    MuzerionSeekSlider(
        value = displayedPosition,
        onValueChange = {
            isSeeking = true
            seekPosition = it
        },
        onValueChangeFinished = {
            if (isSeeking) {
                val target = seekPosition.toLong()
                    .coerceIn(
                        0L,
                        effectiveDuration.coerceAtLeast(0L)
                    )

                player.seekTo(target)
                position = target
                isSeeking = false
            }
        },
        valueRange = 0f..maximum,
        enabled = effectiveDuration > 0L &&
                player.isCommandAvailable(
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
                ),
        modifier = Modifier.semantics {
            contentDescription = tr(R.string.msg_f86e2732ad)
        }
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = formatMuzerionTime(
                displayedPosition.toLong()
            ),
            color = PlayerMuted,
            fontSize = 12.sp
        )

        Text(
            text = formatMuzerionTime(effectiveDuration),
            color = PlayerMuted,
            fontSize = 12.sp
        )
    }

}

@Composable
private fun ShuffleIcon(tint: Color) {
    Canvas(Modifier.size(26.dp)) {
        val unit = size.width / 24f

        val lines = Path().apply {
            moveTo(3f * unit, 6f * unit)
            lineTo(6f * unit, 6f * unit)
            lineTo(17f * unit, 18f * unit)
            lineTo(21f * unit, 18f * unit)

            moveTo(3f * unit, 18f * unit)
            lineTo(6f * unit, 18f * unit)
            lineTo(17f * unit, 6f * unit)
            lineTo(21f * unit, 6f * unit)

            moveTo(18f * unit, 3f * unit)
            lineTo(21f * unit, 6f * unit)
            lineTo(18f * unit, 9f * unit)

            moveTo(18f * unit, 15f * unit)
            lineTo(21f * unit, 18f * unit)
            lineTo(18f * unit, 21f * unit)
        }

        drawPath(
            path = lines,
            color = tint,
            style = Stroke(
                width = 1.8f * unit,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

private fun mix(
    start: Float,
    end: Float,
    progress: Float
): Float {
    return start + (end - start) * progress
}

fun formatMuzerionTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds / 60L % 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        String.format(
            Locale.ROOT,
            "%d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    } else {
        String.format(
            Locale.ROOT,
            "%d:%02d",
            totalSeconds / 60L,
            seconds
        )
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MuzerionSeekSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dragging by interactionSource.collectIsDraggedAsState()
    val radius by animateDpAsState(
        targetValue = if (dragging) 10.dp else 7.dp,
        animationSpec = tween(140),
        label = "SeekThumbRadius"
    )
    val accent = if (enabled) PlayerAccent else PlayerMuted
    val inactive = MuzerionStyle.color(0xFF39313F)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        thumb = {
            Canvas(Modifier.size(24.dp)) {
                drawCircle(color = accent, radius = radius.toPx())
                drawCircle(color = Color.White.copy(alpha = 0.65f), radius = 2.5.dp.toPx())
            }
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val range = state.valueRange.endInclusive - state.valueRange.start
                val fraction = if (range > 0f) {
                    ((state.value - state.valueRange.start) / range).coerceIn(0f, 1f)
                } else 0f
                val start = Offset(if (rtl) size.width else 0f, size.height / 2f)
                val end = Offset(if (rtl) 0f else size.width, size.height / 2f)
                val progress = Offset(
                    if (rtl) size.width * (1f - fraction) else size.width * fraction,
                    size.height / 2f
                )
                drawLine(inactive, start, end, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                if (fraction > 0f) {
                    drawLine(accent, start, progress, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
    )
}
internal fun clearArtworkMemoryCaches() {artworkCache.evictAll();clearAmbientCaches()}
