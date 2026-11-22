package com.supproxy.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

internal data class ExitCoordinates(val latitude: Double, val longitude: Double) {
    companion object {
        fun parse(latitude: String?, longitude: String?): ExitCoordinates? {
            val lat = latitude?.toDoubleOrNull() ?: return null
            val lon = longitude?.toDoubleOrNull() ?: return null
            return if (lat in -90.0..90.0 && lon in -180.0..180.0) ExitCoordinates(lat, lon) else null
        }
    }
}

internal class ExitMapView(context: Context) : View(context) {
    private val land = requireNotNull(context.getDrawable(R.drawable.world_land))
    private val map = Rect()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val scanWidth = 58f * density
    private val scan = LinearGradient(0f, 0f, scanWidth, 0f,
        intArrayOf(0x007E79FF, 0x557E79FF, 0x007E79FF), null, Shader.TileMode.CLAMP)
    private var coordinates: ExitCoordinates? = null
    private var searching = false
    private var visible = false
    private var phase = 0f
    private val animation = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedFraction
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(exit: ExitCoordinates?, lookingUp: Boolean) {
        if (coordinates == exit && searching == lookingUp) return
        coordinates = exit
        searching = lookingUp
        updateAnimation()
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visible = isVisible
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        visible = false
        updateAnimation()
        super.onDetachedFromWindow()
    }

    private fun updateAnimation() {
        if (visible && (searching || coordinates != null) && ValueAnimator.areAnimatorsEnabled()) {
            if (!animation.isStarted) animation.start()
        } else {
            animation.cancel()
            phase = 0f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = (10 * density).toInt()
        val mapHeight = minOf((h - inset).coerceAtLeast(0), (w - 2 * inset).coerceAtLeast(0) / 2)
        val mapWidth = mapHeight * 2
        val left = (w - mapWidth) / 2
        val top = (h - mapHeight) / 2
        map.set(left, top, left + mapWidth, top + mapHeight)
        land.bounds = map
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (map.isEmpty) return
        paint.color = 0x2245A3FF
        paint.strokeWidth = density
        for (longitude in -150..150 step 30) {
            val x = xAt(longitude.toDouble())
            canvas.drawLine(x, map.top.toFloat(), x, map.bottom.toFloat(), paint)
        }
        for (latitude in -60..60 step 30) {
            val y = yAt(latitude.toDouble())
            canvas.drawLine(map.left.toFloat(), y, map.right.toFloat(), y, paint)
        }
        land.draw(canvas)
        if (searching && animation.isRunning) {
            canvas.save()
            canvas.clipRect(map)
            canvas.translate(map.left - scanWidth + phase * (map.width() + scanWidth), 0f)
            paint.color = Color.WHITE
            paint.shader = scan
            canvas.drawRect(0f, map.top.toFloat(), scanWidth, map.bottom.toFloat(), paint)
            paint.shader = null
            canvas.restore()
        }
        coordinates?.let { exit ->
            val x = xAt(exit.longitude)
            val y = yAt(exit.latitude)
            if (animation.isRunning) {
                drawPulse(canvas, x, y, phase)
                drawPulse(canvas, x, y, (phase + 0.5f) % 1f)
            }
            paint.color = 0xFF7064F8.toInt()
            canvas.drawCircle(x, y, 7f * density, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 3f * density, paint)
        }
    }

    private fun drawPulse(canvas: Canvas, x: Float, y: Float, progress: Float) {
        paint.color = Color.argb(((1f - progress) * 140).toInt(), 158, 143, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        canvas.drawCircle(x, y, (8f + progress * 16f) * density, paint)
        paint.style = Paint.Style.FILL
    }

    private fun xAt(longitude: Double) = map.left + ((longitude + 180) / 360 * map.width()).toFloat()
    private fun yAt(latitude: Double) = map.top + ((90 - latitude) / 180 * map.height()).toFloat()
}
