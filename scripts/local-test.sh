#!/bin/sh
# Local JVM unit tests on the Mini. Setup and disk policy:
# docs/local-testing.md.
set -e

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export JAVA_HOME ANDROID_HOME

sizes() {
    du -sh "$HOME/.gradle" "$ANDROID_HOME" "$ROOT/app/build" "$ROOT/app/libs" 2>/dev/null || true
    df -h "$ROOT" | tail -n 1
}

case "${1:-}" in
    --size)
        sizes
        exit 0
        ;;
    --clean)
        if [ -x "$JAVA_HOME/bin/java" ]; then
            ./gradlew --stop >/dev/null 2>&1 || true
            ./gradlew clean --console=plain
        else
            rm -rf "$ROOT/app/build"
        fi
        rm -rf "$ROOT/build/reports/problems"
        find "$HOME/.gradle/daemon" -type f -name '*.log' -mtime +14 -delete 2>/dev/null || true
        sizes
        exit 0
        ;;
esac

if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "JDK 17 not found at $JAVA_HOME. See docs/local-testing.md." >&2
    exit 1
fi
if [ ! -d "$ANDROID_HOME/platforms/android-37.1" ]; then
    echo "Android SDK platform 37.1 not found under $ANDROID_HOME. See docs/local-testing.md." >&2
    exit 1
fi
if [ ! -f "$ROOT/app/libs/libbox.aar" ] || [ ! -f "$ROOT/app/libs/libbox-legacy.aar" ]; then
    echo "libbox.aar / libbox-legacy.aar missing in app/libs. See docs/local-testing.md." >&2
    exit 1
fi

clean_gb=${WONDERSING_CLEAN_THRESHOLD_GB:-10}
min_gb=${WONDERSING_MIN_FREE_GB:-5}
clean_kb=$((clean_gb * 1024 * 1024))
min_kb=$((min_gb * 1024 * 1024))
free_kb=$(df -Pk "$ROOT" | awk 'NR==2 {print $4}')
if [ "${free_kb:-0}" -lt "$clean_kb" ]; then
    echo "Free space ${free_kb}KB is below the ${clean_gb}GB cleanup threshold; running --clean." >&2
    "$0" --clean
    free_kb=$(df -Pk "$ROOT" | awk 'NR==2 {print $4}')
fi
if [ "${free_kb:-0}" -lt "$min_kb" ]; then
    echo "Less than ${min_gb}GB free on this volume (${free_kb}KB). Free space first." >&2
    exit 1
fi

./gradlew :app:testOtherDebugUnitTest --console=plain "$@"
sizes
