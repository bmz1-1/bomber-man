# WearBomber

WearBomber is a Kotlin multi-module Android game prototype with a portrait phone host (`:phone`) and a Wear OS client (`:watch`). Both apps draw their UI and game boards directly with Android `View` and `Canvas`.

## Build

Open the project in Android Studio or build both debug APKs with:

```sh
./gradlew :phone:assembleDebug :watch:assembleDebug
```

The project uses JDK 17, Android Gradle Plugin 8.3.2, Gradle 8.5, and Android SDK 34. GitHub Actions builds both APKs and publishes `Phone-APK` and `Watch-APK` artifacts.

## Controls

- Phone lobby: choose a mode or map; edit either player's character; open the 11x11 map builder; start the Bluetooth host.
- Phone game: use the on-screen D-pad, **BOMB**, and **DETONATE** controls.
- Watch game: swipe to move, single-tap to place a bomb, double-tap to detonate. Larger boards follow the watch player through a centered 9x9 viewport.
- Bluetooth uses RFCOMM UUID `8ce255c0-200a-11e0-ac64-0800200c9a66`. Grant nearby-device permissions and pair the devices before connecting.
