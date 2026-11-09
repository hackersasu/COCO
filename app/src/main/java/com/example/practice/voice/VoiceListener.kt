package com.example.practice.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
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
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        var delivered = false
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
                Log.w(TAG, "음성 인식 오류 코드 $error (7 = 말을 못 알아들음, 6 = 말이 없음, 9 = 권한 없음, 13 = 언어를 쓸 수 없음)")
                lastError = error
                deliver(null)
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Config.SPEECH_LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        isListening = true
        r.startListening(intent)
        Log.i(TAG, "듣기 시작")
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

        /** 다시 해도 계속 실패하는 오류: 권한 없음, 언어 미지원, 언어 사용 불가, 지원 확인 불가. */
        val PERMANENT_ERRORS = setOf(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, // 9
            12, // ERROR_LANGUAGE_NOT_SUPPORTED
            13, // ERROR_LANGUAGE_UNAVAILABLE
            14, // ERROR_CANNOT_CHECK_SUPPORT
        )
    }
}
