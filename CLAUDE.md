# COCO 앱 (Android, Kotlin)

어르신용 "파란 점 조수" 앱. 접근성 서비스가 실제 화면 위에 파란 점만 띄우고,
AI(GPT)는 목표만 정한 뒤 하드코딩된 스텝 스크립트를 따라 안내한다.

**작업을 시작하기 전에 반드시 읽기**
1. `HANDOFF.md` — 지금 상태, 결정 사항, 남은 일, 주의점
2. `docs/COCO_PRD.md` — 요구사항, 절대 원칙(HC-0~HC-6), 마일스톤(M0~M4)

**작업 방식**
- 사용자는 안드로이드·AI 초보. 설명은 한국어로 쉽게, 답장 버튼 이름은 영어로.
- 코드를 바꾸면 파일 경로와 안드로이드 스튜디오에서 실행·확인하는 방법을 단계별로 알려 준다.
- 마일스톤 순서대로, 하나 끝나면 확인 방법을 알려 주고 멈춘다.
- 조절 값은 `app/src/main/java/com/example/practice/Config.kt`에 모은다.
- 버튼 선택자는 추측하지 말고 `adb shell uiautomator dump`로 확인한다.

**명령**
- 단위 테스트: `./gradlew :app:testDebugUnitTest`
- 빌드: `./gradlew :app:assembleDebug` (터미널에서는 `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` 필요)
- 로그: `adb logcat -s COCO`
