# COCO 앱 인수인계 문서

> 작성: 2026-11-09
> 이 문서는 사람과 Claude(클로드 코드)가 함께 읽는 인수인계 문서예요. 처음 이어받는다면 위에서부터 차례로 읽어 주세요.

---

## 1. COCO가 뭐냐면

- 스마트폰을 쓰는 어르신을 위한 **작은 파란 점 조수**
- 앱 화면이 따로 없어요. **실제 홈 화면과 다른 앱 위에 파란 점 하나만** 띄워요. 점은 터치를 막지 않아요.
- 동작 원리
  1. 어르신이 말한다 (예: "리센느가 뭐야?")
  2. **AI(GPT)는 목표만 정한다** → `YT_SEARCH` + 슬롯 `{query: 리센느}`
  3. 그다음은 **하드코딩된 순서(스텝 스크립트)**를 따라 점이 다음에 누를 곳을 가리킨다
     (유튜브 아이콘 → 검색 → 자판 ㄹ ㅣ ㅅ ㅔ ㄴ ㄴ ㅡ → 검색 키 → 결과 스크롤 → 영상)
  4. 대신 눌러 주지 않는다. 어르신이 직접 누른다.
- AI가 화면을 매번 보고 판단하지 않는 이유: **느리기 때문**. 화면 내용은 AI에 보내지 않음.
- 자세한 요구사항: [`docs/COCO_PRD.md`](docs/COCO_PRD.md), 소개 페이지: [`docs/COCO_소개.html`](docs/COCO_소개.html)

---

## 2. 지금 상태

PRD는 5개 마일스톤(M0~M4)으로 나뉘어 있어요.

| 마일스톤 | 내용 | 상태 |
|---|---|---|
| **M0** | 접근성 서비스 + 터치를 막지 않는 파란 점 오버레이 | ✅ 에뮬레이터에서 확인 |
| **M1** | NodeFinder + 스텝 엔진 + `YT_SEARCH` 스크립트 | 🔶 1~3단계(아이콘→검색→입력칸) 확인, 4~5단계(결과 스크롤→영상) 미확인 |
| **M1 추가** | 자판 키 안내(어떤 검색어든 자모로 분해) | 🔶 단위 테스트 통과, **키 좌표 미측정**이라 지금은 마이크를 대신 가리킴 |
| **M2** | 음성 듣기 + GPT 순위 + 1등 목표로 안내 시작 | 🔶 코드 완료·단위 테스트 통과, **기기에서 미확인** |
| **M3** | "아니 그거 말고"(2등으로), 정지, MOVE 애니메이션, `GAL_RECENT`·`CALL_VIDEO` 스크립트, `DEMO_TIME` | ❌ 시작 안 함 |
| **M4** | 안내 단계 설정, 대기(PARKED), 붓펜 스크롤, `SET_FONT`, REJECT 흔들림 | ❌ 시작 안 함 |

단위 테스트: **15개 모두 통과** (`./gradlew :app:testDebugUnitTest`)

---

## 3. 실행 방법

1. Android Studio에서 이 폴더(`Practice`)를 열기
2. 위쪽에 **Sync Now**가 보이면 누르기
3. 기기 선택 후 **▶ Run** (에뮬레이터 `Medium Phone API 37.0` 또는 USB로 연결한 실제 폰)
4. 폰/에뮬레이터: **설정 → 접근성 → 다운로드한 앱(Downloaded apps) → COCO → 'COCO 바로가기'(COCO shortcut) 스위치 켜기 → 허용**
   - 이 앱은 접근성 버튼을 쓰기 때문에 "사용" 스위치가 따로 없고, **바로가기를 켜면 COCO도 켜져요.**
   - ⚠️ **앱을 다시 설치(Run)할 때마다 이 스위치를 껐다 켜야** 연결되는 경우가 있어요.
5. COCO 설정 화면에서 **마이크 허용하기**
6. (선택) GPT 키: `local.properties` 맨 아래 `OPENAI_API_KEY=` 뒤에 키를 따옴표 없이 붙여 넣고 다시 Run
   - 이 파일은 `.gitignore`에 들어 있어서 GitHub에 올라가지 않아요. 키를 채팅이나 코드에 붙여 넣지 마세요.
   - 키가 없으면 `assets/demo_rankings.json`에 적힌 문장만 알아들어요.

### 확인하는 법

