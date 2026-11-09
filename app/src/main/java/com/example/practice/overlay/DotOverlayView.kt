package com.example.practice.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.example.practice.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 다른 앱 위에 겹쳐 그리는 투명한 전체 화면 뷰.
 * HC-0: 파란 점과 파란 도형만 그린다. 텍스트를 그리는 코드는 넣지 않는다.
 * 좌표는 모두 "화면 기준 픽셀"로 받는다(접근성 노드의 getBoundsInScreen과 같은 기준).
 */
class DotOverlayView(context: Context) : View(context) {

    /**
     * 지금 구현된 상태.
     * - TAP: 목표 위 점 + 퍼지는 링. 위치가 바뀔 때 MOVE_MS 동안 미끄러지듯 옮겨 갈 수 있어요(MOVE).
     * - SCROLL: M4에서 붓펜 획으로 바뀌어요. 지금은 점이 위로 미끄러지는 간단한 형태.
     * - REJECT: 좌우로 한 번 흔들리고 사라져요(범위 밖, 후보 소진).
     * DOUBLE_TAP, LONG_PRESS, PINCH, BACK_EDGE, PARKED는 M4/P3에서 추가해요.
     */
    enum class State { HIDDEN, LISTENING, TAP, SCROLL, REJECT }

    var state: State = State.HIDDEN
        private set

    private val density = resources.displayMetrics.density
    private val dotRadius = Config.DOT_DIAMETER_DP * density / 2f

    // 목표 위치와 실제로 그리는 위치. MOVE 중에는 그리는 위치가 목표로 미끄러져 가요.
    private var targetX = 0f
    private var targetY = 0f
    private var drawX = 0f
    private var drawY = 0f
    private var fromX = 0f
    private var fromY = 0f

    private var scrollStartY = 0f
    private var scrollEndY = 0f
    private var phase = 0f // 반복 애니메이션 한 주기 0 → 1
    private var rejectT = 0f // REJECT 진행 0 → 1
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

