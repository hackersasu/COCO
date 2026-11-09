package com.example.practice.ai

import org.json.JSONObject

data class Ranked(val id: String, val p: Double)

/**
 * AI(또는 데모 순위표)가 매긴 순위 (PRD 6.4).
 * 화면에 보여 줄 문구(say, ask)는 만들지 않아요(HC-0). reason은 Logcat에만 남겨요.
 */
data class RankResult(
    val ranking: List<Ranked>,
    val slots: Map<String, String>,
    val reason: String,
    val source: String, // "gpt", "demo", "demo(폴백)"
) {
    val top: Ranked? get() = ranking.firstOrNull()

    fun summary(): String =
        ranking.mapIndexed { i, r -> "${i + 1}. ${r.id} ${"%.2f".format(r.p)}" }.joinToString("  ") +
            (if (slots.isNotEmpty()) "  | 슬롯 $slots" else "") +
            "  | 출처 $source" +
            (if (reason.isNotBlank()) "  | 이유: $reason" else "")

    companion object {
        /**
         * AI 응답 JSON을 읽어요. 기능 목록에 없는 id는 버리고, 확률 순으로 정렬해 topK개만 남겨요.
         * 안드로이드에 의존하지 않아서 단위 테스트(RankResultTest)로 확인해요.
         */
        fun parse(json: JSONObject, allowedIds: Set<String>, topK: Int, source: String): RankResult {
            val arr = json.optJSONArray("ranking")
            val ranking = mutableListOf<Ranked>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id !in allowedIds || ranking.any { it.id == id }) continue
                    ranking += Ranked(id, o.optDouble("p", 0.0).takeUnless { it.isNaN() } ?: 0.0)
                }
            }
            val slotsJson = json.optJSONObject("slots")
            val slots = HashMap<String, String>()
            slotsJson?.keys()?.forEach { k ->
                val v = slotsJson.optString(k)
                if (v.isNotBlank() && v != "null") slots[k] = v
            }
            return RankResult(
                ranking = ranking.sortedByDescending { it.p }.take(topK),
                slots = slots,
                reason = json.optString("reason"),
                source = source,
            )
        }
    }
}

/** 데모 순위표 (demo_rankings.json). 띄어쓰기·문장부호를 빼고, 말 안에 들어 있는 것 중 가장 긴 문장을 골라요. */
class DemoRankings(private val entries: List<Pair<String, JSONObject>>) {

    fun match(utterance: String, allowedIds: Set<String>, topK: Int): RankResult? {
        val said = normalize(utterance)
        if (said.isEmpty()) return null
        val hit = entries
            .filter { (key, _) -> key.isNotEmpty() && said.contains(key) }
            .maxByOrNull { it.first.length } ?: return null
        return RankResult.parse(hit.second, allowedIds, topK, source = "demo")
    }

    companion object {
        private val NOT_WORD = Regex("[\\s\\p{Punct}~…]")

        fun normalize(s: String): String = s.replace(NOT_WORD, "")

        fun parse(o: JSONObject): DemoRankings {
            val arr = o.optJSONArray("entries")
            val list = mutableListOf<Pair<String, JSONObject>>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    list += normalize(e.optString("utterance")) to e
                }
            }
            return DemoRankings(list)
        }
    }
}
