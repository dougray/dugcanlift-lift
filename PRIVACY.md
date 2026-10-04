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
- Your outdoor Run/Walk/Hike history: each recording's start/end time, distance,
  elevation gain, and its full GPS route (latitude, longitude, altitude, and
  accuracy for every point recorded)

## Backup files

When you tap **Save a backup file**, the app writes one file with everything listed
above, **including your outdoor history and every GPS route in full**, to the
place you choose in Android's file picker. The app does not upload it; the file
stays wherever you put it. If you choose a folder that syncs to a cloud service,
that service receives it under its own terms, so treat the file as you would
any other file holding where you have been. **Restore from a backup file** reads a file
you pick and adds only what the phone does not already have.

A backup does not record whether an activity was exported to Health Connect, so
restoring on another phone never claims an export that phone did not make.
Health Connect's own copy (see below) is separate from the backup file.

When you save a goal from the calculator, the app keeps your sex, age and height,
and records the weight you entered as that day's bodyweight, so the calculator
opens filled in next time and your weight history has a real data point. They are
stored the same way as everything else above, on your phone, and go in a backup
file and in Send to Coach. Activity level and goal choice are used to work out the
numbers and are not kept.

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
- **No background location.** LIFT does not ask for "Allow all the time".
  A recording keeps tracking your route when your phone locks or you switch
  to another app because it runs as a foreground service, started when you
  tap Start, which Android lets use the location access you already granted
  for as long as the recording lasts.
- **Foreground service / foreground service location
  (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`)** — Android requires
  an app to run a foreground service, with a persistent notification, to keep
  receiving location updates while the screen is off or another app is open. This is what shows the
  ongoing "Recording your route" notification during a Run or Hike.
- **Notifications (`POST_NOTIFICATIONS`)** — used to show that same "Recording
  your route" notification while a recording is in progress.
- **Health Connect (steps and step history, read-only)** — used to show today's
  steps on the dashboard, and, only if you turn on **Your daily steps** in Send
  to Coach, to put daily step totals in the log you email your coach. The app
  asks for this only when you tap **Read steps from Health Connect** on the
  Steps card, after the card explains it. Steps are not stored by the app and
  are never sent anywhere else.
- **Health Connect (write: `WRITE_EXERCISE`, `WRITE_EXERCISE_ROUTE`,
  `WRITE_DISTANCE`, `WRITE_ELEVATION_GAINED`)** — when you finish a Run or
  Hike and choose to export it, the app can write that activity to Health
  Connect as an exercise session with its GPS route, distance, and elevation
  gain attached, so other apps you've granted access to (for example a coach
  reading your training data) can see it. This only happens when you tap
  Export on a finished activity — never automatically, and never for any
  other kind of data. As with step data, this stays on-device between the app
  and Health Connect; nothing is uploaded anywhere by this app.
- **Nearby devices / Bluetooth (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`; on
  Android 11 and older `BLUETOOTH`, `BLUETOOTH_ADMIN`)** — used only if you
  pair the LIFT watch app for Wear OS, from the Watch card on Home. The phone
  looks for LIFT's own watch app once, to pair, and then connects only to the
  watch you chose. What crosses that link is the day's workout (exercises,
  sets, target weights and your last numbers for each) going to the watch, and
  sessions you finished on the watch coming back into your log. It goes
  directly between your phone and your watch over an encrypted Bluetooth
  connection — no server, no account, nothing else. The scan is declared
  `neverForLocation`: it is not used to work out where you are. On Android 11
  and older, Android itself requires location permission and location services
  for any Bluetooth scan; LIFT asks for nothing new there, because route
  recording already uses that permission.

## Sending your log to a coach

Send to Coach builds a link holding the log you chose to send and opens your
own email app with it written out. You pick the recipient and you press send;
the app uploads nothing itself, and the log rides in the part of the link that
browsers never send to a web server.

The card lists what the email includes before you send it. The link carries
your name, sex, age and height when you have entered them, your training, food
totals or items, bodyweight, goal, and, for runs, walks and hikes, each activity's date, time, distance and climb
plus your personal bests. It carries **no GPS route** unless you turn on **Your
last route**, which is off until you do. With it on, the link includes the route
of your newest activity with the first and last 200 m removed, so it does not
show where you started or finished, and thinned to at most 150 points. It
carries **no steps** unless you turn on **Your daily steps**, which is also off
until you do; with it on, the link includes each day's step total from Health
Connect.

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
