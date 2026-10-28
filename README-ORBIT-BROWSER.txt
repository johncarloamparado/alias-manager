ORBIT MULTI-PROFILE BROWSER (ANDROID)
====================================

What this adds
--------------
- A new Orbit Browser launcher in the existing Alias Manager UI.
- A native Android browser screen that stays inside the app.
- Named profiles using AndroidX WebKit's MULTI_PROFILE feature.
- Each profile gets separate WebView cookies/site data; only one WebView is active at a time.
- Profile names and selected profile are stored locally. Chat history is not copied into Alias Manager.
- If the installed Android System WebView does not support MULTI_PROFILE, the browser refuses to load a shared-session fallback, to avoid mixing account sessions.

Build behavior
--------------
The repository creates the Android project during GitHub Actions. After `npx cap sync android`,
`native_patch.py` installs the Java plugin/activity (native/java/), adds the AndroidX WebKit dependency, registers
the plugin, and declares the Activity.

How to build
------------
1. Commit/push this ZIP's project files to your repository.
2. Run the existing Compile Android APK GitHub Actions workflow.
3. Download and install the resulting debug APK.
4. Open Alias Manager > Orbit Browser.
5. Add a profile, sign into the official Claude website, then switch profiles to test isolation.

Important compatibility notes
-----------------------------
- This is a native integration, not an HTML-only simulation.
- Multi-profile support depends on the Android System WebView provider/version on the device.
  The app checks support at runtime. Update Android System WebView from the Play Store if needed.
- Claude may restrict sign-in or some functionality in embedded WebViews. This implementation does not
  disable those protections or guarantee compatibility. If Claude refuses the embedded environment,
  use its official supported browser/app instead.
- This is for organizing separate accounts, not bypassing Claude plan limits or service restrictions.
- Inactive WebViews are destroyed on profile switch to reduce RAM; each profile's website data persists.
  The page itself reloads when you switch back, so unsaved page state may be lost.
- Profile removal/data-wipe UI is intentionally not included yet to avoid accidental deletion of login data.

Implementation used: JAVA only (native/java/). The old Kotlin copy was removed because the generated
Capacitor project is Java-only and does not compile .kt files (this caused the OrbitBrowserPlugin
"cannot find symbol" build error).
native_patch.py is idempotent and checks, before Gradle runs, that the plugin sits in the same
folder/package as MainActivity, is registered once, and that the manifest/gradle are patched once.
After the build, the workflow also checks that the APK really contains the latest index.html and the
Orbit classes.
Status: NOT build-verified in my environment (Android SDK/AndroidX were not reachable). Run the workflow.

Ready notifications
-------------------
OrbitReminderReceiver.java schedules an Android alarm for when each running 5h timer ends and shows a
"Ready" notification. Needs the notification permission on Android 13+ (the app asks). Alarms are inexact
(may be a few minutes late). After a phone reboot they are re-created automatically (BOOT_COMPLETED);
reminders that finished while the phone was off are shown right after boot.
Turn off with Settings > Ready alerts.

Profile management (⋯ button in Orbit)
--------------------------------------
Rename, emoji, color, and Delete (with a confirmation dialog). Renaming only changes the label: each
profile keeps a fixed internal WebView key, so the login is kept. Delete erases that profile's cookies and
site data on this phone (not the Claude account or chats) and removes its alias link. The last profile can't
be deleted. If the data can't be erased, the profile is kept and an error is shown.

Auto-start toggle
-----------------
Alias Manager > Settings > "Orbit auto-start timer" (On/Off). When Off, Orbit still remembers chats it sees
but does not start any timer (the toolbar shows "auto-start OFF"). Use "▶ Start" manually.
