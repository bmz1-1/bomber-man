# WearBomber

WearBomber is a Kotlin multi-module Android game prototype with a portrait phone host (`:phone`) and a Wear OS client (`:watch`). Both apps draw their UI and game boards directly with Android `View` and `Canvas`.

## Build

Open the project in Android Studio or build both debug APKs with:

```sh
./gradlew :phone:assembleDebug :watch:assembleDebug
```

The project uses JDK 17, Android Gradle Plugin 8.3.2, Gradle 8.5, and Android SDK 34. GitHub Actions builds both APKs and publishes `Phone-APK` and `Watch-APK` artifacts.

## Controls

- Phone lobby: choose a mode or map; edit either player's character; open the 11x11 map builder; choose short or full power-up pickup names; start the Bluetooth host.
- Phone game: use the on-screen D-pad, **BOMB**, and **DETONATE** controls. Bombs block movement after you step away; Bomb Kick lets you shove them.
- Watch game: swipe to move, single-tap to place a bomb, double-tap to detonate. Tap the mode label to cycle modes; use the bottom MAP, LINK, and SET controls for map selection, phone connection, and graphics/touch/name settings. Bombs show a fuse and animated blast. Larger boards follow the watch player through a centered 9x9 viewport.
- Power-ups: Fire Up increases blast range; Bomb Up raises bomb capacity; Dash Boots speed movement; Energy Shield absorbs a hit; Bomb Kick pushes bombs; Phase Blink teleports; Vampire Flame heals on hits; Cluster Bomb adds mini-blasts; Piercing Flame passes through bricks; Extra Heart restores health. Each has a distinct color and icon, with short or full pickup notifications.
- Bluetooth uses RFCOMM UUID `8ce255c0-200a-11e0-ac64-0800200c9a66`. Grant nearby-device permissions and pair the devices before connecting.
  
