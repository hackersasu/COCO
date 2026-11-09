# COCO — You tap. COCO points the way.

**COCO is a small blue dot that helps older adults use their own smartphone, in their own words, with their own hands.**

A grandmother says *"I miss my grandkid."* COCO doesn't open a chat window, doesn't take over the phone, and doesn't ask her to learn anything new. A blue dot appears on the screen she already knows and points at the next thing to tap: **Phone → View contacts → James (Grandchild) → Video call.** She makes the call herself.

---

## Why older adults need this

### 1. Asking for help is hard
Every "how do I…?" means calling a busy son, waiting for a grandchild's visit, or asking a stranger at the store. Asking for the same thing a second or third time feels like being a burden, so many people simply stop asking and stop using the feature. At 11 p.m., when they miss their family most, there is often nobody to ask at all.

**COCO is always there, and it never sighs.** You can ask the same thing every day.

### 2. Older adults speak in feelings, not commands
People don't say "open the dialer, select contact, start video call." They say *"I miss my grandkid," "I want to hear his voice,"* or *"What is this BTS the kids keep talking about?"* Voice assistants expect commands. Menus expect you to already know where things are.

**COCO turns everyday words into the right phone action.** An AI ranks what the person most likely wants: "miss / see" leans toward a video call, "hear the voice" toward a voice call, a question about something unknown toward a YouTube search.

### 3. Doing it *for* them takes away their agency
When family members take the phone and "just do it," or a remote-control app taps for them, the task gets done but the person learns nothing. Each time, they depend a little more on others. Independence in small daily things matters to dignity.

**COCO never taps for you.** It only points. The person's own finger makes every tap, so every success is their own. COCO is built to be needed less over time.

### 4. A new app is the problem, not the solution
Simplified launchers, chatbots and tutorials all add *another* interface to learn, and they don't match the real apps a person uses with family and friends.

**COCO has no interface of its own.** No text, no pop-ups, no new screens. It is just a dot on top of the real Phone, Photos and YouTube apps. What the person practices with COCO is exactly what they'll do without it.

### 5. Mistakes should be cheap
If COCO guesses wrong, the person says *"no, not that"* (or presses the button twice) and the dot moves to the next most likely goal, without starting over or explaining again.

### 6. Their screen is their business
Only the spoken sentence and the name of the app in front go to the AI. Screen contents stay on the phone, and password fields are never read.

---

## What COCO does today

| They say | The dot guides them through | Status |
|---|---|---|
| "I miss my grandkid" | Phone → View contacts → James (Grandchild) → **Video call** | Verified on the Android emulator |
| "I want to hear my grandkid's voice" | Same path, ending on **Call** | AI ranking verified |
| "I want to see my grandchild's photos" | Photos → scrolls to → **the grandchild's photo** | Verified on the emulator |
| "What is BTS?" | YouTube → Search → types **b-t-s** key by key on the keyboard → results → video | Verified up to the search results. After the video opens, COCO watches 30 s for the **Skip ad** button (not yet tested on a live ad) |

COCO's AI can choose from 22 everyday phone tasks (calls, messages, photos, camera, maps, YouTube, text size, Wi-Fi and more). The four tasks above have full step-by-step guidance. The others point to the right app.

## How it works

1. **Call COCO.** Press the COCO accessibility button and speak.
2. **AI picks the goal.** GPT-4o-mini ranks the 22 tasks using the sentence, the time of day, the app in front, nicknames (`grandkid` → `James (Grandchild)`) and past corrections. COCO goes straight to the top choice and doesn't ask "did you mean…?".
3. **Scripted steps guide the taps.** Each goal has a step script (JSON). COCO finds the next button in the phone's accessibility tree and puts the dot on it. After the first answer, guidance needs no further AI calls, so it is instant and works the same every time.
4. **Correct in one phrase.** "No, not that" moves to the next-ranked goal, and the dot glides there from wherever the person is.

## Built with

- **Kotlin / Android**: `AccessibilityService` with a touch-through overlay (the dot never blocks the user's taps)
- **OpenAI GPT-4o-mini** for goal ranking (JSON mode, 6 s timeout), with an offline demo table as a fallback
- **Android SpeechRecognizer** for voice
- **Claude Code** for development, and **[gstack](https://github.com/garrytan/gstack) `/review`** for code review. Six reviewers ran: 9 issues were fixed and 5 are tracked. See the [review report](docs/reviews/2026-11-09-gstack-review.md).

## Try it

1. Open the project in Android Studio and add `OPENAI_API_KEY=<your key>` to `local.properties` (git-ignored).
2. Run `app` on an Android device or emulator (tested on the Pixel emulator, API 37).
3. Go to **Settings → Accessibility → COCO** and turn on **COCO shortcut**.
4. Press the COCO button and speak. Without a microphone, you can send the sentence as text:
   ```bash
   adb shell am broadcast -a com.example.practice.DEBUG_SAY --es text "I miss my grandkid"
   ```

Unit tests: `./gradlew :app:testDebugUnitTest` (24 tests). For the photo demo, `scripts/load_demo_photos.sh` fills the gallery.

## Known limitations

- **Voice recognition is unreliable on the emulator, so the recorded demos send the sentence with the command above.** Everything after speech recognition is the same.
- **Step scripts match the Google Phone, Photos and YouTube apps on a Pixel.** Other brands' apps need their own scripts.
- **English speech for now.** Korean is already in the AI prompt and the correction phrases.

## What's next

- **Fading guidance.** The first time, the dot shows every step. Later it shows only the hard steps. Eventually it shows none: the person has learned the task.
- **More tasks with full guidance:** text size, KakaoTalk messages and photos.
- **Korean voice** and Samsung Galaxy apps.
- **Waiting through interruptions.** If a call comes in mid-task, the dot waits and resumes afterwards.


## Team

- **Seonyeong** (@IamSeonyeong)
- **Hyunseo** (@hackersasu)
