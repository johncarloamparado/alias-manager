#!/usr/bin/env python3
"""Patch generated Capacitor Android project with Orbit isolated-profile browser."""
from pathlib import Path
import re, shutil, sys
root = Path(__file__).resolve().parent
android = root / 'android'
if not android.exists():
    raise SystemExit('android project missing; run npx cap add android first')
main = next(android.glob('app/src/main/java/**/MainActivity.kt'), None)
main_java = next(android.glob('app/src/main/java/**/MainActivity.java'), None)
if main is None and main_java is None:
    raise SystemExit('Could not find MainActivity.kt or MainActivity.java')
if main is None:
    main = main_java
text = main.read_text()
m = re.search(r'^package\s+([\w.]+)', text, re.M)
if not m:
    raise SystemExit('Could not detect Android package')
pkg = m.group(1)
pkg_path = Path(*pkg.split('.'))
dest = android / 'app/src/main/java' / pkg_path
native_src = root / 'native' / 'kotlin'
dest.mkdir(parents=True, exist_ok=True)
for name in ('OrbitBrowserPlugin.kt', 'OrbitBrowserActivity.kt'):
    src = native_src / name
    out = dest / name
    out.write_text(src.read_text().replace('__PACKAGE__', pkg))

# Register the Capacitor plugin in MainActivity.
if 'OrbitBrowserPlugin' not in text:
    if main.suffix == '.kt':
        text = f'''package {pkg}\n\nimport android.os.Bundle\nimport com.getcapacitor.BridgeActivity\nimport {pkg}.OrbitBrowserPlugin\n\nclass MainActivity : BridgeActivity() {{\n    override fun onCreate(savedInstanceState: Bundle?) {{\n        registerPlugin(OrbitBrowserPlugin::class.java)\n        super.onCreate(savedInstanceState)\n    }}\n}}\n'''
    else:
        text = f'''package {pkg};\n\nimport android.os.Bundle;\nimport com.getcapacitor.BridgeActivity;\n\npublic class MainActivity extends BridgeActivity {{\n    @Override\n    protected void onCreate(Bundle savedInstanceState) {{\n        registerPlugin({pkg}.OrbitBrowserPlugin.class);\n        super.onCreate(savedInstanceState);\n    }}\n}}\n'''
    main.write_text(text)

# Add the Activity declaration to the app manifest.
manifest = android / 'app/src/main/AndroidManifest.xml'
mt = manifest.read_text()
if 'OrbitBrowserActivity' not in mt:
    declaration = '''\n        <activity\n            android:name=".OrbitBrowserActivity"\n            android:exported="false"\n            android:configChanges="orientation|keyboardHidden|screenSize"\n            android:theme="@android:style/Theme.Material.NoActionBar" />\n'''
    if '</application>' not in mt:
        raise SystemExit('Manifest has no application closing tag')
    mt = mt.replace('</application>', declaration + '    </application>')
    manifest.write_text(mt)

# Add AndroidX WebKit profile API dependency.
gradle = android / 'app/build.gradle'
if not gradle.exists():
    gradle = android / 'app/build.gradle.kts'
gt = gradle.read_text()
if 'androidx.webkit:webkit' not in gt:
    if gradle.name.endswith('.kts'):
        gt = gt.replace('dependencies {', 'dependencies {\n    implementation("androidx.webkit:webkit:1.14.0")', 1)
    else:
        gt = gt.replace('dependencies {', "dependencies {\n    implementation 'androidx.webkit:webkit:1.14.0'", 1)
    gradle.write_text(gt)

print(f'Patched Android project for package {pkg}')
