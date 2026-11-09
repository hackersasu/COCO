# COCO PRD (Claude 구현용)

> 이 문서는 Claude(클로드 코드)가 COCO 안드로이드 앱을 구현할 때 읽는 제품 요구사항 문서다.
> 원칙 → 범위 → 아키텍처 → 데이터 스키마 → 모듈별 요구사항 → 마일스톤과 완료 기준 순서로 읽는다.

---

## 0. Claude에게: 작업 방식

- 사용자는 안드로이드 개발과 AI가 처음이다. 코드를 줄 때마다 아래를 함께 알려 준다.
  - 어느 파일(전체 경로)에 넣는지
  - 안드로이드 스튜디오에서 어떻게 실행하고 확인하는지 단계별로
- 조절 가능한 값(점 크기, 속도, 굵기, 꼬리 길이, 타이밍 등)은 **파일 상단 상수**로 둔다. 가능하면 `Config.kt` 한 곳에 모은다.
- **마일스톤 순서(M0 → M4)대로** 구현한다. 각 마일스톤이 끝나면 완료 기준을 확인하는 방법을 알려 주고 멈춘다.
- 해커톤이다(남은 시간 약 5시간). 완벽한 일반화보다 데모 시나리오가 확실히 동작하는 것이 우선이다.
- 아래 "절대 원칙"과 충돌하는 구현은 하지 않는다. 충돌이 필요해 보이면 먼저 사용자에게 묻는다.
- 화면 버튼 선택자(텍스트·설명·viewId)는 추측하지 말고 `TODO(dump)`로 표시한다. 사용자가 `adb shell uiautomator dump` 결과를 주면 채운다.

---

## 1. 제품 개요

- **이름:** COCO(코코)
- **대회:** c0mpiled-17 Seoul, 주제 AI for the Aging Population
- **한 줄 정의:** 스마트폰을 쓰는 어르신을 위한 작은 파란 점 조수
- **동작 요약:**
  1. 어르신이 "코코야~" 하고 부르고 편하게 말한다.
  2. AI가 폰 기본 앱 기능 목록 전체에 확률 순위를 매긴다.
  3. 묻지 않고 1등 기능을 목표로 삼는다.
  4. 작은 파란 점이 다음에 누를 곳을 가리키고, 어르신이 직접 누른다.
  5. 원한 게 아니면 어르신이 "아니 그거 말고"라고 말하고, 점은 2등으로 옮겨 간다.
- **핵심 가치:** 어르신의 두서없는 말을 폰 기능으로 번역하고, 대신 해 주지 않고 스스로 익히게 돕는다.
- **카피:** "Meet COCO. You tap. COCO points the way."

---

## 2. 절대 원칙 (Hard constraints)

| ID | 원칙 | 구현상 의미 |
|---|---|---|
| HC-0 | **실제 폰 환경을 방해하지 않는다** | 오버레이에는 파란 점과 파란 HCI 도형(맥동, 링, 붓 획, 점 두 개)만 그린다. 텍스트, 말풍선, 팝업, 다이얼로그, 토스트, 확인 질문, TTS 음성 안내를 띄우지 않는다. |
| HC-1 | 터치를 막지 않는다 | 오버레이 창은 항상 `FLAG_NOT_TOUCHABLE` + `FLAG_NOT_FOCUSABLE`이다. |
| HC-2 | 묻지 않고 1등으로 간다 | 순위 1등을 바로 목표로 설정한다. 확률 차이가 작아도 묻지 않는다. |
| HC-3 | 정정은 한마디로 | "아니/그거 말고" 감지 시 저장된 순위표의 다음 후보로 즉시 전환한다. API를 다시 부르지 않는다. |
| HC-4 | 대신 실행하지 않는다 | `performAction`, `dispatchGesture`, 딥링크 자동 실행을 쓰지 않는다. 사용자의 탭만 기다린다. |
| HC-5 | 목표를 유지한다 | "코코야 그만" 또는 정지 신호 전까지 목표를 유지한다. 다른 앱이나 전화로 이탈하면 대기하고, 복귀하면 재개한다. |
| HC-6 | 화면 내용을 AI에 보내지 않는다 | LLM에는 발화 텍스트와 맥락만 보낸다. 화면 노드는 기기 안에서 버튼을 찾는 데만 쓴다. 비밀번호 필드(`isPassword`)는 읽지 않는다. |

