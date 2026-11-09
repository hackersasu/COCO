package com.example.practice.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class HangulTest {

    @Test
    fun `리센느는 일곱 개 키`() {
        assertEquals(listOf("ㄹ", "ㅣ", "ㅅ", "ㅔ", "ㄴ", "ㄴ", "ㅡ"), Hangul.keystrokes("리센느"))
    }

    @Test
    fun `겹모음과 겹받침은 두 키로 나눈다`() {
        assertEquals(listOf("ㄱ", "ㅗ", "ㅏ"), Hangul.keystrokes("과"))
        assertEquals(listOf("ㄷ", "ㅏ", "ㄹ", "ㄱ"), Hangul.keystrokes("닭"))
        assertEquals(listOf("ㅇ", "ㅡ", "ㅣ"), Hangul.keystrokes("의"))
    }

    @Test
    fun `띄어쓰기는 SPACE 키`() {
        assertEquals(
            listOf("ㄴ", "ㅠ", "ㅈ", "ㅣ", "ㄴ", "ㅅ", "ㅡ", Hangul.SPACE, "ㅁ", "ㅜ", "ㄷ", "ㅐ"),
            Hangul.keystrokes("뉴진스 무대"),
        )
    }

    @Test
    fun `쌍자음은 Shift 키 이름 그대로 남긴다`() {
        assertEquals(listOf("ㄲ", "ㅜ", "ㅁ"), Hangul.keystrokes("꿈"))
    }

    @Test
    fun `치는 도중 글자가 합쳐져도 다음 키를 맞게 고른다`() {
        val wanted = "리센느"
        assertEquals("ㄹ", Hangul.nextKey("", wanted))
        assertEquals("ㅣ", Hangul.nextKey("ㄹ", wanted))
        assertEquals("ㅔ", Hangul.nextKey("릿", wanted))      // ㄹ ㅣ ㅅ 까지 친 상태
        assertEquals("ㄴ", Hangul.nextKey("리세", wanted))
        assertEquals("ㄴ", Hangul.nextKey("리센", wanted))
        assertEquals("ㅡ", Hangul.nextKey("리센ㄴ", wanted))  // 두 번째 ㄴ은 아직 따로 있음
        assertEquals(Hangul.ENTER, Hangul.nextKey("리센느", wanted))
    }

    @Test
    fun `잘못 치면 지우기 키를 가리킨다`() {
        assertEquals(Hangul.BACKSPACE, Hangul.nextKey("리셈", "리센느"))
        assertEquals(Hangul.BACKSPACE, Hangul.nextKey("ㅁ", "리센느"))
    }
}
