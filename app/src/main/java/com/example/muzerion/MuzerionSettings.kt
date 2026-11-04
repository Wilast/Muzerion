package com.example.muzerion

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.abs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController

object MuzerionNav {
    var drawerRequests by mutableIntStateOf(0)
        private set
    var libraryTab by mutableIntStateOf(0)
    var tracksSettled by mutableStateOf(false)
    var playerExpanded by mutableStateOf(false)
    fun requestDrawer() { drawerRequests++ }
}

object MuzerionStyle {
    private var manualDark by mutableStateOf(true)
    var useSystemTheme by mutableStateOf(false)
    var systemDark by mutableStateOf(false)
    var systemPalette by mutableStateOf<ColorScheme?>(null)
    var dark: Boolean
        get() = if(useSystemTheme) systemDark else manualDark
        set(value) { manualDark=value }
    private val system get() = if(useSystemTheme) systemPalette else null
    var accentIndex by mutableIntStateOf(0)
    var monochrome by mutableStateOf(false)
    var backgroundVisibility by mutableFloatStateOf(0.65f)
    var backgroundBlur by mutableFloatStateOf(0.625f)
    val names get() = listOf(tr(R.string.msg_975d01b9b6), tr(R.string.msg_0deebc3a96), tr(R.string.msg_891c5e9e76), tr(R.string.msg_6e9cd37707), tr(R.string.msg_71d3b1f5db), tr(R.string.msg_9161f968d5))
    private val darkAccents = listOf(0xFFB9A0FF,0xFF8EC5FF,0xFF88D9A6,0xFFFFA6CA,0xFFFFBC8F,0xFF71DAD8)
    private val lightAccents = listOf(0xFF6741B5,0xFF175FA8,0xFF236D43,0xFFA52C64,0xFF954A13,0xFF006C6B)
    val accent get() = system?.primary ?: if(monochrome) text else Color((if(dark) darkAccents else lightAccents)[accentIndex.coerceIn(0,5)])
    private fun tinted(base: Float, amount: Float): Color {
        system?.let { return it.surfaceContainerHigh }
        if(monochrome) return Color(base,base,base)
        val c=Color(darkAccents[accentIndex.coerceIn(0,5)])
        return Color(base*(1-amount)+c.red*amount,base*(1-amount)+c.green*amount,base*(1-amount)+c.blue*amount)
    }
    val background get() = system?.background ?: tinted(if(dark) 0.06f else 0.97f, if(dark) 0.15f else 0.05f)
    val panel get() = system?.surfaceContainer ?: tinted(if(dark) 0.12f else 0.93f, if(dark) 0.17f else 0.07f)
    val text get() = system?.onSurface ?: Color(if(dark) 0xFFF4F4F4 else 0xFF202020)
    private val muted get() = system?.onSurfaceVariant ?: Color(if(dark) 0xFF999999 else 0xFF666666)
    fun color(value: Long): Color = when(value) {
        0xFFB9A0FF -> accent
        0xFF7860B0,0xFF44315F -> tinted(if(dark) 0.2f else 0.85f,0.35f)
        0xFF101014 -> background
        0xFF1D1C24 -> panel
        0xFFF4F1FA,0xFFFFFFFF -> text
        0xFF96939F,0xFF80758D -> muted
        0xFFFFB4AB -> system?.error ?: if(monochrome) text else Color(if(dark) 0xFFFFB4AB else 0xFFBA1A1A)
        0xFF211337 -> system?.onPrimary ?: if(monochrome) background else Color(if(dark) value else 0xFFFFFFFF)
        0xFF201A2D,0xFF292333,0xFF24202F,0xFF30233F,0xFF19151F,0xFF39313F,0xFF44374F,0xFF49434F,0xFF393043,0xFF24232D -> panel
        else -> if(monochrome) {
            val c=Color(value); val gray=c.red*0.2126f+c.green*0.7152f+c.blue*0.0722f
            Color(gray,gray,gray,c.alpha)
        } else Color(value)
    }
    fun scheme(): ColorScheme {
        system?.let { return it }
        val onAccent = if(monochrome) background else color(0xFF211337)
        val scheme = if(dark) darkColorScheme(
            primary=accent,onPrimary=onAccent,secondary=accent,onSecondary=onAccent,
            tertiary=accent,onTertiary=onAccent,primaryContainer=panel,onPrimaryContainer=text,
            secondaryContainer=panel,onSecondaryContainer=text,tertiaryContainer=panel,onTertiaryContainer=text,
            background=background,onBackground=text,surface=panel,onSurface=text,
            surfaceVariant=panel,onSurfaceVariant=color(0xFF96939F),surfaceTint=accent,
            outline=color(0xFF96939F),error=if(monochrome)text else Color(0xFFFFB4AB)
        ) else lightColorScheme(
            primary=accent,onPrimary=onAccent,secondary=accent,onSecondary=onAccent,
            tertiary=accent,onTertiary=onAccent,primaryContainer=panel,onPrimaryContainer=text,
            secondaryContainer=panel,onSecondaryContainer=text,tertiaryContainer=panel,onTertiaryContainer=text,
            background=background,onBackground=text,surface=panel,onSurface=text,
            surfaceVariant=panel,onSurfaceVariant=color(0xFF96939F),surfaceTint=accent,
            outline=color(0xFF96939F),error=if(monochrome)text else Color(0xFFBA1A1A)
        )
        return scheme.copy(
            surfaceDim=background,surfaceBright=panel,
            surfaceContainerLowest=background,surfaceContainerLow=panel,
            surfaceContainer=panel,surfaceContainerHigh=panel,surfaceContainerHighest=panel,
            inverseSurface=text,inverseOnSurface=background,inversePrimary=accent,
            outlineVariant=muted,errorContainer=panel,onErrorContainer=text,onError=background
        )
    }
    fun load(context: Context) {
        val p=context.getSharedPreferences("muzerion_settings",0)
        dark=p.getBoolean("appearance_dark",true)
        useSystemTheme=p.getBoolean("appearance_system",false)
        accentIndex=p.getInt("appearance_accent",0).coerceIn(0,5)
        monochrome=p.getBoolean("appearance_monochrome",false)
        backgroundVisibility=p.getFloat("player_background_visibility",0.65f).coerceIn(0f,1f)
        backgroundBlur=p.getFloat("player_background_blur",0.625f).coerceIn(0f,1f)
    }
    fun save(context: Context) {
        context.getSharedPreferences("muzerion_settings",0).edit()
            .putBoolean("appearance_system",useSystemTheme).putBoolean("appearance_dark",manualDark).putInt("appearance_accent",accentIndex)
            .putBoolean("appearance_monochrome",monochrome)
            .putFloat("player_background_visibility",backgroundVisibility)
            .putFloat("player_background_blur",backgroundBlur).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(player: Player, resumeVersion: Int) {
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    var screen by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var handledRequest by remember { mutableIntStateOf(MuzerionNav.drawerRequests) }
    LaunchedEffect(MuzerionNav.drawerRequests) {
        if(MuzerionNav.drawerRequests != handledRequest) {
            handledRequest=MuzerionNav.drawerRequests
            drawer.open()
        }
    }
    BackHandler(screen!=0) { if(page!=0) page=0 else screen=0 }
    ModalNavigationDrawer(
        drawerState=drawer,
        // Closed drawers are opened only by our Tracks gesture or the menu button.
        gesturesEnabled=!MuzerionNav.playerExpanded &&
            (drawer.currentValue != DrawerValue.Closed || drawer.targetValue != DrawerValue.Closed),
        drawerContent={
            ModalDrawerSheet(drawerContainerColor=MuzerionStyle.background) {
                Column(Modifier.fillMaxHeight().widthIn(max=300.dp).padding(20.dp)) {
                    Text("muzerion",fontSize=30.sp,color=MuzerionStyle.text,modifier=Modifier.padding(vertical=24.dp))
                    Text(tr(R.string.msg_861833e620),color=MuzerionStyle.text.copy(alpha=0.6f),fontSize=13.sp,modifier=Modifier.padding(bottom=24.dp))
                    DrawerEntry(tr(R.string.msg_7f17c7c62a),"settings") {screen=1;page=0;scope.launch {drawer.close()} }
                    HorizontalDivider(color=MuzerionStyle.text.copy(alpha=0.15f),modifier=Modifier.padding(vertical=8.dp))
                    DrawerEntry(tr(R.string.msg_1392090686),"updates") {screen=2;page=0;scope.launch {drawer.close()} }
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.padding(vertical=16.dp), verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(BuildConfig.VERSION_NAME,color=MuzerionStyle.text.copy(alpha=0.8f),fontSize=12.sp)
                        Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",color=MuzerionStyle.text.copy(alpha=0.6f),fontSize=12.sp)
                        val architecture = remember { applicationArchitecture() }
                        Text(tr(R.string.msg_26d14fe46f, architecture),color=MuzerionStyle.text.copy(alpha=0.6f),fontSize=12.sp)
                    }
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize().drawerSwipeGesture(drawer, !MuzerionNav.playerExpanded && screen==0 && MuzerionNav.tracksSettled)) {
            LibraryScreen(player,resumeVersion)
            if(screen!=0) Surface(Modifier.fillMaxSize(),color=MuzerionStyle.background) {
                Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick={if(page!=0)page=0 else screen=0}) {Text(tr(R.string.msg_b80940a8a7))}
                    Text(
                        if(screen==2) tr(R.string.msg_1392090686) else if(page==1) tr(R.string.msg_799c76400d) else when(page) {1->tr(R.string.msg_799c76400d);2->tr(R.string.msg_d206f1bed0);3->tr(R.string.msg_8c89c0a42a);4->tr(R.string.msg_3bcc51963d);5->tr(R.string.msg_2525af6b13);6->tr(R.string.msg_50a177527e);7->tr(R.string.msg_e30d45f46a);9->tr(R.string.msg_ed3f0e507a);8->tr(R.string.msg_b9c9ff652d);else->tr(R.string.msg_7f17c7c62a)},
                        fontSize=25.sp,color=MuzerionStyle.text,modifier=Modifier.padding(vertical=16.dp)
                    )
                    if(screen==2) UpdatesContent() else when(page) {
                        0 -> {
                            listOf(Triple(9,tr(R.string.msg_ed3f0e507a),"language"),Triple(2,tr(R.string.msg_d206f1bed0),"palette"),Triple(3,tr(R.string.msg_8c89c0a42a),"music"),Triple(4,tr(R.string.msg_3bcc51963d),"tune"),
                                Triple(5,tr(R.string.msg_2525af6b13),"library"),Triple(6,tr(R.string.msg_50a177527e),"settings"),Triple(7,tr(R.string.msg_e30d45f46a),"folder"),
                                Triple(1,tr(R.string.msg_799c76400d),"computer"),Triple(8,tr(R.string.msg_b9c9ff652d),"info")).forEach {(id,title,icon)->
                                SettingsEntry(title,icon) {page=id}
                                HorizontalDivider(color=MuzerionStyle.text.copy(alpha=.12f))
                            }
                        }
                        9 -> LanguageOptions()
                        3 -> PlaybackOptions()
                        4 -> EqualizerSettings()
                        5 -> LibraryOptions()
                        6 -> GestureOptions()
                        7 -> DataOptions()
                        8 -> AboutOptions(player) {screen=2;page=0}
                        1 -> PcSharingPanel()
                        2 -> {
                            val context=LocalContext.current
                            AppearanceOptions()
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(tr(R.string.msg_1ac01fecce),color=MuzerionStyle.text)

                                }
                                Switch(checked=MuzerionStyle.useSystemTheme,onCheckedChange={MuzerionStyle.useSystemTheme=it;MuzerionStyle.save(context)})
                            }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(tr(R.string.msg_ec1c55d866),color=MuzerionStyle.text.copy(alpha=if(MuzerionStyle.useSystemTheme)0.38f else 1f),modifier=Modifier.weight(1f))
                                Switch(enabled=!MuzerionStyle.useSystemTheme,checked=MuzerionStyle.dark,onCheckedChange={MuzerionStyle.dark=it;MuzerionStyle.save(context)})
                            }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(tr(R.string.msg_229580498f),color=MuzerionStyle.text.copy(alpha=if(MuzerionStyle.useSystemTheme)0.38f else 1f),modifier=Modifier.weight(1f))
                                Switch(enabled=!MuzerionStyle.useSystemTheme,checked=MuzerionStyle.monochrome,onCheckedChange={MuzerionStyle.monochrome=it;MuzerionStyle.save(context)})
                            }
                            HorizontalDivider(Modifier.padding(vertical=16.dp),color=MuzerionStyle.text.copy(alpha=0.12f))
                            Text(tr(R.string.msg_b14cb8159e),color=MuzerionStyle.text,fontSize=18.sp)
                            BackgroundSettingSlider(tr(R.string.msg_a9058f7a2b),MuzerionStyle.backgroundVisibility,tr(R.string.msg_7ffc6bc408),tr(R.string.msg_e366869833)) {
                                MuzerionStyle.backgroundVisibility=it;MuzerionStyle.save(context)
                            }
                            BackgroundSettingSlider(tr(R.string.msg_7a0b4fde1e),MuzerionStyle.backgroundBlur,tr(R.string.msg_e55c062393),tr(R.string.msg_3ccec2c327)) {
                                MuzerionStyle.backgroundBlur=it;MuzerionStyle.save(context)
                            }
                            if(!MuzerionStyle.useSystemTheme && !MuzerionStyle.monochrome) Text(tr(R.string.msg_84b22ed2aa),color=MuzerionStyle.text,modifier=Modifier.padding(top=20.dp))
                            if(!MuzerionStyle.monochrome || MuzerionStyle.useSystemTheme) MuzerionStyle.names.forEachIndexed { index,name ->
                                Row(Modifier.fillMaxWidth().clickable(enabled=!MuzerionStyle.useSystemTheme) {MuzerionStyle.accentIndex=index;MuzerionStyle.save(context)}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                    RadioButton(enabled=!MuzerionStyle.useSystemTheme,selected=MuzerionStyle.accentIndex==index,onClick={MuzerionStyle.accentIndex=index;MuzerionStyle.save(context)})
                                    Text(name,color=MuzerionStyle.text.copy(alpha=if(MuzerionStyle.useSystemTheme)0.38f else 1f))
                                }
                            }
                        }
                    }
                }
            }
            // Block the library for the complete opening/closing animation, too.
            // A tap outside the sheet must never select a track underneath it.
            if (drawer.currentValue != DrawerValue.Closed || drawer.targetValue != DrawerValue.Closed) {
                Box(
                    Modifier.fillMaxSize()
                        .semantics { contentDescription = tr(R.string.msg_f4dcecb83f) }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { scope.launch { drawer.close() } }
                        )
                )
            }
        }
    }
}

