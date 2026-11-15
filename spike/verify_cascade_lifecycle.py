"""Compile real CascadeHttp against deterministic Android main-queue test doubles."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
jdk = Path(os.environ.get('JAVA_HOME', r'C:\Users\Alexv\java\jdk-17')) / 'bin'
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk')))
fixtures = root / 'spike/lifecycle-test'
out = fixtures / 'classes'
out.mkdir(exist_ok=True)
cp = os.pathsep.join([str(root / 'build/classes'), str(sdk / 'platforms/android-34/android.jar')])
sources = [str(p) for p in fixtures.rglob('*.java')]
sources.extend(str(root / ('app/src/ru/alexv/anyailive/voice/' + name + '.java'))
               for name in ['CascadeHttp', 'CascadeStt'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '-cp', cp, '-d', str(out)] + sources, check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out) + os.pathsep + cp, 'CascadeLifecycleTest'], check=True)
