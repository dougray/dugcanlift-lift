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

Your goal, your food log, and everything you enter stay on your device.
There are no accounts, no analytics, and no ads.

The one exception is food search: when you look up a food, your search term
is sent to [Open Food Facts](https://world.openfoodfacts.org), a free and
open food database. Nothing you have logged is ever sent anywhere, and food
search is entirely optional -- you can enter foods by hand and the app never
touches the network.

## Building

Requires Android Studio and JDK 17+.

    ./gradlew installDebug

Minimum Android version: 7.0 (API 24).

## License

Licensed under the GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

Anyone who modifies this code and distributes it — or runs it as a network
service — must make their source available under the same terms.