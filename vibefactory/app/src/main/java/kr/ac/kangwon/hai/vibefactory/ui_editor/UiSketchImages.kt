package kr.ac.kangwon.hai.vibefactory.ui_editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

internal object UiSketchImages {
    fun fit(imageAspect: Float, canvasAspect: Float): UiNormalizedRect {
        val ratio = imageAspect / canvasAspect.coerceAtLeast(.001f)
        val width = if (ratio >= 1) .8f else .8f * ratio
        val height = if (ratio >= 1) .8f / ratio else .8f
        return UiNormalizedRect((1-width)/2, (1-height)/2, (1+width)/2, (1+height)/2)
    }

    /** Resize around the opposite corner, preserving the image's original aspect ratio. */
    fun resize(bounds: UiNormalizedRect, corner: Int, dx: Float, dy: Float): UiNormalizedRect {
        val w = bounds.right - bounds.left; val h = bounds.bottom - bounds.top
        val left = corner == 0 || corner == 2; val top = corner < 2
        val anchorX = if (left) bounds.right else bounds.left
        val anchorY = if (top) bounds.bottom else bounds.top
        val xSpace = if (left) anchorX else 1-anchorX
        val ySpace = if (top) anchorY else 1-anchorY
        val maxScale = minOf(xSpace/w, ySpace/h)
        val minScale = minOf(.04f/w, .04f/h, maxScale)
        val scale = (1 + ((if (left) -dx else dx)*w + (if (top) -dy else dy)*h)/(w*w+h*h))
            .coerceIn(minScale, maxScale)
        return UiNormalizedRect(if (left) anchorX-w*scale else anchorX,
            if (top) anchorY-h*scale else anchorY,
            if (left) anchorX else anchorX+w*scale, if (top) anchorY else anchorY+h*scale)
    }

    fun draw(canvas: Canvas, area: RectF, layers: List<UiSketchImageLayer>, bitmaps: Map<String, Bitmap>) {
        canvas.save(); canvas.clipRect(area)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        layers.forEach { layer ->
            val b = layer.bounds
            bitmaps[layer.imageId]?.takeUnless { it.isRecycled }?.let {
                canvas.drawBitmap(it, null, RectF(area.left+b.left*area.width(), area.top+b.top*area.height(),
                    area.left+b.right*area.width(), area.top+b.bottom*area.height()), paint)
            }
        }
        canvas.restore()
    }

    /** Call on an IO dispatcher. */
    fun decode(path: String, maxSize: Int = 1024): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / (sample * 2) >= maxSize) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
