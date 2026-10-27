#!/usr/bin/env python3
"""Offline SDK build for restricted environments without Gradle daemon sockets."""
import os
from pathlib import Path
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
CACHE = Path(os.environ.get('CIRCLE_GRADLE_CACHE', str(Path.home() / '.gradle/caches/modules-2/files-2.1')))
OUT = ROOT / 'app/build/local'
TOOLS = SDK / 'build-tools/35.0.0'
ANDROID = SDK / 'platforms/android-35/android.jar'

def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT)

def jar(group, artifact, version):
    return next((CACHE / group / artifact / version).glob('*/*.jar'))

OUT.mkdir(parents=True, exist_ok=True)
stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.0')
compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.0.0'), stdlib,
            jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.0.0'),
            jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
            jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
            jar('org.jetbrains', 'annotations', '13.0')]
run('java', '-cp', os.pathsep.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', os.pathsep.join(map(str, [ANDROID, stdlib])),
    '-d', OUT / 'classes.jar', *sorted((ROOT / 'app/src/main/java').rglob('*.kt')))
run(TOOLS / 'aapt2', 'compile', '--dir', ROOT / 'app/src/main/res', '-o', OUT / 'resources.zip')
ns = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ns)
manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
manifest.getroot().set('package', 'org.circleopensearch')
manifest.getroot().find('application').set(f'{{{ns}}}debuggable', 'true')
manifest.write(OUT / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
run(TOOLS / 'aapt2', 'link', '-o', OUT / 'unsigned.apk', '--manifest', OUT / 'AndroidManifest.xml',
    '-I', ANDROID, '-A', ROOT / 'app/src/main/assets', '--min-sdk-version', '29', '--target-sdk-version', '35',
    '--version-code', '11', '--version-name', '0.5.0', OUT / 'resources.zip')
dex = OUT / 'dex'
dex.mkdir(exist_ok=True)
for stale in dex.glob('*.dex'):
    stale.unlink()
run(TOOLS / 'd8', '--lib', ANDROID, '--min-api', '29', '--output', dex, OUT / 'classes.jar', stdlib)
with zipfile.ZipFile(OUT / 'unsigned.apk', 'a') as apk:
    for file in sorted(dex.glob('*.dex')):
        apk.write(file, file.name)
run(TOOLS / 'zipalign', '-f', '4', OUT / 'unsigned.apk', OUT / 'aligned.apk')
key = OUT / 'debug.keystore'
if not key.exists():
    run('keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
        '-alias', 'androiddebugkey', '-dname', 'CN=Android Debug,O=Android,C=US',
        '-keyalg', 'RSA', '-validity', '10000')
run(TOOLS / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--out', OUT / 'circle-open-search-debug.apk', OUT / 'aligned.apk')
run(TOOLS / 'apksigner', 'verify', '--verbose', OUT / 'circle-open-search-debug.apk')
print(OUT / 'circle-open-search-debug.apk')
