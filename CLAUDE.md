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
  because a backup is read by humans and by three platforms. A coach's plan can
  carry sides too -- see "Per-side prescriptions" below. `CoachShareSideTest`
  and `PerLimbSetsTest` pin both, the second
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

## Per-side prescriptions

A coach's plan can say an exercise is done **each side** (`b: 1`: every
prescribed set on both sides, so "3 x 8 each side" is six sets) and that a set
is for **one side** (the set tuple's sixth position, SHARE-FORMAT's flags bits
1-2). PLAN-FORMAT "Sides"; spec `dugcanlift-wip-backups/coach-per-side-prescriptions-spec.md`.
The kit decodes both (`PlanWorkoutExercise.eachSide`, `PlanSet.side`, kit 1.6.0).
LIFT web's `lift/sides.js` "a coach's prescription" block is the rule, ported
function for function into `PerSideLogging` -- change it there first.

- **Old builds degrade correctly, and this is checked.** `PlanSidesOldDecoderTest`
  reads `fixtures/web-plan-per-side.txt` (Coach web's own encoder wrote it; never
  regenerate it) the way LIFT 1.11 does: accepted, both additions ignored,
  every exercise two-sided with the right count. It was first run against main
  itself, unmodified; the old path is now frozen in the test.
- **Stored set by set whenever the targets cannot say it.** `RoutineExercise`
  keeps its flattened targets as always, and gains `prescribed` (the sets one by
  one) and `eachSide` beside them -- see "A prescription is not a count and a
  set" below, which is the rule for when. Starting the session moves `prescribed`
  onto the `LoggedExercise` *when it says something about sides*, so the header's
  targets survive a relaunch and a backup (`prescribed`, `eachSide: true`, LIFT
  web's spellings, both omitted when absent); a ramp has nothing left to say once
  its sets are rows. JSON files, no schema: an older file reads with neither.
- **Accepting an each-side exercise turns "Left and right separately" on** for
  that lift. A named set alone never touches the preference: on a lift not
  logged per side the set form offers Both / L / R until that set is logged.
- **Sided sets are not pre-filled.** A pre-filled left set is a claim nobody
  made. Two-sided sets of a lift that is not each side are laid out as before,
  each with its own numbers. The form starts on the side the next unfilled
  prescribed set names and prefills from that set (switching side refills it
  until something is typed); past the prescription it is the side that is behind.
- **The header counts against the target**: `L 0/3 · R 0/3`, `R 0/1 · 2/2 both`,
  and `L 4/3` when over -- never capped. A "Coach:" line lists the prescribed
  sets, because the sided ones are not rows until they are done.
- Progression, the imbalance figure and volume read the log, never the plan.

## A prescription is not a count and a set

`RoutineExercise` has always been one set's worth of targets plus `targetSets`,
which says "3 x 8 @ 60 lb" and nothing else. PLAN-FORMAT lists a prescription's
sets individually for exactly that reason -- "Coaches ramp, and a
count-and-tuple shape cannot say 225/225/245 without special cases" -- so
reducing them to the most common value of each field, as `PlanImporter` did
until `plan-set-fidelity`, silently prescribed something the coach never wrote:
a ramp of 60/60/70 arrived as three 60s, and `[null, 5]` beside `[225, 5]`
arrived as two sets at 225, inventing the weight the coach left to the lifter.

**`Prescription` is the rule and it is not about sides.** `needsSetBySet` keeps
the coach's sets on `RoutineExercise.prescribed` whenever the targets would lose
something: sets that differ from each other, any set naming a side, or `b: 1`.
The targets are still filled in beside them -- a routine saved from a workout, a
starter split and every routine an older build wrote are nothing but targets --
and a prescription the targets say in full (three identical sets) keeps
`prescribed` null and writes byte for byte the object main wrote.
`Routine.toSession` lays out whatever was kept, each set with its own weight,
reps, RPE, duration, distance and side. `PlanSetFidelityTest` pins all of it,
including a `routines.json` from before any of this.

**Weights never convert anywhere on this path.** PLAN-FORMAT's set tuple is
pounds, `WorkoutSet`/`PrescribedSet` are pounds, and the app shows pounds --
there is no `WeightUnit` on Android, deliberately, the way there is no
kilometres toggle for Outdoor.

**`WorkoutSession.toRoutine` -- "Save as routine" -- still flattens, and that is
a separate path.** It is a lifter's own log being turned into a template, and it
synthesises on purpose (most common reps, *heaviest* weight). Nothing a coach
sent goes through it.

## Road Food

Macro-friendly picks at fast-food chains and gas stations, ranked against what
is left of today, reached from Food ("Road Food"). Spec:
`dugcanlift-wip-backups/lift-road-food-spec.md`.

- **The ranking is a port of LIFT web's `lift/road-food.js`, function for
  function** (`data/RoadFood.kt`, tested by `RoadFoodTest`, itself a port of
  `road-food.test.mjs`): fits at or under what is left; a separate "A little
  over" group up to 10% over (compared as `kcal x 10 <= left x 11`); nothing
  beyond; protein per 100 kcal, then lower sodium, then name. No goal: protein
  per 100 kcal alone, and the screen says so. Unknown protein or sodium ranks
  after known, never as zero. As with `SideBalance`, **the rule changes in
  `road-food.js` first and is ported again**.
- **"What is left" is `remainingFor` (`data/Remaining.kt`)**, the one function
  Home's and Food's "kcal left" read too. Do not compute it a second time.
- **Log it** writes an ordinary `FoodEntry` for today in the chosen meal, so it
  reaches the day's totals and Send to Coach like any food. Saturated fat, sugar
  and sodium travel where the item lists them and stay null where it does not.
- Tracked and shown, never targeted: sodium, sugar and saturated fat are plain
  text on each row. No location of any kind and no new permissions: the person
  picks the chain. Recent chains are `SettingsStore.roadFoodRecent`, not backed up.

**The data, and how the real file drops in.** One file,
`dugcanlift-kit/data/road-food.json`, the same on every platform. It goes into
this app **unchanged** as `app/src/main/assets/road-food.json` -- that one file
is the whole drop-in; no code changes. Until it is there:

- `app/src/debug/assets/road-food-sample.json` is a fixture with obviously fake
  names ("Sample Burger Co"), copied from LIFT web's
  `lift/fixtures/road-food-sample.json`. It is in the **debug** source set only,
  so no release can contain it. `RoadFoodStore` reads `road-food.json` first and
  falls back to the sample only when that is absent, so once the real file is in
  `main` a debug build shows it too.
- A release without the real file shows **no Road Food button** at all
  (`RoadFoodStore.isBundled`), rather than a screen with nothing in it.
- `RoadFoodTest` fails if the sample ever appears under `src/main/assets`, and
  once the real file is there it parses it and fails on any Sample/Example/
  Fictional/Placeholder/Test name. The Road Food assets are declared inputs of
  the unit-test task, so dropping the file in reruns it.
- Refreshing the numbers is the same drop-in: replace the file, release as usual.

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

## Uploading to Google Play

```bash
gh release download v1.9 -p lift-android.aab
fastlane android upload aab:lift-android.aab            # internal testing, as a draft
fastlane android upload aab:lift-android.aab track:alpha # closed testing
```

`fastlane/Fastfile`'s one lane runs `supply` with the signed bundle from the
tag's GitHub Release (it never builds or signs) and everything under
`fastlane/metadata/android`: title, descriptions, graphics, screenshots, and
`changelogs/<versionCode>.txt`, which it refuses to go without. Options:
`track:internal|alpha|beta` (alpha is closed testing), `status:draft|completed`,
`validate:true` to have Play check the upload without committing it.
**Production is refused**; promote a tested release in Play Console. A release
arrives as a draft and is rolled out by hand there.

- **The key is never in this repo.** `fastlane/Appfile` reads the service
  account's JSON key from `PLAY_JSON_KEY_PATH`, falling back to
  `~/keystores/play-publisher.json`; `*.json` under `fastlane/` is gitignored.
  The service account needs release permission for this app under Play Console's
  Users and permissions.
- **The very first upload of a brand-new app goes through the browser.** The
  Publishing API refuses an app that has never had a release, so the first AAB is
  uploaded by hand in Play Console. Then `fastlane android upload listing_only:true`
  sends the text and graphics without a bundle (that versionCode is already
  taken), and every later version goes up with its `aab:`. Until the first
  release is published, Play also accepts only `status:draft`, which is why
  draft is the default.
- **Store screenshots**: phone shots are 1080×1920, no alpha, captioned and
  framed like the rest of the set, at most eight, numbered in carousel order with
  no gaps; renumber the set when inserting. Tablet shots are raw captures, 7-inch
  at `wm size 1080x1920` / `wm density 216` and 10-inch at `2560x1440` / `320`.

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
  whole week in seven columns from 1000 dp, shopping in two columns read downwards;
  Road Food's chains as a card grid and a chain's ranked list beside its ordering rules.
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
