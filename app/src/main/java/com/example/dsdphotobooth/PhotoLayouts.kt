package com.example.dsdphotobooth

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.content.Context
import kotlin.math.roundToInt

internal data class PhotoSlot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun rect(width: Float, height: Float) = RectF(left * width, top * height, right * width, bottom * height)
    fun valid() = left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
        right - left >= 0.025f && bottom - top >= 0.025f
    fun centered(): PhotoSlot {
        val half = (right-left)/2f
        return copy(left = .5f-half,right = .5f+half)
    }
    fun isCentered() = kotlin.math.abs(left + right - 1f) < .003f
    fun overlaps(other: PhotoSlot) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
}
internal data class BoothLayout(val key: String, val label: String, val ratio: Float, val slots: List<PhotoSlot>) {
    val poses get() = slots.size
    fun acceptsRatio(actual: Float) = kotlin.math.abs(actual / ratio - 1f) <= .01f
    val artworkSize get() = "720 × ${(720 * ratio).roundToInt()} px"
    val centeredColumn get() = key != "M" && !key.startsWith("custom-")
}
internal data class ArtworkTemplate(val id: Long, val layoutKey: String, val name: String, val fileName: String,
    val ratio: Float, val slots: List<PhotoSlot>)
internal data class TemplateChoice(val name: String, val ratio: Float, val slots: List<PhotoSlot>, val artwork: ArtworkTemplate? = null)

internal object PhotoLayouts {
    private fun window(l: Float,t: Float,r: Float,b: Float) = PhotoSlot(l,t,r,b)
    private fun column(poses: Int, top: Float, bottom: Float, gap: Float, left: Float = .08f, right: Float = .92f): List<PhotoSlot> {
        val row = (bottom-top-gap*(poses-1))/poses
        return (0 until poses).map { val y = top + it*(row+gap); window(left,y,right,y+row) }
    }
    private const val COMPACT_PORTRAIT = 1.5f
    private val retired = listOf(
        BoothLayout("B","3-pose strip with header",3f,column(3,.15f,.89f,.022f)),
        BoothLayout("C","4-pose strip",3f,column(4,.04f,.84f,.018f)),
        BoothLayout("D","4-pose strip with header",3f,column(4,.14f,.90f,.016f))
    )
    private val oldWindow = BoothLayout("M","4-in-1 portrait window",COMPACT_PORTRAIT,listOf(
            window(.08f,.07f,.485f,.425f), window(.515f,.07f,.92f,.425f),
            window(.08f,.455f,.485f,.81f), window(.515f,.455f,.92f,.81f)
        ))
    val catalog = listOf(
        BoothLayout("A","3-pose strip",3f,column(3,.04f,.83f,.025f)),
        BoothLayout("K","2-pose portrait",1.5f,column(2,.06f,.85f,.035f)),
        BoothLayout("L","1-pose portrait",COMPACT_PORTRAIT,column(1,.07f,.81f,.02f))
    )
    fun named(key: String?) = (catalog + retired + oldWindow).firstOrNull { it.key == key }
    fun forSession(key: String?, poses: Int): BoothLayout =
        named(key)?.takeIf { it.poses == poses } ?: BoothLayout("legacy","Photo strip",maxOf(1.5f,.25f+poses*.65f),
            column(poses,.10f,.91f,.024f))
}

/** Pixel crop centered in the source, with the exact destination aspect ratio as closely as pixels permit. */
internal fun centerCropBounds(width: Int, height: Int, aspect: Float): IntArray {
    require(width > 0 && height > 0 && aspect.isFinite() && aspect > 0f)
    val cropWidth = minOf(width,(height * aspect).roundToInt().coerceAtLeast(1))
    val cropHeight = minOf(height,(width / aspect).roundToInt().coerceAtLeast(1))
    val x = (width-cropWidth)/2
    val y = (height-cropHeight)/2
    return intArrayOf(x,y,x+cropWidth,y+cropHeight)
}
internal fun Canvas.drawCropped(photo: Bitmap, destination: RectF, paint: Paint) {
    val bounds = centerCropBounds(photo.width,photo.height,destination.width()/destination.height())
    drawBitmap(photo,Rect(bounds[0],bounds[1],bounds[2],bounds[3]),destination,paint)
}

internal class LayoutThumbnail(context: Context, private val layout: BoothLayout) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = minOf(width.toFloat()-8f,(height.toFloat()-8f)/layout.ratio).coerceAtLeast(1f)
        val h = w*layout.ratio
        canvas.save()
        canvas.translate((width-w)/2,(height-h)/2)
        pen.color = Color.rgb(27,30,35)
        canvas.drawRect(0f,0f,w,h,pen)
        pen.color = Color.WHITE
        layout.slots.forEach { canvas.drawRect(it.rect(w,h),pen) }
        canvas.restore()
    }
}

internal class TemplateSlotEditor(context: Context, private val image: Bitmap, private val limit: Int,
    private val centeredColumn: Boolean, private val onCount: (Int) -> Unit) : View(context) {
    val slots = mutableListOf<PhotoSlot>()
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var dragging = false
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun artworkBounds(): RectF {
        val scale = minOf(width.toFloat()/image.width,height.toFloat()/image.height)
        val w = image.width*scale
        val h = image.height*scale
        return RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
    }
    fun undo() {
        if (slots.isNotEmpty()) slots.removeAt(slots.lastIndex)
        onCount(slots.size)
        invalidate()
    }
    private fun current() = PhotoSlot(minOf(startX,endX),minOf(startY,endY),maxOf(startX,endX),maxOf(startY,endY)).let { if (centeredColumn) it.centered() else it }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = artworkBounds()
        canvas.drawRect(bounds,Paint().apply { color = Color.WHITE })
        canvas.drawBitmap(image,null,bounds,imagePaint)
        canvas.save()
        canvas.translate(bounds.left,bounds.top)
        (slots + if (dragging) listOf(current()) else emptyList()).forEachIndexed { i,slot ->
            val rect = slot.rect(bounds.width(),bounds.height())
            paint.color = 0x660074C9
            paint.style = Paint.Style.FILL
            canvas.drawRect(rect,paint)
            paint.color = 0xFF0074C9.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density*2
            canvas.drawRect(rect,paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = resources.displayMetrics.scaledDensity*16
            canvas.drawText((i+1).toString(),rect.left+5,rect.top+paint.textSize,paint)
        }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bounds = artworkBounds()
        val x = ((event.x-bounds.left)/bounds.width()).coerceIn(0f,1f)
        val y = ((event.y-bounds.top)/bounds.height()).coerceIn(0f,1f)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if(slots.size >= limit || !bounds.contains(event.x,event.y)) return false
                startX=x; startY=y; endX=x; endY=y; dragging=true
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> { endX=x; endY=y }
            MotionEvent.ACTION_UP -> {
                endX=x; endY=y
                val slot=current()
                if (slot.valid() && slots.none { it.overlaps(slot) }) { slots.add(slot); onCount(slots.size) }
                else announceForAccessibility("Draw a larger space without overlapping another photo.")
                dragging=false
                parent.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { dragging=false; parent.requestDisallowInterceptTouchEvent(false) }
            else -> return false
        }
        invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
