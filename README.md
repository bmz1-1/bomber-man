# WearBomber

WearBomber is a Kotlin multi-module Android game prototype with a portrait phone host (`:phone`) and a Wear OS client (`:watch`). Both apps draw their UI and game boards directly with Android `View` and `Canvas`.

## Build

Open the project in Android Studio or build both debug APKs with:

```sh
./gradlew :phone:assembleDebug :watch:assembleDebug
```

The project uses JDK 17, Android Gradle Plugin 8.3.2, Gradle 8.5, and Android SDK 34. GitHub Actions builds both APKs and publishes `Phone-APK` and `Watch-APK` artifacts.

## Controls

- Phone lobby: open **Levels & Game Modes** to choose a mode; use **Continue Solo** to jump straight to your latest unlocked campaign level. **Solo** opens a 10-stage campaign with saved progress. Edit either player's character, open the 11x11 map builder, use **Settings** or **How to Play**, or start the Bluetooth host. The menu is compact and uses short slide/fade transitions.
- Phone game: tap or hold an on-screen D-pad direction to move repeatedly; tap **BOMB** to plant a bomb. The bomb button shows your current bombs and capacity. Characters have animated movement and health bars; blasts, pickups, keys, doors, and deaths have particle feedback. Bombs explode automatically after 3 seconds, and bombs caught in a blast trigger chain reactions. The former detonation control now shows collected power-ups and active debuff countdowns. Spleef has a wall-free arena with breakable floor. Solo stages require finding a key and reaching the unlocked door; later stages start with visible monsters, whose attacks are slower and less damaging.
- Watch game: swipe to move and tap the board to place a bomb; it explodes automatically after 3 seconds, and nearby bombs trigger chain reactions. Solo key/door campaign state mirrors the phone. Right-edge Back gestures are handled by the game during play, so moving right won't exit to the watch home screen. Characters animate while walking or being defeated, and blasts, pickups, and debris emit particles. The watch includes a Bomber Builder for outfit, accessory, health, speed, and perk choices; linked profiles sync with the phone. Tap the mode label to cycle modes; use the bottom MENU, MAP, LINK, and SET controls to return to the main menu, select a map, connect to the phone, and change graphics/touch/name settings. Watch settings persist across launches. The watch supports Spleef and Solo, shows a compact power-up/debuff inventory, and offers replay/solo options after a result. Larger boards follow the watch player through a centered 9x9 viewport.
- Debuffs: broken bricks may drop Sluggish, Dizzy, Weak Flame, Bomb Lock, Stun, Fragile, Poison, Shield Break, Dash Drain, or Curse. Temporary effects expire after 3–20 seconds depending on severity; Shield Break and Curse apply immediately.
- Power-ups: Fire Up increases blast range; Bomb Up raises bomb capacity; Dash Boots speed movement; Energy Shield absorbs a hit; Bomb Kick pushes bombs; Phase Blink teleports; Vampire Flame heals on hits; Cluster Bomb adds mini-blasts; Piercing Flame passes through bricks; Extra Heart restores health. Each has a distinct color and icon, with short or full pickup notifications.
- Bluetooth uses RFCOMM UUID `8ce255c0-200a-11e0-ac64-0800200c9a66`. Grant nearby-device permissions and pair the devices before connecting.
  
