# Play Console: Data safety and permission declarations (draft)

A filled-in draft of the answers Play Console asks for, written from the code as
of LIFT 1.7 (versionCode 9). Nothing here has been submitted. Every claim cites
the file it comes from; if the code changes, check the claim against it again.

Play's definitions matter for the answers below:

- **Collected** means data sent off the device by the app, to the developer or
  anyone else. Processing that stays on the device is not collection.
- **Shared** means data transferred to a third party. Play exempts transfers the
  user starts and would expect, such as sending something with another app they
  choose.

## Summary

| Question | Draft answer |
| --- | --- |
| Does the app collect or share any required user data types? | **Yes**, narrowly: a food search term or scanned barcode, sent to Open Food Facts when the user searches. Everything else stays on the device. See the note under "In-app search history". |
| Is all user data encrypted in transit? | **Yes.** The only requests the app makes are HTTPS (`data/FoodSearch.kt`, `https://search.openfoodfacts.org`, `https://world.openfoodfacts.org`). |
| Do you provide a way for users to request that their data be deleted? | Nothing is held off the device, so there is nothing to request. Uninstalling removes it all (`PRIVACY.md`, "Deleting your data"). If the form requires a mechanism, answer that data is not collected by the developer. |
| Account creation | None. No login, no accounts, no server operated by the developer. |
| Independent security review | No. |
| Children | Not directed at children (`PRIVACY.md`, "Children"). |

## What never goes to the developer

The developer operates no server that the app talks to. The only network code
is `data/FoodSearch.kt` (`HttpURLConnection`, two Open Food Facts URLs). There
is no analytics, advertising or crash-reporting SDK in `app/build.gradle.kts`
(dependencies are Compose, AndroidX, Health Connect client, ZXing Android
Embedded, and `dugcanlift-kit-android`). `android:allowBackup="false"` in
`AndroidManifest.xml` keeps Android's own Auto Backup from copying app data to
Google Drive.

## Data types, one by one

### App activity: In-app search history — shared with a third party, on use

- **What:** the text typed into food search, or the barcode number scanned.
- **Where it goes:** Open Food Facts (`data/FoodSearch.kt`: `searchByName`,
  `lookupBarcode`). Request headers carry a fixed `User-Agent`
  (`DugCanLift-MacroCalc/1.0 (https://www.dugcanlift.com)`) and nothing that
  identifies the user or device.
- **Collected / shared:** transmitted to a third party, so declare it as
  **shared** (not collected by the developer). Do not claim "processed
  ephemerally": Open Food Facts keeps its own logs under its own policy, which
  the app does not control.
- **Required or optional:** optional. Manual entry makes no request.
- **Purpose:** App functionality.
- **Uncertain:** whether a free-text food name or a product barcode counts as
  "In-app search history" or falls outside Play's user data types entirely. It
  is declared here because declaring it is the safer error.

### Location: Precise location — not collected

- **Use:** on the device only, while the user records a run, walk or hike
  (`LocationTracker.kt`, `LocationRecordingService.kt`,
  `OutdoorRecordingScreen.kt`). Points are stored in
  `outdoor_activities.json` in app-private storage (`data/OutdoorActivity.kt`).
- **Leaves the device only by user action**, none of which is collection:
  - Health Connect export, when the user taps Export on a finished activity
    (`OutdoorReviewScreen.kt`, `data/HealthConnectManager.kt`). Health Connect
    is on-device.
  - Send to Coach, only if the user turns on "Your last route", and trimmed by
    200 m at each end (`data/CoachShare.kt`, `CoachStore.sendLastRoute`). The app
    hands the text to the user's email app (see below).
  - A backup file the user saves to a location they pick (`BackupCard.kt`,
    `data/BackupStore.kt`, `outdoor[]`).
- **Answer:** not collected, not shared.

### Health and fitness — not collected

- **Health info / Fitness info:** food log, macros, goal, bodyweight, workouts,
  outdoor activities. All in app-private JSON files and SharedPreferences
  (`data/FoodRepository.kt`, `data/WorkoutRepository.kt`, `data/CoachStore.kt`,
  `data/GoalStore.kt`, `data/OutdoorActivity.kt`, `data/RecipeRepository.kt`).
- **Health Connect reads:** step counts (`READ_STEPS`, plus
  `READ_HEALTH_DATA_HISTORY` for more than 30 days), used for the dashboard and
  Send to Coach (`data/HealthConnectManager.kt`, `CoachCard.kt`). Not stored by
  the app.
- **Health Connect writes:** an exercise session with route, distance and
  elevation gain, on the user's tap (`WRITE_EXERCISE`, `WRITE_EXERCISE_ROUTE`,
  `WRITE_DISTANCE`, `WRITE_ELEVATION_GAINED`; `data/HealthConnectManager.kt`).
- **Answer:** not collected, not shared.

### Personal info — not collected

- The name and coach email entered under Send to Coach (`CoachCard.kt`), and
  the sex, age, height and bodyweight saved from the calculator
  (`MainActivity.kt`, `onSaveProfile` → `CoachStore.profile`,
  `recordBodyweight`), are stored on the device (`data/CoachStore.kt`) and go
  out only inside the email the user sends. Not collected, not shared.
