package com.example.muzerion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ExecutionException

private val Accent get() = MuzerionStyle.color(0xFFB9A0FF)
private val AppBackground get() = MuzerionStyle.color(0xFF101014)
private val Panel get() = MuzerionStyle.color(0xFF1D1C24)
private val Muted get() = MuzerionStyle.color(0xFF96939F)

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguage.wrap(newBase)) }

    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var controller by mutableStateOf<MediaController?>(null)
    private var connectionError by mutableStateOf<String?>(null)
    private var resumeVersion by mutableIntStateOf(0)
    private val connectingPlayer by lazy { ConnectingPlayer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppOptions.init(this)
        MuzerionStyle.load(this)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            )
        )

        setContent {
            if(BuildConfig.BUILD_TYPE=="performance") {
                DisposableEffect(MuzerionNav.playerExpanded) {
                    val monitor=if(MuzerionNav.playerExpanded) PlayerFrameMonitor(window) else null
                    onDispose { monitor?.close() }
                }
            }
            val deviceDark = androidx.compose.foundation.isSystemInDarkTheme()
            val devicePalette = if (Build.VERSION.SDK_INT >= 31) {
                if(deviceDark) androidx.compose.material3.dynamicDarkColorScheme(this)
                else androidx.compose.material3.dynamicLightColorScheme(this)
            } else {
                // Older Android versions follow system brightness with a neutral palette.
                if(deviceDark) androidx.compose.material3.darkColorScheme()
                else androidx.compose.material3.lightColorScheme()
            }
            SideEffect {
                MuzerionStyle.systemDark = deviceDark
                MuzerionStyle.systemPalette = devicePalette
            }
            val isDark = if(MuzerionStyle.useSystemTheme) deviceDark else MuzerionStyle.dark
            SideEffect {
                val style = if(isDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle=style, navigationBarStyle=style)
            }
            val composeView = LocalView.current

            DisposableEffect(composeView) {
                if (Build.VERSION.SDK_INT >= 35) {
                    composeView.setRequestedFrameRate(120f)
                }

                onDispose {
                    if (Build.VERSION.SDK_INT >= 35) {
                        composeView.setRequestedFrameRate(
                            View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
                        )
                    }
                }
            }

            val deviceDensity=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(deviceDensity.density,deviceDensity.fontScale*AppOptions.number("text_scale",1f).coerceIn(.85f,1.2f))) {
            MaterialTheme(
                colorScheme = if(MuzerionStyle.useSystemTheme) devicePalette else MuzerionStyle.scheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppBackground
                ) {
                    Box(Modifier.fillMaxSize()) {
                        FolderScreen(player = controller ?: connectingPlayer, resumeVersion = resumeVersion)
                        connectionError?.let { message ->
                            Text(message, color=MaterialTheme.colorScheme.error,
                                modifier=Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(16.dp))
                        }
                    }
                }
            }
            }
        }
        window.decorView.doOnPreDraw { view -> view.post { connectPlayer() } }
    }

    private fun connectPlayer() {
        if (isDestroyed || isFinishing || controllerFuture != null) return
        val token = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )

        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (!isDestroyed && !future.isCancelled) {
                    try {
                        controller = future.get()
                    } catch (_: ExecutionException) {
                        connectionError =
                            tr(R.string.msg_1bea3f70c7)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        connectionError = tr(R.string.msg_33efa5c431)
                    }
                }
            },
            ContextCompat.getMainExecutor(this)
        )

    }

    override fun onStart() {
        super.onStart()
        resumeVersion++
    }

    override fun onResume() {
        super.onResume()
        requestHighRefreshRate()
    }

    @Suppress("DEPRECATION")
    private fun requestHighRefreshRate() {
        val screen = if (Build.VERSION.SDK_INT >= 30) {
            display
        } else {
            windowManager.defaultDisplay
        } ?: return

        val current = screen.mode

        val selected = screen.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                        it.physicalHeight == current.physicalHeight &&
                        it.refreshRate <= 120.5f
            }
            .maxByOrNull { it.refreshRate }
            ?: current

        window.attributes = window.attributes.apply {
            preferredDisplayModeId = selected.modeId
            preferredRefreshRate = selected.refreshRate
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        connectingPlayer.release()
        super.onDestroy()
    }
}

