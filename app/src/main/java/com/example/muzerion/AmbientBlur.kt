package com.example.muzerion

// Three separable box passes approximate a soft Gaussian blur. The source stays unchanged.
// Sliding windows keep the work linear in the number of pixels, including clamped edges.
internal fun blurAmbientPixels(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
    require(width>0 && height>0 && source.size==width*height && radius in 0..64)
    if(radius==0) return source.copyOf()
    var pixels=source.copyOf()
    val scratch=IntArray(pixels.size)
    val window=radius*2+1
    fun pass(input: IntArray, output: IntArray, horizontal: Boolean) {
        val length=if(horizontal) width else height
        val lines=if(horizontal) height else width
        fun index(line: Int, offset: Int)=if(horizontal) line*width+offset else offset*width+line
        for(line in 0 until lines) {
            var a=0;var r=0;var g=0;var b=0
            fun add(pixel: Int, sign: Int) {
                a+=(pixel ushr 24)*sign;r+=((pixel ushr 16) and 255)*sign
                g+=((pixel ushr 8) and 255)*sign;b+=(pixel and 255)*sign
            }
            for(offset in -radius..radius) add(input[index(line,offset.coerceIn(0,length-1))],1)
            for(offset in 0 until length) {
                output[index(line,offset)]=((a/window) shl 24) or ((r/window) shl 16) or ((g/window) shl 8) or (b/window)
                add(input[index(line,(offset-radius).coerceIn(0,length-1))],-1)
                add(input[index(line,(offset+radius+1).coerceIn(0,length-1))],1)
            }
        }
    }
    repeat(3) { pass(pixels,scratch,true);pass(scratch,pixels,false) }
    return pixels
}
