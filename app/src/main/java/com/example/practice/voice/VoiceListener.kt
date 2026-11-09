package com.example.practice.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.practice.Config

/**
 * 안드로이드 기본 음성 인식으로 한국어 한 문장을 들어요 (PRD 7.4 COMMAND 모드).
 * 화면에 아무것도 띄우지 않는 SpeechRecognizer를 써요(HC-0). GPT 키는 필요 없어요.
 * 안내 중에 계속 듣는 MONITOR 모드("아니 그거 말고")는 M3에서 추가해요.
 */
class VoiceListener(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    var isListening = false
        private set

    fun hasMicPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 한 문장을 듣고 onDone(들은 말)을 한 번 불러요. 못 들었으면 null. 메인 스레드에서 불러야 해요. */
    fun listenOnce(onDone: (String?) -> Unit) {
        if (!hasMicPermission()) {
            Log.w(TAG, "마이크 권한이 없어요. COCO 설정 화면에서 허용해 주세요.")
            onDone(null)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "이 기기에서는 음성 인식을 쓸 수 없어요.")
            onDone(null)
            return
        }
        cancel()
        val onDevice = useOnDevice()
        val r = createRecognizer(onDevice)
        recognizer = r
        val intent = recognizeIntent()
        var delivered = false
        // 마이크로 소리가 들어오는지 확인용: 들은 동안 가장 큰 소리(dB). 말했는데도 계속 낮으면 마이크 입력이 막힌 거예요.
        var loudest = Float.NEGATIVE_INFINITY
        fun deliver(text: String?) {
            if (delivered) return
            delivered = true
            isListening = false
            // 콜백 안에서 바로 없애지 않고 한 박자 뒤에. 그사이 새로 듣기를 시작했으면 그건 건드리지 않아요.
            main.post {
                r.destroy()
                if (recognizer === r) recognizer = null
            }
            onDone(text)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                Log.i(TAG, "들은 말: $text")
                lastError = null
                deliver(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.let { Log.d(TAG, "듣는 중: $it") }
            }

            override fun onError(error: Int) {
                Log.w(TAG, "음성 인식 오류 코드 $error (7 = 말을 못 알아들음, 6 = 말이 없음, 9 = 권한 없음, 13 = 언어를 쓸 수 없음), 가장 큰 소리 ${"%.1f".format(loudest)}dB")
                val fellBack = onDevice && error in LANGUAGE_ERRORS
                // 기본 인식기로 바꿨으면 '계속 실패하는 오류'로 보지 않아요(MONITOR가 멈추지 않고 기본 인식기로 다시 들어요).
                lastError = if (fellBack) null else error
                if (fellBack) {
                    // 기기 내 인식기에 이 언어가 없으면, 다음부터는 기본 인식기를 써요.
                    Log.w(TAG, "기기 내 인식기에 ${Config.SPEECH_LANGUAGE}가 없어서 다음부터 기본 인식기를 써요")
                    onDeviceFailed = true
                }
                // 언어 팩 내려받기 요청은 여기서 하지 않아요: 다른 앱 위에 시스템 창이 뜰 수 있어서(HC-0) COCO가 켜질 때 한 번만 해요(prepareLanguage).
                deliver(null)
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() {
                Log.i(TAG, "말소리 감지")
            }
            override fun onRmsChanged(rmsdB: Float) {
                if (rmsdB > loudest) loudest = rmsdB
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        isListening = true
        lastError = null
        r.startListening(intent)
        Log.i(TAG, "듣기 시작 (${if (onDevice) "기기 내 인식기" else "기본 인식기"})")
        // 인식기가 끝내 답을 안 주는 경우(예: 마이크로 소리가 안 들어옴)를 막아요. 멈춰 보고, 그래도 답이 없으면 못 들은 걸로 해요.
        main.postDelayed({
            if (delivered || recognizer !== r) return@postDelayed
            Log.w(TAG, "듣기 시간 초과(${Config.LISTEN_MAX_MS / 1000}초), 가장 큰 소리 ${"%.1f".format(loudest)}dB")
            r.stopListening()
            main.postDelayed({
                // 그사이 취소됐거나 새로 듣기 시작했으면(recognizer가 바뀜) 건드리지 않아요.
                if (!delivered && recognizer === r) {
                    r.cancel()
                    lastError = SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    deliver(null)
                }
            }, Config.LISTEN_STOP_GRACE_MS)
        }, Config.LISTEN_MAX_MS)
    }

    /** 기기 내 인식기가 이 언어로 실패한 적이 있으면 true(그 뒤로는 기본 인식기). */
    private var onDeviceFailed = false

    /**
     * 어떤 인식기를 쓸지. 기기 내 인식기(Android System Intelligence)를 먼저 써요.
     * 에뮬레이터에서는 설정 → 시스템 → 언어 → 음성 → 기기 내 인식에서 English (US)를 내려받으면 이쪽이 동작해요.
     * 기기 내 인식기가 없거나 이 언어로 실패했으면 기본 인식기(Google 음성 서비스 등)를 써요.
     */
    private fun useOnDevice(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !onDeviceFailed &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun createRecognizer(onDevice: Boolean): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun recognizeIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Config.SPEECH_LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    /**
     * COCO가 켜질 때 한 번: 음성 인식기에 Config.SPEECH_LANGUAGE 언어 팩이 있는지 보고, 없으면 미리 내려받게 해요.
     * 그래야 처음 말할 때 "언어를 쓸 수 없음(13)" 오류가 안 나요. Android 13 미만은 확인할 방법이 없어서 건너뛰어요.
     */
    fun prepareLanguage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        val onDevice = useOnDevice()
        val r = createRecognizer(onDevice)
        val intent = recognizeIntent()
        val lang = Config.SPEECH_LANGUAGE
        Log.i(TAG, "음성 인식기: ${if (onDevice) "기기 내 인식기" else "기본 인식기"}")
        runCatching {
            r.checkRecognitionSupport(intent, { main.post(it) }, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    fun List<String>.has() = any { it.equals(lang, ignoreCase = true) }
                    val installed = support.installedOnDeviceLanguages.has()
                    val pending = support.pendingOnDeviceLanguages.has()
                    val online = support.onlineLanguages.has()
                    Log.i(TAG, "음성 인식 언어 $lang: 기기에 있음=$installed, 내려받는 중=$pending, 온라인=$online")
                    if (onDevice && !installed) {
                        // 기기 내 인식기에 아직 언어가 없으면 처음부터 기본 인식기를 써요(첫 말을 놓치지 않게).
                        Log.i(TAG, "기기 내 인식기에 $lang 가 아직 없어서 기본 인식기를 써요")
                        onDeviceFailed = true
                    }
                    if (!installed && !pending && !online && support.supportedOnDeviceLanguages.has()) {
                        requestLanguageDownload(r, intent)
                    }
                    main.post { r.destroy() }
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "음성 인식 언어 확인 실패(오류 $error)")
                    main.post { r.destroy() }
                }
            })
        }.onFailure {
            Log.w(TAG, "음성 인식 언어 확인 실패", it)
            r.destroy()
        }
    }

    private fun requestLanguageDownload(r: SpeechRecognizer, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching { r.triggerModelDownload(intent) }
            .onSuccess { Log.i(TAG, "음성 인식 언어 팩(${Config.SPEECH_LANGUAGE}) 내려받기를 요청했어요. 잠시 뒤 다시 말해 보세요.") }
            .onFailure { Log.w(TAG, "음성 인식 언어 팩 내려받기 요청 실패", it) }
    }

    /** 마지막 듣기의 오류 코드(없으면 null). */
    var lastError: Int? = null
        private set

    // ---------- MONITOR 모드 (M3) ----------
    private var monitoring = false
    private var consecutiveErrors = 0
    private var shouldListen: () -> Boolean = { true }
    private var onHeard: (String) -> Unit = {}

    /**
     * 안내 중에 계속 들어요(PRD 7.4 MONITOR). 한 문장이 끝나면 잠깐 쉬었다가 다시 들어요.
     * shouldListen()이 false인 동안(예: 유튜브가 마이크를 쓰는 단계)은 듣지 않고 기다려요.
     * 들은 말은 onHeard로 넘기고, 정정·정지 판별은 Phrases로 해요.
     */
    fun startMonitor(shouldListen: () -> Boolean, onHeard: (String) -> Unit) {
        this.shouldListen = shouldListen
        this.onHeard = onHeard
        if (monitoring) return
        monitoring = true
        consecutiveErrors = 0
        Log.i(TAG, "안내 중 듣기 시작(MONITOR)")
        main.post(monitorTick)
    }

    fun stopMonitor() {
        if (!monitoring) return
        monitoring = false
        main.removeCallbacks(monitorTick)
        cancel()
        Log.i(TAG, "안내 중 듣기 멈춤")
    }

    private val monitorTick: Runnable = object : Runnable {
        override fun run() {
            if (!monitoring) return
            if (isListening) return // 듣는 중이면 결과가 오면 다시 불려요
            if (!hasMicPermission()) {
                Log.w(TAG, "마이크 권한이 없어서 안내 중 듣기를 멈춰요")
                monitoring = false
                return
            }
            if (!shouldListen()) {
                main.postDelayed(this, Config.MONITOR_PAUSED_CHECK_MS)
                return
            }
            listenOnce { said ->
                if (!said.isNullOrBlank()) onHeard(said)
                if (!monitoring) return@listenOnce
                val err = lastError
                if (err != null && err in PERMANENT_ERRORS) {
                    // 이 기기에서 계속 실패할 오류예요. 계속 다시 켜면 마이크 표시가 깜빡이며 시스템이 버거워해요.
                    Log.w(TAG, "음성 인식을 쓸 수 없어서(오류 $err) 안내 중 듣기를 멈춰요. 정정은 접근성 버튼 두 번으로 해 주세요.")
                    monitoring = false
                    return@listenOnce
                }
                consecutiveErrors = if (err == null || err == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || err == SpeechRecognizer.ERROR_NO_MATCH) 0 else consecutiveErrors + 1
                // 오류가 이어지면 점점 길게 쉬었다가 다시 들어요(최대 15초).
                val delay = (Config.MONITOR_RESTART_MS shl consecutiveErrors.coerceAtMost(5)).coerceAtMost(15_000L)
                main.postDelayed(this, delay)
            }
        }
    }

    fun cancel() {
        recognizer?.cancel()
        release()
    }

    private fun release() {
        isListening = false
        recognizer?.destroy()
        recognizer = null
    }

    private companion object {
        const val TAG = "COCO"
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12 // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED (API 31)
        const val ERROR_LANGUAGE_UNAVAILABLE = 13 // SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE (API 31)
        val LANGUAGE_ERRORS = setOf(ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE) // 언어 미지원, 언어 팩 없음

        /** 다시 해도 계속 실패하는 오류: 권한 없음, 언어 미지원, 언어 사용 불가, 지원 확인 불가. */
        val PERMANENT_ERRORS = setOf(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, // 9
            ERROR_LANGUAGE_NOT_SUPPORTED,
            ERROR_LANGUAGE_UNAVAILABLE,
            14, // ERROR_CANNOT_CHECK_SUPPORT
        )
    }
}
