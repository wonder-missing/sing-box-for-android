#!/bin/sh
# Headless Android emulator smoke test on the Mini.
#
# Disk policy is footprint-driven, not driven by a fixed free-space floor:
#   - app/build > 1.5GB: clean build output (unless --no-build).
#   - build/e2e-emulator > 128MB: remove old artifacts.
#   - AVD snapshots > 0: remove (this test always boots with -no-snapshot).
#   - AVD data > 4GB: boot with -wipe-data.
#   - Free space only has to cover the next run: 4GB reserve. Below that,
#     reclaim disposable output once; if still below, fail and report that
#     the volume, not this project, is full.
#   - Gradle caches and SDK/system images are reused and never auto-deleted.
#
# Then boots an arm64 API 36 AVD, builds/installs the otherDebug APK, launches
# MainActivity, dismisses the first-run update prompt, and asserts the
# Dashboard/Profiles UI. Real VPN/service behavior still needs a physical device.
#
#   ./scripts/e2e-emulator.sh
#   ./scripts/e2e-emulator.sh --no-build
#   ./scripts/e2e-emulator.sh --size
#   E2E_KEEP_EMULATOR=1 ./scripts/e2e-emulator.sh
#
# Budgets:
#   E2E_BUILD_BUDGET_MB        (default 1536)  app/build footprint budget
#   E2E_ARTIFACT_BUDGET_MB     (default 128)   build/e2e-emulator budget
#   E2E_AVD_DATA_BUDGET_MB     (default 4096)  AVD data budget; over -> -wipe-data
#   E2E_NEXT_RUN_RESERVE_MB    (default 4096)  free space needed for one run
#   E2E_SDK_INSTALL_RESERVE_MB (default 8192)  one-time SDK install reserve
#   E2E_MIN_TOTAL_MEM_GB       (default 16)    host total memory floor
#   E2E_MIN_FREE_MEM_GB        (default 4)     host free+inactive memory floor
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

AVD=${E2E_AVD:-wondersing-e2e}
PORT=${E2E_PORT:-5556}
SERIAL="emulator-$PORT"
RAM_MB=${E2E_RAM_MB:-3072}
BUILD_BUDGET_MB=${E2E_BUILD_BUDGET_MB:-1536}
ARTIFACT_BUDGET_MB=${E2E_ARTIFACT_BUDGET_MB:-128}
AVD_DATA_BUDGET_MB=${E2E_AVD_DATA_BUDGET_MB:-4096}
NEXT_RUN_RESERVE_MB=${E2E_NEXT_RUN_RESERVE_MB:-4096}
SDK_INSTALL_RESERVE_MB=${E2E_SDK_INSTALL_RESERVE_MB:-8192}
SKIP_BUILD=0
SIZE_ONLY=0
WIPE_DATA=0
for arg in "$@"; do
    case "$arg" in
        --no-build) SKIP_BUILD=1 ;;
        --size) SIZE_ONLY=1 ;;
        -h|--help)
            sed -n '2,/^set -eu$/p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown option: $arg" >&2
            exit 2
            ;;
    esac
done

JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export JAVA_HOME ANDROID_HOME

OUT="$ROOT/build/e2e-emulator"
SCREEN="$OUT/main.png"
WINDOW="$OUT/window.xml"
LOG="$OUT/emulator.log"
AVD_DIR="$HOME/.android/avd/$AVD.avd"
SNAP_DIR="$AVD_DIR/snapshots"
PKG=io.nekohasekai.sfa.wonder
ACTIVITY="$PKG/io.nekohasekai.sfa.compose.MainActivity"
IMAGE="system-images;android-36;google_apis;arm64-v8a"
IMAGE_DIR="$ANDROID_HOME/system-images/android-36/google_apis/arm64-v8a"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"

EMU_PID=""
cleanup() {
    status=$?
    if [ -n "$EMU_PID" ] && [ "${E2E_KEEP_EMULATOR:-0}" != "1" ]; then
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
        kill "$EMU_PID" >/dev/null 2>&1 || true
    elif [ -n "$EMU_PID" ]; then
        echo "emulator kept: $SERIAL"
    fi
    exit "$status"
}
trap cleanup EXIT INT TERM

