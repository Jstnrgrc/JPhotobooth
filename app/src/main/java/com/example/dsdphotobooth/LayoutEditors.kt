package com.example.dsdphotobooth

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

internal data class CoverFraming(val zoom: Float, val x: Float, val y: Float)

internal class WelcomeCoverView(context: Context, var framing: CoverFraming, private val editable: Boolean = false) : View(context) {
    var image: Bitmap? = null
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastX = 0f
    private var lastY = 0f
    private fun scale(photo: Bitmap) = max(width.toFloat()/photo.width,height.toFloat()/photo.height)*framing.zoom
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val photo = image ?: return
        val w = photo.width*scale(photo)
        val h = photo.height*scale(photo)
        val left = (width-w)*(1f+framing.x)/2f
        val top = (height-h)*(1f+framing.y)/2f
        canvas.drawBitmap(photo,null,RectF(left,top,left+w,top+h),paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable) return false
        val photo = image ?: return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                lastX=event.x; lastY=event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val xTravel = (photo.width*scale(photo)-width)/2f
                val yTravel = (photo.height*scale(photo)-height)/2f
                framing = framing.copy(
                    x=if (xTravel > 1f) (framing.x - (event.x-lastX)/xTravel).coerceIn(-1f,1f) else framing.x,
                    y=if (yTravel > 1f) (framing.y - (event.y-lastY)/yTravel).coerceIn(-1f,1f) else framing.y)
                lastX=event.x; lastY=event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            else -> return false
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

internal class ReceiptTileEditor(context: Context, initial: BoothLayout?) : View(context) {
    var ratio = initial?.ratio ?: 2f
    val slots = initial?.slots?.toMutableList() ?: mutableListOf(PhotoSlot(.08f,.08f,.92f,.36f))
    private var selected = 0
    private var mode = 0
    private var initialSlot: PhotoSlot? = null
    private var downX = 0f
    private var downY = 0f
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun bounds(): RectF {
        val w = minOf(width*.94f,height*.96f/ratio)
        val h = w*ratio
        return RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
    }
    fun addTile(): Boolean {
        if (slots.size >= 4) return false
        val candidates = listOf(.28f,.21f,.16f,.12f).flatMap { h ->
            (0..16).map { i -> val top = .04f+i*.05f; PhotoSlot(.08f,top,.92f,top+h) }
        }
        val tile = candidates.firstOrNull { it.valid() && slots.none(it::overlaps) } ?: return false
        slots.add(tile)
        selected = slots.lastIndex
        invalidate()
        return true
    }
    fun removeTile(): Boolean {
        if(slots.size <= 1) return false
        slots.removeAt(selected.coerceIn(0,slots.lastIndex))
        selected = slots.lastIndex
        invalidate()
        return true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paper = bounds()
        pen.color = Color.WHITE
        canvas.drawRect(paper,pen)
        canvas.save()
        canvas.translate(paper.left,paper.top)
        slots.forEachIndexed { i,slot ->
            val r = slot.rect(paper.width(),paper.height())
            pen.color = if (i == selected) 0xffd2d2d2.toInt() else 0xffeeeeee.toInt()
            canvas.drawRect(r,pen)
            pen.color = Color.BLACK
            pen.style = Paint.Style.STROKE
            pen.strokeWidth = if(i == selected) 3f else 1f
            canvas.drawRect(r,pen)
            pen.style = Paint.Style.FILL
            pen.textSize = 16f * resources.displayMetrics.density
            canvas.drawText((i+1).toString(),r.left+6,r.top+pen.textSize+2,pen)
            if (i == selected) canvas.drawCircle(r.right,r.bottom,8f * resources.displayMetrics.density,pen)
        }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val paper = bounds()
        if (paper.isEmpty) return false
        val x = (event.x-paper.left)/paper.width()
        val y = (event.y-paper.top)/paper.height()
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val handle = slots[selected]
                val reach = 16f * resources.displayMetrics.density
                val resize = abs(x-handle.right)*paper.width() <= reach && abs(y-handle.bottom)*paper.height() <= reach
                val hit = if (resize) selected else slots.indexOfLast { x in it.left..it.right && y in it.top..it.bottom }
                if (hit < 0) return false
                selected=hit; mode=if(resize) 2 else 1
                initialSlot=slots[hit]; downX=x; downY=y
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val before = initialSlot ?: return false
                val dx = x-downX
                val dy = y-downY
                val after = if (mode == 2) before.copy(
                    right=(before.right+dx).coerceIn(before.left+.07f,.98f.coerceAtLeast(before.right)),
                    bottom=(before.bottom+dy).coerceIn(before.top+.055f,.98f.coerceAtLeast(before.bottom))
                ) else {
                    val moveX=dx.coerceIn(-before.left,1f-before.right)
                    val moveY=dy.coerceIn(-before.top,1f-before.bottom)
                    PhotoSlot((before.left+moveX).coerceIn(0f,1f),(before.top+moveY).coerceIn(0f,1f),
                        (before.right+moveX).coerceIn(0f,1f),(before.bottom+moveY).coerceIn(0f,1f))
                }
                if (after.valid() && slots.indices.none { it != selected && slots[it].overlaps(after) }) slots[selected]=after
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                initialSlot=null
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            else -> return false
        }
        invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
