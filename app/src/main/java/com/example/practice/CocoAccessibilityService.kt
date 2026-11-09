package com.example.practice

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.example.practice.context.CorrectionLog
import com.example.practice.context.DemoSettings
import com.example.practice.context.Profile
import com.example.practice.engine.AssetCatalogSource
import com.example.practice.engine.AssetStepScriptSource
import com.example.practice.engine.Catalog
import com.example.practice.engine.CatalogStepScriptSource
import com.example.practice.engine.GuidanceEngine
import com.example.practice.engine.KeyboardLayout
import com.example.practice.engine.NodeFinder
import com.example.practice.overlay.DotOverlayView
import com.example.practice.voice.Phrases
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
 * 흐름: 접근성 버튼 → 듣기(점이 숨 쉼) → 말 → AI 순위 → 1등 목표의 하드코딩된 순서로 안내.
 * 안내 중에는 계속 들어요. "아니 그거 말고" → 저장된 순위의 다음 후보로(API 다시 안 부름),
 * "코코야 그만" → 멈춤. 음성이 안 되면 접근성 버튼을 빠르게 두 번 = 정정, 한 번 = 멈춤.
 */
class CocoAccessibilityService : AccessibilityService() {

    private var overlay: DotOverlayView? = null
    private var engine: GuidanceEngine? = null
    private var voice: VoiceListener? = null
    private var rankClient: RankClient? = null
    private var contextBuilder: ContextBuilder? = null
    private var corrections: CorrectionLog? = null
    private var launcherPackage: String? = null
    private var buttonCallback: Any? = null // API 26 미만 기기에서도 클래스가 로드되도록 Any로 보관
    private var debugReceiver: BroadcastReceiver? = null
    private var debugFinder: NodeFinder? = null
    private var pendingSinglePress: Runnable? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rankJob: Job? = null
    private val debouncedRefresh = Runnable { engine?.refresh() }

    /** 마지막으로 들은 말(처음 부를 때의 말)과 순위. */
    var lastUtterance: String? = null
        private set
    var lastRank: RankResult? = null
        private set