    /** 맥동·듣기·스크롤처럼 계속 반복하는 애니메이션. */
    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = Config.PULSE_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    /** 점이 이전 목표에서 새 목표로 옮겨 가는 애니메이션(MOVE). */
    private val move = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = Config.MOVE_MS
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            val t = it.animatedValue as Float
            drawX = fromX + (targetX - fromX) * t
            drawY = fromY + (targetY - fromY) * t
            invalidate()
        }
    }

    /** 좌우로 흔들리고 사라지는 애니메이션(REJECT). 끝나면 HIDDEN. */
    private val reject = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = Config.REJECT_MS
        interpolator = LinearInterpolator()
        addUpdateListener {
            rejectT = it.animatedValue as Float
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (state == State.REJECT) setState(State.HIDDEN)
            }
        })
    }

    /**
     * 목표 위에 점 + 퍼져 나가는 링.
     * animate = true면 지금 보이는 점 위치에서 새 위치로 미끄러지듯 옮겨 가요(정정했을 때 등).
     */
    fun showTap(screenX: Float, screenY: Float, animate: Boolean = false) {
        if (state == State.TAP && screenX == targetX && screenY == targetY) return
        val wasVisible = state == State.TAP || state == State.LISTENING || state == State.SCROLL
        targetX = screenX
        targetY = screenY
        move.cancel()
        if (animate && wasVisible) {
            fromX = drawX
            fromY = drawY
            move.start()
        } else {
            drawX = screenX
            drawY = screenY
        }
        setState(State.TAP)
    }

    /** 목록 위에서 아래→위로 미끄러지는 점(위로 밀어 올리라는 뜻). */
    fun showScroll(listBounds: Rect) {
        val length = Config.BRUSH_LENGTH_DP * density
        val cx = listBounds.exactCenterX()
        val start = (listBounds.exactCenterY() + length / 2f).coerceAtMost(listBounds.bottom - dotRadius * 2)
        val end = (start - length).coerceAtLeast(listBounds.top + dotRadius * 2)
        if (state == State.SCROLL && cx == targetX && start == scrollStartY) return
        move.cancel()
        targetX = cx
        drawX = cx
        scrollStartY = start
        scrollEndY = end
        drawY = start
        setState(State.SCROLL)
    }

    /** 화면 아래 가운데에서 숨 쉬듯 맥동(듣는 중·생각 중). */
    fun showListening() {
        move.cancel()
        drawX = screenWidth() / 2f
        drawY = screenHeight() - LISTEN_BOTTOM_DP * density
        setState(State.LISTENING)
    }

    /** 범위 밖이거나 후보가 다 떨어졌을 때: 지금 자리(없으면 아래 가운데)에서 좌우로 흔들리고 사라져요. */
    fun showReject() {
        move.cancel()
        if (state == State.HIDDEN) {
            drawX = screenWidth() / 2f
            drawY = screenHeight() - LISTEN_BOTTOM_DP * density
        }
        setState(State.REJECT)
    }

    fun hide() = setState(State.HIDDEN)

    private fun setState(next: State) {
        val period = if (next == State.SCROLL) Config.SCROLL_REPEAT_MS else Config.PULSE_PERIOD_MS
        val restartLoop = next != state || loop.duration != period
        state = next
        when (next) {
            State.HIDDEN -> {
                loop.cancel()
                move.cancel()
                reject.cancel()
            }
            State.REJECT -> {
                loop.cancel()
                reject.cancel()
                rejectT = 0f
                reject.start()
            }
            else -> {
                reject.cancel()
                if (restartLoop || !loop.isStarted) {
                    loop.cancel()
                    loop.duration = period
                    loop.start()
                }
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        move.cancel()
        reject.cancel()
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
            State.REJECT -> drawReject(canvas)
            State.HIDDEN -> Unit
        }
        canvas.restore()
    }

    private fun drawTap(canvas: Canvas) {
        // 옮겨 가는 중에는 링을 쉬고 점만 움직여요.
        if (!move.isRunning) {
            val easeOut = 1f - (1f - phase) * (1f - phase) * (1f - phase)
            val ringRadius = dotRadius * (1f + (Config.PULSE_MAX_SCALE - 1f) * easeOut)
            ringPaint.alpha = (255 * 0.8f * (1f - phase)).toInt()
            canvas.drawCircle(drawX, drawY, ringRadius, ringPaint)
        }
        dotPaint.alpha = 255
        canvas.drawCircle(drawX, drawY, dotRadius, dotPaint)
    }

    private fun drawScroll(canvas: Canvas) {
        // 한 주기(SCROLL_REPEAT_MS) 중 앞부분(SCROLL_STROKE_MS) 동안만 움직이고, 나머지는 쉬어요.
        val elapsed = phase * Config.SCROLL_REPEAT_MS
        if (elapsed > Config.SCROLL_STROKE_MS) return
        val p = elapsed / Config.SCROLL_STROKE_MS
        val eased = if (p < 0.5f) 2f * p * p else 1f - (-2f * p + 2f).let { it * it } / 2f
        val y = scrollStartY + (scrollEndY - scrollStartY) * eased
        dotPaint.alpha = (255 * (1f - 0.7f * p)).toInt()
        canvas.drawCircle(drawX, y, dotRadius, dotPaint)
    }

    private fun drawListening(canvas: Canvas) {
        val breath = 0.5f - 0.5f * cos(2f * PI.toFloat() * phase) // 0 → 1 → 0
        val radius = dotRadius * (0.8f + 0.25f * breath)
        dotPaint.alpha = (255 * (0.55f + 0.45f * breath)).toInt()
        canvas.drawCircle(drawX, drawY, radius, dotPaint)
    }

    private fun drawReject(canvas: Canvas) {
        // 좌우로 두 번 흔들리며 점점 작게 흔들리고, 동시에 흐려져요.
        val amplitude = REJECT_SHAKE_DP * density * (1f - rejectT)
        val dx = sin(rejectT * 4f * PI.toFloat()) * amplitude
        dotPaint.alpha = (255 * (1f - rejectT)).toInt().coerceIn(0, 255)
        canvas.drawCircle(drawX + dx, drawY, dotRadius, dotPaint)
    }

    private fun screenWidth() = resources.displayMetrics.widthPixels
    private fun screenHeight() = resources.displayMetrics.heightPixels

    private companion object {
        const val LISTEN_BOTTOM_DP = 120f
        const val REJECT_SHAKE_DP = 12f
    }
}