---

## 3. 범위

### 3.1 지원 앱 (8개)
카카오톡, 카카오맵, 유튜브, 갤러리, 카메라, 전화(음성·영상), 문자, 설정

### 3.2 데모 기기
- 실제 안드로이드 폰 1대(삼성 갤럭시 가정)에 맞춰 하드코딩한다.
- 에뮬레이터는 보조로만 쓴다(카카오톡 로그인 문제 가능).

### 3.3 스텝까지 완성할 기능 (★)

| 기능 ID | 데모 시나리오 |
|---|---|
| `GAL_RECENT` | 훅: 밤 "손주 보고 싶네" → 1등 |
| `CALL_VIDEO` | 훅: "아니 그거 말고" → 2등, 그리고 스스로 익히기(1일째·3일째·7일째) |
| `YT_SEARCH` | "애들이 뉴진스 얘기하던데" |
| `SET_FONT` | "폰 글씨가 너무 작아" |

나머지 기능은 순위 계산에는 포함하고, 안내는 첫 단계(앱 아이콘 가리키기)까지만 한다.

### 3.4 Non-goals
- 화면 텍스트 기반 LLM 자율 조작
- 키보드 키 단위 자모 안내. 검색은 앱의 마이크(음성 검색) 버튼을 가리키는 것으로 대체한다.
- 금융·보험 앱 지원
- Play 스토어 배포 수준의 정책 대응

---

## 4. 사용자 시나리오

### S1. 훅: "손주 보고 싶네" (밤)
1. 홈 화면, `DEMO_TIME = 23:12`
2. 사용자가 COCO를 부르고 "손주 보고 싶네"라고 말한다.
3. 순위는 `GAL_RECENT` > `CALL_VIDEO` > `KT_SEND_TEXT` 순이다.
4. 점이 갤러리 아이콘을 가리킨다 → 사용자가 누른다 → 점이 손주 앨범·최근 사진을 가리킨다.
5. 사용자가 "아니 그거 말고"라고 말한다 → 목표가 `CALL_VIDEO`로 바뀐다.
6. 점이 현재 화면에서 갈 길을 가리킨다(홈으로 나가는 제스처 → 전화 → 연락처 "지은" → 영상통화 버튼).
7. 사용자가 영상통화 버튼을 누르면 완료 진동이 울린다.

### S2. "애들이 뉴진스 얘기하던데"
`YT_SEARCH` 순서: 유튜브 아이콘 → 검색(돋보기) → 음성 검색(마이크) → 사용자가 직접 "뉴진스 무대"라고 말함 → 결과 목록(붓펜 스크롤 1회) → 영상 탭 → 완료

### S3. 스스로 익히기
같은 `CALL_VIDEO`를 안내 단계 설정만 바꿔 실행한다.
- `FULL`: 모든 단계에 점
- `HARD_ONLY`: `difficulty >= 2`인 단계에만 점
- `NONE`: 점 없음

### S4. 정정 연쇄
2등도 아니면 다시 "아니"라고 말한다 → 3등. 후보가 다 떨어지면 점이 좌우로 한 번 흔들리고 사라진 뒤 대기 상태가 된다.

### S5. 목표 유지
안내 중 전화가 온다 → 점이 화면 구석에서 흐리게 대기한다 → 통화가 끝나고 목표 앱으로 돌아오면 다음 단계를 다시 가리킨다.

### S6. 범위 밖
1등이 `OUT_OF_SCOPE`이면 점이 좌우로 흔들리고 사라진다. 화면에 텍스트는 띄우지 않는다.

---

## 5. 아키텍처

```
[트리거] 접근성 버튼 / (P3) 웨이크워드
   ↓
[VoiceListener] SpeechRecognizer(ko-KR) → 발화 텍스트
   ↓                                 ↘ 정정·정지 문구 감지 → GuidanceEngine
[ContextBuilder] 시각, 현재 앱, 호칭 프로필, 정정 기록
   ↓
[RankClient] gpt-4o-mini → ranking[]  (DEMO_MODE면 고정 순위)
   ↓
[GuidanceEngine] 목표=ranking[i] → StepScript 로드 → 현재 단계
   ↓                         ↑ 접근성 이벤트(창 변경, 클릭, 스크롤)
[NodeFinder] 현재 화면에서 단계의 target 노드 찾기 → 화면 좌표
   ↓
[DotOverlayView] 점 / 제스처 그리기 (텍스트 없음)
```

