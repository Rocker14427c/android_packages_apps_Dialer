#!/bin/sh
# Compile and run the offline voice-engine regression suite.
#
# Needs: JDK on PATH (java + javac), and android.jar (or a whole SDK).
#   ANDROID_HOME=/path/to/sdk   -> uses its newest platforms/android-*/android.jar
#   ANDROID_JAR=/path/android.jar
# Extra javac flags can be passed through JAVAC_EXTRA (the Java-8 tools.jar
# compiler needs -bootclasspath, modern JDKs use --release).
#
# Exits non-zero when a promise is broken, so it can gate a release.
set -e

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO=$(CDPATH= cd -- "$HERE/../.." && pwd)
OUT=${OUT:-$(mktemp -d)}

if [ -z "$ANDROID_JAR" ]; then
  if [ -z "$ANDROID_HOME" ]; then
    for cand in /usr/local/lib/android/sdk /opt/android-sdk "$ANDROID_SDK_ROOT"; do
      [ -n "$cand" ] && [ -d "$cand/platforms" ] && ANDROID_HOME=$cand && break
    done
  fi
  [ -n "$ANDROID_HOME" ] || { echo "ANDROID_HOME/ANDROID_JAR not set" >&2; exit 2; }
  ANDROID_JAR=$(ls -d "$ANDROID_HOME"/platforms/android-* 2>/dev/null | sort -V | tail -1)/android.jar
fi
[ -f "$ANDROID_JAR" ] || { echo "android.jar not found: $ANDROID_JAR" >&2; exit 2; }

JAVAC=${JAVAC:-javac}
JAVA=${JAVA:-java}

echo "== javac: $($JAVAC -version 2>&1)"
echo "== android.jar: $ANDROID_JAR"
echo "== output: $OUT"

$JAVAC $JAVAC_EXTRA -nowarn -Xlint:-options -classpath "$ANDROID_JAR" -d "$OUT" \
  "$REPO/java/com/android/incallui/voicefx/VoiceFxProcessor.java" \
  "$REPO/java/com/android/incallui/voicefx/VoiceFxPreset.java" \
  "$REPO"/java/com/android/incallui/voicefx/dsp/*.java \
  "$HERE/selftest/Selftest.java"

$JAVA -cp "$OUT" Selftest
