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