### 5.1 파일 구조 (제안)

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   ├── catalog.json              # 기능 카탈로그 22개
│   ├── steps/                    # 기능별 스텝 스크립트
│   │   ├── GAL_RECENT.json
│   │   ├── CALL_VIDEO.json
│   │   ├── YT_SEARCH.json
│   │   └── SET_FONT.json
│   ├── profile.json              # 호칭 프로필
│   └── demo_rankings.json        # 데모 모드 고정 순위
├── res/xml/accessibility_service_config.xml
└── java/com/coco/app/
    ├── Config.kt                 # 모든 조절 상수
    ├── CocoAccessibilityService.kt
    ├── MainActivity.kt           # 권한 안내, API 키, 데모 설정 (COCO 자체 화면)
    ├── voice/VoiceListener.kt
    ├── voice/Phrases.kt          # 정정/정지 문구
    ├── ai/RankClient.kt
    ├── ai/RankPrompt.kt
    ├── engine/Catalog.kt
    ├── engine/StepScript.kt
    ├── engine/GuidanceEngine.kt
    ├── engine/NodeFinder.kt
    ├── context/ContextBuilder.kt
    ├── context/CorrectionLog.kt
    └── overlay/DotOverlayView.kt
```

- `MainActivity`는 COCO 앱 자체의 설정 화면이라 HC-0의 예외다. 다른 앱 위에는 아무것도 띄우지 않는다.

---

## 6. 데이터 스키마

### 6.1 `catalog.json`
```json
[
  {
    "id": "GAL_RECENT",
    "app": "갤러리",
    "package": "com.sec.android.gallery3d",
    "description": "최근 사진이나 특정 사람(손주 등)의 사진을 본다",
    "examples": ["손주 보고 싶네(밤)", "어제 찍은 사진 어디 갔어"],
    "slots": ["date", "album"]
  }
]
```
- 패키지명은 데모 폰에서 확인한다: `adb shell pm list packages | grep -i <이름>`. 모르면 `TODO(dump)`로 둔다.

**전체 22개 목록**

| 앱 | ID | 예시 발화 | 슬롯 |
|---|---|---|---|
| 유튜브 | YT_SEARCH | "애들이 뉴진스 얘기하던데" | query |
| 유튜브 | YT_FULLSCREEN | "화면이 너무 작아" | – |
| 카카오톡 | KT_SEND_TEXT | "밥은 먹고 다니나 몰라" | contact, message |
| 카카오톡 | KT_SEND_PHOTO | "어제 찍은 사진 아들한테" | contact, photo |
| 카카오톡 | KT_FACETALK | "손주랑 얼굴 보면서 얘기하고 싶어" | contact |
| 카카오톡 | KT_UNREAD | "누가 연락했나?" | – |
| 전화 | CALL_VOICE | "큰애한테 전화 좀" | contact |
| 전화 | CALL_VIDEO | "손주 얼굴 보고 싶어" | contact |
| 전화 | CALL_MISSED | "아까 누가 전화했던데" | – |
| 문자 | SMS_READ_CODE | "번호가 왔다는데 어디 있어" | – |
| 문자 | SMS_SEND | "문자로 보내줘" | contact, message |
| 카메라 | CAM_PHOTO | "사진 찍어줘" | – |
| 카메라 | CAM_SELFIE | "내 얼굴 찍을래" | – |
| 카메라 | CAM_MAGNIFIER | "약봉투 글씨가 안 보이네" | – |
| 갤러리 | GAL_RECENT | "손주 보고 싶네" | date, album |
| 갤러리 | GAL_ZOOM | "사진을 크게 보고 싶어" | – |
| 카카오맵 | MAP_ROUTE | "서울대병원 어떻게 가" | destination |
| 카카오맵 | MAP_NEARBY | "근처에 약국 있나" | place_type |
| 설정 | SET_FONT | "폰 글씨가 너무 작아" | – |
| 설정 | SET_VOLUME | "전화 소리가 안 나" | – |
| 설정 | SET_BRIGHTNESS | "화면이 어두워" | – |
| 설정 | SET_WIFI | "인터넷이 안 돼" | – |
| – | OUT_OF_SCOPE | "통장에 돈 얼마 있어" | – |

### 6.2 스텝 스크립트 `steps/<ID>.json`
```json
{
  "capability": "YT_SEARCH",
  "package": "com.google.android.youtube",
  "steps": [
    {
      "id": "open_app",
      "screen": "launcher",
      "target": {"text": "YouTube"},
      "gesture": "tap",
      "difficulty": 1,
      "done_when": {"package_foreground": "com.google.android.youtube"}
    },
    {
      "id": "tap_search",
      "target": {"desc": "검색", "viewId": "TODO(dump)"},
      "gesture": "tap",
      "difficulty": 2,
      "done_when": {"clicked_target": true}
    },
    {
      "id": "tap_voice",
      "target": {"desc": "음성으로 검색", "viewId": "TODO(dump)"},
      "gesture": "tap",
      "difficulty": 3,
      "done_when": {"window_changed": true}
    },
    {
      "id": "scroll_results",
      "target": {"className": "androidx.recyclerview.widget.RecyclerView"},
      "gesture": "scroll_up",
      "difficulty": 2,
      "done_when": {"user_scrolled": true}
    },
    {
      "id": "tap_video",
      "target": {"firstChildOf": "results", "viewId": "TODO(dump)"},
      "gesture": "tap",
      "difficulty": 1,
      "done_when": {"clicked_target": true},
      "final": true
    }
  ]
}
```

**target 매칭 규칙** (`NodeFinder`)
- 우선순위: `viewId` > `desc`(contentDescription) > `text`(정확 일치) > `textContains` > `className`
- 찾은 노드가 클릭할 수 없으면 클릭 가능한 부모까지 올라간다.
- 슬롯 치환: `{"text": "{contact}"}` 형태로 슬롯 값을 넣는다.

**gesture 값:** `tap`, `double_tap`, `long_press`, `scroll_up`, `scroll_down`, `pinch_out`, `back_edge`, `home`

**done_when 값:** `clicked_target`, `window_changed`, `package_foreground`, `user_scrolled`, `node_appears`

**이탈 처리:** 현재 화면이 예상 경로가 아니면 `home` 또는 `back_edge` 제스처로 경로 복귀를 안내한다. 런처에서는 첫 단계로 리셋한다.

### 6.3 `profile.json`
```json
{
  "aliases": {"손주": "지은", "큰애": "김민수", "딸": "김수연"},
  "preferred_call": {"지은": "CALL_VIDEO"}
}
```

### 6.4 순위 요청과 응답

**요청(user 메시지에 JSON으로)**
```json
{
  "utterance": "손주 보고 싶네",
  "context": {
    "time": "23:12",
    "foreground_app": "launcher",
    "aliases": {"손주": "지은"},
    "recent_corrections": [
      {"utterance": "손주 보고 싶네", "from": "GAL_RECENT", "to": "CALL_VIDEO", "time": "22:40"}
    ]
  }
}
```

**응답(Structured Outputs / JSON 모드)**
```json
{
  "ranking": [
    {"id": "GAL_RECENT", "p": 0.46},
    {"id": "CALL_VIDEO", "p": 0.41},
    {"id": "KT_SEND_TEXT", "p": 0.08},
    {"id": "KT_FACETALK", "p": 0.03},
    {"id": "CALL_VOICE", "p": 0.02}
  ],
  "slots": {"contact": "지은", "album": "지은"},
  "reason": "늦은 밤이라 통화보다 사진이 부담 없음"
}
```
- `ranking`은 상위 5개, `id`는 카탈로그에 있는 값만 허용한다. 파싱 후 카탈로그에 없는 ID는 버린다.
- `say`, `ask` 같은 화면 출력 필드는 만들지 않는다(HC-0).
- `reason`은 Logcat에만 남긴다.

---

## 7. 모듈별 요구사항

### 7.1 `CocoAccessibilityService`
- `accessibility_service_config.xml` 설정
  - 이벤트: `typeWindowStateChanged|typeWindowContentChanged|typeViewClicked|typeViewScrolled`
  - 플래그: `flagRetrieveInteractiveWindows|flagReportViewIds|flagRequestAccessibilityButton`
  - `canRetrieveWindowContent="true"`
- 접근성 버튼 콜백 = COCO 트리거(P0). 이미 활성이면 정지 토글.
- 이벤트를 `GuidanceEngine`에 전달한다. `ContentChanged`는 150ms 디바운스.
- 오버레이 창은 `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN`, 전체 화면, 투명 배경.

### 7.2 `DotOverlayView` (Custom View, Canvas)
- 텍스트를 그리는 코드는 넣지 않는다(HC-0).
- 상태별 표현

| 상태 | 표현 |
|---|---|
| `LISTENING` | 화면 하단 중앙 작은 점이 숨 쉬듯 맥동 |
| `TAP` | 목표 위 점 + 바깥 링이 퍼지는 맥동 |
| `DOUBLE_TAP` | 짧은 맥동 두 번 반복 |
| `LONG_PRESS` | 점 둘레 링이 `LONG_PRESS_FILL_MS` 동안 차오름 |
| `SCROLL` | 붓펜 획. 시작이 굵고 끝이 가늘며 꼬리가 사라짐. `SCROLL_STROKE_MS` 동안 그리고 `SCROLL_REPEAT_MS`마다 반복. 사용자가 스크롤하면 즉시 멈춤 |
| `PINCH` | 점 두 개가 대각선으로 벌어짐 |
| `BACK_EDGE` | 화면 왼쪽 끝에서 안쪽으로 짧은 붓 획 |
| `MOVE` | 이전 목표에서 새 목표로 `MOVE_MS` 동안 ease-in-out 이동(정정 시) |
| `PARKED` | 화면 우하단 구석에서 `PARKED_ALPHA`로 흐리게 |
| `REJECT` | 좌우로 한 번 흔들리고 페이드아웃(범위 밖, 후보 소진) |
| `HIDDEN` | 아무것도 그리지 않음 |

- 애니메이션은 `ValueAnimator` 또는 `Choreographer`로 구현한다. `prefers reduced motion` 대응은 생략해도 된다.

### 7.3 `Config.kt` (필수 상수, 기본값)
```kotlin
object Config {
    // 데모
    const val DEMO_MODE = false            // true면 demo_rankings.json 사용
    const val DEMO_TIME: String? = null    // 예: "23:12". null이면 실제 시각
    var GUIDANCE_LEVEL = GuidanceLevel.FULL // FULL / HARD_ONLY / NONE
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
}
```
- 접근성 버튼 외에 테스트용 트리거로 볼륨 키 길게 누르기를 추가해도 된다(선택).

### 7.4 `VoiceListener`
- `SpeechRecognizer` + `RecognizerIntent.EXTRA_LANGUAGE = "ko-KR"`, `EXTRA_PARTIAL_RESULTS = true`
- 모드
  - `COMMAND`: 트리거 직후 한 문장을 받는다 → 순위 요청
  - `MONITOR`: 안내 중 계속 듣는다. 결과가 끝나면 재시작하는 루프. 결과는 `Phrases`로만 판별한다.
- `Phrases.kt`
  - 정정: `아니`, `그거 말고`, `이거 말고`, `다른 거`, `이거 아니`, `아니야`, `틀렸`
  - 정지: `그만`, `됐어`, `코코야 그만`, `멈춰`
- 정정 문구 뒤에 새 내용이 붙으면 다시 순위를 요청한다. 예: "아니 그거 말고 전화"에서 정정 문구를 뺀 나머지가 `CORRECTION_EXTRA_MIN_CHARS`(기본 2)자 이상인 경우. 이때 이전 후보들은 제외 목록으로 함께 보낸다.
- 마이크 권한 `RECORD_AUDIO`는 `MainActivity`에서 요청한다.
- 유튜브 음성 검색처럼 다른 앱이 마이크를 쓰는 단계에서는 `MONITOR`를 잠시 멈춘다(스텝에 `"pause_listening": true`).

### 7.5 `RankClient`
- OkHttp로 `POST https://api.openai.com/v1/chat/completions`
- `model = Config.OPENAI_MODEL`, `temperature = 0`, `response_format`은 JSON 스키마(Structured Outputs) 또는 `{"type":"json_object"}`
- 네트워크·파싱 실패나 타임아웃이 나면 `demo_rankings.json`의 일치 항목으로 폴백하고, 그것도 없으면 `REJECT`
- API 키는 `local.properties` → `BuildConfig.OPENAI_API_KEY`로 넣는다. 깃 저장소에 커밋하지 않는다.

