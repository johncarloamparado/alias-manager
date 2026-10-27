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
`native_patch.py` installs the Kotlin plugin/activity, adds the AndroidX WebKit dependency, registers
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
