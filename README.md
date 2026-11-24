# PocketHub Launcher

**A dual-screen home launcher made for the AYANEO Pocket DS.**

The stock AYANEO Home treats the Pocket DS like an ordinary phone with a second screen attached.
PocketHub Launcher is built around the two screens from the start: each display gets its own
desktop, its own wallpaper and its own settings, apps can be launched on (or moved to) either screen,
and the small bottom screen gets gestures for closing, minimizing and sending apps to the top.
It is fast, has no ads, no tracking, no network access, and no external libraries.

> Version **1.0** · Android 13 · root (Magisk) required · English, Русский, Deutsch, Español,
> Français, Italiano, Nederlands, 中文

Companion app: [PocketHub](https://github.com/Emile86/PocketHub) — control hub and overlay for the
same device. The two apps are independent; you can use either one alone.

## Why
- **Two screens, two home screens.** The top (1080×1920, landscape) and bottom displays each run
  their own desktop with independent pages, grid, wallpaper and app list.
- **Put apps where you want them.** Launch any app on the top or bottom screen, or move an app
  that is already running to the other screen in one tap.
- **Control the bottom screen like a handheld, not a phone.** Gestures and a hold-menu let you
  close an app, minimize it or throw it to the top screen.
- **Made for a controller.** Everything works with the D-pad and face buttons.
- **Looks the part.** Animated and still retro wallpapers drawn by the launcher itself, plus your
  own images, GIFs and videos.

## Features

### Desktops and icons
- Several desktops (pages) per screen with swipe paging and page dots; choose the main desktop.
- Drag icons freely; drop onto another icon to make a folder, drag to the screen edge to change page.
- Animated folders that open from their icon; drag apps back out of a folder.
- A "✕ Remove from screen" target while dragging (the app stays installed).
- Round or square icons and folders, drawn by the launcher (no system shape overlays needed).
- Long-press an icon: launch on top / bottom screen, move to the other screen, remove, app info,
  uninstall.
- Long-press empty space: new folder, add / remove desktop, make main desktop, lock layout, settings.
- Layout lock to prevent accidental changes.

### All-apps list
- Swipe up to open the app list, with search; drag apps from it straight onto the desktop.
- Per screen: number of columns, background opacity (fully transparent to opaque) and an optional
  search bar.
- Each screen can show the full app list instead of a desktop, scrolling vertically or horizontally.

### Wallpapers
- Animated scenes: Synthwave, Retro waves, Arcade, Pixel city, Aurora, Constellation, Bokeh.
- Still retro wallpapers: Wire horizon (default top), Pixel dusk (default bottom), Gamepads,
  Pixel castle, Memphis.
- Your own image, animated GIF / WebP, or MP4 video (muted, looping), or the system wallpaper.
- The top wallpaper is also applied as the system and lock screen wallpaper, so Recents and the lock
  screen match. Animated wallpapers pause when the screen isn't visible.

### Bottom screen gestures
- Top handle: hold → three dots → menu with **Close**, **Send to top screen**, **Minimize**.
- Swipe up from the bottom edge to minimize; swipe and hold for the menu.
- Adjustable handle size, hold time and swipe zone.

### Controller
- D-pad jumps between icons (empty cells and empty desktops are skipped), LB / RB switch desktops,
  A opens, B goes back, Y opens the menu, X opens all apps. Works in menus, folders, the app list
  and settings. Can be turned off.

### Setup and safety
- First-run assistant: language, root check (asks Magisk), AYANEO Home switch, gesture tutorial with
  an animated drawing of the device, and the default-home button.
- AYANEO Home can be disabled and re-enabled from the settings.
- **Uninstall button** in Settings that restores AYANEO Home as the home app before removing the
  launcher, so the device is never left without a home screen.

## Requirements
- AYANEO Pocket DS on Android 13.
- Root via Magisk — used to move apps between screens, close apps, grant the overlay permission for
  gestures, and switch AYANEO Home on or off.
- Launcher3 Quickstep must stay enabled (it provides Recents and gesture navigation).
- Do not disable `com.ayaneo.gamewindow` — PocketHub uses it for fan control.

## Installation
1. Download the APK from [Releases](https://github.com/Emile86/PocketHub-Launcher/releases) and
   install it.
2. Open PocketHub Launcher — the setup assistant starts. Choose a language, allow root in Magisk,
   and decide whether to turn off AYANEO Home.
3. On the last step tap **Choose home app** and select PocketHub Launcher.
4. Reboot once so the bottom screen switches to the launcher too.

Settings are opened by long-pressing an empty spot on the desktop → **Settings**.

## Uninstalling
Use **Settings → Uninstall PocketHub Launcher** (at the bottom, above About). If you uninstall
another way, re-enable AYANEO Home first.

## Privacy
The launcher has no internet permission, collects nothing and sends nothing. Everything (layout,
settings, your wallpapers) stays in the app's private storage on the device.

---

# For developers

Package `com.pockethub.launcher`, Kotlin, minSdk / targetSdk 33, compileSdk 35, no external
libraries; all UI is built in code.

## Source layout
Kotlin files live directly in `app/src/main/java/` (package `com.pockethub.launcher`; Kotlin allows
omitting the common root package from folders).

| File | Contents |
|---|---|
| HomeActivity.kt | home screen, menus, folders, wallpaper, controller; `SecondaryHomeActivity` |
| HomeGrid.kt | paged grid, touch, dragging, controller cursor; `IconView` |
| Overlays.kt | context menu, open folder, all-apps list |
| Dialogs.kt | modern dark dialogs and the folder app picker |
| SettingsActivity.kt | settings screen |
| SetupActivity.kt | first-run setup assistant |
| DeviceIllustration.kt | animated drawing of the Pocket DS for the gesture pages |
| EdgeGestureService.kt | bottom screen gestures (overlay windows on display 2) |
| I18n.kt | translations (en, ru, de, es, fr, it, nl, zh) |
| RoundIcon.kt | icon shapes and the folder icon |
| Apps.kt | installed apps (LauncherApps), icon cache |
| Model.kt, LayoutStore.kt | screen layouts, JSON in `files/layout_top.json` / `layout_bottom.json` |
| AppLauncher.kt | launching on a display, moving tasks, persistent su |
| AppListView.kt | app list mode (screen content instead of the desktop) |
| StillScenes.kt | built-in still wallpapers (`StillRenderer`) |
| Scenes.kt | animated wallpapers: `SceneRenderer`, `AnimatedWallpaperView` |
| VideoWallpaper.kt | MP4 video wallpaper (`VideoWallpaperView`) |
| Wallpapers.kt | per-screen images (`files/wallpaper_top.jpg` / `_bottom.jpg`), system and lock screen wallpaper |
| Prefs.kt | settings storage |
| Ui.kt | colors, backgrounds, shared helpers |

## Building from source
1. Open the folder in Android Studio, build and install.
2. Make it the default home app:
   ```
   adb shell cmd package set-home-activity com.pockethub.launcher/.HomeActivity
   ```
   (or Settings → Apps → Default apps → Home app, or the button in launcher Settings → General).
3. Reboot so the bottom screen switches to our `SecondaryHomeActivity` too.
4. On first start Magisk asks for root — allow it (needed to move apps between screens,
   for gestures and for the AYANEO Home switch).

Launcher3 Quickstep stays installed and enabled: it provides Recents and gesture navigation.
Do not disable `com.ayaneo.gamewindow` — PocketHub uses it for fan control.

## Debugging
```
adb shell dumpsys activity activities | findstr /i "pockethub launcher3"
adb shell cmd activity stack list
adb logcat -s PHLauncher
```

## Rollback
The safe way is **Settings → Uninstall PocketHub Launcher**: it turns AYANEO Home back on, makes it
the home app and then removes the launcher.

Manually (if the launcher can't be opened):
```
adb shell pm enable com.ayaneo.home
adb shell cmd package set-home-activity com.ayaneo.home/<its home activity>
adb uninstall com.pockethub.launcher
```
Do not uninstall the launcher while AYANEO Home is disabled — the device would be left without a
home screen until you re-enable it over adb.