### 7.6 `RankPrompt` (system 프롬프트 초안)
```
너는 스마트폰을 쓰는 한국 어르신의 말을 폰 기능으로 연결하는 분류기다.
아래 기능 목록 안에서만 고른다. 목록에 없는 기능은 만들지 않는다.

규칙:
- 어르신은 명령 대신 감정, 증상, 이야기, 걱정으로 말한다. 그 말 뒤의 원하는 행동을 추론한다.
- 맥락(시각, 현재 앱, 호칭, 과거 정정 기록)을 반영한다.
  - 늦은 밤(22시~06시)에는 통화보다 사진·메시지를 높게 본다.
  - 과거 정정 기록이 있으면 정정된 쪽을 높게 본다.
- "글씨가 안 보인다"류는 대상이 폰 화면이면 SET_FONT, 종이·약봉투 같은 실물이면 CAM_MAGNIFIER다.
- 폰 기능으로 해결할 수 없는 요청은 OUT_OF_SCOPE를 1등으로 둔다.
- 금융 상품 가입·해지 같은 권유성 행동은 고르지 않는다.
- 상위 5개를 확률(p, 합 ≤ 1)과 함께 내림차순으로 반환한다.
- 호칭은 aliases로 실명 슬롯을 채운다.
- 출력은 지정한 JSON만. 설명 문장 금지.

기능 목록:
{catalog.json의 id, app, description, examples를 한 줄씩}

예시:
- "손주 보고 싶네" (23:12) → GAL_RECENT, CALL_VIDEO, KT_SEND_TEXT …
- "손주 보고 싶네" (14:00) → CALL_VIDEO, KT_FACETALK, GAL_RECENT …
- "애들이 뉴진스 얘기하던데" → YT_SEARCH(query: "뉴진스 무대") …
- "밥은 먹고 다니나 몰라" → KT_SEND_TEXT(message: "밥은 먹었니?") …
- "약봉투 글씨가 안 보이네" → CAM_MAGNIFIER …
- "폰 글씨가 너무 작아" → SET_FONT …
- "그거 있잖아 사진 보내는 거" → KT_SEND_PHOTO …
- "통장에 돈 얼마 있어" → OUT_OF_SCOPE …
```

