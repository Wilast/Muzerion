package com.example.muzerion

import kotlin.math.*

internal val eqFrequencies=listOf(32,64,125,250,500,1000,2000,4000,8000,16000)
internal data class EqConfig(val enabled: Boolean=false,val bands: List<Double> = List(10){0.0},val preamp: Double=0.0,val autoLevel: Boolean=true,val name: String="Ровно") {
    init {require(bands.size==10 && bands.all {it.isFinite() && it in -12.0..12.0} && preamp.isFinite() && preamp in -24.0..0.0)}
    val bypass get()=!enabled || (preamp==0.0 && bands.all {it==0.0})
}
// Moderate starting curves, designed for the octave centres above. Not headphone correction.
internal val eqBuiltIns=linkedMapOf(
    "Ровно" to listOf(0,0,0,0,0,0,0,0,0,0),
    "Больше баса" to listOf(5,4,3,1,0,0,0,0,0,0),
    "Меньше баса" to listOf(-5,-4,-3,-1,0,0,0,0,0,0),
    "Ярче" to listOf(0,0,0,0,0,0,1,2,3,3),
    "Мягче" to listOf(0,0,0,0,0,0,-1,-2,-3,-4),
    "Вокал" to listOf(-2,-2,-1,0,1,2,3,2,0,-1),
    "Рок" to listOf(3,3,1,-2,-2,0,2,3,3,2),
    "Поп" to listOf(1,2,2,2,1,1,0,-1,0,1),
    "Джаз" to listOf(2,2,1,0,-1,0,1,2,2,1),
    "Электроника" to listOf(4,3,1,-1,-2,-1,1,2,3,2)
).mapValues {(name,gains)->EqConfig(true,gains.map(Int::toDouble),name=name)}

internal data class EqCoefficients(val b0: Double,val b1: Double,val b2: Double,val a1: Double,val a2: Double) {
    fun magnitude(hz: Double,rate: Int): Double {
        val w=2*PI*hz/rate;val c=cos(w);val s=sin(w);val c2=cos(2*w);val s2=sin(2*w)
        return hypot(b0+b1*c+b2*c2,-b1*s-b2*s2)/hypot(1+a1*c+a2*c2,-a1*s-a2*s2)
    }
    companion object {
        val identity=EqCoefficients(1.0,0.0,0.0,0.0,0.0)
        // RBJ peaking biquad, one-octave nominal Q. See W3C Audio EQ Cookbook.
        fun peak(hz: Int,gain: Double,rate: Int): EqCoefficients {
            if(gain==0.0 || hz>=rate*.49)return identity
            val a=10.0.pow(gain/40);val w=2*PI*hz/rate;val alpha=sin(w)/(2*sqrt(2.0));val c=cos(w);val a0=1+alpha/a
            return EqCoefficients((1+alpha*a)/a0,-2*c/a0,(1-alpha*a)/a0,-2*c/a0,(1-alpha/a)/a0)
        }
    }
}
internal class EqBank(val config: EqConfig,rate: Int,channels: Int) {
    private val filters=eqFrequencies.mapIndexed {i,f->EqCoefficients.peak(f,if(config.enabled)config.bands[i] else 0.0,rate)}
    private val z1=DoubleArray(channels*10);private val z2=DoubleArray(channels*10)
    val appliedPreamp: Double
    private val gain: Double
    init {
        var peak=0.0
        if(config.enabled && config.autoLevel && config.bands.any {it>0}) {
            for(i in 0..1024) {
                val f=10.0*(rate*.499/10).pow(i/1024.0)
                var magnitude=1.0;filters.forEach {magnitude*=it.magnitude(f,rate)}
                peak=max(peak,20*log10(magnitude.coerceAtLeast(1e-12)))
            }
            // Leave a small margin; a stereo-linked limiter catches exceptional transients.
            peak+=1.0
        }
        appliedPreamp=if(!config.enabled)0.0 else min(config.preamp,-peak)
        gain=10.0.pow(appliedPreamp/20)
    }
    fun process(input: Double,channel: Int): Double {
        if(config.bypass)return input
        var x=input*gain
        for(i in 0..9) {
            val f=filters[i];val n=channel*10+i
            val y=f.b0*x+z1[n];z1[n]=f.b1*x-f.a1*y+z2[n];z2[n]=f.b2*x-f.a2*y;x=y
        }
        return if(x.isFinite()) x else 0.0
    }
}
internal class EqEngine(private val rate: Int,private val channels: Int,initial: EqConfig) {
    private var bank=EqBank(initial,rate,channels)
    private var previous: EqBank?=null
    private var fade=0;private val fadeFrames=max(1,rate/40)
    private var limiter=1.0
    private val release=exp(-1.0/(rate*.08))
    val appliedPreamp get()=bank.appliedPreamp
    val bypass get()=bank.config.bypass && previous==null && limiter>.99999
    fun update(config: EqConfig) {
        if(config==bank.config)return
        previous=bank;bank=EqBank(config,rate,channels);fade=fadeFrames
    }
    // Interleaved channels are independent; gain reduction is linked to preserve stereo position.
    fun frame(input: DoubleArray,output: DoubleArray) {
        val old=previous;val t=if(old==null)1.0 else 1.0-fade.toDouble()/fadeFrames
        var peak=0.0
        for(ch in 0 until channels) {
            val current=bank.process(input[ch],ch)
            val x=if(old==null)current else old.process(input[ch],ch)*(1-t)+current*t
            output[ch]=x;peak=max(peak,abs(x))
        }
        if(old!=null && --fade<=0)previous=null
        val target=if(peak>.995).995/peak else 1.0
        limiter=if(target<limiter)target else min(target,1-(1-limiter)*release)
        for(ch in 0 until channels)output[ch]=(output[ch]*limiter).coerceIn(-1.0,1.0)
    }
}
