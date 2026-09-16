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
- Send to Coach, save or restore a backup file, and choose System, Light or
  Dark appearance (see below)

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
- Fiber tracked alongside protein, fat and carbs
- Browse back through previous days

### Cook

- **Recipes** — write one, or paste a recipe's text (a blog post or a social
  video's caption) and edit the split into ingredients and method
- A recipe's macros per serving, with labelled fields, and the finished dish's
  weight in grams or ounces so a portion can be logged by weight
- **Plan** — put recipes on the days and meals of the week, then log a planned
  meal to the food log in one tap
- **Shopping** — the week's ingredients added up into one list, with ticks

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
- An exercise library of 873 movements, bundled, so it works offline and
  names match what a coach prescribes
- Save any workout as a reusable routine, optionally grouped into folders
- Starter routines to add in one tap: Push, Pull, Legs, Upper, Lower, Full Body,
  Mobility, Active Rest, Short Run, and Long Run
- Start a routine and its target sets are laid out ready to overwrite
- Sessions a coach schedules arrive from a plan link and can be started from
  Train

Time accepts `mm:ss` or plain seconds, so a 90-second sled push and a 22-minute
row both read naturally.

### Outdoor

- Record a **Run**, **Walk**, or **Hike** with GPS: time, distance, pace, elevation
  gain, and the route, drawn as you go
- Keeps recording with the screen off or another app open, with an ongoing
  notification while it does
- Review a finished activity, and export it to Health Connect with its route
- Under Outdoor: your **last route** and **personal bests** per activity type —
  farthest, longest, and fastest pace (only from activities of 1 km or more, so
  a burst of GPS drift can't set a record)

### Coach

- **Send to Coach** writes an email to your coach with your log in a link: pick
  4, 8, or 12 weeks or 6 months, daily food totals or every item, and whether
  to include **your last route**. The route is off until you turn it on, and
  goes with its first and last 200 m cut off so it never shows where you start
- A coach's plan link (training sessions and meals) opens in the app for you to
  preview, then accept or decline

### Your data

- Save a backup file and restore it on this phone or another; restoring only
  adds what the device doesn't already have. The file format is shared with LIFT
  for iOS and the browser version
- Appearance: System (the default), Light, or Dark

## Privacy

Your goal, your food log, your workouts, and everything else you enter stay on
your device. No accounts, no analytics, no ads, no cloud sync.

Two things leave the phone, both only when you use them:

- **Food lookup** sends your search term or barcode to Open Food Facts. Enter
  foods by hand and the app never touches the network.
- **Send to Coach** puts your log in a link and opens your own email app to send
  it. The app uploads nothing itself. GPS routes are left out unless you turn on
  your last route, and that one is trimmed at both ends.

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
  — reads your step count; writes an outdoor activity and its route only when
  you tap Export
- [dugcanlift-kit-android](https://github.com/dougray/dugcanlift-kit-android) —
  the share-link and plan-link formats, ingredient parsing, and the brand
  palette, shared with LIFT Coach for Android
- [Open Food Facts](https://world.openfoodfacts.org) — food and barcode data,
  an open database maintained by volunteers
- [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded)
  — barcode scanning (Apache 2.0), chosen over ML Kit so the app needs no
  Google Play Services

Charts and routes are drawn directly on a Compose `Canvas`; there's no plotting
or maps dependency.

## Not built yet

- Editing a logged entry (currently delete and re-add)
- Saturated fat, sugars, and sodium tracking

## License

Licensed under the GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

Anyone who modifies this code and distributes it — or runs it as a network
service — must make their source available under the same terms.
