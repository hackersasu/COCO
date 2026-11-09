package com.example.practice

/** 안내 단계 설정 (PRD S3 스스로 익히기). */
enum class GuidanceLevel { FULL, HARD_ONLY, NONE }

/**
 * COCO의 모든 조절 값을 모아 둔 곳.
 * 점 크기, 속도, 타이밍 같은 숫자는 여기서만 바꾸면 돼요.
 */
object Config {
    // 데모
    const val DEMO_MODE = false            // true면 demo_rankings.json 사용 (M4)
    val DEMO_TIME: String? = null          // 예: "23:12". null이면 실제 시각 (M3)
    var GUIDANCE_LEVEL = GuidanceLevel.FULL // FULL / HARD_ONLY / NONE (M4)
    const val HARD_DIFFICULTY = 2

    // 점
    const val DOT_DIAMETER_DP = 18f
    const val DOT_COLOR = 0xFF2160F3.toInt()
    const val PULSE_PERIOD_MS = 1600L
    const val PULSE_MAX_SCALE = 2.2f
    const val MOVE_MS = 600L
    const val PARKED_ALPHA = 0.35f

    // 제스처
    const val LONG_PRESS_FILL_MS = 1500L
    const val SCROLL_STROKE_MS = 1000L
    const val SCROLL_REPEAT_MS = 1300L
    const val BRUSH_START_WIDTH_DP = 22f
    const val BRUSH_END_WIDTH_DP = 2f
    const val BRUSH_LENGTH_DP = 220f
    const val TRAIL_FADE_MS = 400L

    // 음성·AI
    const val OPENAI_MODEL = "gpt-4o-mini"
    const val RANK_TOP_K = 5
    const val RANK_TIMEOUT_MS = 6000L

    // 단계 판정
    const val WINDOW_CHANGE_GRACE_MS = 800L // 단계가 막 시작됐을 때 오는 화면 전환 이벤트는 무시
    const val SCROLL_GRACE_MS = 800L        // 결과 목록이 뜨면서 저절로 생기는 스크롤 이벤트는 무시
    const val CONTENT_DEBOUNCE_MS = 150L    // 화면 내용 변화 이벤트를 모아서 한 번만 처리
    const val FULL_SCAN_MIN_INTERVAL_MS = 1000L // 화면 전체 훑기는 1초에 한 번까지만(상대 앱이 느려지지 않게)
    const val DONE_VIBRATION_MS = 80L       // 목표 완료 진동 길이

    // M1: 말(음성) 없이 접근성 버튼으로 바로 시작할 목표와 슬롯. M2부터는 AI가 정해 줘요.
    // 예: "리센느가 뭐야?" → AI가 YT_SEARCH + {query: 리센느}를 고른 것처럼 흉내 내요.
    const val M1_TEST_GOAL = "YT_SEARCH"
    val M1_TEST_SLOTS = mapOf("query" to "리센느")
}
