package com.example.practice.engine

import android.content.Context
import android.util.Log
import com.example.practice.Config
import org.json.JSONArray
import org.json.JSONObject

/**
 * 화면에서 찾을 버튼 하나의 조건. 적힌 조건을 "모두" 만족하는 노드를 찾아요.
 * desc, text 같은 값은 배열로 여러 개 적을 수 있어요(예: 영어 "Search" / 한국어 "검색").
 * "{contact}"처럼 중괄호로 쓴 부분은 AI가 뽑은 슬롯 값으로 바뀌어요.
 */
data class Target(
    val viewId: String? = null,
    val desc: List<String> = emptyList(),
    val text: List<String> = emptyList(),
    val textContains: List<String> = emptyList(),
    val descContains: List<String> = emptyList(),
    val className: String? = null,
    /** 이 노드 안에서만 찾아요(예: 결과 목록 안의 영상). 화면 전체를 훑지 않아서 빠르고 정확해요. */
    val within: Target? = null,
    /** true면 눌 수 있는(회색으로 꺼지지 않은) 버튼만 찾아요. 꺼져 있으면 단계의 fallback_target을 가리켜요. */
    val enabledOnly: Boolean = false,
) {
    val isEmpty: Boolean
        get() = viewId == null && className == null &&
            desc.isEmpty() && text.isEmpty() && textContains.isEmpty() && descContains.isEmpty()

    fun withSlots(slots: Map<String, String>): Target {
        if (slots.isEmpty()) return this
        fun fill(s: String) = fillTemplate(s, slots)
        return copy(
            desc = desc.map(::fill),
            text = text.map(::fill),
            textContains = textContains.map(::fill),
            descContains = descContains.map(::fill),
            within = within?.withSlots(slots),
        )
    }

    companion object {
        fun parse(o: JSONObject?): Target? {
            if (o == null) return null
            val t = Target(
                viewId = o.cleanString("viewId"),
                desc = o.strings("desc"),
                text = o.strings("text"),
                textContains = o.strings("textContains"),
                descContains = o.strings("descContains"),
                className = o.cleanString("className"),
                within = parse(o.optJSONObject("within")),
                enabledOnly = o.optBoolean("enabled", false),
            )
            return if (t.isEmpty) null else t
        }
    }
}

/** 단계가 끝났다고 보는 조건. 여러 개를 적으면 하나만 만족해도 끝나요. */
data class DoneWhen(
    val clickedTarget: Boolean = false,
    val windowChanged: Boolean = false,
    val packageForeground: String? = null,
    val userScrolled: Boolean = false,
    val nodeAppears: Target? = null,
)

/** "{query}" 같은 자리를 슬롯 값으로 채워요. 값이 없으면 그대로 둬요. */
fun fillTemplate(template: String, slots: Map<String, String>): String =
    SLOT_PATTERN.replace(template) { m -> slots[m.groupValues[1]] ?: m.value }

private val SLOT_PATTERN = Regex("\\{(\\w+)\\}")

enum class Gesture {
    /** TYPE: 자판에서 text를 한 키씩 치게 안내해요(키 위치는 keyboard.json에 하드코딩). */
    TAP, DOUBLE_TAP, LONG_PRESS, SCROLL_UP, SCROLL_DOWN, PINCH_OUT, BACK_EDGE, HOME, TYPE;

    companion object {
        fun parse(s: String?): Gesture = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: TAP
    }
}

data class Step(
    val id: String,
    /** "launcher"면 홈 화면에 있을 때만 이 단계로 봐요. */
    val screen: String?,
    /** 이 노드가 화면에 있어야 이 단계로 봐요. 같은 id의 목록이 여러 화면에 있을 때 구분용. */
    val screenHas: Target?,
    val target: Target,
    val gesture: Gesture,
    val difficulty: Int,
    val doneWhen: DoneWhen,
    val final: Boolean,
    val pauseListening: Boolean,
    /** TYPE 단계에서 칠 글자. 예: "{query}" → "리센느" */
    val text: String? = null,
    /** TYPE 단계에서 자판으로 칠 수 없을 때 대신 가리킬 곳(예: 마이크). */
    val fallbackTarget: Target? = null,
)

