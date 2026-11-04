package com.example.muzerion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EqualizerSheet(onDismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MuzerionStyle.panel,contentColor=MuzerionStyle.text) {
        HighRefreshRateSheetWindow()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(tr(R.string.msg_8005fc4871),fontSize=21.sp,modifier=Modifier.weight(1f))
                PlayerIconButton(onDismiss,Modifier.semantics {contentDescription=tr(R.string.msg_398c7d4f7b)}) {MuzerionIcon("check",MuzerionStyle.accent)}
            }
            EqualizerContent()
        }
    }
}
@Composable
internal fun EqualizerSettings() {Text(tr(R.string.msg_8005fc4871),fontSize=18.sp);EqualizerContent()}

@Composable
private fun EqualizerContent() {
    val config=run {AppOptions.revision;EqualizerState.current}
    var save by remember {mutableStateOf(false)};var name by remember {mutableStateOf("")};var error by remember {mutableStateOf("")}
    var remove by remember {mutableStateOf<String?>(null)}
    val custom=EqualizerState.presets()
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(if(config.enabled) eqDisplayName(config) else tr(R.string.msg_6eb7540d7c),modifier=Modifier.weight(1f),fontSize=15.sp)
        Switch(checked=config.enabled,onCheckedChange={EqualizerState.save(config.copy(enabled=it))},modifier=Modifier.semantics {contentDescription=tr(R.string.msg_5a10389801)})
    }
    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(vertical=8.dp)) {
        items(eqBuiltIns.keys.toList()) {label->
            val selected=config.name==label && config.bands==eqBuiltIns.getValue(label).bands
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(MuzerionStyle.accent.copy(alpha=if(selected).24f else .09f))
                .clickable(role=Role.Button) {EqualizerState.save(eqBuiltIns.getValue(label))}.padding(horizontal=14.dp,vertical=12.dp)) {
                Text(eqBuiltInName(label),fontSize=13.sp,color=if(selected)MuzerionStyle.accent else MuzerionStyle.text)
            }
        }
    }
    Text(tr(R.string.msg_ade34d9483),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=10.dp,bottom=8.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        eqFrequencies.forEachIndexed {i,hz->
            EqBand(hz,config.bands[i].toFloat(),config.enabled) {value->
                val current=EqualizerState.current
                EqualizerState.save(current.copy(bands=current.bands.toMutableList().apply {this[i]=value.toDouble()},name="Мои настройки"))
            }
        }
    }
    var preamp by remember(config.preamp) {mutableFloatStateOf(config.preamp.toFloat())}
    Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(tr(R.string.msg_f88c984afe),fontSize=14.sp,modifier=Modifier.weight(1f));Text(db(preamp),fontSize=12.sp,color=MuzerionStyle.accent)
    }
    MuzerionSeekSlider(preamp,{preamp=(it*2).roundToInt()/2f},{EqualizerState.save(EqualizerState.current.copy(preamp=preamp.toDouble(),name="Мои настройки"))},-24f..0f,config.enabled,Modifier.semantics {contentDescription=tr(R.string.msg_f88c984afe)})
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(tr(R.string.msg_1a76051700),fontSize=14.sp,modifier=Modifier.weight(1f))
        Switch(config.autoLevel,{EqualizerState.save(config.copy(autoLevel=it))},enabled=config.enabled,modifier=Modifier.semantics {contentDescription=tr(R.string.msg_1a76051700)})
    }
    Text(tr(R.string.msg_6ff4f02e0a),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        PlayerTextButton({name="";error="";save=true}) {Text(tr(R.string.msg_fbb31625b0),fontSize=13.sp)}
        PlayerTextButton({EqualizerState.save(EqConfig(enabled=config.enabled))}) {Text(tr(R.string.msg_058f162d29),fontSize=13.sp)}
    }
    val names=custom.keys().asSequence().toList().sorted()
    if(names.isNotEmpty()) Text(tr(R.string.msg_4068df48cc),fontSize=17.sp,modifier=Modifier.padding(top=12.dp,bottom=8.dp))
    names.forEach {label->
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable {EqualizerState.save(EqualizerState.decode(custom.getJSONObject(label)).copy(enabled=true,name=label))}.padding(vertical=14.dp)) {Text(label,fontSize=14.sp)}
            PlayerIconButton({remove=label},Modifier.semantics {contentDescription=tr(R.string.msg_6f38f84d8d, label)}) {MuzerionIcon("delete",MuzerionStyle.text)}
        }
    }
    if(save) AlertDialog(onDismissRequest={save=false},title={Text(tr(R.string.msg_1e2763caf0))},text={Column {
        OutlinedTextField(name,{name=it.take(40);error=""},singleLine=true,label={Text(tr(R.string.msg_3de49828e8))})
        if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
    }},confirmButton={TextButton({
        val label=name.trim()
        when {
            label.isEmpty() -> error=tr(R.string.msg_629b745c61)
            eqBuiltIns.keys.any {it.equals(label,true) || eqBuiltInName(it).equals(label,true)} -> error=tr(R.string.msg_4d03d8b7d4)
            custom.length()>=50 && !custom.has(label) -> error=tr(R.string.msg_1fe95be246)
            else -> {val value=EqualizerState.current.copy(name=label);custom.put(label,EqualizerState.encode(value));AppOptions.put("equalizer_presets",custom.toString());EqualizerState.save(value);save=false}
        }
    }) {Text(if(custom.has(name.trim())) tr(R.string.msg_d772cc53be) else tr(R.string.msg_4864057d62))}},dismissButton={TextButton({save=false}) {Text(tr(R.string.msg_0ec753be8d))}})
    remove?.let {label->AlertDialog(onDismissRequest={remove=null},title={Text(tr(R.string.msg_ae3a08e626, label))},confirmButton={TextButton({custom.remove(label);AppOptions.put("equalizer_presets",custom.toString());remove=null}) {Text(tr(R.string.msg_86ea33aef5))}},dismissButton={TextButton({remove=null}) {Text(tr(R.string.msg_0ec753be8d))}})}
}
private fun db(value: Float)=String.format(Locale.getDefault(),tr(R.string.msg_18c284ac29),value)

