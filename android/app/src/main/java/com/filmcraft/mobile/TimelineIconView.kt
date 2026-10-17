package com.filmcraft.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Android drawing of the small FilmCraft desktop timeline glyphs on their shared 16px grid. */
internal class TimelineIconView(context: Context, private val glyph: Glyph, color: Int) : View(context) {
    enum class Glyph { SELECT, RAZOR, TRIM_IN, TRIM_OUT, GROUP, NEST, EFFECTS, UNDO, REDO, DELETE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = 1.25f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shape = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height) / 16f
        canvas.save()
        canvas.translate((width - scale * 16f) / 2f, (height - scale * 16f) / 2f)
        canvas.scale(scale, scale)
        fun line(vararg points: Float) {
            shape.reset()
            shape.moveTo(points[0], points[1])
            var index = 2
            while (index + 1 < points.size) {
                shape.lineTo(points[index], points[index + 1])
                index += 2
            }
            canvas.drawPath(shape, paint)
        }
        fun closed(vararg points: Float) {
            shape.reset()
            shape.moveTo(points[0], points[1])
            var index = 2
            while (index + 1 < points.size) {
                shape.lineTo(points[index], points[index + 1])
                index += 2
            }
            shape.close()
            canvas.drawPath(shape, paint)
        }
        fun fill(vararg points: Float) {
            val old = paint.style
            paint.style = Paint.Style.FILL
            closed(*points)
            paint.style = old
        }
        fun circle(x: Float, y: Float, radius: Float) = canvas.drawCircle(x, y, radius, paint)
        fun rect(left: Float, top: Float, right: Float, bottom: Float) = canvas.drawRect(left, top, right, bottom, paint)
        when (glyph) {
            Glyph.SELECT -> fill(4f, 2f, 4f, 13f, 6.8f, 10.4f, 8.8f, 14.6f, 10.4f, 13.9f, 8.5f, 9.8f, 12.2f, 9.6f)
            Glyph.RAZOR -> {
                closed(2.5f, 5f, 13.5f, 5f, 13.5f, 11f, 2.5f, 11f)
                circle(8f, 8f, 1.3f)
                line(5f, 8f, 6.5f, 8f)
                line(9.5f, 8f, 11f, 8f)
            }
            Glyph.TRIM_IN -> {
                line(5f, 3f, 4f, 3f, 3.5f, 3.5f, 3.5f, 6f, 3f, 7f, 2f, 8f, 3f, 9f, 3.5f, 10f, 3.5f, 12.5f, 4f, 13f, 5f, 13f)
                fill(13f, 3.5f, 6f, 8f, 13f, 12.5f)
            }
            Glyph.TRIM_OUT -> {
                line(11f, 3f, 12f, 3f, 12.5f, 3.5f, 12.5f, 6f, 13f, 7f, 14f, 8f, 13f, 9f, 12.5f, 10f, 12.5f, 12.5f, 12f, 13f, 11f, 13f)
                fill(3f, 3.5f, 10f, 8f, 3f, 12.5f)
            }
            Glyph.GROUP -> {
                rect(2f, 3f, 8f, 8f)
                rect(8f, 8f, 14f, 13f)
            }
            Glyph.NEST -> {
                rect(1.5f, 2.5f, 14.5f, 13.5f)
                rect(4.5f, 5.5f, 11.5f, 10.5f)
            }
            Glyph.EFFECTS -> {
                line(7.5f, 2.5f, 6f, 2.5f, 5f, 4f, 4f, 13.5f)
                line(2.5f, 6.5f, 7f, 6.5f)
                line(8.5f, 7f, 13.5f, 13.5f)
                line(13.5f, 7f, 8.5f, 13.5f)
            }
            Glyph.UNDO -> {
                val bounds = android.graphics.RectF(4.5f, 4.5f, 13.5f, 13.5f)
                canvas.drawArc(bounds, 180f, 270f, false, paint)
                fill(2f, 9f, 4.5f, 6f, 7f, 9f)
            }
            Glyph.REDO -> {
                val bounds = android.graphics.RectF(2.5f, 4.5f, 11.5f, 13.5f)
                canvas.drawArc(bounds, 90f, 270f, false, paint)
                fill(14f, 9f, 11.5f, 6f, 9f, 9f)
            }
            Glyph.DELETE -> {
                line(2.5f, 4f, 13.5f, 4f)
                line(6f, 4f, 6.5f, 2.5f, 9.5f, 2.5f, 10f, 4f)
                rect(4f, 5.5f, 12f, 14f)
                line(6.5f, 7.5f, 6.5f, 12f)
                line(9.5f, 7.5f, 9.5f, 12f)
            }
        }
        canvas.restore()
    }
}