### 7.7 `GuidanceEngine` (상태 머신)
```
IDLE → LISTENING → RANKING → GUIDING(candidateIdx, stepIdx) → DONE → IDLE
                                  ↕ PARKED (목표 앱 이탈)
GUIDING --정정--> GUIDING(candidateIdx+1, stepIdx=경로 재계산)
GUIDING --정지--> IDLE
후보 소진 / OUT_OF_SCOPE → REJECT → IDLE
```
- **목표 전환 시 경로 재계산:** 새 목표의 스텝 중 현재 화면에서 target이 보이는 가장 뒤 단계부터 시작한다. 없으면 `home` 제스처로 런처 복귀를 안내한 뒤 첫 단계부터 시작한다.
- **안내 단계 적용:**
  - `HARD_ONLY`이면 `difficulty < HARD_DIFFICULTY`인 단계는 점을 숨기고 `done_when`만 감시한다.
  - `NONE`이면 점을 전부 숨기고 완료 진동만 낸다.
- **목표 유지:**
  - 전경 패키지가 목표 앱도 런처도 아니면(전화 수신 UI 포함) `PARKED`
  - 목표 앱으로 돌아오면 `GUIDING` 재개
- **완료:** `final` 단계의 `done_when` 충족 시 진동 1회(`VibrationEffect`, 80ms) 후 `IDLE`
- **정정 기록:** `CorrectionLog`에 `{utterance, from, to, time}`을 남기고 다음 요청 맥락에 최근 5개를 넣는다(SharedPreferences).

