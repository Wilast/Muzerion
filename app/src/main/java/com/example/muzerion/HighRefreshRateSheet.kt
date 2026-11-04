package com.example.muzerion

import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

// ModalBottomSheet owns a Dialog window; the Activity's frame-rate vote does not
// cover this surface. Request the same high rate for its window and Compose view.
@Composable
internal fun HighRefreshRateSheetWindow() {
    val view=LocalView.current
    val configuration=LocalConfiguration.current
    DisposableEffect(view,configuration) {
        var ancestor: View?=view
        var window: Window?=null
        while(ancestor!=null && window==null) {
            window=(ancestor as? DialogWindowProvider)?.window
            ancestor=ancestor.parent as? View
        }
        val sheetWindow=window
        if(sheetWindow==null) return@DisposableEffect onDispose {}
        val oldMode=sheetWindow.attributes.preferredDisplayModeId
        val oldRate=sheetWindow.attributes.preferredRefreshRate
        val rateViews=listOf(view,sheetWindow.decorView).distinct()
        val previousRates=if(Build.VERSION.SDK_INT>=35) rateViews.map {it.requestedFrameRate} else emptyList()
        fun requestRate() {
            val display=view.display ?: sheetWindow.decorView.display ?: return
            val current=display.mode
            val mode=display.supportedModes.filter {
                it.physicalWidth==current.physicalWidth && it.physicalHeight==current.physicalHeight && it.refreshRate<=120.5f
            }.maxByOrNull {it.refreshRate} ?: current
            sheetWindow.attributes=sheetWindow.attributes.apply {
                preferredDisplayModeId=mode.modeId
                preferredRefreshRate=mode.refreshRate
            }
            if(Build.VERSION.SDK_INT>=35) rateViews.forEach {it.setRequestedFrameRate(mode.refreshRate)}
        }
        val listener=object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {requestRate()}
            override fun onViewDetachedFromWindow(v: View) {}
        }
        view.addOnAttachStateChangeListener(listener)
        requestRate()
        onDispose {
            view.removeOnAttachStateChangeListener(listener)
            if(Build.VERSION.SDK_INT>=35) rateViews.forEachIndexed {i,v->v.setRequestedFrameRate(previousRates[i])}
            sheetWindow.attributes=sheetWindow.attributes.apply {
                preferredDisplayModeId=oldMode
                preferredRefreshRate=oldRate
            }
        }
    }
}
