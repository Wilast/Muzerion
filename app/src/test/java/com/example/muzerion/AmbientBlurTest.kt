package com.example.muzerion

import org.junit.Assert.*
import org.junit.Test

class AmbientBlurTest {
    @Test fun uniformCoverKeepsExactColor() {
        val source=IntArray(5*3) { 0xFF76A1C8.toInt() }
        assertArrayEquals(source,blurAmbientPixels(source,5,3,5))
    }
    @Test fun inputIsNotModified() {
        val source=intArrayOf(0xFF000000.toInt(),0xFFFFFFFF.toInt(),0xFF223344.toInt())
        val before=source.copyOf()
        blurAmbientPixels(source,3,1,2)
        assertArrayEquals(before,source)
    }
    @Test fun tinyAndNarrowImagesHandleClampedEdges() {
        assertArrayEquals(intArrayOf(0xFF334455.toInt()),blurAmbientPixels(intArrayOf(0xFF334455.toInt()),1,1,5))
        compareReference(1,7,5);compareReference(8,1,3)
    }
    @Test fun slidingWindowsMatchReferenceAcrossColorChannels() {
        compareReference(7,5,2);compareReference(3,4,5)
    }
    @Test fun zeroRadiusReturnsIndependentCopy() {
        val source=intArrayOf(42)
        val result=blurAmbientPixels(source,1,1,0)
        assertArrayEquals(source,result);assertNotSame(source,result)
    }
    private fun compareReference(width: Int,height: Int,radius: Int) {
        val source=IntArray(width*height) { i -> (255 shl 24) or (((i*31)%256) shl 16) or (((i*67)%256) shl 8) or ((i*97)%256) }
        var expected=source.copyOf()
        repeat(3) {
            for(horizontal in listOf(true,false)) {
                val input=expected
                expected=IntArray(source.size) { i ->
                    val x=i%width;val y=i/width
                    var output=0
                    for(shift in listOf(0,8,16,24)) {
                        var sum=0
                        for(offset in -radius..radius) {
                            val xx=if(horizontal)(x+offset).coerceIn(0,width-1) else x
                            val yy=if(horizontal)y else (y+offset).coerceIn(0,height-1)
                            sum+=(input[yy*width+xx] ushr shift) and 255
                        }
                        output=output or ((sum/(radius*2+1)) shl shift)
                    }
                    output
                }
            }
        }
        assertArrayEquals(expected,blurAmbientPixels(source,width,height,radius))
    }
}
