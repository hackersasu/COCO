package com.example.practice.context

import android.content.Context
import android.util.Log
import com.example.practice.Config
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 호칭 프로필 (PRD 6.3). "손주" → "지은"처럼 어르신의 호칭을 연락처 이름으로 바꿔요. */
data class Profile(val aliases: Map<String, String>) {
    companion object {
        fun load(context: Context): Profile = runCatching {
            val o = JSONObject(context.assets.open("profile.json").bufferedReader().use { it.readText() })
            val a = o.optJSONObject("aliases")
            val map = HashMap<String, String>()
            a?.keys()?.forEach { k -> map[k] = a.getString(k) }
            Profile(map)
        }.onFailure { Log.w("COCO", "호칭 프로필을 못 읽었어요", it) }.getOrDefault(Profile(emptyMap()))
    }
}

/**
 * AI에 같이 보낼 맥락(PRD 6.4)을 만들어요: 시각, 지금 앱, 호칭, 최근 정정 기록, 제외할 후보.
 * HC-6: 화면 내용은 넣지 않아요. 앱 이름(패키지)만 넣어요.
 */
class ContextBuilder(
    private val profile: Profile,
    private val corrections: CorrectionLog,
    private val settings: DemoSettings,
) {

    /** 지금 시각. 설정 화면의 데모 시각 → Config.DEMO_TIME → 실제 시각 순서로 써요. */
    fun time(): String = settings.demoTime ?: Config.DEMO_TIME ?: SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())

    fun build(foregroundApp: String?, exclude: List<String> = emptyList()): JSONObject = JSONObject()
        .put("time", time())
        .put("foreground_app", foregroundApp ?: "unknown")
        .put("aliases", JSONObject(profile.aliases as Map<*, *>))
        .put("recent_corrections", corrections.recent())
        .put("exclude", JSONArray(exclude))
}
