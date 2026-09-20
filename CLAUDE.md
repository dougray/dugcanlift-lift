# LIFT — Android

Native Android build of the LIFT wellness app (Jetpack Compose). Workout and
food tracking, plus COOK meal planning, all local to the device.

## Shared code lives in dugcanlift-kit-android

The plan-link and share-link wire codecs, the ingredient parser, recipe
nutrition types, local day keys and the DUGCANLIFT palette live in
`dugcanlift-kit-android` (module `:liftcore`, package `com.dugcanlift.kit`),
not here — the same code the iOS build and the coach site's link decoder
depend on. A change there reaches every consumer, so it stays boring and
covered by the kit's own tests.

- `PlanLinkCodec` + the `Plan*` types + `PlanDecodeResult` — plan links.
- `ShareLinkCodec` + the `Share*` types — coach share links. `CoachShare.kt`
  only maps this app's stores into a `SharePayload` and calls the codec.
- `OutdoorShare` + `OutdoorShareActivity` — a link's runs, walks and hikes
  (`o` per day, all-time bests `ob`, and the trimmed last route `lr`, sent only
  when `CoachStore.sendLastRoute` is on). `CoachShare.kt` maps
  `OutdoorActivity` in; the rounding and polyline stay in the kit because every
  sender must produce the same string, checked against LIFT web's fixtures in
  `CoachShareOutdoorTest`.
- `IngredientParser`, `RecipeIngredient`, `RecipeNutrition`, `Double.trimZeros()`.
- `ShareSide` + `ShareSet.side` (kit 1.5.0) — which limb a set was performed
  with, in the set tuple's flags bits 1-2. See "Per-limb sets" below.
- `DayKey` — local `yyyy-MM-dd` day keys. `todayKey()`/`dateKey()` in
  `data/FoodEntry.kt` are one-line wrappers over `DayKey.today()`/`DayKey.make()`
  kept under their existing names so nothing else in the app had to change.
- `DclPalette` — the ARGB constants `ui/theme/Color.kt`'s `DclBg`/`DclAccent`/…
  wrap.

The app depends on it as `com.github.dougray:dugcanlift-kit-android`, pinned to an **exact
release tag** (never a branch or `SNAPSHOT`) in `app/build.gradle.kts`, resolved
through JitPack.

For local development against a kit checkout instead of the published tag, put

```
kitPath=../../dugcanlift-kit-android
```

in `android/local.properties` (gitignored, not committed) — path is relative to
this `android/` Gradle root. `settings.gradle.kts` turns that into an
`includeBuild` with a dependency substitution, so the local `:liftcore` project
wins over the JitPack artifact with no network resolution. Remove the line (or
delete the file) to go back to building against the pinned tag.

LIFT's own App Links (tap-to-open for shared plan/share links) are verified
through the site's `.well-known/assetlinks.json`, the same mechanism Coach
Android's tap-to-import depends on — that file also carries LIFT's release
signing fingerprint alongside Coach's, so a broken or missing entry for
either app shows up as autoVerify silently falling back to the
disambiguation sheet for that app specifically, not both.

## Per-limb sets

A set may name a limb. `WorkoutSet.side` is a `SetSide?`, and **null means
both** — which is what every set written before this means, and what every set
of a two-sided lift means now. Nothing ever fills it in by guessing, and it
never defaults to left. No side is ever inferred for data logged before the
feature; those sets stay both, which is honest.

- **The wire is fixed by the spec, not by this app.** SHARE-FORMAT puts the
  side in the set tuple's **flags bits 1-2** (`0` both, `1` left, `2` right),
  beside bit 0's warmup flag — no new tuple position, so a coach app that
  ignores flags still reads the weight, the reps and the volume. BACKUP-FORMAT
  spells it as a **named field**, `side: "left" | "right"`, omitted when both,
  because a backup is read by humans and by three platforms. PLAN-FORMAT is
  unchanged in v1: a coach prescribes as before and the lifter picks the side
  when logging. `CoachShareSideTest` and `PerLimbSetsTest` pin both, the second
  against `fixtures/backup-main-no-sides.json`, a file `BackupStore.build` on
  main actually wrote — do not regenerate it from this branch.
- **A both-sided set writes nothing.** No `side` key in the backup, `0` flags in
  the link, which the codec trims away. A phone with no per-limb sets therefore
  writes the same file and the same link it wrote before the feature existed.