@Composable
fun LibraryScreen(
    player: Player,
    resumeVersion: Int
) {
    val context = LocalContext.current
    val pages = rememberPagerState(pageCount = { 3 })
    LaunchedEffect(pages) {
        snapshotFlow {
            pages.currentPage to (pages.settledPage == 0 && pages.currentPage == 0 &&
                !pages.isScrollInProgress && kotlin.math.abs(pages.currentPageOffsetFraction) < 0.001f)
        }.collect { (tab, settled) ->
            MuzerionNav.libraryTab = tab
            MuzerionNav.tracksSettled = settled
        }
    }
    val navigationScope = rememberCoroutineScope()
    var selection by remember { mutableStateOf(setOf<String>()) }
    var libraryTracks by remember { mutableStateOf(emptyList<Track>()) }
    var deleteTargets by remember { mutableStateOf<List<Track>?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deletionMessage by remember { mutableStateOf<String?>(null) }
    var deletedUris by remember { mutableStateOf(setOf<String>()) }
    BackHandler(enabled = selection.isNotEmpty() && !deleting && !MuzerionNav.playerExpanded) { selection = emptySet() }


    val preferences = remember {
        context.getSharedPreferences(
            "muzerion_settings",
            Context.MODE_PRIVATE
        )
    }

    var folderUri by remember {
        val saved = preferences.getString("music_folder", null)

        val hasAccess = saved != null &&
                context.contentResolver.persistedUriPermissions.any {
                    it.uri.toString() == saved && it.isReadPermission
                }

        mutableStateOf(if (hasAccess) saved else null)
    }

    LaunchedEffect(AppOptions.libraryRevision) {
        val configured=preferences.getString("music_folder",null)
        if(configured!=null && context.contentResolver.persistedUriPermissions.any {it.uri.toString()==configured && it.isReadPermission}) folderUri=configured
    }

    var folderError by remember {
        mutableStateOf<String?>(null)
    }

    var playbackError by remember {
        mutableStateOf<String?>(null)
    }

    var currentTrack by remember(player) {
        mutableStateOf(currentTrackFrom(player))
    }

    var isPlaying by remember(player) {
        mutableStateOf(player.isPlaying)
    }

    var manualRefresh by remember {
        mutableIntStateOf(0)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {

            override fun onEvents(
                player: Player,
                events: Player.Events
            ) {
                currentTrack = currentTrackFrom(player)
                isPlaying = player.isPlaying

                if (
                    events.contains(
                        Player.EVENT_MEDIA_ITEM_TRANSITION
                    )
                ) {
                    playbackError = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError =
                    tr(R.string.msg_bab4cc4e1d, error.errorCodeName)
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                try {
                    context.contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (_: SecurityException) {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                preferences.edit()
                    .putString("music_folder", uri.toString())
                    .apply()

                if (folderUri != uri.toString()) {
                    player.stop()
                    player.clearMediaItems()
                    currentTrack = null
                    playbackError = null
                }

                selection = emptySet()
                deletedUris = emptySet()
                folderUri = uri.toString()
                folderError = null
                manualRefresh++
            } catch (_: SecurityException) {
                folderError =
                    tr(R.string.msg_980b47406c)
            }
        }
    }

    // Keep the invisible library's playback-dependent UI unchanged while the
    // expanded player is active. Its navigation, scroll state and library flow stay alive.
    var libraryTrackSnapshot by remember {mutableStateOf(currentTrack)}
    var libraryPlayingSnapshot by remember {mutableStateOf(isPlaying)}
    PlayerTransitionHost(
        player = player,
        track = currentTrack
    ) { openPlayer, hideMiniCover, freezeLibraryPlayback, onMiniCoverBounds ->
        val libraryTrack=if(freezeLibraryPlayback) libraryTrackSnapshot else currentTrack
        val libraryPlaying=if(freezeLibraryPlayback) libraryPlayingSnapshot else isPlaying
        SideEffect {
            if(!freezeLibraryPlayback) {
                libraryTrackSnapshot=libraryTrack
                libraryPlayingSnapshot=libraryPlaying
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MuzerionStyle.color(0xFF201A2D),
                            AppBackground,
                            AppBackground
                        )
                    )
                )
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { MuzerionNav.requestDrawer() }) {
                    Text("☰", fontSize = 26.sp, color = Accent)
                }
                Text(
                    text = "muzerion",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent
                )

                Spacer(Modifier.weight(1f))

                if (selection.isNotEmpty()) {
                    Text("${selection.size}", color = Accent, fontWeight = FontWeight.Bold)
                    IconButton(enabled = !deleting, onClick = { selection = emptySet() },
                        modifier = Modifier.semantics { contentDescription = tr(R.string.msg_716e5371c7) }) {
                        Text("×", fontSize = 26.sp)
                    }
                    IconButton(enabled = !deleting, onClick = {
                        deleteTargets = libraryTracks.filter { it.uri.toString() in selection }.toList()
                    }, modifier = Modifier.semantics { contentDescription = tr(R.string.msg_52cf8f51b4) }) {
                        MuzerionIcon("delete", MaterialTheme.colorScheme.error)
                    }
                } else {
                    PlayerIconButton(onClick = { folderPicker.launch(folderUri?.let { Uri.parse(it) }) },
                        modifier=Modifier.semantics {contentDescription=if(folderUri==null) tr(R.string.msg_14a658ce61) else tr(R.string.msg_cde0aeaa67)}) {
                        MuzerionIcon("folder",Accent)
                    }
                }
            }

            folderError?.let { ErrorLabel(it) }
            playbackError?.let { ErrorLabel(it) }
            deletionMessage?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { deletionMessage = null }) { Text(tr(R.string.msg_4ae50d3073)) }
                }
            }
            if (deleting) LinearProgressIndicator(Modifier.fillMaxWidth())



            val selectedFolder = folderUri

            if (selectedFolder == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MuzerionArtwork(
                            artworkUri = null,
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(20.dp))
                        )

                        Text(
                            text = tr(R.string.msg_d46e28939b),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(
                                top = 20.dp,
                                bottom = 8.dp
                            )
                        )

                        Text(
                            text = tr(R.string.msg_a07f883b38),
                            color = Muted,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                TrackList(
                    folder = selectedFolder,
                    pages = pages,
                    onLibraryReady = { libraryTracks = it; restoreMissingQueue(player, it) },
                    selectedUris = selection,
                    deletedUris = deletedUris,
                    selectionEnabled = !deleting,
                    onSelectionChange = { selection = it },
                    currentTrackUri = libraryTrack?.uri,
                    refreshKey = (if(AppOptions.bool("library_auto",true)) resumeVersion else 0) to (manualRefresh+AppOptions.libraryRevision),
                    onRefresh = {
                        manualRefresh++
                    },
                    modifier = Modifier.weight(1f),
                    onTrackClick = { track, queue ->
                        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                            playbackError = tr(R.string.msg_7cfa30e099)
                            return@TrackList
                        }
                        playbackError = null

                        playQueue(
                            player = player,
                            tracks = queue,
                            selectedTrack = track
                        )

                        currentTrack = currentTrackFrom(player)
                            ?: track
                    }
                )
            }

            libraryTrack?.let { track ->
                MiniPlayer(
                    track = track,
                    isPlaying = libraryPlaying,
                    hideCover = hideMiniCover,
                    onCoverBounds = onMiniCoverBounds,
                    onOpen = openPlayer,
                    onToggle = {
                        playbackError = null
                        togglePlayback(player)
                    }
                )
            }
            NavigationBar(containerColor = AppBackground, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
                listOf(tr(R.string.msg_7b4cc84722) to "music", tr(R.string.msg_630d41adb3) to "library", tr(R.string.msg_6433b522a1) to "search").forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = pages.currentPage == index,
                        onClick = { navigationScope.launch { pages.animateScrollToPage(index) } },
                        icon = { MuzerionIcon(item.second, if(pages.currentPage == index) Accent else Muted) },
                        label = { Text(item.first) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent, selectedTextColor = Accent,
                            unselectedIconColor = Muted, unselectedTextColor = Muted,
                            indicatorColor = Panel
                        )
                    )
                }
            }

        }
    }
    deleteTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTargets = null },
            icon = { MuzerionIcon("delete", MaterialTheme.colorScheme.error) },
            title = { Text(tr(R.string.msg_0ab1d5ba10)) },
            text = { Column {
                Text(tr(R.string.msg_8ff8c8f631, targets.size))
                Spacer(Modifier.height(12.dp))
                targets.take(4).forEach { Text("• ${it.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (targets.size > 4) Text(tr(R.string.msg_6e6bc6d47e, targets.size - 4))
            } },
            confirmButton = { TextButton(enabled = targets.isNotEmpty() && !deleting, onClick = {
                deleting = true
                deleteTargets = null
                val folderAtStart = folderUri
                navigationScope.launch {
                    // Finish this explicit operation across navigation/configuration cancellation.
                    withContext(kotlinx.coroutines.NonCancellable) {
                        val result = deleteLocalTracks(context, folderAtStart, targets)
                        val removed = result.deleted
                        runCatching {
                            for (index in player.mediaItemCount - 1 downTo 0) {
                                if (player.getMediaItemAt(index).mediaId in removed) player.removeMediaItem(index)
                            }
                        }
                        deletedUris = deletedUris + removed
                        selection = selection - removed
                        deletionMessage = result.message
                        manualRefresh++
                        deleting = false
                    }
                }
            }) { Text(tr(R.string.msg_64de3c18ac), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTargets = null }) { Text(tr(R.string.msg_0ec753be8d)) } }
        )
    }

}

