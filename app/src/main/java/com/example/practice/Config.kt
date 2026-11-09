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
    const val REJECT_MS = 700L // 좌우로 흔들리고 사라지는 시간

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
    // 안내 중에도 계속 들으며 "No, not that"(아니 그거 말고)을 알아들을지.
    // 에뮬레이터에서는 듣기를 다시 켤 때마다 마이크 표시가 깜빡여 System UI가 멈춰서 꺼 둠. 실폰에서는 true로.
    const val MONITOR_ENABLED = false
    const val MONITOR_RESTART_MS = 400L       // 한 문장 듣고 다시 듣기까지 쉬는 시간
    const val MONITOR_PAUSED_CHECK_MS = 1000L // 듣기를 쉬는 단계에서 다시 확인하는 간격
    const val BUTTON_DOUBLE_PRESS_MS = 600L   // 접근성 버튼 두 번 누르기 = 정정(음성이 안 될 때 대신)

    // 단계 판정
    const val WINDOW_CHANGE_GRACE_MS = 800L // 단계가 막 시작됐을 때 오는 화면 전환 이벤트는 무시
    const val SCROLL_GRACE_MS = 1500L       // 스크롤 안내 점이 보인 뒤 이만큼 지나야 사용자 스크롤로 인정(앱이 저절로 하는 스크롤 무시)
    const val CONTENT_DEBOUNCE_MS = 150L    // 화면 내용 변화 이벤트를 모아서 한 번만 처리
    const val FULL_SCAN_MIN_INTERVAL_MS = 1000L // 화면 전체 훑기는 1초에 한 번까지만(상대 앱이 느려지지 않게)
    const val DONE_VIBRATION_MS = 80L       // 목표 완료 진동 길이

    // M1: 말(음성) 없이 접근성 버튼으로 바로 시작할 목표와 슬롯. M2부터는 AI가 정해 줘요.
    // 예: "리센느가 뭐야?" → AI가 YT_SEARCH + {query: 리센느}를 고른 것처럼 흉내 내요.
    const val M1_TEST_GOAL = "YT_SEARCH"
    val M1_TEST_SLOTS = mapOf("query" to "BTS")

    // 음성 인식 언어. 대회 시연이 영어라 en-US. 한국어로 말하려면 "ko-KR".
    const val SPEECH_LANGUAGE = "en-US"
    const val LISTEN_MAX_MS = 10_000L // 한 번 들을 때 최대 시간. 인식기가 답을 안 주면 이만큼 뒤에 멈춰요.
    const val LISTEN_STOP_GRACE_MS = 2_000L // 멈춘 뒤에도 답이 없으면 이만큼 더 기다렸다가 못 들은 걸로 해요.

    // 목표를 마친 뒤 잠깐 더 지켜보기(after_done, 예: 유튜브 광고 건너뛰기). 스크립트의 for_ms가 없을 때 기본값과 최대값.
    const val AFTER_DONE_WATCH_MS = 30_000L
    const val AFTER_DONE_MAX_MS = 60_000L
}
