package com.example.muzerion

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal class EqualizerAudioProcessor: BaseAudioProcessor() {
    private var engine: EqEngine?=null
    private var inputs=DoubleArray(0);private var outputs=DoubleArray(0)
    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if(format.encoding!=C.ENCODING_PCM_16BIT)throw AudioProcessor.UnhandledAudioFormatException(format)
        return format // Remain active while disabled so toggling EQ requires no playback restart.
    }
    override fun onFlush() {
        val f=inputAudioFormat
        if(f.sampleRate>0 && f.channelCount>0) {
            engine=EqEngine(f.sampleRate,f.channelCount,EqualizerState.current)
            inputs=DoubleArray(f.channelCount);outputs=DoubleArray(f.channelCount)
        }
    }
    override fun onReset() {engine=null;inputs=DoubleArray(0);outputs=DoubleArray(0)}
    override fun queueInput(input: ByteBuffer) {
        // The pipeline also drains processors using an already-consumed buffer.
        // Do not clear/reuse our output buffer while that empty object is the input.
        if(!input.hasRemaining()) return
        input.order(java.nio.ByteOrder.nativeOrder())
        val e=engine ?: run {replaceOutputBuffer(input.remaining()).put(input).flip();return}
        e.update(EqualizerState.current)
        val output=replaceOutputBuffer(input.remaining())
        if(e.bypass) output.put(input) else {
            while(input.remaining()>=inputs.size*2) {
                for(ch in inputs.indices) inputs[ch]=input.short/32768.0
                e.frame(inputs,outputs)
                for(ch in outputs.indices)output.putShort((outputs[ch]*32768).roundToInt().coerceIn(-32768,32767).toShort())
            }
        }
        output.flip()
    }
}
