# Privacy Policy

**LIFT** (`com.dugcanlift.macrocalc`)

Last updated: 16 September 2026

## The short version

Everything you enter stays on your phone. There are no accounts, no analytics,
no advertising, and no cloud sync. Nothing leaves your device unless you choose
to send it: a food search term or barcode when you search, and your log when you
use Send to Coach (see below).

## What the app stores, and where

All of the following is written to the app's private storage on your device.
Other apps cannot read it. It is never uploaded.

- Your calculated calorie and macro goal
- Your food log: what you ate, how much, which meal, and on what date
- Your workout log: exercises, equipment, sets, weight, reps, RPE, time,
  distance, and dates
- Saved workout routines
- Your training focus preference
- Your outdoor Run/Hike history: each recording's start/end time, distance,
  elevation gain, and its full GPS route (latitude, longitude, altitude, and
  accuracy for every point recorded)

Outdoor activity data is **not** currently included when you use the app's own
backup/export feature — this is a deliberate, temporary gap (GPS traces are a
more sensitive category than food or workout logs, and inclusion needs its own
explicit decision) rather than an oversight, and will be revisited in a future
update. It is written to Health Connect (see below) independently of that
backup file.

The figures you enter into the calculator — sex, age, weight, height, activity
level — are used to compute your goal and are not retained beyond the resulting
numbers.

Your daily step goal is stored the same way as your training focus. Today's
step count itself is **not** stored by the app — it's read live from Health
Connect each time you open the dashboard and is never written anywhere,
including back to Health Connect.

## What leaves your device

**Food search and barcode scanning only.**

When you search for a food or scan a barcode, the app sends that search term or
barcode number to Open Food Facts, an open, volunteer-maintained food database:

- Name search: `https://search.openfoodfacts.org`
- Barcode lookup: `https://world.openfoodfacts.org`

What is sent: the text you typed, or the barcode you scanned.

What is **not** sent: your food log, your workouts, your goal, your weight, any
identifier for you or your device, or anything else at all.

Open Food Facts operates its own servers and has its own privacy policy, at
<https://world.openfoodfacts.org/privacy>. We have no affiliation with them and
no access to their logs.

This feature is optional. If you enter foods manually, the app makes no network
requests whatsoever.

## Permissions

- **Internet** — used solely for the food lookups described above.
- **Camera** — used solely to read a barcode when you tap Scan. No image or
  video is stored or transmitted; the camera feed is decoded on the device and
  discarded.
- **Precise location (`ACCESS_FINE_LOCATION`, plus `ACCESS_COARSE_LOCATION`
  as Android requires alongside it)** — used only while you are actively
  recording a Run or Hike, to plot your route and calculate distance and
  elevation gain. Location is never collected at any other time and never
  leaves your device.
- **Background location (`ACCESS_BACKGROUND_LOCATION`)** — optional. Lets a
  recording keep tracking your route if your phone locks or you switch to
  another app mid-run. You can decline it and still record — the recording
  just stops if you lock your phone or leave the app. Used only during an
  active recording, never otherwise.
- **Foreground service / foreground service location
  (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`)** — Android requires
  an app to run a foreground service, with a persistent notification, to keep
  receiving location updates while backgrounded. This is what shows the
  ongoing "Recording your route" notification during a Run or Hike.
- **Notifications (`POST_NOTIFICATIONS`)** — used to show that same "Recording
  your route" notification while a recording is in progress.
- **Health Connect (step count, read-only)** — used to show today's steps on
  the dashboard. Read-only for step data: it stays between Health Connect and
  the app, on your device, and is never sent anywhere.
- **Health Connect (write: `WRITE_EXERCISE`, `WRITE_EXERCISE_ROUTE`,
  `WRITE_DISTANCE`, `WRITE_ELEVATION_GAINED`)** — when you finish a Run or
  Hike and choose to export it, the app can write that activity to Health
  Connect as an exercise session with its GPS route, distance, and elevation
  gain attached, so other apps you've granted access to (for example a coach
  reading your training data) can see it. This only happens when you tap
  Export on a finished activity — never automatically, and never for any
  other kind of data. As with step data, this stays on-device between the app
  and Health Connect; nothing is uploaded anywhere by this app.

## Sending your log to a coach

Send to Coach builds a link holding the log you chose to send and opens your
own email app with it written out. You pick the recipient and you press send;
the app uploads nothing itself, and the log rides in the part of the link that
browsers never send to a web server.

The link carries your training, food totals or items, steps, bodyweight, goal,
and, for runs, walks and hikes, each activity's date, time, distance and climb
plus your personal bests. It carries **no GPS route** unless you turn on **Your
last route**, which is off until you do. With it on, the link includes the route
of your newest activity with the first and last 200 m removed, so it does not
show where you started or finished, and thinned to at most 150 points.

## Analytics, advertising, and tracking

There are none. The app contains no analytics SDK, no advertising SDK, no crash
reporting service, and no third-party tracking of any kind.

## Children

The app is not directed at children and collects nothing that would identify
anyone.

## Deleting your data

Uninstalling the app removes all of it. There is nothing held anywhere else,
so there is no account to close and no deletion request to make.

## Changes

If the app's behaviour changes in a way that affects this policy, this document
will be updated in the same commit as the change, and the date above revised.

## Contact

Questions: privacy@dugcanlift.com
