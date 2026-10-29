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


Step 8 additions
- Alias list: Alias Manager keeps a saved copy for Orbit (syncAliases), so the 🔗 Alias menu always has your emails.
- ✉ button: Gmail in its own isolated profile. claude.ai links tapped in Gmail open in the active Claude profile.
  Google may refuse sign-in inside an embedded WebView; this app does not try to disguise itself.
- claude.ai/login: the linked alias is filled into the email box (you tap Continue).
- Compact toolbar; ⋯ > Hide address bar for even more room.
- Delete profile: removed at once; its data is erased with retries, or at the next Orbit start.
- Chat detected with no alias linked: asks which alias, then starts that alias timer.
- 🌐 Open only appears for aliases that are logged in (or were started before).

Step 9 additions
----------------
- Tab labels: a profile still named "Account N" shows its linked alias (note, or the part before @). A profile you
  renamed keeps your name.
- ⋯ > "Sign out (keep this tab)": erases that profile's cookies/site data only; the tab, alias link and color stay.
  The alias's 🌐 Open button is hidden until you log in again.
- Limit detection (user-requested; follows Settings > Orbit auto-start): every 10 s, while the Claude page you are
  looking at is open on a profile with a linked alias, Orbit checks the page text for a "you've reached your limit"
  line. Only that one line (max 300 chars) is looked at; nothing else is read or stored. If a reset time can be read
  ("in 2 hours 10 minutes" / "at 3:30 PM", within the next 5 h) the alias becomes Running and Ready exactly then,
  with a notification; otherwise it is marked Running from now. A chat that merely TALKS about usage limits could
  trigger it by mistake - use ↺ Reset on the card if that happens.
- Backup: Settings > Export now writes {aliases, orbit}. orbit = profiles, which alias each profile uses, colors,
  emoji, WebView keys. Import restores them too (old plain-list backups still import). Logins/cookies are NOT in
  the backup - after a reinstall you sign in again, but the links come back.