@Composable
private fun EqBand(hz: Int,value: Float,enabled: Boolean,onCommit: (Float)->Unit) {
    var draft by remember(value) {mutableFloatStateOf(value)}
    var dragging by remember {mutableStateOf(false)}
    val commit by rememberUpdatedState(onCommit)
    val saved by rememberUpdatedState(value)
    val active=MuzerionStyle.accent;val inactive=MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.width(56.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(db(draft),fontSize=11.sp,color=if(enabled)active else MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.width(48.dp).height(170.dp)
            .semantics {
                contentDescription=tr(R.string.msg_1217534f2f, hz)
                progressBarRangeInfo=ProgressBarRangeInfo(draft,-12f..12f,47)
                if(enabled) setProgress {new->draft=(new.coerceIn(-12f,12f)*2).roundToInt()/2f;commit(draft);true} else disabled()
            }
            .pointerInput(enabled) {if(enabled)detectTapGestures {position->
                val pad=12.dp.toPx();draft=((12f-24f*((position.y-pad)/(size.height-2*pad)).coerceIn(0f,1f))*2).roundToInt()/2f;commit(draft)
            }}
            .pointerInput(enabled) {if(enabled)detectVerticalDragGestures(
                onDragStart={dragging=true},onDragEnd={dragging=false;commit(draft)},onDragCancel={dragging=false;draft=saved},
                onVerticalDrag={change,_->change.consume();val pad=12.dp.toPx();draft=((12f-24f*((change.position.y-pad)/(size.height-2*pad)).coerceIn(0f,1f))*2).roundToInt()/2f}
            )}) {
            val x=size.width/2;val top=12.dp.toPx();val bottom=size.height-top;val middle=(top+bottom)/2
            drawLine(inactive,Offset(x,top),Offset(x,bottom),4.dp.toPx(),StrokeCap.Round)
            drawLine(inactive,Offset(x-7.dp.toPx(),middle),Offset(x+7.dp.toPx(),middle),1.dp.toPx())
            val y=top+(12-draft)/24*(bottom-top)
            drawLine(if(enabled)active else inactive,Offset(x,middle),Offset(x,y),4.dp.toPx(),StrokeCap.Round)
            drawCircle(if(enabled)active else inactive,(if(dragging)9.dp else 7.dp).toPx(),Offset(x,y))
        }
        Text(if(hz>=1000) "${hz/1000}k" else "${hz}",fontSize=12.sp)
    }
}

internal fun eqBuiltInName(name: String): String = when(name) {
    "Ровно" -> tr(R.string.msg_81a4b6506a)
    "Больше баса" -> tr(R.string.msg_352cca4bf9)
    "Меньше баса" -> tr(R.string.msg_0eb1e88009)
    "Ярче" -> tr(R.string.msg_746462fa42)
    "Мягче" -> tr(R.string.msg_2e62a3d4d6)
    "Вокал" -> tr(R.string.msg_5c41492dce)
    "Рок" -> tr(R.string.msg_0229b82fcb)
    "Поп" -> tr(R.string.msg_62f83b983b)
    "Джаз" -> tr(R.string.msg_130ffde592)
    "Электроника" -> tr(R.string.msg_e55d152fd6)
    else -> name
}
internal fun eqDisplayName(config: EqConfig): String = if(EqualizerState.presets().has(config.name)) config.name else if(eqBuiltIns[config.name]?.bands==config.bands) eqBuiltInName(config.name) else if(config.name=="Мои настройки") tr(R.string.msg_b3b20a8a08) else config.name
