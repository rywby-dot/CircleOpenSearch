#!/usr/bin/env python3
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[1]
cache = Path(os.environ.get('CIRCLE_GRADLE_CACHE', str(Path.home() / '.gradle/caches/modules-2/files-2.1')))
def jar(group, artifact, version):
    return next((cache / group / artifact / version).glob('*/*.jar'))
stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.0')
compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.0.0'), stdlib,
            jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.0.0'), jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
            jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'), jar('org.jetbrains', 'annotations', '13.0')]
out = root / 'app/build/policy-tests.jar'
out.parent.mkdir(parents=True, exist_ok=True)
subprocess.run(['java', '-cp', os.pathsep.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', str(stdlib), '-d', str(out), str(root / 'app/src/main/java/org/circleopensearch/LensPolicy.kt'), str(root / 'tests/LensPolicyTest.kt'), str(root / 'app/src/main/java/org/circleopensearch/SingleCapture.kt'), str(root / 'tests/SingleCaptureTest.kt'), str(root / 'app/src/main/java/org/circleopensearch/PixelMapping.kt'), str(root / 'tests/PixelMappingTest.kt'), str(root / 'app/src/main/java/org/circleopensearch/MusicFingerprint.kt'), str(root / 'tests/MusicFingerprintTest.kt')], check=True)
subprocess.run(['java', '-cp', os.pathsep.join(map(str, [out, stdlib])), 'org.circleopensearch.LensPolicyTestKt'], check=True)

subprocess.run(['java', '-cp', os.pathsep.join(map(str, [out, stdlib])), 'org.circleopensearch.capturetests.SingleCaptureTestKt'], check=True)


subprocess.run(['java', '-cp', os.pathsep.join(map(str, [out, stdlib])), 'org.circleopensearch.pickerTests.PixelMappingTestKt'], check=True)
subprocess.run(['java', '-cp', os.pathsep.join(map(str, [out, stdlib])), 'org.circleopensearch.musicTests.MusicFingerprintTestKt'], check=True)
