# DugCanLift Macro Calculator

A calorie and macronutrient calculator for Android, built to match the
calculator at [dugcanlift.com](https://www.dugcanlift.com).

## What it does

Enter age, sex, weight, height, activity level, and goal. Returns daily
calories plus protein, fat, carb, and fiber targets.

Calories use the Mifflin-St Jeor equation, adjusted for activity level and
goal. Protein is set at 2.0 g per kg of bodyweight, fat at 25% of calories,
carbs fill the remainder, and fiber is calculated at 14 g per 1000 kcal.

## Privacy

Everything is computed on your device. The app has no network permission,
no analytics, no ads, and no accounts. Nothing you enter leaves your phone.

## Building

Requires Android Studio and JDK 17+.

    ./gradlew installDebug

Minimum Android version: 7.0 (API 24).

## License

Licensed under the GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

Anyone who modifies this code and distributes it — or runs it as a network
service — must make their source available under the same terms.