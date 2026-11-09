package com.example.practice.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.animation.LinearInterpolator
import com.example.practice.Config
import kotlin.math.PI
import kotlin.math.cos

/**
 * 다른 앱 위에 겹쳐 그리는 투명한 전체 화면 뷰.
 * HC-0: 파란 점과 파란 도형만 그린다. 텍스트를 그리는 코드는 넣지 않는다.
 * 좌표는 모두 "화면 기준 픽셀"로 받는다(접근성 노드의 getBoundsInScreen과 같은 기준).
 */
class DotOverlayView(context: Context) : View(context) {

    /**
     * 지금 구현된 상태. DOUBLE_TAP, LONG_PRESS, PINCH, BACK_EDGE, MOVE, PARKED, REJECT는 M3~M4에서 추가한다.
     * SCROLL은 M1에서는 "점이 위로 미끄러지는" 간단한 형태이고, M4에서 붓펜 획으로 바뀐다.
     */
    enum class State { HIDDEN, LISTENING, TAP, SCROLL }

    var state: State = State.HIDDEN
        private set

    private val density = resources.displayMetrics.density
    private val dotRadius = Config.DOT_DIAMETER_DP * density / 2f

    private var targetX = 0f
    private var targetY = 0f
    private var scrollStartY = 0f
    private var scrollEndY = 0f
    private var phase = 0f // 0 → 1, 애니메이션 한 주기마다 반복
    private val screenOffset = IntArray(2)

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Config.DOT_COLOR
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Config.DOT_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = Config.PULSE_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    /** 목표 위에 점 + 퍼져 나가는 링. */
    fun showTap(screenX: Float, screenY: Float) {
        if (state == State.TAP && screenX == targetX && screenY == targetY) return
        targetX = screenX
        targetY = screenY
        setState(State.TAP)
    }

    /** 목록 위에서 아래→위로 미끄러지는 점(위로 밀어 올리라는 뜻). */
    fun showScroll(listBounds: Rect) {
        val length = Config.BRUSH_LENGTH_DP * density
        val cx = listBounds.exactCenterX()
        val start = (listBounds.exactCenterY() + length / 2f).coerceAtMost(listBounds.bottom - dotRadius * 2)
        val end = (start - length).coerceAtLeast(listBounds.top + dotRadius * 2)
        if (state == State.SCROLL && cx == targetX && start == scrollStartY) return
        targetX = cx
        scrollStartY = start
        scrollEndY = end
        setState(State.SCROLL)
    }

    /** 화면 아래 가운데에서 숨 쉬듯 맥동(듣는 중). */
    fun showListening() = setState(State.LISTENING)

    fun hide() = setState(State.HIDDEN)

    private fun setState(next: State) {
        val period = if (next == State.SCROLL) Config.SCROLL_REPEAT_MS else Config.PULSE_PERIOD_MS
        val restart = next != state || animator.duration != period
        state = next
        if (next == State.HIDDEN) {
            animator.cancel()
        } else if (restart || !animator.isStarted) {
            animator.cancel()
            animator.duration = period
            animator.start()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (state == State.HIDDEN) return

        // 화면 좌표 → 이 뷰의 좌표. 오버레이 창이 상태바 아래에서 시작해도 점 위치가 어긋나지 않게 해요.
        getLocationOnScreen(screenOffset)
        canvas.save()
        canvas.translate(-screenOffset[0].toFloat(), -screenOffset[1].toFloat())

        when (state) {
            State.TAP -> drawTap(canvas)
            State.SCROLL -> drawScroll(canvas)
            State.LISTENING -> drawListening(canvas)
            State.HIDDEN -> Unit
        }
        canvas.restore()
    }

    private fun drawTap(canvas: Canvas) {
        // 바깥 링: 점 크기에서 PULSE_MAX_SCALE배까지 퍼지면서 흐려져요.
        val easeOut = 1f - (1f - phase) * (1f - phase) * (1f - phase)
        val ringRadius = dotRadius * (1f + (Config.PULSE_MAX_SCALE - 1f) * easeOut)
        ringPaint.alpha = (255 * 0.8f * (1f - phase)).toInt()
        canvas.drawCircle(targetX, targetY, ringRadius, ringPaint)

        dotPaint.alpha = 255
        canvas.drawCircle(targetX, targetY, dotRadius, dotPaint)
    }

    private fun drawScroll(canvas: Canvas) {
        // 한 주기(SCROLL_REPEAT_MS) 중 앞부분(SCROLL_STROKE_MS) 동안만 움직이고, 나머지는 쉬어요.
        val elapsed = phase * Config.SCROLL_REPEAT_MS
        if (elapsed > Config.SCROLL_STROKE_MS) return
        val p = elapsed / Config.SCROLL_STROKE_MS
        val eased = if (p < 0.5f) 2f * p * p else 1f - (-2f * p + 2f).let { it * it } / 2f
        val y = scrollStartY + (scrollEndY - scrollStartY) * eased
        dotPaint.alpha = (255 * (1f - 0.7f * p)).toInt()
        canvas.drawCircle(targetX, y, dotRadius, dotPaint)
    }

    private fun drawListening(canvas: Canvas) {
        val breath = 0.5f - 0.5f * cos(2f * PI.toFloat() * phase) // 0 → 1 → 0
        val radius = dotRadius * (0.8f + 0.25f * breath)
        dotPaint.alpha = (255 * (0.55f + 0.45f * breath)).toInt()
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels - 120f * density
        canvas.drawCircle(x, y, radius, dotPaint)
    }
}
