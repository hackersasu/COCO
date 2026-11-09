package com.example.practice.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class PhrasesTest {

    @Test
    fun `아니 그거 말고는 다음 후보`() {
        assertEquals(Phrases.Kind.Correction(null), Phrases.classify("아니 그거 말고"))
        assertEquals(Phrases.Kind.Correction(null), Phrases.classify("아니야"))
        assertEquals(Phrases.Kind.Correction(null), Phrases.classify("이거 아니에요"))
    }

    @Test
    fun `정정 뒤에 말이 붙으면 그 말로 다시 순위`() {
        assertEquals(Phrases.Kind.Correction("전화"), Phrases.classify("아니 그거 말고 전화"))
        assertEquals(Phrases.Kind.Correction("영상통화 하고 싶어"), Phrases.classify("그거 말고 영상통화 하고 싶어"))
    }

    @Test
    fun `정지 문구`() {
        assertEquals(Phrases.Kind.Stop, Phrases.classify("코코야 그만"))
        assertEquals(Phrases.Kind.Stop, Phrases.classify("됐어"))
    }

    @Test
    fun `영어 정정과 정지`() {
        assertEquals(Phrases.Kind.Correction(null), Phrases.classify("No, not that"))
        assertEquals(Phrases.Kind.Correction("video call"), Phrases.classify("No, not that. Video call"))
        assertEquals(Phrases.Kind.Stop, Phrases.classify("COCO stop"))
    }

    @Test
    fun `영어 단어 일부는 정정으로 보지 않는다`() {
        assertEquals(Phrases.Kind.None, Phrases.classify("I know how to do it now"))
    }

    @Test
    fun `상관없는 말은 무시`() {
        assertEquals(Phrases.Kind.None, Phrases.classify("이거 누르면 되나"))
        assertEquals(Phrases.Kind.None, Phrases.classify(""))
    }
}
