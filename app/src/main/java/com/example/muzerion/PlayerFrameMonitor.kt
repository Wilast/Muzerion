package com.example.muzerion

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window

// Installed only while the player is open in the local performance build.
// The callback runs on its own thread and records timing numbers, never music data.
internal class PlayerFrameMonitor(private val window: Window) : AutoCloseable {
    private val thread=HandlerThread("MuzerionFrameMetrics").apply { start() }
    private var lastLog=0L
    private val listener=Window.OnFrameMetricsAvailableListener { _,frame,droppedReports ->
        val total=frame.getMetric(FrameMetrics.TOTAL_DURATION)
        val now=SystemClock.elapsedRealtime()
        if(frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME)==0L && total>=18_000_000L && now-lastLog>=500L) {
            lastLog=now
            fun ms(metric: Int)=frame.getMetric(metric)/1_000_000L
            val gpu=if(Build.VERSION.SDK_INT>=31) ms(FrameMetrics.GPU_DURATION) else -1L
            Log.i("MuzerionFrames","slow_frame total=${total/1_000_000}ms " +
                "delay=${ms(FrameMetrics.UNKNOWN_DELAY_DURATION)} input=${ms(FrameMetrics.INPUT_HANDLING_DURATION)} " +
                "animation=${ms(FrameMetrics.ANIMATION_DURATION)} layout=${ms(FrameMetrics.LAYOUT_MEASURE_DURATION)} " +
                "draw=${ms(FrameMetrics.DRAW_DURATION)} sync=${ms(FrameMetrics.SYNC_DURATION)} " +
                "commands=${ms(FrameMetrics.COMMAND_ISSUE_DURATION)} swap=${ms(FrameMetrics.SWAP_BUFFERS_DURATION)} " +
                "gpu=${gpu} reports_lost=${droppedReports}")
        }
    }
    init { window.addOnFrameMetricsAvailableListener(listener,Handler(thread.looper)) }
    override fun close() {
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }
}
