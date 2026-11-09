package com.example.practice.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** 홈 화면 아이콘 이름과 패키지. */
data class AppInfo(val name: String, val labels: List<String>, val packages: List<String>)

/** AI가 고를 수 있는 기능 하나 (PRD 6.1). */
data class CatalogEntry(
    val id: String,
    val app: String?,
    val description: String,
    val examples: List<String>,
    val slots: List<String>,
)

/** 기능 목록 전체. AI는 이 안에서만 고르고, 목록에 없는 id는 버려요. */
class Catalog(val apps: Map<String, AppInfo>, val entries: List<CatalogEntry>) {
    val ids: Set<String> = entries.map { it.id }.toSet()

    fun byId(id: String): CatalogEntry? = entries.firstOrNull { it.id == id }

    fun appOf(id: String): AppInfo? = byId(id)?.app?.let { apps[it] }

    companion object {
        const val OUT_OF_SCOPE = "OUT_OF_SCOPE"

        fun parse(o: JSONObject): Catalog {
            val apps = HashMap<String, AppInfo>()
            o.optJSONObject("apps")?.let { a ->
                a.keys().forEach { name ->
                    val info = a.getJSONObject(name)
                    apps[name] = AppInfo(name, info.optJSONArray("labels").strings(), info.optJSONArray("packages").strings())
                }
            }
            val arr = o.getJSONArray("capabilities")
            val entries = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                CatalogEntry(
                    id = c.getString("id"),
                    app = if (c.isNull("app")) null else c.optString("app"),
                    description = c.optString("description"),
                    examples = c.optJSONArray("examples").strings(),
                    slots = c.optJSONArray("slots").strings(),
                )
            }
            return Catalog(apps, entries)
        }
    }
}

/** 기능 목록을 어디서 가져올지. 지금은 assets, 나중에 SDK 파트너가 자기 목록을 꽂을 수 있게 분리해 둬요(PRD 11). */
interface CatalogSource {
    fun load(): Catalog?
}

class AssetCatalogSource(private val context: Context) : CatalogSource {
    override fun load(): Catalog? = runCatching {
        Catalog.parse(JSONObject(context.assets.open("catalog.json").bufferedReader().use { it.readText() }))
    }.onFailure { Log.w("COCO", "기능 목록을 못 읽었어요", it) }.getOrNull()
}

/**
 * 스텝 스크립트가 있는 기능(★)은 그 스크립트를, 없는 기능은 "앱 아이콘 가리키기" 한 단계만 만들어 줘요.
 * (PRD 3.3: 나머지 기능은 첫 단계까지만 안내)
 */
class CatalogStepScriptSource(
    private val scripts: StepScriptSource,
    private val catalog: Catalog?,
) : StepScriptSource {
    override fun load(capabilityId: String): StepScript? {
        scripts.load(capabilityId)?.let { return it }
        val app = catalog?.appOf(capabilityId) ?: return null
        if (app.labels.isEmpty()) return null
        val openApp = Step(
            id = "open_app",
            screen = "launcher",
            screenHas = null,
            target = Target(text = app.labels),
            gesture = Gesture.TAP,
            difficulty = 1,
            doneWhen = DoneWhen(clickedTarget = true),
            final = true,
            pauseListening = false,
        )
        return StepScript(capabilityId, app.packages.firstOrNull { !it.startsWith("TODO") }.orEmpty(), listOf(openApp))
    }
}

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }.filter { it.isNotBlank() }