fail() {
    echo "E2E FAILED: $*" >&2
    [ -f "$LOG" ] && tail -40 "$LOG" >&2 || true
    exit 1
}

free_kb() {
    df -Pk "$ROOT" | awk 'NR==2 { print $4 }'
}

dir_kb() {
    du -sk "$1" 2>/dev/null | awk 'NR==1 {print $1}'
}

total_mem_kb() {
    mem_bytes=$(sysctl -n hw.memsize 2>/dev/null || echo 0)
    echo $((mem_bytes / 1024))
}

free_mem_kb() {
    page_size=$(vm_stat 2>/dev/null | sed -n 's/.*page size of \([0-9][0-9]*\) bytes.*/\1/p' | head -n 1)
    free_pages=$(vm_stat 2>/dev/null | awk '/Pages free:/ { gsub(/\./,"",$3); print $3 }')
    inactive_pages=$(vm_stat 2>/dev/null | awk '/Pages inactive:/ { gsub(/\./,"",$3); print $3 }')
    if [ -z "$page_size" ] || [ -z "$free_pages" ] || [ -z "$inactive_pages" ]; then
        echo 0
        return
    fi
    echo $(( (free_pages + inactive_pages) * page_size / 1024 ))
}

check_memory() {
    min_total_kb=$(( ${E2E_MIN_TOTAL_MEM_GB:-16} * 1024 * 1024 ))
    min_free_kb=$(( ${E2E_MIN_FREE_MEM_GB:-4} * 1024 * 1024 ))
    total=$(total_mem_kb)
    free=$(free_mem_kb)
    if [ "$total" -lt "$min_total_kb" ]; then
        echo "E2E FAILED: host memory ${total}KB < ${min_total_kb}KB; need ${E2E_MIN_TOTAL_MEM_GB:-16}GB total." >&2
        exit 1
    fi
    if [ "$free" -lt "$min_free_kb" ]; then
        echo "E2E FAILED: host free+inactive memory ${free}KB < ${min_free_kb}KB; close other VMs/apps first." >&2
        exit 1
    fi
    echo "memory: total=$((total / 1024 / 1024))GB free+inactive=$((free / 1024 / 1024))GB"
}

sdk_missing() {
    [ ! -x "$SDKMANAGER" ] || [ ! -x "$AVDMANAGER" ] || [ ! -x "$ADB" ] || [ ! -x "$EMULATOR" ] ||
        [ ! -d "$IMAGE_DIR" ]
}

reserve_mb() {
    if sdk_missing; then
        echo "$SDK_INSTALL_RESERVE_MB"
    else
        echo "$NEXT_RUN_RESERVE_MB"
    fi
}

disk_report() {
    build_kb=$(dir_kb "$ROOT/app/build"); build_kb=${build_kb:-0}
    e2e_kb=$(dir_kb "$OUT"); e2e_kb=${e2e_kb:-0}
    avd_kb=$(dir_kb "$AVD_DIR"); avd_kb=${avd_kb:-0}
    snap_kb=$(dir_kb "$SNAP_DIR"); snap_kb=${snap_kb:-0}
    free=$(free_kb)
    reserve=$(reserve_mb)
    total=$(total_mem_kb)
    free_mem=$(free_mem_kb)
    echo "disk: app/build=${build_kb}KB/${BUILD_BUDGET_MB}MB e2e=${e2e_kb}KB/${ARTIFACT_BUDGET_MB}MB avd=${avd_kb}KB/${AVD_DATA_BUDGET_MB}MB snapshots=${snap_kb}KB free=${free}KB/${reserve}MB-reserve"
    echo "memory: total=$((total / 1024 / 1024))GB free+inactive=$((free_mem / 1024 / 1024))GB"
    if [ "$build_kb" -gt "$((BUILD_BUDGET_MB * 1024))" ]; then
        if [ "$SKIP_BUILD" = "1" ]; then
            echo "disk: app/build over budget; --no-build keeps the existing APK"
        else
            echo "disk: app/build over budget; a run would clean it first"
        fi
    fi
    if [ "$e2e_kb" -gt "$((ARTIFACT_BUDGET_MB * 1024))" ]; then
        echo "disk: e2e artifacts over budget; a run would remove them first"
    fi
    if [ "$snap_kb" -gt 0 ]; then
        echo "disk: AVD snapshots present; a run would remove them first"
    fi
    if [ "$avd_kb" -gt "$((AVD_DATA_BUDGET_MB * 1024))" ]; then
        echo "disk: AVD data over budget; the next run would boot with -wipe-data"
    fi
    if [ "$free" -lt "$((reserve * 1024))" ]; then
        echo "disk: free below the next-run reserve; a run would reclaim, then fail if still below"
    fi
    if [ "$build_kb" -le "$((BUILD_BUDGET_MB * 1024))" ] &&
        [ "$e2e_kb" -le "$((ARTIFACT_BUDGET_MB * 1024))" ] &&
        [ "$snap_kb" -eq 0 ] &&
        [ "$avd_kb" -le "$((AVD_DATA_BUDGET_MB * 1024))" ] &&
        [ "$free" -ge "$((reserve * 1024))" ]; then
        echo "disk: no cleanup needed"
    fi
}

