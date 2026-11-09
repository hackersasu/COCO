package com.example.practice.engine

import android.content.Context
import android.graphics.PointF
import android.util.Log
import org.json.JSONObject

/**
 * 시연 폰 자판의 키 위치표(화면 픽셀). assets/keyboard.json에서 읽어요.
 * 화면을 매번 훑지 않고 좌표를 하드코딩해 둬서 빠르지만, 폰이나 자판이 바뀌면 다시 재야 해요.
 * 두벌식(쿼티) 한글 자판 기준이에요. 천지인 자판이면 맞지 않아요.
 */
class KeyboardLayout(private val keys: Map<String, PointF>) {

    fun position(key: String): PointF? = keys[key]

    /** 이 키 순서를 전부 칠 수 있는지(위치표에 모든 키 + 지우기 + 검색 키가 있는지). */
    fun canType(keystrokes: List<String>): Boolean =
        keystrokes.isNotEmpty() &&
            keystrokes.all { it in keys } &&
            Hangul.BACKSPACE in keys &&
            Hangul.ENTER in keys

    companion object {
        /** 위치표가 비어 있으면 null. 그러면 엔진이 마이크로 대신 안내해요. */
        fun load(context: Context): KeyboardLayout? = runCatching {
            val raw = context.assets.open("keyboard.json").bufferedReader().use { it.readText() }
            val o = JSONObject(raw)
            val map = HashMap<String, PointF>()

            val rows = o.optJSONArray("rows")
            if (rows != null) {
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONObject(r)
                    if (row.isNull("y") || row.isNull("x0") || row.isNull("dx")) continue
                    val y = row.getDouble("y").toFloat()
                    val x0 = row.getDouble("x0").toFloat()
                    val dx = row.getDouble("dx").toFloat()
                    val names = row.getJSONArray("keys")
                    for (i in 0 until names.length()) map[names.getString(i)] = PointF(x0 + dx * i, y)
                }
            }

            val special = o.optJSONObject("special")
            special?.keys()?.forEach { name ->
                val xy = special.optJSONArray(name) ?: return@forEach
                if (xy.length() == 2) map[name] = PointF(xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat())
            }

            if (map.isEmpty()) null else KeyboardLayout(map)
        }.onFailure { Log.w("COCO", "자판 위치표를 못 읽었어요", it) }.getOrNull()
    }
}