### 7.8 `MainActivity` (COCO 자체 화면)
- 접근성 서비스 켜기 안내 버튼(`Settings.ACTION_ACCESSIBILITY_SETTINGS`)
- 마이크 권한 요청
- 데모 설정: 데모 모드 토글, `DEMO_TIME` 입력, 안내 단계 선택(FULL / HARD_ONLY / NONE)
- 테스트 입력칸: 음성 대신 문장을 넣어 순위 요청을 시험(개발용)

---

## 8. 마일스톤과 완료 기준

| M | 내용 | 완료 기준 |
|---|---|---|
| **M0** | 프로젝트 생성, 접근성 서비스 등록, 오버레이 창 | 접근성 설정에서 COCO를 켜면 홈 화면 고정 좌표에 파란 점이 보이고, 점 아래 아이콘이 그대로 눌린다 |
| **M1** | `NodeFinder` + `TAP` 표현 + 스텝 엔진 + `YT_SEARCH` 스크립트 | 접근성 버튼 → (발화 없이) 목표=YT_SEARCH → 점이 유튜브 아이콘 → 검색 → 마이크 → 결과 → 영상 순서로 이동하고, 사용자의 탭에 따라 다음 단계로 넘어간다 |
| **M2** | `RankClient` + `RankPrompt` + `VoiceListener(COMMAND)` | "애들이 뉴진스 얘기하던데"라고 말하면 Logcat에 순위가 찍히고, 1등 목표로 M1 흐름이 시작된다 |
| **M3** | 정정·정지(`MONITOR`) + `MOVE` + `GAL_RECENT`·`CALL_VIDEO` 스크립트 + `DEMO_TIME` | S1 훅 시나리오가 처음부터 끝까지 동작한다 |
| **M4** | 안내 단계 설정, 목표 유지(`PARKED`), `SCROLL` 붓펜, `SET_FONT`, 데모 모드 폴백 | S2·S3·S5가 동작하고, 네트워크를 끈 상태에서도 데모 모드로 S1이 동작한다 |
| P3 | 웨이크워드(Porcupine), `LONG_PRESS`·`PINCH`·`DOUBLE_TAP` | 선택 |

