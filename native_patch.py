#!/usr/bin/env python3
"""Install the Orbit Browser (Java) into the Capacitor-generated android/ project.

Deterministic and idempotent: running it 1x or 2x gives the same result.
Fails loudly (non-zero exit) if anything is not in the expected place.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "android"
NATIVE = ROOT / "native" / "java"
FILES = ("OrbitBrowserPlugin.java", "OrbitBrowserActivity.java", "OrbitReminderReceiver.java")
WEBKIT_VERSION = "1.14.0"


def die(msg):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


if not ANDROID.exists():
    die("android/ missing; run `npx cap add android` first")

# --- 1. Find MainActivity (Capacitor 7 generates Java) and read its package ---
java_root = ANDROID / "app/src/main/java"
mains = sorted(java_root.glob("**/MainActivity.java"))
if not mains:
    kt = sorted(java_root.glob("**/MainActivity.kt"))
    die("MainActivity.java not found" + (" (found MainActivity.kt: this patch is Java-only)" if kt else ""))
main = mains[0]
text = main.read_text()
m = re.search(r"^package\s+([\w.]+)\s*;", text, re.M)
if not m:
    die("cannot read package from " + str(main))
pkg = m.group(1)
dest = main.parent  # same folder as MainActivity => guaranteed matching package
print("Package: " + pkg)
print("Dest   : " + str(dest.relative_to(ANDROID)))

# --- 2. Copy native sources with the real package ---
for name in FILES:
    src = NATIVE / name
    if not src.exists():
        die("missing source " + str(src))
    body = src.read_text()
    if "package __PACKAGE__;" not in body:
        die(name + " must start with `package __PACKAGE__;`")
    (dest / name).write_text(body.replace("__PACKAGE__", pkg))

# --- 3. Register the plugin in MainActivity (inject, do not rewrite) ---
reg = "registerPlugin(OrbitBrowserPlugin.class);"
text = re.sub(r"[ \t]*registerPlugin\((?:[\w.]*\.)?OrbitBrowserPlugin\.class\);[ \t]*\n", "", text)
if "onCreate(" in text:
    text = re.sub(r"(\n([ \t]*)super\.onCreate\(savedInstanceState\);)",
                  lambda mo: "\n" + mo.group(2) + reg + mo.group(1), text, count=1)
else:
    if "import android.os.Bundle;" not in text:
        text = re.sub(r"(package\s+[\w.]+\s*;\n)", r"\1\nimport android.os.Bundle;", text, count=1)
    method = ("\n    @Override\n    public void onCreate(Bundle savedInstanceState) {\n"
              "        " + reg + "\n        super.onCreate(savedInstanceState);\n    }\n")
    idx = text.rstrip().rfind("}")
    text = text[:idx].rstrip() + "\n" + method + "}\n"
if text.count("OrbitBrowserPlugin.class") != 1:
    die("MainActivity registration failed")
main.write_text(text)

# --- 4. Manifest: exactly one Activity + one Receiver declaration, notification permission ---
manifest = ANDROID / "app/src/main/AndroidManifest.xml"
mt = manifest.read_text()
mt = re.sub(r"\s*<activity\s+android:name=\"[^\"]*OrbitBrowserActivity\"[^>]*/>", "", mt)
mt = re.sub(r"\s*<receiver\s+android:name=\"[^\"]*OrbitReminderReceiver\"(?:[^>]*/>|[^>]*>.*?</receiver>)", "", mt, flags=re.S)
if "android.permission.RECEIVE_BOOT_COMPLETED" not in mt:
    mt = mt.replace("<application", '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n    <application', 1)
if "android.permission.POST_NOTIFICATIONS" not in mt:
    if "<application" not in mt:
        die("manifest has no <application")
    mt = mt.replace("<application", '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n    <application', 1)
decl = ('\n        <activity\n            android:name="' + pkg + '.OrbitBrowserActivity"\n'
        '            android:exported="false"\n'
        '            android:configChanges="orientation|keyboardHidden|screenSize"\n'
        '            android:theme="@android:style/Theme.Material.NoActionBar" />\n'
        '        <receiver android:name="' + pkg + '.OrbitReminderReceiver" android:exported="false">\n'
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.BOOT_COMPLETED" />\n'
        '            </intent-filter>\n'
        '        </receiver>\n')
if "</application>" not in mt:
    die("manifest has no </application>")
mt = mt.replace("</application>", decl + "    </application>", 1)
manifest.write_text(mt)

# --- 5. Gradle: exactly one androidx.webkit dependency ---
gradle = ANDROID / "app/build.gradle"
kts = False
if not gradle.exists():
    gradle = ANDROID / "app/build.gradle.kts"
    kts = True
if not gradle.exists():
    die("app/build.gradle(.kts) not found")
gt = gradle.read_text()
if "androidx.webkit:webkit" not in gt:
    dep = ('    implementation("androidx.webkit:webkit:%s")' if kts
           else "    implementation 'androidx.webkit:webkit:%s'") % WEBKIT_VERSION
    if "dependencies {" not in gt:
        die("no dependencies block in " + gradle.name)
    gt = gt.replace("dependencies {", "dependencies {\n" + dep, 1)
    gradle.write_text(gt)

# --- 6. Final validation (before Gradle starts) ---
for name in FILES:
    f = dest / name
    first = f.read_text().splitlines()[0]
    if first.strip() != "package " + pkg + ";":
        die(name + " wrong package line: " + first)
    print("OK file : " + str(f.relative_to(ANDROID)) + "  [" + first + "]")
if main.read_text().count("OrbitBrowserPlugin.class") != 1:
    die("MainActivity registration check failed")
if manifest.read_text().count("OrbitBrowserActivity") != 1:
    die("manifest Activity count != 1")
if manifest.read_text().count("OrbitReminderReceiver") != 1:
    die("manifest Receiver count != 1")
if manifest.read_text().count("android.permission.RECEIVE_BOOT_COMPLETED") != 1:
    die("RECEIVE_BOOT_COMPLETED count != 1")
if manifest.read_text().count("android.permission.POST_NOTIFICATIONS") != 1:
    die("POST_NOTIFICATIONS count != 1")
if gradle.read_text().count("androidx.webkit:webkit") != 1:
    die("webkit dependency count != 1")
print("OK MainActivity registers OrbitBrowserPlugin; manifest + gradle patched once")
