package com.example.muzerion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import java.util.Locale

private fun semitones(pitch: Float)=(12f*ln(pitch)/ln(2f)).coerceIn(-12f,12f)
private fun pitchFactor(semitones: Float)=2.0.pow(semitones/12.0).toFloat().coerceIn(.5f,2f)
private fun roundedSpeed(value: Float)=(value.coerceIn(.5f,2f)*100).roundToInt()/100f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSoundSheet(player: Player,onDismiss: ()->Unit) {
    val context=LocalContext.current
    val preferences=remember(context) {context.getSharedPreferences("muzerion_settings",0)}
    var speed by remember(player) {mutableFloatStateOf(player.playbackParameters.speed.coerceIn(.5f,2f))}
    var tone by remember(player) {mutableFloatStateOf(semitones(player.playbackParameters.pitch))}
    var linked by remember {mutableStateOf(preferences.getBoolean("sound_linked",true) && kotlin.math.abs(player.playbackParameters.speed-player.playbackParameters.pitch)<.001f)}
    var available by remember(player) {mutableStateOf(player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH))}
    DisposableEffect(player) {
        val listener=object: Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                val wasAvailable=available
                available=availableCommands.contains(Player.COMMAND_SET_SPEED_AND_PITCH)
                if(available && !wasAvailable) {
                    speed=player.playbackParameters.speed.coerceIn(.5f,2f)
                    tone=semitones(player.playbackParameters.pitch)
                }
            }
        }
        player.addListener(listener)
        onDispose {player.removeListener(listener)}
    }
    fun apply() {
        if(player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            val parameters=PlaybackParameters(speed,if(linked)speed else pitchFactor(tone))
            if(player.playbackParameters!=parameters) player.playbackParameters=parameters
            preferences.edit().putFloat("sound_speed",parameters.speed).putFloat("sound_pitch",parameters.pitch).apply()
        }
    }
    // Avoid flooding the audio processor while dragging; release applies immediately.
    LaunchedEffect(speed,tone,linked,available) {if(available) {delay(80);apply()} }
    fun setLinked(value: Boolean) {
        linked=value
        if(value) tone=semitones(speed)
        preferences.edit().putBoolean("sound_linked",value).apply()
        apply()
    }
    fun preset(value: Float) {
        speed=value;tone=semitones(value);setLinked(true)
    }
    ModalBottomSheet(onDismissRequest={apply();onDismiss()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=MuzerionStyle.panel,contentColor=MuzerionStyle.text) {
        HighRefreshRateSheetWindow()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=24.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(tr(R.string.msg_007be3a0e3),fontSize=18.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                PlayerIconButton(onClick={apply();onDismiss()},modifier=Modifier.semantics {contentDescription=tr(R.string.msg_398c7d4f7b)}) {MuzerionIcon("check",MuzerionStyle.accent)}
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(tr(R.string.msg_d2b1c49a1d) to .8f,tr(R.string.msg_4cc6290561) to 1f,tr(R.string.msg_5ac0bcf0e3) to 1.25f).forEach { (label,value)->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(MuzerionStyle.accent.copy(alpha=.12f))
                        .clickable(enabled=available,role=Role.Button) {preset(value)}
                        .padding(vertical=14.dp),contentAlignment=Alignment.Center) {
                        Text(label,color=MuzerionStyle.accent,fontSize=12.sp,maxLines=1,softWrap=false)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top=20.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(tr(R.string.msg_061828d17c),fontSize=14.sp,modifier=Modifier.weight(1f))
                Switch(checked=linked,enabled=available,onCheckedChange={setLinked(it)},
                    modifier=Modifier.semantics {contentDescription=tr(R.string.msg_061828d17c)})
            }
            SoundSlider(tr(R.string.msg_09625bf08f),String.format(Locale.getDefault(),"%.2f×",speed),speed,.5f..2f,available,tr(R.string.msg_8b7138bc77),tr(R.string.msg_20f75ce6c7),
                onChange={speed=roundedSpeed(it);if(linked)tone=semitones(speed)},onFinish={apply()})
            SoundSlider(tr(R.string.msg_6fe10e81a9),String.format(Locale.getDefault(),tr(R.string.msg_ca5418b910),tone),tone,-12f..12f,available,"−12","+12",
                onChange={tone=(it*2).roundToInt()/2f;if(linked) {speed=pitchFactor(tone);tone=semitones(speed)}},onFinish={apply()})
            Spacer(Modifier.height(12.dp))
            PlayerTextButton(onClick={speed=1f;tone=0f;apply()}) {Text(tr(R.string.msg_058f162d29))}
            if(!available) Text(tr(R.string.msg_cf775143a8),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SoundSlider(title: String,label: String,value: Float,range: ClosedFloatingPointRange<Float>,enabled: Boolean,
    start: String,end: String,onChange: (Float)->Unit,onFinish: ()->Unit) {
    Row(Modifier.fillMaxWidth().padding(top=24.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
        Text(label,color=MuzerionStyle.accent,fontSize=12.sp)
    }
    MuzerionSeekSlider(value=value,onValueChange=onChange,onValueChangeFinished=onFinish,valueRange=range,enabled=enabled,
        modifier=Modifier.fillMaxWidth().semantics {contentDescription=title})
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(start,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(end,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
