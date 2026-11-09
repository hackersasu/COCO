package com.example.practice.voice

/**
 * 안내 중에 들은 말이 "정정"인지 "정지"인지 판별해요 (PRD 7.4).
 * 안드로이드에 의존하지 않는 순수 Kotlin이라 단위 테스트(PhrasesTest)로 확인해요.
 */
object Phrases {

    /** 정정: 지금 목표가 아니라는 뜻. 다음 후보로 옮겨요. (영어 시연용 문구도 함께) */
    val CORRECTION = listOf(
        "그거 말고", "이거 말고", "다른 거", "이거 아니", "아니야", "틀렸", "아니",
        "not that", "something else", "wrong", "no",
    )

    /** 정지: 안내를 멈춰요. */
    val STOP = listOf("코코야 그만", "그만", "됐어", "멈춰", "coco stop", "stop", "never mind")

    /** 정정 뒤에 이만큼 이상 말이 더 붙으면, 그 말로 순위를 다시 매겨요. 예: "아니 그거 말고 전화" */
    const val CORRECTION_EXTRA_MIN_CHARS = 2

    private val ENDINGS = listOf("에요", "예요", "요", "야")

    sealed class Kind {
        object None : Kind()
        object Stop : Kind()
        /** extra가 있으면 새로 순위를 매길 말("전화"), 없으면 다음 후보로. */
        data class Correction(val extra: String?) : Kind()
    }

    fun classify(said: String): Kind {
        val text = said.trim().lowercase()
        if (text.isEmpty()) return Kind.None
        if (STOP.any { lastEnd(text, it) >= 0 }) return Kind.Stop

        // 가장 뒤에 나온 정정 문구를 찾아서, 그 뒤에 붙은 말을 따로 떼어 내요.
        val end = CORRECTION.maxOf { lastEnd(text, it) }
        if (end < 0) return Kind.None
        var extra = text.substring(end).trim().trimStart(',', '.', '!', '?', '~').trim()
        // "이거 아니에요"의 "에요"처럼 말끝만 남은 건 새 내용이 아니에요.
        var stripped = true
        while (stripped) {
            stripped = false
            for (f in ENDINGS) {
                if (extra.startsWith(f)) {
                    extra = extra.removePrefix(f).trim().trimStart(',', '.', '!', '?', '~').trim()
                    stripped = true
                }
            }
        }
        val usable = extra.replace(" ", "").length >= CORRECTION_EXTRA_MIN_CHARS
        return Kind.Correction(if (usable) extra else null)
    }

    /**
     * 문구가 마지막으로 나온 곳의 끝 위치. 없으면 -1.
     * 영어는 단어 단위로만 맞춰요("no"가 "now", "know" 안에서 걸리지 않게).
     */
    private fun lastEnd(text: String, phrase: String): Int {
        val ascii = phrase.all { it.code < 128 }
        if (!ascii) {
            val i = text.lastIndexOf(phrase)
            return if (i >= 0) i + phrase.length else -1
        }
        val match = Regex("\\b${Regex.escape(phrase)}\\b").findAll(text).lastOrNull() ?: return -1
        return match.range.last + 1
    }
}
