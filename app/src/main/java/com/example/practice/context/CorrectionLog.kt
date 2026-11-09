package com.example.practice.context

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 정정 기록 (PRD 7.7). "손주 보고 싶네" → GAL_RECENT였는데 "아니 그거 말고" → CALL_VIDEO 같은 기록을
 * 기기 안(SharedPreferences)에 남기고, 다음 순위 요청 맥락에 최근 5개를 넣어요.
 */
class CorrectionLog(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(utterance: String, from: String, to: String, time: String = now()) {
        val arr = load()
        arr.put(JSONObject().put("utterance", utterance).put("from", from).put("to", to).put("time", time))
        // 최근 MAX개만 남겨요.
        val trimmed = JSONArray()
        for (i in maxOf(0, arr.length() - MAX) until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString(KEY, trimmed.toString()).apply()
    }

    fun recent(): JSONArray = load()

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun load(): JSONArray = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())

    private companion object {
        const val PREFS = "coco"
        const val KEY = "corrections"
        const val MAX = 5
        fun now(): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())
    }
}

/** 설정 화면에서 바꾸는 데모 값들. 앱을 다시 켜도 남아 있어요. */
class DemoSettings(context: Context) {

    private val prefs = context.getSharedPreferences("coco", Context.MODE_PRIVATE)

    /** 예: "23:12". 비어 있으면 실제 시각을 써요(PRD DEMO_TIME). */
    var demoTime: String?
        get() = prefs.getString("demo_time", null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString("demo_time", value?.trim().orEmpty()).apply()
}
