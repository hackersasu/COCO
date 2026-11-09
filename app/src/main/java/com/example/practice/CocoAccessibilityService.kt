package com.example.practice

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.practice.ai.DemoRankings
import com.example.practice.ai.RankClient
import com.example.practice.ai.RankResult
import com.example.practice.context.ContextBuilder
import com.example.practice.context.Profile
import com.example.practice.engine.AssetCatalogSource
import com.example.practice.engine.AssetStepScriptSource
import com.example.practice.engine.Catalog
import com.example.practice.engine.CatalogStepScriptSource
import com.example.practice.engine.GuidanceEngine
import com.example.practice.engine.KeyboardLayout
import com.example.practice.engine.NodeFinder
import com.example.practice.overlay.DotOverlayView
import com.example.practice.voice.VoiceListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * COCO의 본체. 접근성 서비스로 화면을 읽고, 투명한 오버레이 창에 파란 점만 그려요.
 *
 * M2 흐름: 접근성 버튼 → 듣기(점이 숨 쉼) → 말 → AI 순위 → 1등 목표의 하드코딩된 순서로 안내.
 * AI는 "목표"만 정하고, 그다음 단계는 스텝 스크립트를 그대로 따라가요.
 */
class CocoAccessibilityService : AccessibilityService() {

    private var overlay: DotOverlayView? = null
    private var engine: GuidanceEngine? = null
    private var voice: VoiceListener? = null
    private var rankClient: RankClient? = null
    private var contextBuilder: ContextBuilder? = null
    private var launcherPackage: String? = null
    private var buttonCallback: Any? = null // API 26 미만 기기에서도 클래스가 로드되도록 Any로 보관

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rankJob: Job? = null
    private val debouncedRefresh = Runnable { engine?.refresh() }

    /** 마지막으로 들은 말과 순위. M3의 "아니 그거 말고"(2등으로 옮기기)에 써요. */
    var lastUtterance: String? = null
        private set
    var lastRank: RankResult? = null
        private set

    val usesGpt: Boolean get() = rankClient?.usesGpt == true

