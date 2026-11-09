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
                deliver(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.let { Log.d(TAG, "듣는 중: $it") }
            }

            override fun onError(error: Int) {
                Log.w(TAG, "음성 인식 오류 코드 $error (7 = 말을 못 알아들음, 6 = 말이 없음, 9 = 권한 없음)")
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        isListening = true
        r.startListening(intent)
        Log.i(TAG, "듣기 시작")
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
    }
}
