package com.example.muzerion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// Keep semantics on the clickable node itself. Material3 1.4 ChildSemantics nodes
// can be read after a lazy player header is detached during a distant queue seek.
// Foundation interactions retain the normal press indication and accessibility.
@Composable
internal fun PlayerIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MuzerionStyle.text,
    content: @Composable () -> Unit
) {
    val haptic=LocalHapticFeedback.current
    Box(modifier.size(48.dp).clip(CircleShape).background(containerColor)
        .clickable(enabled=enabled,role=Role.Button,onClick={if(AppOptions.bool("haptic"))haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);onClick()}),contentAlignment=Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Composable
internal fun PlayerToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val haptic=LocalHapticFeedback.current
    Box(modifier.size(48.dp).clip(CircleShape)
        .toggleable(value=checked,enabled=enabled,role=Role.Checkbox,onValueChange={if(AppOptions.bool("haptic"))haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);onCheckedChange(it)}),
        contentAlignment=Alignment.Center) { content() }
}

@Composable
internal fun PlayerTextButton(onClick: () -> Unit,content: @Composable () -> Unit) {
    val haptic=LocalHapticFeedback.current
    Box(Modifier.heightIn(min=48.dp).clip(CircleShape)
        .clickable(role=Role.Button,onClick={if(AppOptions.bool("haptic"))haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);onClick()}).padding(horizontal=12.dp),contentAlignment=Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides MuzerionStyle.accent) { content() }
    }
}