- **글로 시험:** COCO 설정 화면 → "테스트: 말 대신 글로" → `리센느가 뭐야?` → **순위 요청하고 안내 시작** → 홈 화면 유튜브 아이콘에 점
- **AI 없이 M1만:** "유튜브 '리센느' 검색 안내 바로 시작" 버튼
- **말로 시험(실제 폰):** 화면의 사람 모양 접근성 버튼 → 말하기
- **Logcat:** Android Studio 아래 Logcat 탭에서 `tag:COCO` 검색. 주요 로그:
  - `COCO 연결됨. 런처 = …, 자판 위치표 = …, 기능 23개, 순위 = …` → 서비스 연결됨
  - `순위 "리센느가 뭐야?" → 1. YT_SEARCH 0.90 | 슬롯 {query=리센느} | 출처 demo`
  - `단계 → 2. tap_search`, `화면 상태로 완료: …`, `경로 다시 맞춤: …`, `목표 완료: …`

---

## 4. 파일 구조

```
app/src/main/
├── AndroidManifest.xml          # 접근성 서비스, 인터넷·진동·마이크 권한, queries
├── assets/
│   ├── catalog.json             # AI가 고를 수 있는 기능 22개 + OUT_OF_SCOPE, 앱별 아이콘 이름·패키지
│   ├── steps/YT_SEARCH.json     # ★ 유튜브 검색 스텝 스크립트(하드코딩된 순서)
│   ├── keyboard.json            # 시연 폰 자판 키 좌표(아직 null = 미측정)
│   ├── profile.json             # 호칭 → 실명 ("손주" → "지은")
│   └── demo_rankings.json       # 키 없을 때/실패 시 쓰는 고정 순위
├── res/xml/accessibility_service_config.xml   # 접근성 이벤트·플래그
└── java/com/example/practice/
    ├── Config.kt                # ★ 모든 조절 값(점 크기, 속도, 타이밍, 모델, 테스트 목표)
    ├── CocoAccessibilityService.kt  # 본체: 오버레이 창, 접근성 버튼, 말→순위→안내 흐름
    ├── MainActivity.kt          # COCO 설정 화면(접근성/마이크/AI 상태/테스트 입력칸)
    ├── overlay/DotOverlayView.kt    # 점 그리기(TAP, LISTENING, SCROLL). 텍스트 없음
    ├── engine/
    │   ├── StepScript.kt        # 스텝 스크립트 데이터 + 로더(인터페이스 분리)
    │   ├── NodeFinder.kt        # 화면에서 버튼 위치 찾기(viewId/글자로 빠르게)
    │   ├── GuidanceEngine.kt    # 상태 머신: 단계 진행, 경로 다시 맞춤, 자판 안내
    │   ├── Hangul.kt            # 한글 → 두벌식 키 순서, 다음 키 고르기
    │   ├── KeyboardLayout.kt    # keyboard.json 읽기
    │   └── Catalog.kt           # catalog.json 읽기 + 스크립트 없는 기능은 "앱 아이콘 가리키기" 1단계 생성
    ├── ai/
    │   ├── RankPrompt.kt        # GPT 지시문(PRD 7.6)
    │   ├── RankClient.kt        # OkHttp로 GPT 호출, 실패 시 데모 순위표
    │   └── RankResult.kt        # 응답 해석 + 데모 순위표 매칭
    ├── context/ContextBuilder.kt    # 시각·현재 앱·호칭(+정정 기록 자리)
    └── voice/VoiceListener.kt   # SpeechRecognizer로 한 문장 듣기(COMMAND)

app/src/test/…/HangulTest.kt, RankResultTest.kt   # 단위 테스트
docs/COCO_PRD.md, docs/COCO_소개.html              # 기획 문서
```

패키지 이름은 PRD의 `com.coco.app`이 아니라 `com.example.practice`예요.

---

## 5. PRD와 달라진 점 (결정 사항)

1. **검색어 입력 = 자판 키 안내** (PRD 3.4 Non-goal의 "마이크로 대체"를 뒤집음)
   - 검색어를 자모로 분해(`리센느` → ㄹ ㅣ ㅅ ㅔ ㄴ ㄴ ㅡ)하고, 키 좌표는 `keyboard.json`에 **하드코딩**
   - 입력 칸 글자를 보고 다음 키를 고름. 잘못 치면 지우기 키, 다 치면 검색 키
   - 키 좌표가 없거나 칠 수 없는 글자(쌍자음 ㄲ ㅆ 등, 영어)가 있으면 **마이크를 대신 가리킴**
   - `docs/COCO_PRD.md` 3.4절은 아직 옛 내용이니 고쳐 둘 것