clean_disposable() {
    clean_build=$1
    clean_artifacts=$2
    clean_snapshots=$3
    if [ "$clean_build" = "1" ] && [ "$SKIP_BUILD" != "1" ]; then
        "$ROOT/scripts/local-test.sh" --clean
    fi
    if [ "$clean_artifacts" = "1" ]; then
        rm -rf "$OUT"
    fi
    if [ "$clean_snapshots" = "1" ]; then
        rm -rf "$SNAP_DIR"
    fi
}

check_disk() {
    build_kb=$(dir_kb "$ROOT/app/build"); build_kb=${build_kb:-0}
    e2e_kb=$(dir_kb "$OUT"); e2e_kb=${e2e_kb:-0}
    free=$(free_kb)
    build_budget_kb=$((BUILD_BUDGET_MB * 1024))
    artifact_budget_kb=$((ARTIFACT_BUDGET_MB * 1024))
    reserve_kb=$(( $(reserve_mb) * 1024 ))
    clean_build=0
    clean_artifacts=0

    if [ "$build_kb" -gt "$build_budget_kb" ]; then
        if [ "$SKIP_BUILD" = "1" ]; then
            echo "disk: app/build ${build_kb}KB is over the ${build_budget_kb}KB budget; --no-build keeps the existing APK." >&2
        else
            echo "disk: app/build ${build_kb}KB is over the ${build_budget_kb}KB budget; cleaning." >&2
            clean_build=1
        fi
    fi
    if [ "$e2e_kb" -gt "$artifact_budget_kb" ]; then
        echo "disk: e2e artifacts ${e2e_kb}KB are over the ${artifact_budget_kb}KB budget; removing." >&2
        clean_artifacts=1
    fi
    if [ "$free" -lt "$reserve_kb" ]; then
        echo "disk: free ${free}KB is below the ${reserve_kb}KB next-run reserve; reclaiming disposable output." >&2
        clean_build=1
        clean_artifacts=1
    fi
    if [ "$clean_build" -eq 1 ] || [ "$clean_artifacts" -eq 1 ]; then
        clean_disposable "$clean_build" "$clean_artifacts" 0
        build_kb=$(dir_kb "$ROOT/app/build"); build_kb=${build_kb:-0}
        e2e_kb=$(dir_kb "$OUT"); e2e_kb=${e2e_kb:-0}
        free=$(free_kb)
    fi

    if [ "$free" -lt "$reserve_kb" ]; then
        avd_kb=$(dir_kb "$AVD_DIR"); avd_kb=${avd_kb:-0}
        echo "E2E FAILED: ${free}KB free is still below the ${reserve_kb}KB next-run reserve after cleanup." >&2
        echo "Owned: app/build=${build_kb}KB e2e=${e2e_kb}KB avd=${avd_kb}KB; Gradle caches and SDK/system images were not deleted." >&2
        echo "Free space on the volume, or lower E2E_NEXT_RUN_RESERVE_MB if a smaller run is intended." >&2
        exit 1
    fi
    echo "disk: app/build=${build_kb}KB e2e=${e2e_kb}KB free=${free}KB; next-run reserve=${reserve_kb}KB"
}

