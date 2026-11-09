package com.example.practice.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.example.practice.Config
import com.example.practice.overlay.DotOverlayView

/**
 * 목표 하나를 받아서, 단계마다 점을 어디에 띄울지 정하는 상태 머신 (PRD 7.7).
 *
 * M1 범위: IDLE ↔ GUIDING(stepIdx) → 완료 진동 → IDLE
 * - 사용자의 탭/스크롤/화면 전환을 보고 다음 단계로 넘어가요.
 * - HC-4: 대신 누르지 않아요. performAction, dispatchGesture를 쓰지 않아요.
 * - 정정(MOVE), 대기(PARKED), 안내 단계 설정은 M3~M4에서 추가해요.
 */
class GuidanceEngine(
    private val service: AccessibilityService,
    private val overlay: DotOverlayView,
    private val finder: NodeFinder,
    private val scripts: StepScriptSource,
    private val launcherPackage: String?,
    /** 시연 폰 자판의 키 위치표. 없으면 TYPE 단계는 마이크(fallback_target)로 대신 안내해요. */
    private val keyboard: KeyboardLayout?,
    private val onDone: () -> Unit,
) {
    enum class Phase { IDLE, GUIDING }

    var phase = Phase.IDLE
        private set

    private var script: StepScript? = null
    private var stepIdx = 0
    private var stepStartedAt = 0L
    private var slots: Map<String, String> = emptyMap()
    private var lastTargetBounds: Rect? = null

    /** 목표를 정하고 첫 단계부터 안내를 시작해요. */
    fun start(capabilityId: String, slots: Map<String, String> = emptyMap()): Boolean {
        val loaded = scripts.load(capabilityId)
        if (loaded == null || loaded.steps.isEmpty()) {
            Log.w(TAG, "$capabilityId 스텝 스크립트가 없어요")
            stop()
            return false
        }
        script = loaded
        this.slots = slots
        phase = Phase.GUIDING
        enterStep(0)
        Log.i(TAG, "목표 시작: $capabilityId (${loaded.steps.size}단계)")
        refresh()
        return true
    }

    fun stop() {
        if (phase != Phase.IDLE) Log.i(TAG, "안내 멈춤")
        phase = Phase.IDLE
        script = null
        lastTargetBounds = null
        overlay.hide()
    }

    fun onEvent(event: AccessibilityEvent) {
        if (phase != Phase.GUIDING) return
        val step = currentStep() ?: return
        val sinceStart = SystemClock.uptimeMillis() - stepStartedAt
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (step.doneWhen.clickedTarget && clickedOnTarget(event)) {
                    Log.i(TAG, "탭 확인: ${step.id}")
                    advance()
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (step.doneWhen.userScrolled && sinceStart > Config.SCROLL_GRACE_MS && scrolledTarget(event, step)) {
                    Log.i(TAG, "스크롤 확인: ${step.id}")
                    advance()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (step.doneWhen.windowChanged && sinceStart > Config.WINDOW_CHANGE_GRACE_MS) {
                    Log.i(TAG, "화면 전환 확인: ${step.id}")
                    advance()
                }
            }
        }
        refresh()
    }

    /** 지금 화면을 보고 점 위치를 다시 정해요. 화면 내용이 바뀔 때마다 불려요. */
    fun refresh() {
        if (phase != Phase.GUIDING) return
        val s = script ?: return
        finder.newFrame()
        val fg = service.rootInActiveWindow?.packageName?.toString()

        // 1) 화면 상태만으로 끝난 걸 알 수 있는 단계는 바로 넘겨요(앱이 열림, 다음 화면의 버튼이 보임).
        var guard = 0
        while (guard++ < s.steps.size) {
            val st = currentStep() ?: return
            val done = (st.doneWhen.packageForeground != null && fg == st.doneWhen.packageForeground) ||
                (st.doneWhen.nodeAppears?.let { finder.exists(it.withSlots(slots)) } == true)
            if (!done) break
            Log.i(TAG, "화면 상태로 완료: ${st.id}")
            if (!advance()) return
        }

        // 2) 지금 단계의 버튼이 보이면 거기에 점.
        val step = currentStep() ?: return
        findOnScreen(step, fg)?.let { show(step, it); return }

        // 3) 안 보이면, 사용자가 앞서 나간 건지 확인해요. 가장 가까운 뒤 단계로 맞춰요.
        for (j in stepIdx + 1 until s.steps.size) {
            val later = s.steps[j]
            val bounds = findOnScreen(later, fg) ?: continue
            Log.i(TAG, "경로 다시 맞춤: ${step.id} → ${later.id}")
            enterStep(j)
            show(later, bounds)
            return
        }

        // 4) 홈 화면으로 돌아왔으면 첫 단계부터 다시.
        val first = s.steps.first()
        if (stepIdx > 0 && fg == launcherPackage && first.screen == SCREEN_LAUNCHER) {
            findOnScreen(first, fg)?.let {
                Log.i(TAG, "홈 화면으로 돌아와서 첫 단계부터 다시")
                enterStep(0)
                show(first, it)
                return
            }
        }

        // 5) 갈 곳이 안 보이면 점을 숨기고 기다려요. (M4에서 PARKED로 바뀌어요)
        lastTargetBounds = null
        overlay.hide()
    }

    private fun findOnScreen(step: Step, fg: String?): Rect? {
        if (step.screen == SCREEN_LAUNCHER && fg != launcherPackage) return null
        step.screenHas?.let { if (!finder.exists(it.withSlots(slots))) return null }
        return finder.find(step.target.withSlots(slots))
    }

    private fun show(step: Step, bounds: Rect) {
        lastTargetBounds = bounds
        when (step.gesture) {
            Gesture.TYPE -> showTyping(step, bounds)
            Gesture.SCROLL_UP -> overlay.showScroll(bounds)
            // 나머지 제스처 표현(더블탭, 길게 누르기, 확대, 뒤로가기)은 M4/P3에서 추가해요. 지금은 탭 점으로 보여요.
            else -> overlay.showTap(bounds.exactCenterX(), bounds.exactCenterY())
        }
    }

    /**
     * 자판 입력 단계. 입력 칸에 지금 적힌 글자를 보고, 다음에 누를 키 위에 점을 띄워요.
     * 키 위치는 keyboard.json에 하드코딩한 좌표라 화면을 훑지 않아요.
     * 잘못 치면 지우기 키, 다 치면 검색(엔터) 키를 가리켜요.
     */
    private fun showTyping(step: Step, fieldBounds: Rect) {
        val wantedText = fillTemplate(step.text.orEmpty(), slots)
        val kb = keyboard
        if (kb == null || !kb.canType(Hangul.keystrokes(wantedText))) {
            // 자판 위치표가 아직 없거나 칠 수 없는 글자(쌍자음, 영어 등)가 있으면 마이크로 대신 안내해요.
            val alt = step.fallbackTarget?.let { finder.find(it.withSlots(slots)) }
            if (alt != null) {
                lastTargetBounds = alt
                overlay.showTap(alt.exactCenterX(), alt.exactCenterY())
            } else {
                lastTargetBounds = null
                overlay.hide()
            }
            return
        }
        if (!isKeyboardOpen()) {
            // 자판이 아직 안 떴으면 입력 칸을 먼저 누르게 해요.
            lastTargetBounds = fieldBounds
            overlay.showTap(fieldBounds.exactCenterX(), fieldBounds.exactCenterY())
            return
        }
        val typed = finder.textOf(step.target.withSlots(slots)).orEmpty()
        val key = Hangul.nextKey(typed, wantedText)
        val point = kb.position(key)
        if (point == null) {
            overlay.hide()
            return
        }
        lastTargetBounds = null // 자판 위 탭은 접근성 클릭 이벤트로 오지 않아서, 글자 변화로 진행을 판단해요.
        Log.d(TAG, "자판 안내: '$typed' → $key")
        overlay.showTap(point.x, point.y)
    }

    /** 화면에 자판(IME) 창이 떠 있는지. 창 목록만 보니 빨라요. */
    private fun isKeyboardOpen(): Boolean = runCatching {
        service.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    /** 다음 단계로. 마지막 단계였으면 완료 진동 후 IDLE로 가고 false를 돌려줘요. */
    private fun advance(): Boolean {
        val s = script ?: return false
        val step = currentStep() ?: return false
        if (step.final || stepIdx >= s.steps.lastIndex) {
            Log.i(TAG, "목표 완료: ${s.capability}")
            onDone()
            stop()
            return false
        }
        enterStep(stepIdx + 1)
        return true
    }

    private fun enterStep(index: Int) {
        stepIdx = index
        stepStartedAt = SystemClock.uptimeMillis()
        lastTargetBounds = null
        Log.i(TAG, "단계 → ${index + 1}. ${currentStep()?.id}")
    }

    private fun currentStep(): Step? = script?.steps?.getOrNull(stepIdx)

    private fun clickedOnTarget(event: AccessibilityEvent): Boolean {
        val target = lastTargetBounds ?: return false
        val src = event.source ?: return false
        val r = Rect().also { src.getBoundsInScreen(it) }
        return Rect.intersects(r, target)
    }

    private fun scrolledTarget(event: AccessibilityEvent, step: Step): Boolean {
        val wantedId = step.target.viewId ?: return true
        val vid = event.source?.viewIdResourceName ?: return false
        return vid == wantedId || vid.endsWith(":id/$wantedId")
    }

    private companion object {
        const val TAG = "COCO"
        const val SCREEN_LAUNCHER = "launcher"
    }
}
