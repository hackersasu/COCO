# Code review with gstack `/review` — 2026-11-09

We ran [gstack](https://github.com/garrytan/gstack) `/review` (v1.98.0.0, installed as `/gstack-review`) in Claude Code on the voice, phone and Skip-ad changes before committing them. This file summarizes that run. The raw records gstack wrote are in [`2026-11-09-gstack/`](2026-11-09-gstack/):

- `reviews.jsonl`: the review result and the adversarial-pass record, with per-finding actions
- `timeline.jsonl`: start (14:17 KST) and finish (14:34 KST)

The reviewed changes landed in commit `dc271e2` ("Guide the YouTube Skip-ad button and harden voice input").

## Scope

- Base: `bce3fe0` (main on GitHub at the time)
- Reviewed: commit `20fe667` plus the uncommitted working tree, 9 files, 232 changed lines
- Gate scan: `Gate edits: none detected (0 listed, 0 read; patterns: RH-1,RH-3,RH-4,RH-12,RH-13,RH-14,RH-15,RH-16; unlisted idioms: none)`

## Reviewers

| Reviewer | How it ran | Result |
|---|---|---|
| Core checklist (critical and informational passes) | Main agent | 3 findings |
| Testing specialist | Subagent | 4 findings |
| Maintainability specialist | Subagent | 4 findings |
| Simplification specialist | Subagent | No findings |
| Red team (diff over 200 lines) | Subagent, given the earlier findings | 7 findings |
| Adversarial pass ("think like an attacker and a chaos engineer") | Subagent | 16 findings, 1 rated High |
| Codex outside review | Not run | Codex CLI not installed |

Security, performance, migration, API and design specialists were skipped because the diff did not touch those areas.

## Findings and what we did

The rows merge duplicates; "Found by" lists every reviewer that reported the same problem.

| # | Finding | Found by | Action |
|---|---|---|---|
| 1 | The Skip-ad watcher matched "Skip" text in any app for 30 s, not just YouTube (rated High) | Core, red team, adversarial | **Fixed**: `GuidanceEngine.refreshWatching` only looks while the script's app is in front |
| 2 | The 2 s fallback after a listen timeout could end a newer listening session | Core, maintainability, testing, adversarial | **Fixed**: checks it still owns the recognizer and records a timeout error |
| 3 | Errors 12/13 were handled two ways: on-device → default fallback vs "permanent, stop listening" | Maintainability, testing, red team, adversarial | **Fixed**: a fallback no longer counts as permanent, and a missing on-device pack switches to the default recognizer up front |
| 4 | Any click that overlapped the Skip button (e.g. tapping the video) ended the watch early | Red team, adversarial | **Fixed**: the watch ends by time; the dot hides when the button goes away |
| 5 | The 30 s watch default was hard-coded and unbounded | Red team, adversarial | **Fixed**: `Config.AFTER_DONE_WATCH_MS`, clamped to 1–60 s |
| 6 | The engine's state diagram didn't mention the new WATCHING phase | Maintainability | **Fixed** |
| 7 | Pressing COCO during the watch started listening, but the watcher covered or hid the listening dot | Core, maintainability, testing, adversarial | **Fixed (team decision D1)**: the button ends the watch, then listens |
| 8 | The language-pack download request could show a system window over other apps (our rule HC-0) | Red team, adversarial | **Fixed (D2)**: requested only when COCO starts |
| 9 | `CLAUDE.md` told agents to use gstack's `/browse`, which we didn't install | Red team, adversarial | **Fixed (D4)**: removed that line |
| 10 | No unit tests for `after_done` parsing or WATCHING | Testing, adversarial | **Deferred (D3)** |
| 11 | With no ad, the watcher scans YouTube about once a second for 30 s | Red team, adversarial | **Deferred (D3)** |
| 12 | Skip-ad selectors are not yet confirmed with a UI dump and are not limited to the player | Core, red team, adversarial | **Open**: needs a dump while a real ad plays |
| 13 | On-device errors other than 12/13 (5, 8, 11) never fall back | Adversarial | **Open** |
| 14 | The contact step could stall if "View contacts" completes on the recents screen | Adversarial | **Open** |

Lower-severity notes from the adversarial pass, kept for later: recognizer and timer cleanup on service shutdown, the 10 s listening cap starting before the recognizer is ready, and pinning the gstack version in `CLAUDE.md`.

## Verification

- Unit tests: `./gradlew :app:testDebugUnitTest`, 24 tests, 0 failures after the fixes
- Build: `./gradlew :app:assembleDebug` succeeded
- Not verified on a device: the emulator was out of memory during the review, so the Skip-ad dot was not seen on a live ad

## Status

gstack recorded the review as **incomplete** (`completed: false`): the on-device checks above didn't run, and we skipped the re-review pass after applying fixes to save time. Final count: 5 open or deferred issues, 0 critical (items 10–14).