check_avd_disk() {
    snap_kb=$(dir_kb "$SNAP_DIR"); snap_kb=${snap_kb:-0}
    if [ "$snap_kb" -gt 0 ]; then
        echo "disk: removing AVD snapshots (${snap_kb}KB); e2e always boots with -no-snapshot." >&2
        rm -rf "$SNAP_DIR"
    fi
    avd_kb=$(dir_kb "$AVD_DIR"); avd_kb=${avd_kb:-0}
    if [ "$avd_kb" -gt "$((AVD_DATA_BUDGET_MB * 1024))" ]; then
        echo "disk: AVD data ${avd_kb}KB is over the ${AVD_DATA_BUDGET_MB}MB budget; adding -wipe-data." >&2
        WIPE_DATA=1
    fi
}

ensure_sdk() {
    if sdk_missing; then
        [ -x "$JAVA_HOME/bin/java" ] || {
            echo "E2E FAILED: JDK 17 not found at $JAVA_HOME." >&2
            exit 1
        }
        need_kb=$((SDK_INSTALL_RESERVE_MB * 1024))
        have=$(free_kb)
        if [ "$have" -lt "$need_kb" ]; then
            fail "installing emulator packages needs ~${SDK_INSTALL_RESERVE_MB}MB free; only ${have}KB available"
        fi
        echo "installing Android emulator packages (first run)..." >&2
        yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
            "cmdline-tools;latest" "emulator" "$IMAGE" >&2 || true
        # Fall back to the Homebrew sdkmanager when the SDK-local one was just installed.
        if [ ! -x "$SDKMANAGER" ]; then
            SDKMANAGER=$(command -v sdkmanager || true)
            [ -n "$SDKMANAGER" ] || fail "sdkmanager not found"
            yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "cmdline-tools;latest" "emulator" "$IMAGE" >&2
        fi
        AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
        [ -x "$EMULATOR" ] || fail "emulator still missing after install"
        [ -x "$AVDMANAGER" ] || fail "avdmanager still missing after install"
        [ -d "$IMAGE_DIR" ] || fail "system image still missing after install"
    fi
}

ensure_avd() {
    if ! "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: $AVD"; then
        echo "creating AVD $AVD" >&2
        echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" --device "pixel_6" --force >&2
    fi
    cfg="$HOME/.android/avd/$AVD.avd/config.ini"
    [ -f "$cfg" ] || fail "AVD config missing: $cfg"
    python3 - "$cfg" "$RAM_MB" <<'PY'
from pathlib import Path
import sys
path, ram = Path(sys.argv[1]), sys.argv[2]
values = {
    "hw.ramSize": ram,
    "disk.dataPartition.size": "6G",
    "showDeviceFrame": "no",
    "hw.keyboard": "yes",
    "hw.gpu.enabled": "yes",
    "hw.gpu.mode": "swiftshader_indirect",
}
lines = path.read_text().splitlines()
seen = set()
out = []
for line in lines:
    key = line.split("=", 1)[0]
    if key in values:
        out.append(f"{key}={values[key]}")
        seen.add(key)
    else:
        out.append(line)
for key, value in values.items():
    if key not in seen:
        out.append(f"{key}={value}")
path.write_text("\n".join(out) + "\n")
PY
}

kill_stale_emulator() {
    if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
        n=0
        while "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; do
            n=$((n + 1))
            [ "$n" -ge 20 ] && break
            sleep 1
        done
        if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
            fail "stale emulator $SERIAL did not stop within 20s"
        fi
    fi
}

