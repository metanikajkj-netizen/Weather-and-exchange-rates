#!/bin/bash
# Isolated build environment for WeatherCurrency (reuses VpnAutoSwitch toolchain read-only)
set -euo pipefail
export JAVA_HOME=/opt/vpnautoswitch-ru/build/jdk/jdk-17.0.21+9
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/opt/weather-currency/build/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
TOOLSLIB=/opt/vpnautoswitch-ru/build/tools/usr/lib
export LD_LIBRARY_PATH="$TOOLSLIB:$TOOLSLIB/aarch64-linux-gnu/android:$TOOLSLIB/aarch64-linux-gnu:$TOOLSLIB/7zip"
AAPT2=/opt/weather-currency/build/sdk/build-tools/35.0.0/aapt2
GRADLE=/opt/vpnautoswitch-ru/build/sdk/gradle-8.14/bin/gradle
cd /opt/weather-currency
exec "$GRADLE" \
  -Dorg.gradle.jvmargs="-Xmx2048m -Dfile.encoding=UTF-8" \
  -Dorg.gradle.workers.max=2 \
  -Dkotlin.daemon.jvmargs="-Xmx1024m" \
  -Pandroid.aapt2FromMavenOverride="$AAPT2" \
  --no-daemon \
  "$@"
