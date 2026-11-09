# COCO — a blue-dot helper for older smartphone users

COCO lets an older person say what they want in their own words, then shows them where to tap with **a single blue dot on top of the real apps**. There is no chat screen and no new interface to learn. The dot moves across the home screen and the phone's own apps, step by step, until the task is done.

> "I miss my grandkid" → the dot goes to **Phone** → **View contacts** → **James (Grandchild)** → **Video call**

## How it works

1. **Ask.** The user presses the COCO accessibility button and speaks, for example "I miss my grandkid" or "What is BTS?".
2. **AI picks the goal.** GPT-4o-mini ranks what the person most likely wants from a catalog of 22 phone tasks, plus "out of scope". It uses the time of day, the app in front, nicknames (`grandkid` → `James (Grandchild)`) and past corrections. If "see" is in the sentence, a video call ranks higher; if "hear the voice" is, a voice call does.
3. **Hard-coded steps guide the taps.** The top goal's step script (JSON) says which button comes next. COCO finds it in the accessibility tree and puts the blue dot on it. When the user taps, COCO moves to the next step. The AI is not called again for each screen, which keeps guidance instant.
4. **Correct without starting over.** Saying "no, not that", or pressing the button twice, moves to the next-ranked goal without another AI call.

### Ground rules

- COCO **never taps for the user**: no `performAction` or `dispatchGesture`. It only points.
- **No pop-ups over other apps.** The dot is the whole interface.
- **Screen content stays on the phone.** Only the spoken sentence and the name of the app in front are sent to the AI. Password fields are never read.

## What works today

| Say | COCO guides | Status |
|---|---|---|
| "I miss my grandkid" | Phone → View contacts → James (Grandchild) → Video call | Verified on the emulator |
| "I want to hear my grandkid's voice" | Same path, ending on Call | GPT ranking verified; same path as video call |
| "I want to see my grandchild's photos" | Google Photos → the grandchild photo (scrolls to it when the gallery is long) | Verified on the emulator |
| "What is BTS?" | YouTube → Search → types **b-t-s** key by key on the keyboard → results → video | Verified up to the search results. After the video opens, COCO watches 30 s for the **Skip ad** button (not yet tested on a live ad) |

The other catalog tasks (messages, camera, maps, settings and so on) currently open the right app. Their full step scripts are next.

## Built with

- **Kotlin / Android**: `AccessibilityService` with a `TYPE_ACCESSIBILITY_OVERLAY` dot, minSdk 24, targetSdk 37
- **OpenAI GPT-4o-mini** (JSON mode, 6 s timeout), with a built-in demo ranking table as an offline fallback
- **Android SpeechRecognizer**: on-device first, falling back to the default recognizer
- **Claude Code** for development, and **[gstack](https://github.com/garrytan/gstack) `/review`** to review the voice and Skip-ad changes before they landed. Six reviewers ran, 9 issues were fixed and 5 are tracked: see the [review report](docs/reviews/2026-11-09-gstack-review.md).

## Project layout

```
app/src/main/java/com/example/practice/
  CocoAccessibilityService.kt   button → listen → rank → guide
  engine/                       step engine, screen finder, keyboard and Hangul keystrokes
  ai/                           GPT prompt, ranking client, demo fallback table
  voice/                        speech recognizer and correction phrases
  overlay/DotOverlayView.kt     the blue dot (tap, scroll, listening and reject animations)
  Config.kt                     all tunable values
app/src/main/assets/
  catalog.json                  the 22 tasks the AI can choose from
  steps/*.json                  step scripts: CALL_VIDEO, CALL_VOICE, GAL_RECENT, YT_SEARCH
  profile.json                  nicknames (grandkid → James (Grandchild))
docs/COCO_PRD.md                product spec (Korean)
docs/reviews/                   gstack review report and raw records
scripts/load_demo_photos.sh     fills the device gallery for the photo demo
```

## Run it

1. Open the project in Android Studio and add your key to `local.properties` (this file is git-ignored):
   ```
   OPENAI_API_KEY=sk-...
   ```
2. Run the `app` configuration on a device or emulator. Tested on the Pixel emulator, API 37.
3. On the device, go to **Settings → Accessibility → COCO** and turn on **COCO shortcut**.
4. Press the floating COCO button and speak.

Without a microphone, you can type the sentence:

```bash
adb shell am broadcast -a com.example.practice.DEBUG_SAY --es text "I miss my grandkid"
```

For the photo demo, put photos named `1` to `8` in a folder and run `./scripts/load_demo_photos.sh <folder>`. Photo 8 becomes the grandchild photo.

Unit tests: `./gradlew :app:testDebugUnitTest` (24 tests).

## Known limitations

- **Voice on the emulator is unreliable.** The emulator's recognizers often return no text, so the recorded demos used the `DEBUG_SAY` command above in place of speech. The rest of the path is the same.
- **The step scripts match the Google Phone, Google Photos and YouTube apps on the Pixel emulator.** Other phones, such as Samsung's apps, need their own button IDs.
- **Not yet checked on a live ad:** the Skip-ad button IDs.
- **English only for now.** The speech language is set to `en-US` in `Config.kt`; Korean phrases are already in the AI prompt and the correction list.
