package com.example.muzerion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as BitmapCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private fun bitmapCache(bytes: Int)=object : LruCache<String,Bitmap>(bytes) {
    override fun sizeOf(key: String,value: Bitmap)=value.allocationByteCount
}
private val ambientCache=bitmapCache(3*1024*1024)
private val backgroundCache=bitmapCache(8*1024*1024)

private suspend fun blurredArtwork(context: Context,uri: Uri,widthLimit: Int,radius: Int): Bitmap? {
    val key="${uri}@${widthLimit}@${radius}"
    ambientCache.get(key)?.let { return it }
    val source=loadArtworkBitmap(context,uri,widthLimit) ?: return null
    return withContext(Dispatchers.Default) {
        ambientCache.get(key) ?: run {
            val scale=minOf(1f,widthLimit.toFloat()/maxOf(source.width,source.height))
            val width=(source.width*scale).roundToInt().coerceAtLeast(1)
            val height=(source.height*scale).roundToInt().coerceAtLeast(1)
            val small=Bitmap.createScaledBitmap(source,width,height,true)
            val pixels=IntArray(width*height)
            small.getPixels(pixels,0,width,0,0,width,height)
            if(small!==source) small.recycle()
            coroutineContext.ensureActive()
            Bitmap.createBitmap(blurAmbientPixels(pixels,width,height,radius),width,height,Bitmap.Config.ARGB_8888)
                .also { ambientCache.put(key,it) }
        }
    }
}

// Composite the theme, artwork and dimming once on a small software canvas.
// Rendering then needs one opaque texture, with no full-screen alpha blending.
private fun renderBackground(source: Bitmap?,width: Int,height: Int,bg: Int,panel: Int,visibility: Float,dark: Boolean): Bitmap {
    val result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
    val canvas=BitmapCanvas(result)
    val paint=Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    paint.shader=LinearGradient(0f,0f,0f,height.toFloat(),intArrayOf(panel,panel,bg),null,Shader.TileMode.CLAMP)
    canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
    if(source!=null) {
        paint.shader=null
        paint.alpha=(visibility*255).roundToInt()
        val scale=maxOf(width.toFloat()/source.width,height.toFloat()/source.height)*1.15f
        val w=source.width*scale;val h=source.height*scale
        canvas.drawBitmap(source,null,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),paint)
    }
    paint.alpha=255
    fun dim(alpha: Float)=(bg and 0x00FFFFFF) or ((alpha*255).roundToInt() shl 24)
    paint.shader=LinearGradient(0f,0f,0f,height.toFloat(),intArrayOf(dim(if(dark).28f else .58f),dim(if(dark).35f else .45f),dim(.94f)),null,Shader.TileMode.CLAMP)
    canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
    result.setHasAlpha(false)
    return result
}

@Composable
internal fun AmbientArtwork(uri: Uri?,aspectRatio: Float,modifier: Modifier=Modifier) {
    val context=LocalContext.current
    val bg=MuzerionStyle.background.toArgb()
    val panel=MuzerionStyle.panel.toArgb()
    val dark=MuzerionStyle.dark
    val visibility=(MuzerionStyle.backgroundVisibility*100).roundToInt().coerceIn(0,100)
    val blur=(MuzerionStyle.backgroundBlur*100).roundToInt().coerceIn(0,100)
    // Sharp artwork needs more detail; a strongly blurred background can stay tiny.
    val width=if(blur<8)512 else if(blur<30)256 else 128
    val radius=(blur/100f*width/12f).roundToInt().coerceIn(0,64)
    val height=(width/aspectRatio.coerceAtLeast(.1f)).roundToInt().coerceIn(16,1536)
    val key="${uri}@${width}@${height}@${bg}@${panel}@${visibility}@${blur}@${dark}"
    val backdrop by produceState<Bitmap?>(backgroundCache.get(key),key) {
        val cached=backgroundCache.get(key)
        if(cached!=null) { value=cached;return@produceState }
        val art=if(visibility==0)null else uri?.let { blurredArtwork(context,it,width,radius) }
        value=withContext(Dispatchers.Default) {
            backgroundCache.get(key) ?: renderBackground(art,width,height,bg,panel,visibility/100f,dark).also { backgroundCache.put(key,it) }
        }
    }
    backdrop?.let { Image(it.asImageBitmap(),null,modifier,contentScale=ContentScale.FillBounds) }
}

internal fun clearAmbientCaches() {ambientCache.evictAll();backgroundCache.evictAll()}
