# Pocket Plus

Pocket Plus is a standalone Android app for a controller with digital buttons. By default, hold Select and tap R1/L1 for media volume or R2/L2 for brightness. Keep both buttons held for one second to repeat the adjustment. Release either button to stop. The overlay shows a segmented meter for the level you changed, alongside the time and battery status. It closes automatically.

The app requires Android 9 or later and was tested on a Retroid Pocket 6 running Android 13. It uses Android Accessibility to detect shortcuts and the Modify system settings permission to adjust brightness. The in-app updater connects to GitHub when you check for or download an update.

Open **Quick Adjust** to change the size, slide durations, time on screen, button bindings, or repeat behavior. Tap one button in the recorder to bind a single press. To record a combo, hold the first button, press the second, and release both. To bind a rear button that does not register, first map it to an unused digital key in your handheld settings. **Settings** contains the permission controls and the Dark, Light, and Material You themes. The selected theme applies to the app and the overlay. Material You uses Android's palette on Android 12 or later.

## Set up on the Pocket 6

1. Download the APK from [GitHub Releases](https://github.com/linxlone666/Pocket-Plus/releases), install it, and open Pocket Plus.
2. Open the app's **Settings** tab, tap **Enable controller shortcuts**, and turn on **Pocket Plus** in Android Accessibility settings. If Android blocks the toggle for a sideloaded app, open **Settings → Apps → Pocket Plus**, then choose **Allow restricted settings** in the overflow menu.
3. Return to Pocket Plus and tap **Allow brightness changes** to grant the system settings permission.
4. Test the four shortcuts over an emulator. If L2/R2 only send analog input, set the handheld's L2/R2 mode to **Both** or **Digital**. Disable overlapping shortcuts in other key mappers or RetroHrai while testing.

Volume adjusts Android's shared media stream. Volume and brightness combos reserve their first button by default. Turn off **Block shortcut starter input** to let that button reach games on its own. Pocket Plus consumes the shortcut’s digital action button through release. If L2/R2 still affects a game during a shortcut, use **Digital** trigger mode on Retroid to stop separate analog trigger input. If adaptive brightness changes the level back, disable it in Display settings to use manual brightness.

To open ClusterTune's profile picker with the physical Back button, enable **Left-edge profile picker** in ClusterTune under **Settings → Quick access**. In Pocket Plus, enable **Home and Back gestures** and choose **ClusterTune profile picker** under **Button Remap → Home & Back → Back → Hold**. The shortcut needs the edge handle to be visible in the current app. Press **B** or physical **Back** to close the picker.

## Button Remap

Tap a button on the controller diagram to choose the input it sends across apps. Use **Default** to restore the output for the current layout. A custom output overrides the global A/B and X/Y swap. **Controller mode** shows the active firmware mode, and **Test gamepad** shows the inputs Android receives. L2/R2 remapping applies to digital presses. Set **Swapped face button caps** to match a physical A/B and X/Y cap swap. The diagram, test view, and button icons follow your selected console color. Use **Controller presets** to save, apply, update, rename, or delete named mappings.

## Home and Back gestures

In **Button Remap**, enable **Home and Back gestures**, then choose actions directly under **Home** or **Back** for tap, double tap, and hold. Home defaults to Home on tap, Recent apps on double tap, and a Quick actions menu on hold. In Quick actions, press A for HUD, X for Device, or Y for Tools. A, X, and Y select the three options in each folder. Device shows the current fan and performance modes. In Smart fan mode, press Start to open Retroid’s fan curve editor. Use **Controller button → Home** to assign an available button on another controller to Android Home. In Recent apps, use D-pad Left or Right to browse, A to open, Y to close the centered app, Select to clear all on the Pocket 6 in landscape, or B to return to Home. Turn off Retroid accidental Home protection on the same page if the firmware requires two presses.

## Performance overlay

Open **Performance** to enable the overlay, pick Inline, List, Full list, or Top grid, and choose a preset or select individual metrics. The floating HUD starts off after a device reboot. Use the gear beside FPS to show its recent history. The gear beside Battery controls the readings shown in the HUD and Live status. The live preview reflects the HUD settings. RAM display offers percentage, used / total GiB, and used GiB. Drag the HUD to move it or pinch it to resize. Pin it to pass touches through to your game. Use Background, HUD scale, and Metric order to change its appearance, or Reset position to return it to a corner. Record a button or combo under Controller shortcuts to toggle the overlay or cycle its layout. Battery level, battery temperature, battery power, RAM, and display refresh rate use Android's built-in readings. Battery power estimates charging or discharge at the battery from current and voltage. Battery time estimates runtime while discharging and time to full while charging. A plug replaces watts when Android reports external power without active charging. The Pocket 6 can show fan duty as a percentage, RPM when the firmware reports it, or both.

CPU and GPU load and chip temperatures work directly on the Pocket 6. Other devices may restrict access to these counters. On compatible stock firmware such as the Pocket 6, FPS reads the foreground app's frame timings through the handheld's built-in service. It needs no separate setup. A dash indicates that the selected reading is unavailable.

## Stick lighting

On the Retroid Pocket 6, open **Lighting** in the app or **Stick lighting** in Quick actions. Use the color wheel or a preset, link both sticks or choose their colors separately, and adjust brightness and power. Breathing, Rainbow, and Pulse have an effect speed slider. Changes preview live.

## Per-app profiles, display color, and keyboard

- **Perf Mode** sets performance and fan modes per app on stock Retroid Pocket 6 firmware. Add an app from the icon picker and choose its overrides. Pocket Plus restores your previous modes when you leave the app.
- **Color** adjusts display saturation and color temperature on supported stock firmware.
- **Settings** lets you enable and choose Pocket Keyboard. The keyboard supports English and Thai, touch and controller input, a numbers and symbols layer, and Shift Lock.

## Update Pocket Plus

Open **About → Check for updates** to find the latest stable GitHub release. Pocket Plus verifies the update APK and opens Android’s installer for confirmation. The first time, Android may ask you to allow installs from Pocket Plus. You can also manage this permission under **Settings → App updates**.

## Build from source

Open this folder in Android Studio, or install JDK 17 and Android SDK Platform 35 with Build Tools 34.0.0 and run the Gradle wrapper.

```sh
./gradlew :app:assembleDebug
```

On Windows, run `gradlew.bat :app:assembleDebug`. Gradle writes the debug APK to `app/build/outputs/apk/debug/app-debug.apk`.

## License

[MIT](LICENSE)