private fun applicationArchitecture(): String {
    // os.arch describes the current app process, rather than all ABIs the device supports.
    val arch = System.getProperty("os.arch").orEmpty()
    val abi = when (arch.lowercase()) {
        "aarch64", "arm64" -> "arm64-v8a"
        "arm", "armv7l", "armv8l" -> "armeabi-v7a"
        "amd64", "x86_64" -> "x86_64"
        "x86", "i686", "i386" -> "x86"
        else -> arch.ifBlank { tr(R.string.msg_478fa30563) }
    }
    return "${abi} · ${if (Process.is64Bit()) tr(R.string.msg_ca31f27956) else tr(R.string.msg_912ae5210e)}"
}

@Composable
private fun Modifier.drawerSwipeGesture(drawer: DrawerState, enabled: Boolean): Modifier {
    val canOpen by rememberUpdatedState(enabled)
    // Keep the pointer coroutine alive while the pager changes state. Eligibility
    // is captured at touch-down, so reaching Tracks mid-swipe never opens the drawer.
    return pointerInput(drawer) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!canOpen || drawer.currentValue != DrawerValue.Closed ||
                drawer.targetValue != DrawerValue.Closed) {
                return@awaitEachGesture
            }
            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1) break
                val distance = change.position - down.position
                if (!claimed) {
                    if (abs(distance.y) > viewConfiguration.touchSlop && abs(distance.y) >= abs(distance.x)) break
                    if (distance.x < -viewConfiguration.touchSlop) break
                    if (distance.x > viewConfiguration.touchSlop && distance.x > abs(distance.y)) claimed = true
                }
                if (claimed) change.consume()
                if (!change.pressed) {
                    if (claimed && distance.x >= 56.dp.toPx()) MuzerionNav.requestDrawer()
                    break
                }
            }
        }
    }
}

