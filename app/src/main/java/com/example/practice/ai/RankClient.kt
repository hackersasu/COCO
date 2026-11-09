package com.example.practice.ai

import android.util.Log
import com.example.practice.BuildConfig
import com.example.practice.Config
import com.example.practice.engine.Catalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 어르신의 말 → 기능 순위 (PRD 7.5).
 * - 키가 있으면 GPT에 물어요. LLM에는 말과 맥락만 보내고 화면 내용은 보내지 않아요(HC-6).
 * - 키가 없거나, DEMO_MODE이거나, 실패·시간 초과면 데모 순위표에서 찾아요.
 * - 그것도 없으면 빈 순위를 돌려줘요(→ 점이 사라짐, M4에서 REJECT 표현).
 */
class RankClient(
    private val catalog: Catalog,
    private val demo: DemoRankings,
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(Config.RANK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val apiKey: String get() = BuildConfig.OPENAI_API_KEY
    val usesGpt: Boolean get() = !Config.DEMO_MODE && apiKey.isNotBlank()

    suspend fun rank(utterance: String, context: JSONObject): RankResult {
        val topK = Config.RANK_TOP_K
        if (!usesGpt) {
            return demo.match(utterance, catalog.ids, topK) ?: empty("demo(없음)")
        }
        return try {
            askGpt(utterance, context)
        } catch (e: Exception) {
            Log.w(TAG, "GPT 순위 요청 실패 → 데모 순위표로: ${e.message}")
            demo.match(utterance, catalog.ids, topK)?.copy(source = "demo(폴백)") ?: empty("실패")
        }
    }

    private suspend fun askGpt(utterance: String, context: JSONObject): RankResult = withContext(Dispatchers.IO) {
        val user = JSONObject().put("utterance", utterance).put("context", context)
        val body = JSONObject()
            .put("model", Config.OPENAI_MODEL)
            .put("temperature", 0)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", RankPrompt.system(catalog)))
                    .put(JSONObject().put("role", "user").put("content", user.toString())),
            )

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw IOException("HTTP ${res.code} ${msg.orEmpty()}".trim())
            }
            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            RankResult.parse(JSONObject(content), catalog.ids, Config.RANK_TOP_K, source = "gpt")
        }
    }

    private fun empty(source: String) = RankResult(emptyList(), emptyMap(), "", source)

    private companion object {
        const val TAG = "COCO"
        const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
