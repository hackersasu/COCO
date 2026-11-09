package com.example.practice.engine

/**
 * 한글을 두벌식 자판에서 누를 키 순서로 바꿔요. 예: "리센느" → ㄹ ㅣ ㅅ ㅔ ㄴ ㄴ ㅡ
 * 안드로이드에 의존하지 않는 순수 Kotlin이라 단위 테스트(HangulTest)로 확인해요.
 *
 * 쌍자음(ㄲ ㄸ ㅃ ㅆ ㅉ)과 ㅒ ㅖ는 Shift가 필요해서 키 이름 그대로 남겨 둬요.
 * 자판 위치표에 그 키가 없으면 "칠 수 없는 글자"로 보고 마이크로 대신 안내해요.
 */
object Hangul {
    const val SPACE = "SPACE"
    const val BACKSPACE = "BACKSPACE"
    const val ENTER = "ENTER"

    private val CHO = listOf(
        "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ",
        "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
    )
    private val JUNG = listOf(
        "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅘ", "ㅙ",
        "ㅚ", "ㅛ", "ㅜ", "ㅝ", "ㅞ", "ㅟ", "ㅠ", "ㅡ", "ㅢ", "ㅣ",
    )
    private val JONG = listOf(
        "", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ",
        "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
    )

    /** 자판에서 두 번에 나눠 누르는 겹모음·겹받침. */
    private val SPLIT = mapOf(
        "ㅘ" to listOf("ㅗ", "ㅏ"), "ㅙ" to listOf("ㅗ", "ㅐ"), "ㅚ" to listOf("ㅗ", "ㅣ"),
        "ㅝ" to listOf("ㅜ", "ㅓ"), "ㅞ" to listOf("ㅜ", "ㅔ"), "ㅟ" to listOf("ㅜ", "ㅣ"),
        "ㅢ" to listOf("ㅡ", "ㅣ"),
        "ㄳ" to listOf("ㄱ", "ㅅ"), "ㄵ" to listOf("ㄴ", "ㅈ"), "ㄶ" to listOf("ㄴ", "ㅎ"),
        "ㄺ" to listOf("ㄹ", "ㄱ"), "ㄻ" to listOf("ㄹ", "ㅁ"), "ㄼ" to listOf("ㄹ", "ㅂ"),
        "ㄽ" to listOf("ㄹ", "ㅅ"), "ㄾ" to listOf("ㄹ", "ㅌ"), "ㄿ" to listOf("ㄹ", "ㅍ"),
        "ㅀ" to listOf("ㄹ", "ㅎ"), "ㅄ" to listOf("ㅂ", "ㅅ"),
    )

    private const val SYLLABLE_COUNT_PER_CHO = 21 * 28

    /** 글자를 누를 키 순서로. 띄어쓰기는 SPACE, 한글이 아닌 글자는 그 글자 그대로 넣어요. */
    fun keystrokes(text: String): List<String> {
        val out = mutableListOf<String>()
        for (ch in text) {
            when {
                ch == ' ' -> out += SPACE
                ch in '가'..'힣' -> {
                    val code = ch - '가'
                    addJamo(out, CHO[code / SYLLABLE_COUNT_PER_CHO])
                    addJamo(out, JUNG[(code % SYLLABLE_COUNT_PER_CHO) / 28])
                    JONG[code % 28].takeIf { it.isNotEmpty() }?.let { addJamo(out, it) }
                }
                else -> addJamo(out, ch.toString()) // 낱자(ㄴ, ㅘ …)나 영어·숫자
            }
        }
        return out
    }

    private fun addJamo(out: MutableList<String>, jamo: String) {
        val parts = SPLIT[jamo]
        if (parts != null) out += parts else out += jamo
    }

    /** 두 키 순서가 앞에서부터 몇 개 같은지. */
    fun commonPrefix(a: List<String>, b: List<String>): Int {
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        return i
    }

    /**
     * 지금 입력 칸의 글자(typed)를 보고 다음에 누를 키를 정해요.
     * - 잘못 친 글자가 있으면 BACKSPACE
     * - 다 쳤으면 ENTER(검색)
     * - 아니면 다음 자모 키
     * 한글 자판은 치는 도중에 글자가 합쳐지지만("릿" → "리세"), 자모로 풀어서 비교하니 괜찮아요.
     */
    fun nextKey(typed: String, wanted: String): String {
        val t = keystrokes(typed)
        val w = keystrokes(wanted)
        val ok = commonPrefix(t, w)
        return when {
            t.size > ok -> BACKSPACE
            ok >= w.size -> ENTER
            else -> w[ok]
        }
    }
}
