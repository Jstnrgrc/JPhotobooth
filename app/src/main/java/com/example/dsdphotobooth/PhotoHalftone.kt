package com.example.dsdphotobooth

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt

/** Serpentine error diffusion at printer-dot resolution, with a lighter ink limit in shadows. */
internal object PhotoHalftone {
    private const val WHITE = -1
    private const val BLACK = -16777216
    private const val SHADOW_INK_LIMIT = .62f
    private const val EDGE_LIMIT = 56

    fun apply(pixels: IntArray, width: Int, height: Int) {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
        val gray = IntArray(pixels.size)
        val histogram = IntArray(256)
        for (i in pixels.indices) {
            val rgb = pixels[i]
            val alpha = rgb ushr 24
            val luminance = (299*((rgb ushr 16) and 255) + 587*((rgb ushr 8) and 255) + 114*(rgb and 255)) / 1000
            gray[i] = (luminance*alpha + 255*(255-alpha) + 127)/255
            histogram[gray[i]]++
        }
        val blackPoint = percentile(histogram,gray.size,.02f).coerceAtMost(24)
        val whitePoint = percentile(histogram,gray.size,.98f).coerceAtLeast(232)
        val levelScale = 255f / (whitePoint - blackPoint)
        var rowError = FloatArray(width)
        var nextError = FloatArray(width)
        for (y in 0 until height) {
            val base = y * width
            val direction = if (y % 2 == 0) 1 else -1
            var x = if (direction == 1) 0 else width-1
            while (x in 0 until width) {
                val index = base+x
                val tone = gray[index]
                val left = gray[base + (x-1).coerceAtLeast(0)]
                val right = gray[base + (x+1).coerceAtMost(width-1)]
                val top = gray[if (y > 0) index-width else index]
                val bottom = gray[if (y+1 < height) index+width else index]
                // A light unsharp mask helps retain hair, eyes, and fabric detail at receipt size.
                val detail = ((tone - (left+right+top+bottom)*.25f)*.62f).coerceIn(-24f,24f)
                val leveled = ((tone-blackPoint)*levelScale).coerceIn(0f,255f)
                val lifted = leveled + 18f * (leveled/255f) * (1f-leveled/255f) * 4f
                val adjusted = (lifted + detail).coerceIn(0f,255f)
                val darkness = 1f - adjusted/255f
                // Lift deep midtones while keeping thin, high-contrast contours readable.
                val coverage = darkness * (.82f + .18f * darkness) * SHADOW_INK_LIMIT
                val target = (coverage*255f + rowError[x]).coerceIn(0f,255f)
                val ink = target >= 127.5f
                pixels[index] = if (ink) BLACK else WHITE
                val error = (target - if (ink) 255f else 0f) / 16f

                // Don't carry ink error across a strong edge into a light background.
                val forward = x+direction
                val backward = x-direction
                if (forward in 0 until width && similar(tone,gray[base+forward])) rowError[forward] += error*7f
                if (y+1 < height) {
                    val nextBase = base+width
                    if (backward in 0 until width && similar(tone,gray[nextBase+backward])) nextError[backward] += error*3f
                    if (similar(tone,gray[nextBase+x])) nextError[x] += error*5f
                    if (forward in 0 until width && similar(tone,gray[nextBase+forward])) nextError[forward] += error
                }
                x += direction
            }
            val previous = rowError
            rowError = nextError
            nextError = previous
            nextError.fill(0f)
        }
    }

    private fun similar(first: Int, second: Int) = kotlin.math.abs(first-second) < EDGE_LIMIT

    private fun percentile(histogram: IntArray, size: Int, quantile: Float): Int {
        val rank = ((size-1)*quantile).toInt()
        var seen = 0
        for (i in histogram.indices) {
            seen += histogram[i]
            if (seen > rank) return i
        }
        return 255
    }
}

internal fun Canvas.drawDottedPhoto(source: Bitmap, dest: RectF, dotsPerPoint: Float, transform: Int = 0) {
    val left = (dest.left*dotsPerPoint).roundToInt()
    val top = (dest.top*dotsPerPoint).roundToInt()
    val width = ((dest.right*dotsPerPoint).roundToInt()-left).coerceAtLeast(1)
    val height = ((dest.bottom*dotsPerPoint).roundToInt()-top).coerceAtLeast(1)
    val photo = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width*height)
    val target = Canvas(photo)
    target.scale(if (transform and 1 != 0) -1f else 1f,if (transform and 2 != 0) -1f else 1f,width/2f,height/2f)
    target.drawCropped(source,RectF(0f,0f,width.toFloat(),height.toFloat()),Paint(Paint.FILTER_BITMAP_FLAG))
    source.recycle()
    photo.getPixels(pixels,0,width,0,0,width,height)
    PhotoHalftone.apply(pixels,width,height)
    if (transform and 4 != 0) for (i in pixels.indices) pixels[i] = pixels[i] xor 0x00ffffff
    photo.setPixels(pixels,0,width,0,0,width,height)
    val snapped = RectF(left/dotsPerPoint,top/dotsPerPoint,(left+width)/dotsPerPoint,(top+height)/dotsPerPoint)
    drawBitmap(photo,null,snapped,Paint().apply { isFilterBitmap = false })
}
