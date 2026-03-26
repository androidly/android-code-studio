package com.tom.rv2ide.fragments.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils

class AIAssistantWorkingTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val shaderMatrix = Matrix()
    private var shimmerAnimator: ValueAnimator? = null
    private var shimmerShader: LinearGradient? = null
    private var shimmerEnabled = false
    private var shimmerOffset = -1f

    fun setShimmerEnabled(enabled: Boolean) {
        if (shimmerEnabled == enabled) {
            return
        }
        shimmerEnabled = enabled
        if (enabled) {
            post { startShimmerIfNeeded() }
        } else {
            stopShimmer()
        }
        updateShader()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (shimmerEnabled) {
            post { startShimmerIfNeeded() }
        }
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) {
            stopShimmer()
        } else if (shimmerEnabled) {
            post { startShimmerIfNeeded() }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shimmerShader = null
        if (shimmerEnabled) {
            updateShader()
        }
    }

    override fun setTextColor(color: Int) {
        super.setTextColor(color)
        shimmerShader = null
        if (shimmerEnabled) {
            updateShader()
        }
    }

    private fun startShimmerIfNeeded() {
        if (!shimmerEnabled || !isShown || width <= 0 || text.isNullOrBlank()) {
            return
        }
        if (shimmerAnimator != null) {
            return
        }
        shimmerAnimator = ValueAnimator.ofFloat(-1.15f, 1.15f).apply {
            duration = 1300L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                shimmerOffset = animator.animatedValue as Float
                updateShader()
            }
            start()
        }
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        shimmerOffset = -1f
        paint.shader = null
        invalidate()
    }

    private fun updateShader() {
        if (!shimmerEnabled || width <= 0 || text.isNullOrBlank()) {
            paint.shader = null
            invalidate()
            return
        }
        val baseColor = currentTextColor
        val highlightColor = ColorUtils.blendARGB(baseColor, Color.WHITE, 0.6f)
        val shader = shimmerShader ?: LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            intArrayOf(baseColor, highlightColor, baseColor),
            floatArrayOf(0.18f, 0.5f, 0.82f),
            Shader.TileMode.CLAMP
        ).also { created ->
            shimmerShader = created
        }
        shaderMatrix.reset()
        shaderMatrix.setTranslate(width * shimmerOffset, 0f)
        shader.setLocalMatrix(shaderMatrix)
        paint.shader = shader
        invalidate()
    }
}
