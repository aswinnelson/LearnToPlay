# Learn to Play — Pre-MVP Android Scaffold

Kotlin + Jetpack Compose starter implementing the core loop from the product doc:
**preset curriculum → quiz → score-based time bank → gated app unlock**, with
Parent Admin Mode / Child Mode separated in-app (single Android profile, no login).

## What's implemented
- Room DB: curricula, questions, quiz results, score→time rules, gated apps, admin PIN (salted hash)
- Preset curriculum + question seed (CBSE Grade 5, 2 subjects) — no photo/OCR, per pre-MVP scope
- Child Mode: home screen + 5-question quiz → score → time-bank credit
- Admin Mode: PIN gate, curriculum picker, score→time rule editor (3 bands), gated-app toggles
- Single `NavHost` switching between modes by navigation state, not OS profiles
- `AppLockAccessibilityService` + `TimeBankTrackerService` stubs for the enforcement loop
  (foreground-app detection and time-bank countdown)

## Lock overlay (now implemented)
`LockOverlayManager` draws the quiz-gate overlay via `WindowManager.addView()` with a real
`ComposeView`. The tricky part of this pattern: a WindowManager-hosted view sits outside any
Activity/Fragment, so Compose has nothing to attach its `LifecycleOwner` /
`ViewModelStoreOwner` / `SavedStateRegistryOwner` to — and throws without one.
`OverlayLifecycleOwner` supplies a minimal manual implementation of all three, created when the
overlay shows and destroyed when it hides.

Tapping "Start quiz" in the overlay (`QuizGateOverlayContent`) relaunches `MainActivity` with
`EXTRA_OPEN_QUIZ=true`; `AppNavGraph` reads that and navigates straight to the quiz screen
instead of making the child tap through Child Home again. `AppLockAccessibilityService` hops
back to `Dispatchers.Main` before touching the overlay, since `WindowManager` calls must happen
on the main thread.

**Two permissions gate all of this, and Android won't prompt for either automatically:**
"Display over other apps" (`SYSTEM_ALERT_WINDOW`) and the Accessibility Service toggle both
require the user to flip them on in Settings. `OverlayPermissions` checks both and builds the
right `Settings` intents; `AdminDashboardScreen` shows a "Finish setup" card with Grant buttons
until both are on, and re-checks on every `ON_RESUME` so it updates when the parent comes back
from Settings.

**Still worth hardening before a real pilot:**
- The overlay currently has no re-entrancy guard against extremely rapid foreground-app
  switching (e.g. task switcher spam) — fine for MVP, but add a debounce if it flickers on
  real devices.
- No visual regression testing across OEM skins (MIUI, One UI, etc. handle
  `TYPE_APPLICATION_OVERLAY` slightly differently) — test on a couple of physical devices
  before shipping.

## Other deliberately stubbed / TODO (flagged inline in code)
- Installed-app picker — **now implemented.** `InstalledAppsProvider` queries launchable apps
  via `PackageManager` (scoped through a `<queries>` manifest declaration, not the broader
  `QUERY_ALL_PACKAGES` permission — no Play Console review needed to ship). `AppPickerDialog`
  shows the list with icons and a search field; `GatedAppsScreen`'s + button opens it.
- Quiz history screen — **now implemented.** `AdminViewModel.quizHistory` combines
  `QuizRepository.observeHistory()` with the curriculum list to show readable labels instead
  of raw `curriculumId` values. `QuizHistoryScreen` lists each attempt (date, score, minutes
  earned), most recent first; the dashboard's "Quiz History" button now navigates there
  instead of doing nothing.
- `PinHasher` uses SHA-256 + salt for speed of scaffolding — swap for BCrypt/Argon2 before
  shipping a real PIN store.
- `QuizRepository.submitQuizAndAwardTime()` — **now implemented as one atomic transaction.**
  Recording the quiz result and crediting the time bank used to be two separate suspend calls
  (`submitQuiz()` then `TimeBankRepository.awardForScore()`); if the app was killed between
  them, a result could get permanently recorded with `minutesAwarded=0` even on a passing
  score, with nothing to roll it back. `db.withTransaction` now wraps the rule lookup, the
  result insert, and the time-bank credit as a single all-or-nothing unit.

## Explicit non-goals for this pre-MVP (see product doc)
- Photo/OCR syllabus capture (fast-follow)
- Multi-child profiles
- Cloud account / login (everything is local-first, anonymous)
- Bypass-proof enforcement — the gate is meant to be frictionless and rewarding, not
  un-defeatable by a technical child

## To actually build this
Open in Android Studio (Koala+), let Gradle sync, run on a device/emulator with API 26+.
You'll need to grant Accessibility Service, Usage Access, and Overlay permissions manually
on first run — these can't be requested via a normal runtime permission dialog.