- **Whether an exercise is logged per side is the lifter's choice**, remembered
  by `SettingsStore.logsPerSide` under the dictionary's own key
  (`name|equipment`). `PerSideLogging.looksUnilateral` only decides where the
  toggle *starts*; the moment the lifter answers, the answer is stored and the
  guess is never consulted again — including when they turn it off for
  something that looks unilateral.
- **Logging costs one extra tap.** The L/R control starts on the side with
  fewer sets for that exercise today (`PerSideLogging.defaultSide`), so it
  alternates by itself, the header shows `L 3 · R 3` (plus `· 2 both` where
  sets predate the toggle, as the web's `countsLabel` does), and the form pre-fills
  from that side's last set or, failing that, from the set just logged — the
  first side's numbers, which is what most people are about to match. With the
  preference off, the set row is exactly what it was.
- **Grouping keys on name, equipment *and* side** (`sideKey`). An exercise's
  identity — what `historyFor` and `matchKey` match on — is still name and
  equipment, because a side is a property of a set, not of the lift; it is the
  *series* drawn from those sets that are per side. L and R are never merged
  into one line, for the reason a cable pulldown is not a machine pulldown.
- **The imbalance maths is pure** (`SideBalance`, `SideBalanceTest`) **and it
  is a port of LIFT web's `lift/sides.js`, function for function**:
  `(strong − weak) / strong` on estimated 1RM, each side taken as the **mean of
  its last three sessions** — not its best, not its latest — shown only when
  both sides have `MIN_SESSIONS` (3) in the window, with the trend comparing
  that against each side's first three and needing `MIN_FOR_TREND` (4) before
  it says anything at all. Half a percentage point of movement is noise. Three
  platforms printing different percentages from one log is worse than any of
  them printing a slightly better number, so **the rule changes in `sides.js`
  first and is ported again** — it is never improved here. A rule that decides
  what a number on a card says also cannot live in a composable's state where
  nothing can reach it.
- **Tracked and shown, never targeted** — the discipline saturated fat, sugar
  and sodium follow. No threshold, no colour, no warning, no advice. A 10% gap
  is ordinary, and what a particular one means is a question for a trainer.

## Recording a route needs no background location

The manifest does **not** declare `ACCESS_BACKGROUND_LOCATION`, and nothing asks
for "Allow all the time". Play requires a declaration form and a video for that
permission and often rejects it; this app does not need it. A recording keeps
going with the screen locked or another app open because the Start tap
(`OutdoorRecordingScreen.kt`) calls `LocationTracker.start`, which starts
`LocationRecordingService` — `foregroundServiceType="location"`, passed again to
`startForeground` on API 29+ — while the app is visible. A location foreground
service started from the foreground keeps "while in use" access for as long as
it runs.

The condition is the whole rule: **start a recording only while the app is on
screen.** A start from a broadcast, a notification action, a widget or a restart
after process death would get no location, and the fix is not to declare
background location. The permission flow is fine location (coarse alone does
not unlock Start) plus `POST_NOTIFICATIONS` in one dialog, then Start.
`LocationPermissionsManifestTest` pins the manifest; the manifest is a declared
input of the unit test task, so a manifest-only change reruns it.

Checked on the `dcl_pixel` emulator (API 36): the location dialog offers only
"While using the app"; after Start, with `geo fix` points fed every few seconds,
Home then `KEYCODE_SLEEP` for 60 s, `dumpsys location` showed the GPS
registration still delivering (18 to 31 fixes while asleep) and the live
distance went from 0.06 to 0.28 mi, the route drawn unbroken.

## Releases

A pushed `v*` tag runs `.github/workflows/release.yml`. Tests and lint run on a
GitHub-hosted runner; the `sign` job then waits for approval on the `release`
environment and runs on the self-hosted signer, where the keystore lives. It
builds `:app:assembleRelease :app:bundleRelease` in one Gradle run with the one
signing config, and publishes both to the tag's GitHub Release:

- **`lift-android.apk`** — the sideload download the website links to. Keep its
  name; checked with `apksigner verify --print-certs`.
- **`lift-android.aab`** — the App Bundle for Google Play, which accepts only
  `.aab`. An AAB has a JAR signature that `apksigner` does not read, so it is
  checked with `jarsigner -verify -strict` (only exit bit 4, "self-signed", is
  allowed) and `keytool -printcert -jarfile` (exactly one signer).

Both must carry `RELEASE_CERT_SHA256` or nothing is published, and `SHA256SUMS`
lists both files. Locally, with no `keystore.properties`,
`./gradlew :app:bundleRelease` builds an unsigned bundle.

## Large screens

Layout follows the **window's width**, never the device: Material 3's classes,
compact < 600 dp, medium 600–840, expanded ≥ 840 (`ui/adaptive/WindowLayout.kt`).
`ProvideWindowLayout` measures the window once in `MainActivity` and provides
`LocalWindowWidth`; every decision is a plain function on `AdaptiveLayout`, pinned
by `WindowLayoutTest`. Put a new width rule there, not in a composable.

- **Compact is the phone app, unchanged**: tabs along the top, one column
  everywhere. Every split answers "one column" below 600 dp of *content* width, and
  the compact branches emit the same cards with the same spacers as before, so a
  phone is pixel-identical to main. Check a change there against main's
  screenshots, not "looks fine".
- **Medium and expanded**: a `NavigationRail` replaces the `TabRow`. The rail and the
  tab row are the only conditional parts of `AppTabs`' tree, so crossing 600 dp
  re-lays the open screen out rather than rebuilding it.
- **Pages measure their pane** (`MeasuredPane`), never `LocalConfiguration`: with a
  rail, the screen's width is wrong. A page caps at `MAX_CONTENT_DP` (1200) by
  widening its side gutter (`sideGutter`), which is 16 dp — the phone's — below
  that. Forms that stand alone (the calculator) cap at `READABLE_DP`; dialogs are
  already capped by `AlertDialog`.
- What goes multi-column, matching LIFT iOS: Home's cards in two masonry columns;
  Food's totals and add/edit forms beside the meal list; Train's lifting beside
  Outdoor, with Last route (map larger) beside Personal bests below and routines as
  a card grid; Cook recipes as a card grid, the plan as two days a row and the
  whole week in seven columns from 1000 dp, shopping in two columns read downwards.
  Two panes need two 320 dp panes (656 dp of content), so a tablet in portrait
  splits and an unfolded foldable in portrait (≈560 dp of content) does not.
- Recording and reviewing a route (`RouteScreenLayout`) put the square map beside
  the numbers from a 600 dp pane: in landscape a window-wide square would push the
  Finish button off the screen, and those screens do not scroll.
- Rotation, resizing and folding do not recreate the activity (`configChanges`
  includes `smallestScreenSize`); the activity is no longer portrait-locked (the
  barcode scanner still is). A theme or density change still recreates it, so the
  state someone is typing into is `rememberSaveable`: the day shown, open panels
  and editors (by id), and form fields as strings.
- Folds are not avoided: nothing reads `FoldingFeature`. Only a half-open book
  posture separates the window, and no LIFT screen is a list/detail split to move
  onto the hinge.
- Rail icons are drawn in `AdaptiveComponents.kt`; do not add material-icons for four
  glyphs. The dumbbell and pot are Coach Android's.

To check on the one phone AVD: `adb shell wm size 2560x1600 && adb shell wm density
320` (tablet landscape), `1600x2560` (portrait), `1767x2208` / `2208x1767` at 420
(foldable inner, portrait and landscape), then **always** `wm size reset` and
`wm density reset`.

## Health Connect consent

Health Connect asks for a prominent in-app disclosure before its permission
sheet, and allows its data to reach a third party only with explicit consent.

- **Nothing opens the steps permission sheet on its own.** The Steps card on
  Home explains what LIFT reads and why (`StepsAccessExplanation`) and its
  button is what asks. The first-launch auto-prompt is gone; do not bring it
  back.
- **Steps go to a coach only when `CoachStore.sendSteps` is on**, off by
  default. `CoachShare.buildSharePayload` and `weekSummary` enforce it whatever a
  caller passes, and the card does not even read step history while it is off.
- **The Coach card says what the email includes before Send**
  (`CoachShare.includedSummary`), from the same stores and choices the encoder
  reads. A new field in the payload needs a line there too.
  `CoachShareStepsTest` pins both rules.
- `PermissionsRationaleActivity` (and its Android 14 `VIEW_PERMISSION_USAGE`
  alias) opens the privacy policy the Play listing names;
  `HealthConnectRationaleManifestTest` pins the manifest side.