@Composable
private fun ErrorLabel(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    hideCover: Boolean,
    onCoverBounds: (Rect) -> Unit,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    Surface(
        color = MuzerionStyle.color(0xFF292333),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MuzerionArtwork(
                artworkUri = track.artworkUri,
                selected = true,
                modifier = Modifier
                    .size(52.dp)
                    .onGloballyPositioned {
                        onCoverBounds(it.boundsInRoot())
                    }
                    .graphicsLayer {
                        alpha = if (hideCover) 0f else 1f
                    }
                    .clip(RoundedCornerShape(14.dp))
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = track.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(iterations = if(hideCover) 0 else Int.MAX_VALUE)
                )

                Text(
                    text = track.artist.ifBlank {
                        if (isPlaying) tr(R.string.msg_d49a9545fb) else tr(R.string.msg_59a416ade7)
                    },
                    color = Accent,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = if (isPlaying) {
                            tr(R.string.msg_03498e395a)
                        } else {
                            tr(R.string.msg_edf6515d2e)
                        }
                    },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Accent,
                    contentColor = MuzerionStyle.color(0xFF211337)
                )
            ) {
                Text(
                    text = if (isPlaying) "Ⅱ" else "▶",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

fun currentTrackFrom(player: Player): Track? {
    val item = player.currentMediaItem ?: return null

    if (item.mediaId.isBlank()) {
        return null
    }

    val metadata = item.mediaMetadata

    val title = metadata.title
        ?.toString()
        ?.takeIf { it.isNotBlank() }
        ?: tr(R.string.msg_32b74a3c47)

    return Track(
        name = metadata.extras?.getString("file_name") ?: title,
        uri = Uri.parse(item.mediaId),
        title = title,
        artist = metadata.artist?.toString().orEmpty(),
        album = metadata.albumTitle?.toString().orEmpty(),
        artworkUri = metadata.artworkUri,
        durationMs = metadata.extras?.getLong("duration_ms") ?: 0L,
        sizeBytes = metadata.extras?.getLong("size_bytes") ?: 0L,
        addedAt = metadata.extras?.getLong("added_at") ?: 0L
    )
}