wait_boot() {
    deadline=$(( $(date +%s) + 240 ))
    while ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; do
        if [ "$(date +%s)" -ge "$deadline" ]; then
            fail "emulator did not appear within 240s"
        fi
        sleep 2
    done
    while [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        if [ "$(date +%s)" -ge "$deadline" ]; then
            fail "emulator did not finish booting in 240s"
        fi
        sleep 3
    done
    "$ADB" -s "$SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
}

dump_window() {
    "$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || return 1
    "$ADB" -s "$SERIAL" pull /sdcard/window.xml "$WINDOW" >/dev/null 2>&1 || return 1
    [ -s "$WINDOW" ]
}

tap_text() {
    text=$1
    dump_window || return 1
    coords=$(python3 - "$WINDOW" "$text" <<'PY'
import re
import sys
path, wanted = sys.argv[1], sys.argv[2]
xml = open(path, encoding="utf-8", errors="replace").read()
for match in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if match.group(1) == wanted:
        x1, y1, x2, y2 = map(int, match.group(2, 3, 4, 5))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
    [ -n "$coords" ] || return 1
    "$ADB" -s "$SERIAL" shell input tap $coords
    return 0
}

assert_text() {
    text=$1
    dump_window || fail "could not dump the UI"
    grep -q "text=\"$text\"" "$WINDOW" || {
        echo "UI texts:" >&2
        grep -o 'text="[^"]*"' "$WINDOW" | head -30 >&2 || true
        fail "expected UI text '$text' not found"
    }
}

if [ "$SIZE_ONLY" = "1" ]; then
    disk_report
    exit 0
fi

check_memory
check_disk
ensure_sdk
kill_stale_emulator
ensure_avd
check_avd_disk
check_disk
mkdir -p "$OUT"
: > "$LOG"

echo "booting $AVD on $SERIAL..." >&2
set -- -avd "$AVD" -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -no-snapshot -no-metrics \
    -port "$PORT" -memory "$RAM_MB"
if [ "$WIPE_DATA" = "1" ]; then
    set -- "$@" -wipe-data
fi
"$EMULATOR" "$@" >> "$LOG" 2>&1 &
EMU_PID=$!
wait_boot
echo "emulator: $("$ADB" -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r') / $("$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"

if [ "$SKIP_BUILD" != "1" ]; then
    "$ROOT/gradlew" :app:assembleOtherDebug --console=plain
fi
APK=$(find "$ROOT/app/build/outputs/apk/other/debug" -name '*.apk' -type f | head -n 1)
[ -n "$APK" ] || fail "debug APK not found; run without --no-build"
echo "apk: $APK"

if ! "$ADB" -s "$SERIAL" install -r -g "$APK"; then
    "$ADB" -s "$SERIAL" uninstall "$PKG" >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" install -g "$APK"
fi
"$ADB" -s "$SERIAL" shell pm clear "$PKG" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" logcat -c
launch=$("$ADB" -s "$SERIAL" shell am start -W -n "$ACTIVITY" 2>&1)
echo "$launch"
echo "$launch" | grep -q 'Status: ok' || fail "MainActivity did not start"
sleep 2
[ -n "$("$ADB" -s "$SERIAL" shell pidof "$PKG" | tr -d '\r')" ] || fail "app process is not running"

# First run shows the update-check prompt. Dismiss it and assert the main UI.
if dump_window && grep -q 'text="Check Update"' "$WINDOW"; then
    tap_text "No, thanks" || fail "could not dismiss the update prompt"
    sleep 1
fi
assert_text "Dashboard"
assert_text "Profiles"
"$ADB" -s "$SERIAL" shell dumpsys activity activities | grep -q "topResumedActivity=.*$PKG/" ||
    fail "app is not the top resumed activity"
if "$ADB" -s "$SERIAL" logcat -d -v brief | grep -qE 'FATAL EXCEPTION|AndroidRuntime.*io\.nekohasekai\.sfa'; then
    "$ADB" -s "$SERIAL" logcat -d -v brief | grep -E 'FATAL EXCEPTION|AndroidRuntime.*io\.nekohasekai\.sfa' | head -30 >&2
    fail "fatal exception in logcat"
fi
"$ADB" -s "$SERIAL" exec-out screencap -p > "$SCREEN"

echo "E2E OK"
echo "  emulator: $SERIAL"
echo "  apk:      $APK"
echo "  window:   $WINDOW"
echo "  screenshot: $SCREEN"