data class StepScript(
    val capability: String,
    val packageName: String,
    val steps: List<Step>,
    /** 목표를 마친 뒤 잠깐 더 지켜볼 것(예: 유튜브 광고의 건너뛰기 버튼). */
    val afterDone: AfterDone? = null,
)

/**
 * 목표 완료 뒤 forMs 동안, targets 중 하나가 화면에 보이면 그 위에 점을 띄워요.
 * 그 버튼을 누르거나 시간이 지나면 끝나요. 안 보이면 아무것도 안 해요.
 */
data class AfterDone(val targets: List<Target>, val forMs: Long)

/** 스텝 스크립트를 어디서 가져올지. 지금은 assets, 나중에 SDK 파트너가 자기 소스를 꽂을 수 있게 분리해 둬요. */
interface StepScriptSource {
    fun load(capabilityId: String): StepScript?
}

class AssetStepScriptSource(private val context: Context) : StepScriptSource {
    override fun load(capabilityId: String): StepScript? = runCatching {
        // 스크립트가 없는 기능은 조용히 null (CatalogStepScriptSource가 첫 단계만 만들어 줘요).
        if (context.assets.list("steps")?.contains("$capabilityId.json") != true) return null
        val raw = context.assets.open("steps/$capabilityId.json").bufferedReader().use { it.readText() }
        parseScript(JSONObject(raw))
    }.onFailure { Log.w("COCO", "스텝 스크립트를 못 읽었어요: $capabilityId", it) }.getOrNull()

    private fun parseScript(o: JSONObject): StepScript {
        val arr = o.getJSONArray("steps")
        val steps = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val done = s.optJSONObject("done_when") ?: JSONObject()
            Step(
                id = s.optString("id", "step$i"),
                screen = s.cleanString("screen"),
                screenHas = Target.parse(s.optJSONObject("screen_has")),
                target = Target.parse(s.optJSONObject("target")) ?: Target(),
                gesture = Gesture.parse(s.optString("gesture")),
                difficulty = s.optInt("difficulty", 1),
                doneWhen = DoneWhen(
                    clickedTarget = done.optBoolean("clicked_target"),
                    windowChanged = done.optBoolean("window_changed"),
                    packageForeground = done.cleanString("package_foreground"),
                    userScrolled = done.optBoolean("user_scrolled"),
                    nodeAppears = Target.parse(done.optJSONObject("node_appears")),
                ),
                final = s.optBoolean("final"),
                pauseListening = s.optBoolean("pause_listening"),
                text = s.cleanString("text"),
                fallbackTarget = Target.parse(s.optJSONObject("fallback_target")),
            )
        }
        val afterDone = o.optJSONObject("after_done")?.let { a ->
            val watch = a.optJSONArray("watch") ?: JSONArray()
            AfterDone(
                targets = (0 until watch.length()).mapNotNull { Target.parse(watch.optJSONObject(it)) },
                forMs = a.optLong("for_ms", Config.AFTER_DONE_WATCH_MS).coerceIn(1_000L, Config.AFTER_DONE_MAX_MS),
            )
        }
        return StepScript(o.getString("capability"), o.optString("package"), steps, afterDone)
    }
}

/** "TODO(dump)"처럼 아직 모르는 값은 없는 것으로 봐요. */
private fun JSONObject.cleanString(key: String): String? =
    optString(key, "").takeIf { it.isNotBlank() && !it.startsWith("TODO") }

private fun JSONObject.strings(key: String): List<String> = when (val v = opt(key)) {
    is String -> listOf(v)
    is JSONArray -> (0 until v.length()).map { v.getString(it) }
    else -> emptyList()
}.filter { it.isNotBlank() && !it.startsWith("TODO") }
