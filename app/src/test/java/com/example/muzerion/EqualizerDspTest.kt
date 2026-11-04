package com.example.muzerion

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class EqualizerDspTest {
    @Test fun flatAndDisabledAreBypassed() {
        assertTrue(EqEngine(44100,2,EqConfig()).bypass)
        assertTrue(EqEngine(48000,2,EqConfig(enabled=true)).bypass)
        assertTrue(EqEngine(48000,2,EqConfig(false,List(10){12.0})).bypass)
    }
    private fun response(db: Double,frequency: Double): Double {
        val config=EqConfig(true,List(10){if(it==5)db else 0.0},autoLevel=false)
        val bank=EqBank(config,48000,1);var inputPower=0.0;var outputPower=0.0
        for(i in 0 until 48000) {
            val x=.01*sin(2*PI*frequency*i/48000);val y=bank.process(x,0)
            if(i>12000) {inputPower+=x*x;outputPower+=y*y}
        }
        return 10*log10(outputPower/inputPower)
    }
    @Test fun bandCentreBoostMatchesDb() {assertEquals(6.0,response(6.0,1000.0),.02)}
    @Test fun bandCentreCutMatchesDb() {assertEquals(-6.0,response(-6.0,1000.0),.02)}
    @Test fun distantFrequenciesAreNotBoostedLikeCentre() {assertTrue(abs(response(6.0,50.0))<.05)}
    @Test fun manualPreampIsApplied() {
        val bank=EqBank(EqConfig(true,preamp=-6.0,autoLevel=false),48000,1)
        assertEquals(10.0.pow(-6.0/20),bank.process(1.0,0),1e-10)
    }
    @Test fun stereoChannelsStayIndependent() {
        val engine=EqEngine(44100,2,eqBuiltIns.getValue("Больше баса"));val input=DoubleArray(2);val output=DoubleArray(2)
        repeat(40000) {input[0]=.1*sin(it*.01);engine.frame(input,output);assertEquals(0.0,output[1],0.0)}
    }
    @Test fun hotSignalRemainsFiniteAndBoundedAtDifferentRates() {
        for(rate in listOf(8000,22050,44100,48000,96000)) {
            val engine=EqEngine(rate,2,EqConfig(true,List(10){12.0},autoLevel=false));val input=DoubleArray(2);val output=DoubleArray(2);val rng=Random(4)
            repeat(rate/2) {for(i in input.indices)input[i]=rng.nextDouble(-1.0,1.0);engine.frame(input,output);assertTrue(output.all {it.isFinite() && abs(it)<=1})}
        }
    }
    @Test fun automaticHeadroomCoversPositiveResponse() {
        val c=EqConfig(true,List(10){12.0});val bank=EqBank(c,48000,1)
        assertTrue(bank.appliedPreamp < -12)
        for(i in 0..1000) {
            val hz=10.0*2000.0.pow(i/1000.0)
            val magnitude=eqFrequencies.mapIndexed {j,f->EqCoefficients.peak(f,c.bands[j],48000).magnitude(hz,48000)}.fold(1.0,Double::times)*10.0.pow(bank.appliedPreamp/20)
            assertTrue("$hz: $magnitude",magnitude<1.0)
        }
    }
    @Test fun bypassReturnsAfterCrossfade() {
        val e=EqEngine(48000,1,eqBuiltIns.getValue("Рок"));e.update(EqConfig());assertFalse(e.bypass)
        repeat(1300) {e.frame(doubleArrayOf(.2),DoubleArray(1))};assertTrue(e.bypass)
    }
    @Test fun presetsAreCompleteModerateAndDistinct() {
        assertEquals(10,eqFrequencies.size);assertEquals(32,eqFrequencies.first());assertEquals(16000,eqFrequencies.last())
        assertTrue(eqBuiltIns.values.all {it.bands.size==10 && it.bands.all {abs(it)<=5}})
        assertEquals(eqBuiltIns.size,eqBuiltIns.values.map {it.bands}.distinct().size)
    }
    @Test fun invalidConfigurationsAreRejected() {
        for(bands in listOf(List(9){0.0},List(10){Double.NaN},List(10){13.0})) {
            try {EqConfig(bands=bands);fail("Accepted invalid data")} catch(_: IllegalArgumentException) {}
        }
    }
}