    /** 지금 안내 중인 후보 순서와 몇 번째인지. 정정하면 다음 후보로 넘어가요(HC-3). */
    private var candidates: List<String> = emptyList()
    private var candidateIdx = 0
    private var lastSlots: Map<String, String> = emptyMap()

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
            onDone = {
                vibrateDone()
                voice?.stopMonitor()
            },
        )
        voice = VoiceListener(this).also { it.prepareLanguage() }
        rankClient = catalog?.let { RankClient(it, loadDemoRankings()) }
        val log = CorrectionLog(this)
        corrections = log
        contextBuilder = ContextBuilder(Profile.load(this), log, DemoSettings(this))
        registerAccessibilityButton()
        debugFinder = NodeFinder(this)
        registerDebugReceiver()
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
        voice?.stopMonitor()
        voice?.cancel()
        main.removeCallbacksAndMessages(null)
        engine?.stop()
        engine = null
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugReceiver = null
        unregisterAccessibilityButton()
        detachOverlay()
        super.onDestroy()
    }

    /**
     * 접근성 버튼 = 코코 부르기.
     * - 쉬고 있을 때: 듣기 시작
     * - 안내 중: 한 번 = 멈춤, 빠르게 두 번 = 정정(다음 후보). 음성 정정이 안 될 때를 위한 대안(PRD 10 리스크).
     * - 듣는 중·생각 중: 멈춤
     */
    fun onCocoButton() {
        if (engine?.phase == GuidanceEngine.Phase.GUIDING) {
            val pending = pendingSinglePress
            if (pending != null) {
                main.removeCallbacks(pending)
                pendingSinglePress = null
                Log.i(TAG, "버튼 두 번 → 정정")
                correct(null)
                return
            }
            val single = Runnable {
                pendingSinglePress = null
                Log.i(TAG, "버튼 한 번 → 코코 멈춤")
                stopAll()
            }
            pendingSinglePress = single
            main.postDelayed(single, Config.BUTTON_DOUBLE_PRESS_MS)
            return
        }
        if (engine?.phase == GuidanceEngine.Phase.WATCHING) {
            // 목표 뒤 지켜보기(예: 광고 건너뛰기) 중이면 끝내고 새로 들어요. 버튼은 늘 '코코 부르기'예요.
            Log.i(TAG, "지켜보기 끝내고 새로 듣기")
            engine?.stop()
        }
        if (isBusy()) {
            Log.i(TAG, "코코 멈춤")
            stopAll()
            return
        }
        val v = voice ?: return
        overlay?.showListening()
        v.listenOnce { said ->
            if (said.isNullOrBlank()) overlay?.hide() else handleUtterance(said)
        }
    }

    /**
     * 말 → 순위 → 1등 목표로 안내 시작. 묻지 않고 1등으로 가요(HC-2).
     * 설정 화면의 테스트 입력칸도 여기로 와요.
     */
    fun handleUtterance(said: String) {
        stopAll()
        overlay?.showListening() // 생각하는 동안에도 같은 점이 숨 쉬어요
        lastUtterance = said
        rankAndGo(said, exclude = emptyList(), correctionFrom = null)
    }

    /**
     * 정정(HC-3).
     * - extra가 없으면: 저장된 순위표의 다음 후보로 바로 옮겨요. API를 다시 부르지 않아요.
     * - extra가 있으면("아니 그거 말고 전화"): 그 말로 다시 순위를 매기되, 이미 아니라고 한 후보는 빼요.
     */
    fun correct(extra: String?) {
        val e = engine ?: return
        val from = e.currentCapability
        if (extra != null) {
            val tried = candidates.take(candidateIdx + 1)
            Log.i(TAG, "정정 + 새 말 \"$extra\" → 다시 순위 (제외: $tried)")
            rankAndGo(extra, exclude = tried, correctionFrom = from)
            return
        }
        var next = candidateIdx + 1
        while (next < candidates.size && candidates[next] == Catalog.OUT_OF_SCOPE) next++
        if (next >= candidates.size) {
            Log.i(TAG, "후보가 다 떨어졌어요 → 흔들고 사라짐")
            reject()
            return
        }
        candidateIdx = next
        val to = candidates[next]
        recordCorrection(from, to)
        Log.i(TAG, "정정: $from → $to (${next + 1}등)")
        if (!e.switchTo(to, lastSlots)) correct(null) // 스크립트가 없으면 그다음 후보로
    }

    fun stopAll() {
        pendingSinglePress?.let { main.removeCallbacks(it) }
        pendingSinglePress = null
        voice?.stopMonitor()
        voice?.cancel()
        rankJob?.cancel()
        engine?.stop()
        overlay?.hide()
    }

    /** M1 테스트용: 말 없이 정해 둔 목표로 바로 시작. */
    fun startGuide(capabilityId: String = Config.M1_TEST_GOAL, slots: Map<String, String> = Config.M1_TEST_SLOTS) {
        stopAll()
        candidates = listOf(capabilityId)
        candidateIdx = 0
        lastSlots = slots
        if (engine?.start(capabilityId, slots) == true) startMonitor()
    }

    private fun rankAndGo(said: String, exclude: List<String>, correctionFrom: String?) {
        val client = rankClient
        if (client == null) {
            Log.w(TAG, "기능 목록(catalog.json)을 못 읽어서 순위를 매길 수 없어요")
            overlay?.hide()
            return
        }
        rankJob?.cancel()
        rankJob = scope.launch {
            val ctx = contextBuilder?.build(currentForegroundApp(), exclude) ?: JSONObject()
            val result = client.rank(said, ctx, exclude)
            if (correctionFrom == null) lastRank = result
            Log.i(TAG, "순위 \"$said\" → ${result.summary()}")
            val ids = result.ranking.map { it.id }
            val top = ids.firstOrNull()
            if (top == null || top == Catalog.OUT_OF_SCOPE) {
                Log.i(TAG, "범위 밖이거나 후보가 없어요 → 흔들고 사라짐")
                reject()
                return@launch
            }
            candidates = if (correctionFrom != null) exclude + ids else ids
            candidateIdx = if (correctionFrom != null) exclude.size else 0
            lastSlots = result.slots
            val e = engine ?: return@launch
            val ok = if (correctionFrom != null) {
                recordCorrection(correctionFrom, top)
                e.switchTo(top, result.slots)
            } else {
                e.start(top, result.slots)
            }
            if (ok) startMonitor() else overlay?.hide()
        }
    }

    /** 안내 중에 들은 말: 정지·정정이면 처리하고, 아니면 무시해요. */
    private fun onHeardDuringGuide(said: String) {
        when (val kind = Phrases.classify(said)) {
            Phrases.Kind.Stop -> {
                Log.i(TAG, "\"$said\" → 멈춤")
                stopAll()
            }
            is Phrases.Kind.Correction -> {
                Log.i(TAG, "\"$said\" → 정정")
                correct(kind.extra)
            }
            Phrases.Kind.None -> Log.d(TAG, "\"$said\" → 정정·정지 아님, 무시")
        }
    }

    private fun startMonitor() {
        if (!Config.MONITOR_ENABLED) return
        val v = voice ?: return
        if (!v.hasMicPermission()) return
        v.startMonitor(
            shouldListen = { engine?.phase == GuidanceEngine.Phase.GUIDING && engine?.pausesListening != true },
            onHeard = ::onHeardDuringGuide,
        )
    }

    private fun reject() {
        voice?.stopMonitor()
        rankJob?.cancel()
        engine?.stop(hide = false)
        candidates = emptyList()
        overlay?.showReject()
    }

    private fun recordCorrection(from: String?, to: String) {
        val said = lastUtterance ?: return
        if (from == null) return
        corrections?.add(said, from, to, contextBuilder?.time() ?: "")
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

    /**
     * 개발용(디버그 빌드에서만): 말 대신 adb로 문장을 넣어요. 에뮬레이터처럼 마이크가 안 될 때 써요.
     * 쉬고 있을 때는 "처음 부르는 말", 안내 중에는 "안내 중에 들은 말"로 처리해요.
     *   adb shell am broadcast -a com.example.practice.DEBUG_SAY --es text '아니 그거 말고'
     */
    private fun registerDebugReceiver() {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_DEBUG_FIND) {
                    // adb shell am broadcast -a com.example.practice.DEBUG_FIND --es text 'play video'
                    val word = intent.getStringExtra("text").orEmpty()
                    debugFinder?.debugDump(word)?.forEach { Log.i(TAG, "찾기[$word] $it") }
                    return
                }
                val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
                Log.i(TAG, "디버그 입력: $text")
                if (engine?.phase == GuidanceEngine.Phase.GUIDING) onHeardDuringGuide(text) else handleUtterance(text)
            }
        }
        val filter = IntentFilter(ACTION_DEBUG_SAY).apply { addAction(ACTION_DEBUG_FIND) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        debugReceiver = receiver
    }

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
        const val ACTION_DEBUG_SAY = "com.example.practice.DEBUG_SAY"
        const val ACTION_DEBUG_FIND = "com.example.practice.DEBUG_FIND"

        /** 같은 앱 안의 설정 화면에서 테스트 버튼으로 부르기 위한 참조. 서비스가 꺼져 있으면 null. */
        @Volatile
        var instance: CocoAccessibilityService? = null
            private set
    }
}
