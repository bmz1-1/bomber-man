# WearBomber

WearBomber is a Kotlin multi-module Android game prototype with a portrait phone host (`:phone`) and a Wear OS client (`:watch`). Both apps draw their UI and game boards directly with Android `View` and `Canvas`.

## Build

Open the project in Android Studio or build both debug APKs with:

```sh
./gradlew :phone:assembleDebug :watch:assembleDebug
```

The project uses JDK 17, Android Gradle Plugin 8.3.2, Gradle 8.5, and Android SDK 34. GitHub Actions builds both APKs and publishes `Phone-APK` and `Watch-APK` artifacts.

## Controls

- Phone lobby: choose a mode or map; edit either player's character; open the 11x11 map builder; open **Settings** for power-up names or **How to Play** for controls; start the Bluetooth host. Menu screens use short slide/fade transitions.
- Phone game: tap or hold an on-screen D-pad direction to move repeatedly; tap **BOMB** to plant a bomb. The bomb button shows your current bombs and capacity. Bomber characters have animated arms and legs while walking, with a health bar above each player. Bombs explode automatically after 3 seconds, and bombs caught in a blast trigger chain reactions. The former detonation control now shows collected power-ups and active debuff countdowns. Spleef has a wall-free arena with breakable floor; Solo pits you against monsters hidden in bricks. Eliminations show a results screen with replay and solo options.
- Watch game: swipe to move and tap the board to place a bomb; it explodes automatically after 3 seconds, and nearby bombs trigger chain reactions. The screen shows bomb usage, characters animate while walking, health bars show damage at a glance, and active debuffs show their remaining time. Bomb-lock feedback and haptics make important actions easier to notice. Tap the mode label to cycle modes; use the bottom MAP, LINK, and SET controls for map selection, phone connection, and graphics/touch/name settings. The watch supports Spleef and Solo, shows a compact power-up/debuff inventory, and offers replay/solo options after a result. Larger boards follow the watch player through a centered 9x9 viewport.
- Debuffs: broken bricks may drop Sluggish, Dizzy, Weak Flame, Bomb Lock, Stun, Fragile, Poison, Shield Break, Dash Drain, or Curse. Temporary effects expire after 3–20 seconds depending on severity; Shield Break and Curse apply immediately.
- Power-ups: Fire Up increases blast range; Bomb Up raises bomb capacity; Dash Boots speed movement; Energy Shield absorbs a hit; Bomb Kick pushes bombs; Phase Blink teleports; Vampire Flame heals on hits; Cluster Bomb adds mini-blasts; Piercing Flame passes through bricks; Extra Heart restores health. Each has a distinct color and icon, with short or full pickup notifications.
- Bluetooth uses RFCOMM UUID `8ce255c0-200a-11e0-ac64-0800200c9a66`. Grant nearby-device permissions and pair the devices before connecting.
  
