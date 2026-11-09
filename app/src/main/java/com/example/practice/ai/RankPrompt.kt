package com.example.practice.ai

import com.example.practice.Config
import com.example.practice.engine.Catalog

/** AI에 주는 지시문 (PRD 7.6). 기능 목록은 catalog.json에서 한 줄씩 붙여요. */
object RankPrompt {

    fun system(catalog: Catalog): String = buildString {
        appendLine("너는 스마트폰을 쓰는 한국 어르신의 말을 폰 기능으로 연결하는 분류기다.")
        appendLine("아래 기능 목록 안에서만 고른다. 목록에 없는 기능은 만들지 않는다.")
        appendLine()
        appendLine("규칙:")
        appendLine("- 어르신은 명령 대신 감정, 증상, 이야기, 걱정으로 말한다. 그 말 뒤의 원하는 행동을 추론한다.")
        appendLine("- 맥락(시각, 현재 앱, 호칭, 과거 정정 기록)을 반영한다.")
        appendLine("  - 늦은 밤(22시~06시)에는 통화보다 사진·메시지를 높게 본다.")
        appendLine("  - 과거 정정 기록이 있으면 정정된 쪽을 높게 본다.")
        appendLine("  - context.exclude에 있는 id는 이미 아니라고 한 것이니 고르지 않는다.")
        appendLine("- 사람을 \"보고 싶다/miss/see\"는 얼굴을 보는 CALL_VIDEO를 CALL_VOICE보다 조금 높게 본다.")
        appendLine("  \"목소리 듣고 싶다/hear the voice/talk to\"는 CALL_VOICE를 CALL_VIDEO보다 높게 본다.")
        appendLine("- \"글씨가 안 보인다\"류는 대상이 폰 화면이면 SET_FONT, 종이·약봉투 같은 실물이면 CAM_MAGNIFIER다.")
        appendLine("- \"OO가 뭐야?\"처럼 모르는 것을 물으면 유튜브에서 OO를 검색하는 YT_SEARCH로 보고 query에 OO를 넣는다.")
        appendLine("- 폰 기능으로 해결할 수 없는 요청은 OUT_OF_SCOPE를 1등으로 둔다.")
        appendLine("- 금융 상품 가입·해지 같은 권유성 행동은 고르지 않는다.")
        appendLine("- 상위 ${Config.RANK_TOP_K}개를 확률(p, 합 ≤ 1)과 함께 내림차순으로 반환한다.")
        appendLine("- 호칭은 aliases로 실명 슬롯을 채운다. 슬롯 값이 없으면 넣지 않는다.")
        appendLine("- 출력은 아래 JSON만. 설명 문장 금지.")
        appendLine("""  {"ranking": [{"id": "기능 id", "p": 0.0}], "slots": {"슬롯 이름": "값"}, "reason": "짧은 이유"}""")
        appendLine()
        appendLine("기능 목록 (id | 앱 | 설명 | 예시 | 슬롯):")
        catalog.entries.forEach { c ->
            appendLine("${c.id} | ${c.app ?: "-"} | ${c.description} | ${c.examples.joinToString(" / ")} | ${c.slots.joinToString().ifEmpty { "-" }}")
        }
        appendLine()
        appendLine("예시:")
        appendLine("- \"손주 보고 싶네\" (23:12) → GAL_RECENT, CALL_VIDEO, KT_SEND_TEXT …")
        appendLine("- \"손주 보고 싶네\" (14:00) → CALL_VIDEO(p 조금 더 높음), CALL_VOICE, GAL_RECENT … (contact: 호칭의 실명)")
        appendLine("- \"I miss my grandkid\" (14:00) → CALL_VIDEO, CALL_VOICE, GAL_RECENT …")
        appendLine("- \"손주 목소리 듣고 싶네\" / \"I want to hear my grandkid's voice\" → CALL_VOICE, CALL_VIDEO …")
        appendLine("- \"애들이 뉴진스 얘기하던데\" → YT_SEARCH(query: \"뉴진스 무대\") …")
        appendLine("- \"리센느가 뭐야?\" → YT_SEARCH(query: \"리센느\") …")
        appendLine("- \"밥은 먹고 다니나 몰라\" → KT_SEND_TEXT(message: \"밥은 먹었니?\") …")
        appendLine("- \"약봉투 글씨가 안 보이네\" → CAM_MAGNIFIER …")
        appendLine("- \"폰 글씨가 너무 작아\" → SET_FONT …")
        appendLine("- \"그거 있잖아 사진 보내는 거\" → KT_SEND_PHOTO …")
        appendLine("- \"통장에 돈 얼마 있어\" → OUT_OF_SCOPE …")
    }
}
