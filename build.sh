#!/bin/sh
# Build helper for ExpenseTracker.
# Run from a Termux session on this device:  sh build.sh   (or: sh build.sh assembleRelease)
#
# Same discovery order as the working GentleDawn/build.sh on this device:
#   Gradle -> wrapper, shared copy, or PATH
#   JDK    -> $JAVA_HOME, PATH java, Termux openjdk
#   SDK    -> $ANDROID_HOME, shared SDK, Termux SDK
#
# Everything is logged to build.log; compiler errors are summarised in build-errors.txt.
set -e
cd "$(dirname "$0")"

TASK="${1:-assembleDebug}"
LOG=build.log

APK=app/build/outputs/apk/debug/app-debug.apk
case "$TASK" in
  *Release*) APK=app/build/outputs/apk/release/app-release.apk ;;
esac

# ---------------------------------------------------------------- gradle
GRADLE=""
if [ -f /storage/emulated/0/apps/gradle-9.7.1/bin/gradle ]; then
  GRADLE=/storage/emulated/0/apps/gradle-9.7.1/bin/gradle
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
fi
# The wrapper is the right choice here (AGP 8.7.0 needs Gradle 8.9); the shared
# distribution is only a fallback.
USE_WRAPPER=1

# ---------------------------------------------------------------- jdk
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  J="$(command -v java 2>/dev/null || true)"
  if [ -n "$J" ]; then
    JAVA_HOME="$(cd "$(dirname "$J")/.." && pwd)"
  else
    for c in /data/data/com.termux/files/usr/lib/jvm/java-21-openjdk \
             /data/data/com.termux/files/usr/lib/jvm/java-17-openjdk; do
      [ -x "$c/bin/java" ] && JAVA_HOME="$c" && break
    done
  fi
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "No JDK found." >&2
  echo "On-device fix:  pkg install openjdk-17" >&2
  exit 1
fi
export JAVA_HOME
echo "JAVA_HOME: $JAVA_HOME"
"$JAVA_HOME/bin/java" -version

# ---------------------------------------------------------------- sdk
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME/platforms" ]; then
  for c in /storage/emulated/0/apps/android-sdk \
           /data/data/com.termux/files/home/android-sdk; do
    [ -d "$c/platforms" ] && ANDROID_HOME="$c" && break
  done
fi
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME/platforms" ]; then
  echo "No Android SDK found (needs platform android-35)." >&2
  exit 1
fi
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
echo "ANDROID_HOME: $ANDROID_HOME"

# keep local.properties in sync with the SDK we are actually using
if [ ! -f local.properties ] || ! grep -q "^sdk.dir=$ANDROID_HOME$" local.properties; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
  echo "Wrote local.properties (sdk.dir=$ANDROID_HOME)"
fi

# the aapt2 shipped in the SDK is not runnable in Termux; other apps here point
# Gradle at Termux's own aapt2 (see README).
if [ -x /data/data/com.termux/files/usr/bin/aapt2 ] && \
   ! grep -qs aapt2FromMavenOverride "$HOME/.gradle/gradle.properties"; then
  echo "NOTE: if the build stops on aapt2, add this to ~/.gradle/gradle.properties:"
  echo "  android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2"
fi

# ---------------------------------------------------------------- build
rm -f "$LOG" build-errors.txt
# drop the previous APK first: a stale file must never be reported as a fresh build
rm -f "$APK"

if [ "$USE_WRAPPER" = "1" ]; then
  # the shared-storage mount cannot carry the exec bit -> run via sh
  sh ./gradlew "$TASK" 2>&1 | tee "$LOG"
else
  sh "$GRADLE" "$TASK" 2>&1 | tee "$LOG"
fi

# summarise the compiler errors, easy to read on a phone
grep -E "^e: |^error:|Execution failed for task" "$LOG" > build-errors.txt 2>/dev/null || true
if [ -s build-errors.txt ]; then
  echo
  echo "---- errors (also in build-errors.txt) ----"
  cat build-errors.txt
  echo "----------------------------------------"
fi

if grep -qE "Execution failed for task|BUILD FAILED" "$LOG" 2>/dev/null; then
  echo "BUILD FAILED - see build-errors.txt" >&2
  exit 1
fi

if [ -f "$APK" ]; then
  echo
  echo "BUILD OK -> $APK ($(wc -c < "$APK") bytes)"
  echo "Install: adb install -r '$APK'"
  echo "Or copy it out: cp '$APK' /storage/emulated/0/Download/ExpenseTracker.apk"
else
  echo "Build finished with no APK at $APK (see errors above)." >&2
  exit 1
fi
