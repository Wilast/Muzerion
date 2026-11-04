package com.example.muzerion

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackList(
    folder: String, pages: PagerState, onLibraryReady: (List<Track>) -> Unit,
    currentTrackUri: Uri?,
    refreshKey: Pair<Int, Int>, onRefresh: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    selectedUris: Set<String>, deletedUris: Set<String>, selectionEnabled: Boolean,
    onSelectionChange: (Set<String>) -> Unit, modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("muzerion_settings", Context.MODE_PRIVATE) }
    var query by rememberSaveable(folder) { mutableStateOf("") }
    var sort by remember { mutableStateOf(prefs.getString("library_sort", "alphabetical") ?: "alphabetical") }
    var sortMenuPage by remember { mutableStateOf<Int?>(null) }
    var collectionKey by rememberSaveable(folder) { mutableStateOf<String?>(null) }
    var collectionTab by rememberSaveable { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val lastManual=remember(folder) {intArrayOf(refreshKey.second)}
    val state by produceState(rememberedLibrarySnapshot(folder), folder, refreshKey) {
        val requested=refreshKey.second!=lastManual[0]
        lastManual[0]=refreshKey.second
        observeCombinedLibrary(context.applicationContext, folder, deferScan = refreshKey.second == 0, manual = requested)
            .collect { value = it }
    }
    val collator = remember { Collator.getInstance(AppLanguage.locale).apply { strength = Collator.PRIMARY } }
    val order = remember(collator) {
        Comparator<Track> { a, b ->
            val title = collator.compare(a.title, b.title)
            if (title != 0) title else {
                val artist = collator.compare(a.artist, b.artist)
                if (artist != 0) artist else a.uri.toString().compareTo(b.uri.toString())
            }
        }
    }
    val tracks = remember(state.tracks, sort, deletedUris, AppOptions.revision) {
        val available = state.tracks.filterNot { it.uri.toString() in deletedUris }
            .filter { !AppOptions.bool("library_hide_short") || it.durationMs==0L || it.durationMs>=30000L }
            .map {if(AppOptions.bool("library_filenames")) it.copy(title=it.name.substringBeforeLast('.')) else it}
        if (sort == "recent") available.sortedWith(compareByDescending<Track> { it.addedAt }.then(order))
        else available.sortedWith(order)
    }
    val latestReady by rememberUpdatedState(onLibraryReady)
    LaunchedEffect(tracks) { latestReady(tracks) }
    val collections by produceState(emptyList<LocalCollection>(), tracks) {
        value = withContext(Dispatchers.Default) { buildLocalCollections(tracks) }
    }
    val collection = collections.firstOrNull { it.key == collectionKey }
    LaunchedEffect(collections) {
        if (!state.loading && !state.refreshing && collectionKey != null && collection == null) collectionKey = null
    }
    BackHandler(enabled = pages.currentPage == 1 && collectionKey != null && selectedUris.isEmpty() && !MuzerionNav.playerExpanded) {
        collectionKey = null
    }
    val filtered = remember(tracks, query) {
        val terms = searchKey(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        tracks.filter { track -> terms.all { it in searchKey("${track.title} ${track.artist} ${track.album}") } }
    }
    val listStates = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
    val overviewState = rememberLazyListState()
    LaunchedEffect(query) { listStates[2].scrollToItem(0) }
    LaunchedEffect(collectionKey) { listStates[1].scrollToItem(0) }
    LaunchedEffect(sort) { listStates.forEach { it.scrollToItem(0) } }
    LaunchedEffect(pages.currentPage, pages.isScrollInProgress) { sortMenuPage = null }
    fun toggle(track: Track) {
        if (!selectionEnabled) return
        val key = track.uri.toString()
        onSelectionChange(if (key in selectedUris) selectedUris - key else selectedUris + key)
    }
    HorizontalPager(state = pages, beyondViewportPageCount = 0, modifier = modifier.fillMaxWidth(), key = { it }) { page ->
        val visible = when (page) { 0 -> tracks; 1 -> collection?.tracks.orEmpty(); else -> filtered }
        val overview = page == 1 && collectionKey == null
        PullToRefreshBox(isRefreshing = state.refreshing || state.loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page == 1 && collectionKey != null) {
                        IconButton(onClick = { collectionKey = null }, modifier = Modifier.semantics { contentDescription = tr(R.string.msg_9a22bf0126) }) {
                            Text("‹", fontSize = 32.sp)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(when(page) { 0 -> tr(R.string.msg_2525af6b13); 1 -> collection?.title ?: tr(R.string.msg_630d41adb3); else -> tr(R.string.msg_6433b522a1) },
                            fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val summary = if (page == 1 && collection != null) collection.subtitle
                            else "${trackCountLabel(tracks.size)} · ${Formatter.formatShortFileSize(context, tracks.sumOf { it.sizeBytes })}"
                        Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    }
                    if (!overview && page != 1) Box {
                        TextButton(onClick = { sortMenuPage = page }) { Text(if (sort == "recent") tr(R.string.msg_454c899601) else tr(R.string.msg_80f4283474), fontSize = 12.sp) }
                        DropdownMenu(expanded = sortMenuPage == page && pages.currentPage == page && !pages.isScrollInProgress,
                            onDismissRequest = { sortMenuPage = null }) {
                            listOf("alphabetical" to tr(R.string.msg_461d22517a), "recent" to tr(R.string.msg_b6730543a4)).forEach { (key, title) ->
                                DropdownMenuItem(text = { Text(title) }, onClick = {
                                    sort = key; sortMenuPage = null; prefs.edit().putString("library_sort", key).apply()
                                })
                            }
                        }
                    }
                }
                if (page == 2) OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(tr(R.string.msg_3b1ad01c36), fontSize = 14.sp) },
                    leadingIcon = { MuzerionIcon("search", MuzerionStyle.accent) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.semantics { contentDescription = tr(R.string.msg_a736131fb8) }) { Text("×", fontSize = 22.sp) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                if (overview) {
                    LazyColumn(state = overviewState, modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                        item(key = "all") {
                            val all = collections.firstOrNull { it.key == "all" }
                            Surface(onClick = { collectionKey = "all" }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    MuzerionArtwork(all?.cover, modifier = Modifier.size(68.dp).clip(RoundedCornerShape(16.dp)))
                                    Column(Modifier.weight(1f)) {
                                        Text(tr(R.string.msg_ff824bdc8c), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                        Text(trackCountLabel(tracks.size), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                                    }
                                    Text("›", fontSize = 28.sp)
                                }
                            }
                        }
                        item(key = "tabs") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf(tr(R.string.msg_777d93af15), tr(R.string.msg_0bd8ba7777)).forEachIndexed { index, text ->
                                    FilterChip(selected = collectionTab == index, onClick = { collectionTab = index },
                                        label = { Text(text) }, leadingIcon = { MuzerionIcon(if (index == 0) "artist" else "album", MuzerionStyle.accent, Modifier.size(20.dp)) })
                                }
                            }
                            val count = collections.count { it.kind == if (collectionTab == 0) "artist" else "album" }
                            Text("${if (collectionTab == 0) tr(R.string.msg_cfef7397a7) else tr(R.string.msg_0bd8ba7777)}: ${count}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val matching = collections.filter { it.kind == if (collectionTab == 0) "artist" else "album" }
                        if (matching.isEmpty()) item { Text(if (state.loading || state.refreshing) tr(R.string.msg_2a72282940) else tr(R.string.msg_835f821d57), modifier = Modifier.padding(16.dp)) }
                        items(matching, key = { it.key }) { group ->
                            Surface(onClick = { collectionKey = group.key }, shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    MuzerionArtwork(group.cover, modifier = Modifier.size(66.dp).clip(if (group.kind == "artist") CircleShape else RoundedCornerShape(12.dp)))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(group.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(group.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("›", fontSize = 25.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    if (selectedUris.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(R.string.msg_9a4e6f0e95, selectedUris.size), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(enabled = selectionEnabled && visible.isNotEmpty(), onClick = {
                            val ids = visible.map { it.uri.toString() }.toSet()
                            onSelectionChange(if (ids.all { it in selectedUris }) selectedUris - ids else selectedUris + ids)
                        }) { Text(if (visible.isNotEmpty() && visible.all { it.uri.toString() in selectedUris }) tr(R.string.msg_3657a98e37) else tr(R.string.msg_bdda7682fb)) }
                    }
                    LazyColumn(state = listStates[page], modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                        if (visible.isEmpty()) item { Text(if (state.loading) tr(R.string.msg_0112107a24) else if (page == 2 && query.isNotBlank()) tr(R.string.msg_1e1b70b17c) else tr(R.string.msg_45afa3ed54),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }
                        items(visible, key = { it.uri.toString() }) { track ->
                            LocalTrackRow(track, track.uri == currentTrackUri, selectedUris.isNotEmpty(), track.uri.toString() in selectedUris,
                                enabled = selectionEnabled, onClick = {
                                    keyboard?.hide(); focus.clearFocus()
                                    if (selectedUris.isNotEmpty()) toggle(track) else onTrackClick(track, if (page == 1) visible else tracks)
                                }, onLongClick = { toggle(track) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalTrackRow(track: Track, current: Boolean, selecting: Boolean, checked: Boolean,
                          enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(modifier = Modifier.fillMaxWidth().clip(shape)
        .background(if (checked || current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
        .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick, hapticFeedbackEnabled = AppOptions.bool("haptic"),
            onLongClickLabel = tr(R.string.msg_ae9e43f1bb))
        .padding(vertical = if(AppOptions.bool("compact_list")) 4.dp else 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selecting) Checkbox(checked = checked, onCheckedChange = { onClick() }, enabled = enabled, modifier = Modifier.size(32.dp))
        MuzerionArtwork(track.artworkUri, selected = current, modifier = Modifier.size(AppOptions.number("cover_size",52f).coerceIn(36f,64f).dp).clip(RoundedCornerShape(13.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(track.title, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) MuzerionStyle.accent else MaterialTheme.colorScheme.onSurface)
            Text(track.artist.ifBlank { tr(R.string.msg_b434c976d0, track.name.substringAfterLast('.').uppercase()) },
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (track.durationMs > 0) formatMuzerionTime(track.durationMs) else "—:—", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun searchKey(value: String) = value.lowercase(Locale.ROOT).replace('ё', 'е').trim()
internal fun trackCountLabel(count: Int): String = AppLanguage.tracks(count)

internal data class LocalCollection(val key: String, val kind: String, val title: String, val subtitle: String,
                                    val tracks: List<Track>, val cover: Uri?)

internal fun buildLocalCollections(tracks: List<Track>): List<LocalCollection> {
    val result = mutableListOf(LocalCollection("all", "all", tr(R.string.msg_ff824bdc8c), trackCountLabel(tracks.size), tracks,
        tracks.maxWithOrNull(compareBy<Track> { it.addedAt }.thenBy { it.uri.toString() })?.artworkUri))
    fun groups(kind: String, entries: Map<String, List<Track>>, title: (Track) -> String) {
        entries.entries.sortedBy { title(it.value.first()).lowercase(Locale.ROOT) }.forEach { (key, entries) ->
            val latest = entries.maxWithOrNull(compareBy<Track> { it.addedAt }.thenBy { it.uri.toString() })!!
            val subtitle = if (kind == "album") "${latest.albumArtist.ifBlank { latest.artist }.ifBlank { tr(R.string.msg_42a1e6c35f) }} · ${trackCountLabel(entries.size)}"
                else trackCountLabel(entries.size)
            val ordered = if (kind == "album") entries.sortedWith(
                compareBy<Track> { it.discNumber.takeIf { n -> n > 0 } ?: 1 }
                    .thenBy { it.trackNumber.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }
                    .thenBy { it.title.lowercase(Locale.ROOT) }) else entries
            result += LocalCollection("${kind}:${key}", kind, title(entries.first()), subtitle, ordered, latest.artworkUri)
        }
    }
    val artists = linkedMapOf<String, MutableList<Track>>()
    val names = mutableMapOf<String, String>()
    tracks.forEach { track ->
        val parts = artistNames(track.artist)
        parts.forEach { name ->
            val key = collectionIdentity(name)
            names.putIfAbsent(key, name)
            artists.getOrPut(key) { mutableListOf() }.add(track)
        }
    }
    artists.entries.sortedBy { names[it.key]!!.lowercase(Locale.ROOT) }.forEach { (key, entries) ->
        val latest = entries.maxWithOrNull(compareBy<Track> { it.addedAt }.thenBy { it.uri.toString() })!!
        result += LocalCollection("artist:${key}", "artist", names[key]!!, trackCountLabel(entries.size), entries, latest.artworkUri)
    }
    groups("album", tracks.groupBy { albumIdentity(it.album, it.albumArtist.ifBlank { it.artist }) }) { it.album.ifBlank { tr(R.string.msg_e5d706d611) } }
    return result
}
