package com.supproxy.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

internal class ExitGlobeView(context: Context) : View(context) {
    private val globe = requireNotNull(context.getDrawable(R.drawable.exit_location_pin)).apply {
        setBounds(0, 0, 104, 96)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pin = Path().apply {
        moveTo(67f, 29f)
        cubicTo(61f, 29f, 57f, 33f, 57f, 39f)
        cubicTo(57f, 45f, 63f, 51f, 67f, 56f)
        cubicTo(71f, 51f, 77f, 45f, 77f, 39f)
        cubicTo(77f, 33f, 73f, 29f, 67f, 29f)
        close()
        addCircle(67f, 39f, 4.5f, Path.Direction.CW)
        fillType = Path.FillType.EVEN_ODD
    }
    private val pinGlow = RadialGradient(
        67f, 40f, 26f, 0xFFCD7AFF.toInt(), 0x00CD7AFF, Shader.TileMode.CLAMP
    )
    private var foreground = false
    private var visible = false
    private var lastFrame = 0L
    private var phase = 0f
    private val motion = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 4600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = System.nanoTime()
            if (now - lastFrame >= 15_000_000L) {
                lastFrame = now
                phase = it.animatedValue as Float
                invalidate()
            }
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setForegroundActive(active: Boolean) {
        foreground = active
        updateMotion()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visible = isVisible
        updateMotion()
    }

    override fun onDetachedFromWindow() {
        visible = false
        motion.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateMotion() {
        if (foreground && visible && ValueAnimator.areAnimatorsEnabled()) {
            if (!motion.isStarted) motion.start()
        } else {
            motion.cancel()
            if (phase != 0f) {
                phase = 0f
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 104f, height / 96f)
        val wave = sin(phase)
        canvas.save()
        canvas.translate((width - 104 * scale) / 2, (height - 96 * scale) / 2)
        canvas.scale(scale, scale)
        globe.draw(canvas)
        canvas.save()
        canvas.rotate(-24f + 7f * wave, 52f, 50f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0x66AD63FF
        canvas.drawOval(5f, 36f - wave, 99f, 65f + wave, paint)
        paint.color = 0xFFD385FF.toInt()
        canvas.drawArc(5f, 36f - wave, 99f, 65f + wave, 0f, 180f, false, paint)
        canvas.restore()

        paint.style = Paint.Style.FILL
        paint.shader = pinGlow
        paint.alpha = (115 + 33 * wave).toInt()
        canvas.drawCircle(67f, 40f, 26f, paint)
        paint.shader = null
        paint.color = 0xFFFCF2FF.toInt()
        val pulse = 1f + 0.055f * wave
        canvas.scale(pulse, pulse, 67f, 40f)
        canvas.drawPath(pin, paint)
        canvas.restore()
    }
}
