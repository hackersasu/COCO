package com.example.practice.ai

import com.example.practice.engine.Catalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RankResultTest {

    private val catalog = Catalog.parse(JSONObject(File("src/main/assets/catalog.json").readText()))
    private val demo = DemoRankings.parse(JSONObject(File("src/main/assets/demo_rankings.json").readText()))

    @Test
    fun `목록에 없는 id는 버리고 확률 순으로 정렬한다`() {
        val json = JSONObject(
            """{"ranking":[{"id":"CALL_VIDEO","p":0.41},{"id":"MADE_UP","p":0.9},{"id":"GAL_RECENT","p":0.46}],
               "slots":{"contact":"지은","album":null},"reason":"늦은 밤"}""",
        )
        val r = RankResult.parse(json, catalog.ids, topK = 5, source = "gpt")
        assertEquals(listOf("GAL_RECENT", "CALL_VIDEO"), r.ranking.map { it.id })
        assertEquals(mapOf("contact" to "지은"), r.slots)
        assertEquals("늦은 밤", r.reason)
    }

    @Test
    fun `상위 K개만 남긴다`() {
        val ids = listOf("YT_SEARCH", "SET_FONT", "CAM_PHOTO", "SMS_SEND", "GAL_ZOOM", "SET_WIFI")
        val arr = ids.mapIndexed { i, id -> """{"id":"$id","p":${0.5 - i * 0.05}}""" }.joinToString(",")
        val r = RankResult.parse(JSONObject("""{"ranking":[$arr]}"""), catalog.ids, topK = 5, source = "gpt")
        assertEquals(5, r.ranking.size)
        assertEquals("YT_SEARCH", r.top?.id)
    }

    @Test
    fun `데모 순위표 - 리센느가 뭐야`() {
        val r = demo.match("리센느가 뭐야?", catalog.ids, 5)
        assertNotNull(r)
        assertEquals("YT_SEARCH", r!!.top?.id)
        assertEquals("리센느", r.slots["query"])
    }

    @Test
    fun `데모 순위표 - 손주 사진과 손주 보고 싶네를 구분한다`() {
        assertEquals("GAL_RECENT", demo.match("손주 사진 보고 싶네", catalog.ids, 5)?.top?.id)
        assertEquals("CALL_VIDEO", demo.match("손주 보고 싶어", catalog.ids, 5)?.top?.id)
        assertEquals("GAL_RECENT", demo.match("아이고 손주 보고 싶네", catalog.ids, 5)?.top?.id)
    }

    @Test
    fun `데모 순위표에 없는 말은 null`() {
        assertNull(demo.match("오늘 날씨 어때", catalog.ids, 5))
    }

    @Test
    fun `데모 순위표와 기능 목록이 서로 맞는다`() {
        val raw = JSONObject(File("src/main/assets/demo_rankings.json").readText()).getJSONArray("entries")
        for (i in 0 until raw.length()) {
            val ranking = raw.getJSONObject(i).getJSONArray("ranking")
            for (j in 0 until ranking.length()) {
                val id = ranking.getJSONObject(j).getString("id")
                assertTrue("데모 순위표의 $id 가 catalog.json에 없어요", id in catalog.ids)
            }
        }
    }

    @Test
    fun `기능 목록은 22개 + 범위 밖이고, 앱 이름이 모두 정의돼 있다`() {
        assertEquals(23, catalog.entries.size)
        assertEquals(catalog.entries.size, catalog.ids.size) // id 중복 없음
        assertTrue(Catalog.OUT_OF_SCOPE in catalog.ids)
        for (e in catalog.entries) {
            if (e.app != null) assertNotNull("${e.id}의 앱 '${e.app}'이 apps에 없어요", catalog.apps[e.app])
        }
    }

    @Test
    fun `프롬프트에 모든 기능 id가 들어간다`() {
        val prompt = RankPrompt.system(catalog)
        for (id in catalog.ids) assertTrue("프롬프트에 $id 가 없어요", prompt.contains(id))
    }
}