- **`PRIVACY.md` disagrees with the code here.** It says the calculator's sex,
  age, weight and height "are not retained beyond the resulting numbers", but
  `onSaveProfile` stores them. Fix the policy before submitting; Play compares
  the Data safety form with the policy.

### Photos and videos — not collected

- The camera is used by ZXing Android Embedded to decode a barcode on the device
  (`FoodSearchPanel.kt`, `ScanContract`). No image is stored or sent; only the
  decoded number is used, as above.

### Everything else

Financial info, messages, contacts, calendar, files and docs, audio, web
browsing, device or other IDs, app info and performance (crash logs,
diagnostics): **not collected**. The coach link carries a random lifter id that
the app generates itself (`CoachStore.lifterId`); it is not a device identifier
and reaches only the coach the user emails.

## Send to Coach and the sharing exemption

Send to Coach builds a link and opens the user's own email app with
`Intent.ACTION_SENDTO` on a `mailto:` URI (`data/CoachShare.kt`). The user
chooses the recipient and presses send; the app uploads nothing. The log rides
in the link's fragment, which browsers never send to a server
(`PRIVACY.md`, "Sending your log to a coach"). This is a user-initiated transfer
to a recipient the user picks, which Play exempts from "shared". Contents:
training, food totals or items, steps, bodyweight, goal, profile (sex, age,
height if entered), outdoor activities and bests, and the trimmed last route
only when opted in.

## Background location declaration

Play requires a declaration form, a short video, and an in-app prominent
disclosure for `ACCESS_BACKGROUND_LOCATION` (declared in `AndroidManifest.xml`).

**Draft declaration text** (the "core functionality" field):

> LIFT records the GPS route of a run, walk or hike that the user starts. Location
> is used only between the user tapping Start and tapping Finish. Background
> location keeps that recording going when the phone locks or the user switches
> to another app mid-activity, which is how runners carry a phone; without it the
> route stops at the moment the screen turns off. A foreground service with an
> ongoing "Recording your route" notification runs for the whole recording. Location is
> stored on the device, is never sent to the developer, and is never collected
> when no recording is in progress. Background location is optional: a user who
> declines can still record with the app open.

The notification is `LocationRecordingService.kt`, a
`foregroundServiceType="location"` service in `AndroidManifest.xml`.

**Video:** show starting a run, the disclosure card, granting "Allow all the
time" in Settings, locking the phone, the ongoing notification, and the finished
route. Not recorded yet.

**Prominent disclosure — likely a gap.** The in-app prompt
(`OutdoorRecordingScreen.kt`, `showBackgroundPrompt`) reads "To keep recording
when your phone locks or you switch apps, allow ... for location in Settings."
Play's policy asks the disclosure to say the app *collects location data*, what
for, and that it happens *when the app is closed or not in use*. The current
wording may be rejected; suggested wording:

> LIFT collects location data to record your run, walk or hike route even when
> the app is closed or not in use, for as long as a recording is running. It
> stays on your phone.

Changing it is a UI change and has not been made here.

## Other declarations Play Console will ask for

- **Foreground service type `location`** (`FOREGROUND_SERVICE_LOCATION`): a
  declaration of the user-visible task (recording a route) and a video.
- **Health Connect**: the health apps declaration and the Health Connect
  permissions form, justifying each of the six health permissions above. The
  rationale screen Health Connect requires exists (`PermissionsRationaleActivity`,
  linking to `https://www.dugcanlift.com/app/privacy/`).
- **Health apps category** declaration (fitness and nutrition tracking).
- **Privacy policy URL**: `https://www.dugcanlift.com/app/privacy/`
  (`PermissionsRationaleActivity.PRIVACY_POLICY_URL`). Confirm the live page
  matches `PRIVACY.md` as of this change.
- **Camera**: no special declaration; covered by the data types above.

## Still missing before a submission

- **A Play Console developer account** (one-time fee, identity verification) and
  the app created in it.
- **An Android App Bundle.** `release.yml` builds and publishes an APK
  (`./gradlew :app:assembleRelease`, `lift-android.apk`); Play requires an AAB
  (`bundleRelease`) for new apps.
- **Play App Signing enrolment**, and a decision about the upload key versus the
  existing release key, whose fingerprint `release.yml` and the site's
  `.well-known/assetlinks.json` both pin. Enrolling changes the signing
  certificate Play distributes with unless the existing key is uploaded, and the
  App Links entry must carry whichever certificate Play signs with.
- **Store listing assets** Play requires beyond what `fastlane/metadata` has: a
  1024x500 feature graphic, and a check that the four phone screenshots are
  current (they predate Cook, Outdoor and light mode).
- **Content rating questionnaire**, **target audience**, **ads declaration**
  (no ads), and **app access** (no login needed).
- **Closed testing** requirement for new personal developer accounts (a set
  number of testers for a set period before production access).
- The background location video, the prominent disclosure wording above, and
  the Health Connect declarations.