각 마일스톤마다 Claude는 아래를 알려 준다.
1. 바뀐 파일 목록
2. 실행 방법
3. 완료 기준을 확인하는 손동작 순서
4. 자주 나는 오류와 해결법

---

## 9. 평가 (정확도)

- `eval/utterances.csv`: `utterance,time,expected_id` 형식, 30~50줄
- `eval/run_eval.py`: 같은 시스템 프롬프트로 API를 불러 지표를 계산한다.
  - **Top-1 적중률:** 첫 안내가 맞은 비율
  - **Top-2 적중률:** "아니" 한 번 안에 맞은 비율
  - 틀린 문장 목록도 출력한다.
- 발표와 영상에는 이 스크립트로 측정한 숫자만 쓴다.

---

## 10. 리스크와 대응

| 리스크 | 대응 |
|---|---|
| 앱 버전마다 버튼 선택자가 다름 | 데모 폰 하나에 맞춰 `uiautomator dump` 결과로 하드코딩 |
| SpeechRecognizer 재시작 시 효과음, 끊김 | MONITOR 루프 간격 조정. 문제가 크면 정정은 접근성 버튼 두 번 누르기로 대체(테스트용) |
| API 지연·실패 | 데모 모드 고정 순위 폴백 |
| 카카오톡 로그인 기기 제한 | 실폰 사용 |
| 유튜브 광고 | 촬영 전 영상 미리 선택 |
| 오버레이가 터치를 막음 | HC-1 플래그 확인. M0 완료 기준에 포함 |

---

## 11. 확장 맥락 (구현 범위 밖, 설계 참고)

- 엔진(순위 + 카탈로그 + 점)은 앱에 묶이지 않게 설계한다. 카탈로그와 스텝 스크립트는 JSON으로 분리한다.
- 확장 형태
  - **OS 내장:** 폰 제조사가 시스템 기능 전체를 카탈로그로 제공
  - **SDK:** 금융·보험사가 자기 앱 안에서 카탈로그를 제공
- 그래서 `Catalog`와 `StepScript` 로딩은 인터페이스로 분리해 둔다.