2. **스텝 스크립트에 `screen_has` 추가**: 같은 id(`results`)가 홈 피드와 검색 결과에 모두 있어서, 결과 화면(`search_query`가 보일 때)만 그 단계로 봄
3. **스텝에 `gesture: "type"`, `text`, `fallback_target` 추가** (자판 안내용)
4. **target 값은 배열 허용**: 에뮬레이터(영어)와 갤럭시(한국어)를 같이 적기 위해. 예: `"desc": ["Search", "검색"]`. 적힌 조건은 모두 만족해야 매칭(AND)
5. **`descContains` 매처 추가**: 유튜브 영상 항목은 id가 없고 설명이 "… play video"로 끝남
6. **스크립트 없는 기능**: `catalog.json`의 앱 아이콘 이름으로 "앱 아이콘 가리키기" 1단계를 자동 생성(PRD 3.3)
7. **경로 다시 맞춤**: 지금 단계 버튼이 안 보이면 뒤 단계 중 화면에 보이는 가장 가까운 단계로 이동, 홈으로 돌아오면 첫 단계부터
8. **속도**: NodeFinder는 `findAccessibilityNodeInfosByViewId/ByText`로 찾고, 화면 전체 훑기는 1초에 한 번까지만(유튜브가 멈췄던 문제 수정)

---

## 6. 남은 일 (우선순위 순)

1. **시연 폰 준비**: 실제 안드로이드 폰(가능하면 갤럭시) + USB 디버깅. 이 맥(8GB)에서는 에뮬레이터가 계속 멈춤
2. **자판 키 좌표 측정** → `assets/keyboard.json`
   - 시연 폰에서 삼성 키보드를 **두벌식(쿼티)** 으로 설정
   - 유튜브 검색 칸을 눌러 자판을 띄우고 스크린샷 → 각 줄의 y, 첫 키 중심 x0, 키 간격 dx, 그리고 SPACE/BACKSPACE/ENTER 좌표를 채움
3. **선택자 다시 확인(실폰)**: `adb shell uiautomator dump` 로 유튜브 각 화면의 id/desc 확인 후 `steps/YT_SEARCH.json` 수정. `catalog.json`의 `TODO(dump)` 패키지도 `adb shell pm list packages | grep -i <이름>`으로 채우기
4. **M2 기기 확인**: 글로 시험 → 말로 시험. Logcat에서 순위가 찍히는지
5. **M1 4~5단계 확인**: 결과 화면에서 점이 위로 미끄러지는지 → 스크롤 → 첫 영상 → 진동
6. **M3**: `voice/Phrases.kt`(정정·정지 문구), VoiceListener `MONITOR` 모드, 정정 시 `lastRank`의 다음 후보로 전환(API 다시 안 부름), `MOVE` 애니메이션, `GAL_RECENT.json`·`CALL_VIDEO.json` 스크립트(전화 앱 → 연락처 "지은" → 영상통화 버튼), `DEMO_TIME`, `CorrectionLog`
7. **M4**: 안내 단계(FULL/HARD_ONLY/NONE), PARKED(다른 앱으로 가면 구석에서 흐리게 대기), 붓펜 SCROLL, `SET_FONT.json`, REJECT(범위 밖이면 좌우로 흔들고 사라짐), 설정 화면에 데모 설정

---

## 7. 알려진 문제와 팁

- **8GB 맥 + 에뮬레이터**: "isn't responding" 창이 자주 뜸 → Wait. 다른 프로그램을 닫고, 그래도 안 되면 Device Manager → ⋮ → Cold Boot Now. 결국 실폰이 답
- **재설치 후 COCO가 안 붙음**: 접근성 설정에서 COCO 바로가기를 껐다 켜기
- **유튜브는 마지막 화면에서 다시 열림**: 영상 화면에서 시작하면 점이 안 보일 수 있음. 테스트 전 유튜브를 홈으로 돌려 두거나 `adb shell am force-stop com.google.android.youtube`
- **점이 작은 아이콘을 거의 가림**(18dp): `Config.DOT_DIAMETER_DP`로 조절
- **유튜브 첫 실행 시 알림 권한 창**이 뜸 → "허용 안 함"
- 영상 선택은 지금 **첫 번째 결과**. 특정 영상으로 고정하려면 `steps/YT_SEARCH.json`의 `tap_video` → `descContains`에 제목 일부를 넣기

---

## 8. 다음에 이어받는 Claude에게

- 사용자는 **안드로이드 개발과 AI가 처음**이에요. 설명은 **한국어**로, 어려운 말은 풀어서. 답장 버튼 이름은 영어로
- 코드를 바꾸면 **어느 파일인지, 안드로이드 스튜디오에서 어떻게 실행·확인하는지**를 단계별로 알려 주기
- PRD의 **절대 원칙(HC-0~HC-6)** 을 지키기. 특히 오버레이에 글자·팝업 금지, 터치 막지 않기, 대신 누르지 않기, 화면 내용을 AI에 보내지 않기
- **마일스톤 순서대로** 하고, 하나 끝날 때마다 확인 방법을 알려 주고 멈추기
- 버튼 선택자는 **추측하지 말고** `uiautomator dump`로 확인
- 조절 값은 `Config.kt`에 모으기