@Composable
private fun DrawerEntry(title: String, icon: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically) {
        MuzerionIcon(icon,MuzerionStyle.text)
        Text(title,color=MuzerionStyle.text,fontSize=18.sp,modifier=Modifier.padding(start=16.dp))
    }
}

@Composable
private fun SettingsEntry(title: String, icon: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=20.dp),verticalAlignment=Alignment.CenterVertically) {
        MuzerionIcon(icon,MuzerionStyle.text)
        Text(title,color=MuzerionStyle.text,fontSize=17.sp,modifier=Modifier.padding(start=16.dp).weight(1f))
        Text("›",color=MuzerionStyle.text,fontSize=22.sp)
    }
}

@Composable
fun MuzerionIcon(kind: String, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val stroke=Stroke(width=1.8f,cap=StrokeCap.Round,join=StrokeJoin.Round)
        withTransform({scale(size.width/24f,size.height/24f,pivot=Offset.Zero)}) {
            val path=Path()
            when(kind) {
                "language" -> {
                    drawCircle(tint,9f,Offset(12f,12f),style=stroke)
                    drawOval(tint,topLeft=Offset(8f,3f),size=androidx.compose.ui.geometry.Size(8f,18f),style=stroke)
                    path.moveTo(3f,12f);path.lineTo(21f,12f)
                }
                "previous", "next" -> {
                    val previous=kind=="previous"
                    val bar=if(previous) 4f else 17f
                    drawRect(tint,topLeft=Offset(bar,4f),size=androidx.compose.ui.geometry.Size(3f,16f))
                    val triangle=Path().apply {
                        moveTo(if(previous)8f else 16f,12f)
                        lineTo(if(previous)20f else 4f,4f)
                        lineTo(if(previous)20f else 4f,20f)
                        close()
                    }
                    drawPath(triangle,tint)
                }
                "check" -> {
                    path.moveTo(4f,12f);path.lineTo(9f,17f);path.lineTo(20f,6f)
                }
                "down" -> {
                    path.moveTo(5f,9f);path.lineTo(12f,16f);path.lineTo(19f,9f)
                }
                "folder" -> {
                    path.moveTo(3f,6f);path.lineTo(9f,6f);path.lineTo(11f,8f);path.lineTo(21f,8f)
                    path.lineTo(21f,20f);path.lineTo(3f,20f);path.close()
                    path.moveTo(3f,11f);path.lineTo(21f,11f)
                }
                "speed" -> {
                    drawCircle(tint,9f,Offset(12f,12f),style=stroke)
                    path.moveTo(12f,12f);path.lineTo(18f,6f)
                    drawCircle(tint,1.5f,Offset(12f,12f))
                    path.moveTo(5f,12f);path.lineTo(6f,12f)
                    path.moveTo(12f,5f);path.lineTo(12f,6f)
                    path.moveTo(18f,12f);path.lineTo(19f,12f)
                }
                "tune" -> {
                    path.moveTo(3f,6f);path.lineTo(8f,6f);path.moveTo(12f,6f);path.lineTo(21f,6f)
                    path.moveTo(3f,12f);path.lineTo(14f,12f);path.moveTo(18f,12f);path.lineTo(21f,12f)
                    path.moveTo(3f,18f);path.lineTo(6f,18f);path.moveTo(10f,18f);path.lineTo(21f,18f)
                    drawCircle(tint,2f,Offset(10f,6f),style=stroke)
                    drawCircle(tint,2f,Offset(16f,12f),style=stroke)
                    drawCircle(tint,2f,Offset(8f,18f),style=stroke)
                }
                "info" -> {
                    drawCircle(tint,radius=9f,center=Offset(12f,12f),style=stroke)
                    drawCircle(tint,radius=1f,center=Offset(12f,7f))
                    path.moveTo(12f,11f);path.lineTo(12f,17f)
                }
                "search" -> {
                    drawCircle(tint,radius=6.5f,center=Offset(10f,10f),style=stroke)
                    path.moveTo(15f,15f);path.lineTo(21f,21f)
                }
                "music" -> {
                    path.moveTo(9f,18f);path.lineTo(9f,5f);path.lineTo(20f,3f);path.lineTo(20f,16f)
                    path.moveTo(9f,9f);path.lineTo(20f,7f)
                    drawOval(tint,topLeft=Offset(3f,16f),size=androidx.compose.ui.geometry.Size(6f,4f))
                    drawOval(tint,topLeft=Offset(14f,14f),size=androidx.compose.ui.geometry.Size(6f,4f))
                }
                "artist" -> {
                    drawCircle(tint,4f,Offset(12f,7f),style=stroke)
                    path.moveTo(4f,21f);path.cubicTo(4f,12f,20f,12f,20f,21f)
                }
                "album" -> {
                    drawCircle(tint,9f,Offset(12f,12f),style=stroke)
                    drawCircle(tint,2.5f,Offset(12f,12f),style=stroke)
                }
                "delete" -> {
                    path.moveTo(3f,6f);path.lineTo(21f,6f)
                    path.moveTo(8f,6f);path.lineTo(8f,3f);path.lineTo(16f,3f);path.lineTo(16f,6f)
                    path.moveTo(5f,6f);path.lineTo(6f,21f);path.lineTo(18f,21f);path.lineTo(19f,6f)
                    path.moveTo(10f,10f);path.lineTo(10f,17f);path.moveTo(14f,10f);path.lineTo(14f,17f)
                }
                "library" -> {
                    path.moveTo(4f,3f);path.lineTo(4f,21f);path.lineTo(20f,21f)
                    path.moveTo(8f,3f);path.lineTo(20f,3f);path.lineTo(20f,17f);path.lineTo(8f,17f);path.close()
                    path.moveTo(11f,7f);path.lineTo(17f,7f);path.moveTo(11f,11f);path.lineTo(17f,11f)
                }
                "updates" -> {
                    path.moveTo(20f,10f);path.cubicTo(19f,4f,12f,1f,7f,5f)
                    path.moveTo(7f,2f);path.lineTo(7f,5f);path.lineTo(10f,5f)
                    path.moveTo(4f,14f);path.cubicTo(5f,20f,12f,23f,17f,19f)
                    path.moveTo(14f,19f);path.lineTo(17f,19f);path.lineTo(17f,22f)
                }
                "computer" -> {
                    path.moveTo(3f,4f);path.lineTo(21f,4f);path.lineTo(21f,17f);path.lineTo(3f,17f);path.close()
                    path.moveTo(12f,17f);path.lineTo(12f,21f);path.moveTo(7f,21f);path.lineTo(17f,21f)
                }
                "palette" -> {
                    drawCircle(tint,9f,Offset(12f,12f),style=stroke)
                    listOf(Offset(8f,8f),Offset(14f,6.5f),Offset(18f,11f),Offset(7f,14f)).forEach {drawCircle(tint,1.5f,it)}
                }
                else -> {
                    drawCircle(tint,3f,Offset(12f,12f),style=stroke)
                    for(i in 0..7) {
                        val a=i*Math.PI/4;val x=kotlin.math.cos(a).toFloat();val y=kotlin.math.sin(a).toFloat()
                        drawLine(tint,Offset(12+x*7,12+y*7),Offset(12+x*10,12+y*10),strokeWidth=2f,cap=StrokeCap.Round)
                    }
                    drawCircle(tint,7f,Offset(12f,12f),style=stroke)
                }
            }
            drawPath(path,tint,style=stroke)
        }
    }
}

