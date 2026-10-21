ALIAS MANAGER — ANDROID APK BUILD PROJECT
=========================================

Ang project na ito ay nakaayos para buuin bilang tunay na Android APK gamit ang GitHub Actions.
Hindi ito nangangailangan ng web hosting. Ang GitHub ay gagamitin lang para magpatakbo ng build at ibigay ang APK bilang downloadable artifact.

KASAMA
------
- www/                  Web app files na naka-package sa Android app
- assets/icon.png       Source app icon
- assets/splash.png     Source splash image
- capacitor.config.json Capacitor Android wrapper configuration
- package.json          Build dependencies
- .github/workflows/build-apk.yml  Automated APK build workflow

PAANO KUNIN ANG APK
-------------------
1. I-download at i-extract ang buong ZIP.
2. Gumawa ng repository sa https://github.com/new .
3. I-upload ang LAHAT ng laman ng folder na Alias-Manager-APK-Project sa repository root.
   Dapat direktang makita ang package.json, capacitor.config.json, www/ at .github/ sa root.
4. I-commit ang files sa main branch.
5. Buksan ang repository > Actions > Build Alias Manager APK.
6. Kung hindi nagsimula ang build nang kusa, piliin ang Run workflow.
7. Hintaying maging berde ang workflow run.
8. Buksan ang matagumpay na run at i-download ang artifact na alias-manager-debug-apk.
9. I-extract ang artifact ZIP; makikita roon ang app-debug.apk.
10. Ilipat ang APK sa Android phone at i-install. Maaaring hingin ng Android ang pahintulot na mag-install mula sa source na ginamit.

IMPORTANTENG PAALALA
-------------------
- Ito ay DEBUG APK, hindi Play Store release. Maaari itong i-install para sa personal testing.
- Ang GitHub Actions build ay nangangailangan ng internet habang nag-i-install ng dependencies at Android build tools.
- Huwag i-upload ang signing keystore o passwords sa repository.
- Ang app data ay nananatili sa local storage ng app/browser environment; i-export o i-backup ang importanteng data bago mag-uninstall.
- Hindi awtomatikong inilalagay ang APK sa Releases; nasa Actions run artifact ito at may 30-araw na retention.
- Kung may build error, buksan ang Actions run at ibahagi ang error log para maayos ang workflow.