    override fun onServiceConnected() {
        super.onServiceConnected()
        val view = attachOverlay()
        launcherPackage = findLauncherPackage()
        val keyboard = KeyboardLayout.load(this)
        val catalog = AssetCatalogSource(this).load()
        engine = GuidanceEngine(
            service = this,
            overlay = view,
            finder = NodeFinder(this),
            scripts = CatalogStepScriptSource(AssetStepScriptSource(this), catalog),
            launcherPackage = launcherPackage,
            keyboard = keyboard,
            onDone = ::vibrateDone,
        )
        voice = VoiceListener(this)
        rankClient = catalog?.let { RankClient(it, loadDemoRankings()) }
        contextBuilder = ContextBuilder(Profile.load(this))
        registerAccessibilityButton()
        instance = this
        Log.i(
            TAG,
            "COCO 연결됨. 런처 = $launcherPackage, 자판 위치표 = ${if (keyboard != null) "있음" else "없음(마이크로 대신 안내)"}, " +
                "기능 ${catalog?.entries?.size ?: 0}개, 순위 = ${if (usesGpt) "GPT(${Config.OPENAI_MODEL})" else "데모 순위표"}",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val e = engine ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // 화면 내용 변화는 자주 오니까 150ms 모아서 한 번만 처리해요.
            main.removeCallbacks(debouncedRefresh)
            main.postDelayed(debouncedRefresh, Config.CONTENT_DEBOUNCE_MS)
        } else {
            main.removeCallbacks(debouncedRefresh)
            e.onEvent(event)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        voice?.cancel()
        main.removeCallbacksAndMessages(null)
        engine?.stop()
        engine = null
        unregisterAccessibilityButton()
        detachOverlay()
        super.onDestroy()
    }

    /** 접근성 버튼 = 코코 부르기. 듣는 중·생각 중·안내 중에 누르면 멈춰요. */
    fun onCocoButton() {
        if (isBusy()) {
            Log.i(TAG, "코코 멈춤")
            stopAll()
            return
        }
        val v = voice ?: return
        overlay?.showListening()
        v.listenOnce { said ->
            if (said.isNullOrBlank()) {
                overlay?.hide()
            } else {
                handleUtterance(said)
            }
        }
    }

    /**
     * 말 → 순위 → 1등 목표로 안내 시작 (M2). 묻지 않고 1등으로 가요(HC-2).
     * 설정 화면의 테스트 입력칸도 여기로 와요.
     */
    fun handleUtterance(said: String) {
        val client = rankClient
        if (client == null) {
            Log.w(TAG, "기능 목록(catalog.json)을 못 읽어서 순위를 매길 수 없어요")
            return
        }
        rankJob?.cancel()
        engine?.stop()
        overlay?.showListening() // 생각하는 동안에도 같은 점이 숨 쉬어요
        lastUtterance = said
        rankJob = scope.launch {
            val ctx = contextBuilder?.build(currentForegroundApp()) ?: JSONObject()
            val result = client.rank(said, ctx)
            lastRank = result
            Log.i(TAG, "순위 \"$said\" → ${result.summary()}")
            val top = result.top
            if (top == null || top.id == Catalog.OUT_OF_SCOPE) {
                Log.i(TAG, "범위 밖이거나 후보가 없어요 → 점을 숨겨요 (M4에서 좌우로 흔들고 사라지게 바꿔요)")
                overlay?.hide()
                return@launch
            }
            if (engine?.start(top.id, result.slots) != true) overlay?.hide()
        }
    }

    fun stopAll() {
        voice?.cancel()
        rankJob?.cancel()
        engine?.stop()
        overlay?.hide()
    }

    /** M1 테스트용: 말 없이 정해 둔 목표로 바로 시작. */
    fun startGuide(capabilityId: String = Config.M1_TEST_GOAL, slots: Map<String, String> = Config.M1_TEST_SLOTS) {
        stopAll()
        engine?.start(capabilityId, slots)
    }

    private fun isBusy(): Boolean =
        engine?.phase == GuidanceEngine.Phase.GUIDING || voice?.isListening == true || rankJob?.isActive == true

    /** AI에 보낼 "지금 앱". 화면 내용은 보내지 않고 앱 이름만 보내요(HC-6). */
    private fun currentForegroundApp(): String? {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return null
        return if (pkg == launcherPackage) "launcher" else pkg
    }

    private fun loadDemoRankings(): DemoRankings = runCatching {
        DemoRankings.parse(JSONObject(assets.open("demo_rankings.json").bufferedReader().use { it.readText() }))
    }.onFailure { Log.w(TAG, "데모 순위표를 못 읽었어요", it) }.getOrDefault(DemoRankings(emptyList()))

    private fun attachOverlay(): DotOverlayView {
        overlay?.let { return it }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // HC-1: 터치와 포커스를 절대 가져가지 않아요. 점 아래 버튼이 그대로 눌려요.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val view = DotOverlayView(this)
        wm.addView(view, params)
        overlay = view
        return view
    }

    private fun detachOverlay() {
        val view = overlay ?: return
        (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        overlay = null
    }

    private fun registerAccessibilityButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val callback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                Log.i(TAG, "접근성 버튼 눌림")
                onCocoButton()
            }
        }
        accessibilityButtonController.registerAccessibilityButtonCallback(callback, main)
        buttonCallback = callback
    }

    private fun unregisterAccessibilityButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val callback = buttonCallback as? AccessibilityButtonController.AccessibilityButtonCallback ?: return
        accessibilityButtonController.unregisterAccessibilityButtonCallback(callback)
        buttonCallback = null
    }

    /** 목표 완료: 짧은 진동 한 번. 칭찬 문구나 소리는 없어요(HC-0). */
    private fun vibrateDone() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(Config.DONE_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(Config.DONE_VIBRATION_MS)
        }
    }

    /** 기기의 기본 홈 화면 앱 패키지 이름. 에뮬레이터는 Pixel 런처, 갤럭시는 One UI 홈이에요. */
    private fun findLauncherPackage(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    companion object {
        const val TAG = "COCO"

        /** 같은 앱 안의 설정 화면에서 테스트 버튼으로 부르기 위한 참조. 서비스가 꺼져 있으면 null. */
        @Volatile
        var instance: CocoAccessibilityService? = null
            private set
    }
}