@Composable
fun RepeatTrackButton(player: Player) {
    val context=LocalContext.current
    var enabled by remember(player) {mutableStateOf(player.repeatMode==Player.REPEAT_MODE_ONE)}
    DisposableEffect(player) {
        val listener=object: Player.Listener { override fun onRepeatModeChanged(repeatMode: Int) {enabled=repeatMode==Player.REPEAT_MODE_ONE} }
        player.addListener(listener);onDispose {player.removeListener(listener)}
    }
    PlayerToggleButton(checked=enabled,onCheckedChange={
        player.repeatMode=if(it)Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        enabled=it;context.getSharedPreferences("muzerion_settings",0).edit().putBoolean("repeat_one",it).apply()
    },modifier=Modifier.size(44.dp)) {
        val tint = if(enabled) MuzerionStyle.accent else MuzerionStyle.color(0xFF96939F)
        androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
            val unit = size.width / 24f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(4*unit, 9*unit); lineTo(4*unit, 8*unit)
                cubicTo(4*unit, 6.34f*unit, 5.34f*unit, 5*unit, 7*unit, 5*unit)
                lineTo(20*unit, 5*unit)
                moveTo(17*unit, 2*unit); lineTo(20*unit, 5*unit); lineTo(17*unit, 8*unit)
                moveTo(20*unit, 15*unit); lineTo(20*unit, 16*unit)
                cubicTo(20*unit, 17.66f*unit, 18.66f*unit, 19*unit, 17*unit, 19*unit)
                lineTo(4*unit, 19*unit)
                moveTo(7*unit, 16*unit); lineTo(4*unit, 19*unit); lineTo(7*unit, 22*unit)
            }
            drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.8.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            ))
        }
    }
}


@Composable
private fun BackgroundSettingSlider(title: String,value: Float,start: String,end: String,onCommit: (Float)->Unit) {
    var draft by remember(value) {mutableFloatStateOf(value)}
    Row(Modifier.fillMaxWidth().padding(top=16.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,color=MuzerionStyle.text,modifier=Modifier.weight(1f))
        Text("${(draft*100).toInt()}%",color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value=draft,onValueChange={draft=it},onValueChangeFinished={onCommit(draft)},
        modifier=Modifier.fillMaxWidth().semantics {contentDescription=title})
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(start,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=11.sp)
        Text(end,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=11.sp)
    }
}
