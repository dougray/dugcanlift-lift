# LIFT

A nutrition and training tracker for Android. Works offline, keeps your data on
your device, and has no accounts, ads, or analytics.

Built for [dugcanlift.com](https://www.dugcanlift.com), and matched to the macro
calculator on that site so the app and the website give the same answers.

> **Status: pre-release.** Not yet published to any app store. Usable, but
> expect rough edges and occasional breaking changes to stored data.

## What it does

### Dashboard

Opens on today: calories and macros against your goal, what you've eaten so far,
and what you trained. Below that, a week of stats and charts.

- Calories and macros remaining, with progress bars per macro
- Today's steps against a daily goal (10,000 recommended, editable), read
  live from Health Connect
- Today's training summary — exercises, sets, volume
- Last 7 days: days logged, average calories, workouts, total volume
- Line charts for calories and macros across the week
- Per-exercise progression: pick a lift, see top weight and estimated 1RM
  across your last ten sessions of it

Days you didn't log show as gaps rather than zeros, and the weekly calorie
average divides by days actually logged — not tracking shouldn't look like
eating less.

### Calculator

Mifflin-St Jeor BMR, scaled by activity level, adjusted for your goal.

- Sex, age, weight (lb), height (ft/in)
- Five activity levels, from sedentary to very active
- Lose (−500 kcal), maintain, or gain (+300 kcal)
- Selectable protein target: 0.7, 0.8, or 1.0 g per lb of bodyweight
- Selectable fat target: 20%, 25%, 30%, or 35% of calories
- Carbs fill the remainder; fiber at 14 g per 1000 kcal

Save the result as your goal and everything else tracks against it.

### Food log

- Entries grouped by meal — breakfast, lunch, dinner, snack — with per-meal
  calorie subtotals
- Meal defaults to the current time of day
- Search [Open Food Facts](https://world.openfoodfacts.org) by name
- Scan a barcode to pull nutrition straight off the packet
- One-tap re-log of anything you've eaten before
- Manual entry for anything not in the database
- Browse back through previous days

### Workout log

- Sessions contain exercises; exercises contain sets
- Equipment is tracked separately from the exercise name, so "Lat Pulldown
  (Cable)" and "Lat Pulldown (Machine)" keep separate histories
- Each set records weight, reps, RPE, time, and distance — all optional
- A **training focus** decides which of those fields you see: Bodybuilding,
  Powerlifting, CrossFit, Hyrox, Endurance, or everything. Switching focus
  never discards data; every field is always stored
- Shows what you lifted last time, right under the exercise name
- New sets prefill from the previous one
- Save any workout as a reusable routine, optionally grouped into folders
- Start a routine and its target sets are laid out ready to overwrite

Time accepts `mm:ss` or plain seconds, so a 90-second sled push and a 22-minute
row both read naturally.

## Privacy

Your goal, your food log, your workouts, and everything else you enter stay on
your device. No accounts, no analytics, no ads, no cloud sync.

The one exception is food lookup: searching or scanning sends your search term
or barcode to Open Food Facts. Nothing you have logged is ever transmitted, and
the feature is entirely optional — enter foods by hand and the app never touches
the network.

See [PRIVACY.md](PRIVACY.md) for the full detail.

## Building

Requires Android Studio and JDK 17+.

    ./gradlew installDebug

Minimum Android version: 8.0 (API 26) — raised from 7.0 for Health Connect,
whose client library requires it.

Storage is plain JSON in the app's private directory — no database, no
annotation processing. On a debug build you can inspect it:

    adb shell run-as com.dugcanlift.macrocalc ls -l files/

## Built with

- [Jetpack Compose](https://developer.android.com/compose) — UI
- [Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect)
  — read-only access to today's step count
- [Open Food Facts](https://world.openfoodfacts.org) — food and barcode data,
  an open database maintained by volunteers
- [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded)
  — barcode scanning (Apache 2.0), chosen over ML Kit so the app needs no
  Google Play Services

Charts are drawn directly on a Compose `Canvas`; there's no plotting
dependency.

## Not built yet

- Editing a logged entry (currently delete and re-add)
- A bundled offline exercise database
- Built-in split templates (PPL, upper/lower, full body)
- Export for sharing with a coach
- Saturated fat, sugars, and sodium tracking

## License

Licensed under the GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

Anyone who modifies this code and distributes it — or runs it as a network
service — must make their source available under the same terms.
