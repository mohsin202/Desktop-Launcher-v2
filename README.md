# Desktop Launcher

Package: `com.android.desktoplauncher` — Min SDK 31 (Android 12), Target 37 (Android 17).
Plain Android views, **no external libraries** (no AndroidX / Compose), so the build is small and simple.

## Features
- Desktop = the `/sdcard/Desktop` folder (files, folders, app shortcuts, widgets)
- Right-click / long-press menu: View (Small/Normal/Large), Cut, Copy, Paste, New (File/Folder), Wallpapers, Widgets, Settings
- Cut / Copy / Paste are faint until something is selected or copied
- Item menu: Open, Cut, Copy, Share, Remove, Delete (apps: Open, App Info, Share, Remove, Uninstall)
- Folders open in the file manager, files open in the proper app
- App drawer (rounded panel bottom-left) with universal search: apps, settings, app shortcuts, contacts, files, in-app search, web
- Drawer long-press menu: Open, App Info, Share, Pin to Taskbar, Add to Desktop, Uninstall
- Two layouts like Launcher3: smallest width < 600 dp = phone (dock on the home screen, icons in rows, swipe up for the drawer); 600 dp or more = desktop (dock merges into the taskbar, icons in columns). Can be forced in Settings.
- Icons can be dragged to any free cell (swap if occupied)
- Language switch icon on the taskbar while a keyboard is in use; Windows key opens the drawer
- Optional UI blur (Settings -> Enable UI blur), otherwise solid Material You colours
- Taskbar in an accessibility overlay: shown over every app (fullscreen and windowed), start button, pinned + running apps, Back / Home / Recents, clock
- Settings: icon size, themed drawer icons, notification dots, taskbar options

## Build on GitHub
Upload everything (keep the folder structure), open **Actions -> Build APK -> Run workflow**, download the `DesktopLauncher` artifact.

## First run
1. Install the APK, press Home and choose **Desktop Launcher** -> Always.
2. Settings -> Accessibility -> **Desktop Launcher taskbar** -> On (starts the taskbar).
3. Allow **All files access** and **Notification access** (Settings screen has buttons).

If Android blocks the accessibility toggle (restricted setting) run as root:
`appops set com.android.desktoplauncher ACCESS_RESTRICTED_SETTINGS allow`

## Phone / tablet behaviour (same rule as Launcher3)
- Smallest width **< 600 dp**: normal launcher. Floating dock at the bottom of the home screen, icons fill rows.
- Smallest width **>= 600 dp**: the dock merges into the taskbar (accessibility overlay, over every app, fullscreen or windowed),
  Back/Home/Recents move into it, desktop icons fill columns.
- Both layouts use the same desktop-style taskbar look. Colours follow the wallpaper (Material You, Android 12+).

## Widgets (grid based)
- Widgets always snap to the home screen grid (whole cells). Long-press a widget: resize frame with corner handles (drag the widget body to move it); long-press again: Remove.
- Built-in launcher widgets (Widgets menu): Clock (digital), Clock (analog), Search bar.

## Taskbar notes
- The taskbar is always left-to-right. Only the app strip scrolls; its position is kept; opening an app scrolls it into view.
- Running lines are removed when an app disappears from Recents (needs the ROM to expose other apps' processes, or the Recents "Clear all" button).
