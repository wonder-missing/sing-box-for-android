#!/bin/sh
# Local JVM unit tests on the Mini. Setup and disk policy:
# docs/local-testing.md.
#
# Disk cleanup is footprint-driven, not driven by a fixed free-space floor:
# app/build above WONDERSING_BUILD_BUDGET_MB is regenerated; free space only
# has to cover the next run (WONDERSING_NEXT_RUN_RESERVE_MB). Gradle caches
# and the SDK are reused and are never cleaned automatically.
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

dir_kb() {
    du -sk "$1" 2>/dev/null | awk 'NR==1 {print $1}'
}

free_kb() {
    df -Pk "$ROOT" | awk 'NR==2 {print $4}'
}

build_budget_kb() {
    echo $(( ${WONDERSING_BUILD_BUDGET_MB:-1536} * 1024 ))
}

next_run_reserve_kb() {
    echo $(( ${WONDERSING_NEXT_RUN_RESERVE_MB:-3072} * 1024 ))
}

disk_plan() {
    build_kb=$(dir_kb "$ROOT/app/build"); build_kb=${build_kb:-0}
    budget_kb=$(build_budget_kb)
    reserve_kb=$(next_run_reserve_kb)
    free=$(free_kb)
    echo "disk: app/build=${build_kb}KB budget=${budget_kb}KB; free=${free}KB next-run-reserve=${reserve_kb}KB"
    if [ "$build_kb" -gt "$budget_kb" ]; then
        echo "disk: app/build over budget; a test run would clean it first"
    fi
    if [ "$free" -lt "$reserve_kb" ]; then
        echo "disk: free below the next-run reserve; a test run would clean, then fail if still below"
    fi
    if [ "$build_kb" -le "$budget_kb" ] && [ "$free" -ge "$reserve_kb" ]; then
        echo "disk: no cleanup needed"
    fi
}

check_disk() {
    build_kb=$(dir_kb "$ROOT/app/build"); build_kb=${build_kb:-0}
    budget_kb=$(build_budget_kb)
    reserve_kb=$(next_run_reserve_kb)
    free=$(free_kb)
    clean=0

    if [ "$build_kb" -gt "$budget_kb" ]; then
        echo "disk: app/build ${build_kb}KB is over the ${budget_kb}KB budget; cleaning." >&2
        clean=1
    fi
    if [ "$free" -lt "$reserve_kb" ]; then
        echo "disk: free ${free}KB is below the ${reserve_kb}KB next-run reserve; cleaning disposable output." >&2
        clean=1
    fi
    if [ "$clean" -eq 1 ]; then
        "$0" --clean
        build_kb=$(dir_kb "$ROOT/app/build"); build_kb=${build_kb:-0}
        free=$(free_kb)
    fi

    if [ "$free" -lt "$reserve_kb" ]; then
        echo "ERROR: ${free}KB free is still below the ${reserve_kb}KB next-run reserve." >&2
        echo "The project now owns ${build_kb}KB in app/build; reusable Gradle/SDK data is not deleted automatically." >&2
        echo "Free space on the volume, or lower WONDERSING_NEXT_RUN_RESERVE_MB if a smaller run is intended." >&2
        exit 1
    fi
    echo "disk: app/build=${build_kb}KB free=${free}KB; next-run reserve=${reserve_kb}KB" >&2
}

case "${1:-}" in
    --size)
        sizes
        disk_plan
        exit 0
        ;;
    --clean)
        if [ -x "$JAVA_HOME/bin/java" ] && [ -d "$ANDROID_HOME/platforms/android-37.1" ]; then
            ./gradlew --stop >/dev/null 2>&1 || true
            ./gradlew clean --console=plain || rm -rf "$ROOT/app/build"
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

check_disk
./gradlew :app:testOtherDebugUnitTest --console=plain "$@"
sizes
