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
