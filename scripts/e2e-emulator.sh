#!/bin/sh
# Headless Android emulator smoke test on the Mini.
#
# Preflight: disk cleanup threshold, hard free-space floor, host memory floor.
# Then boots an arm64 API 36 AVD, builds/installs the otherDebug APK, launches
# MainActivity, dismisses the first-run update prompt, and asserts the
# Dashboard/Profiles UI. Real VPN/service behavior still needs a physical device.
#
#   ./scripts/e2e-emulator.sh
#   ./scripts/e2e-emulator.sh --no-build
#   E2E_KEEP_EMULATOR=1 ./scripts/e2e-emulator.sh
#
# Thresholds:
#   E2E_CLEAN_THRESHOLD_GB (default 20)  run cleanup below this free space
#   E2E_MIN_FREE_GB        (default 10)  hard stop below this free space
#   E2E_MIN_TOTAL_MEM_GB   (default 16)  host total memory floor
#   E2E_MIN_FREE_MEM_GB    (default 4)   host free+inactive memory floor
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

AVD=${E2E_AVD:-wondersing-e2e}
PORT=${E2E_PORT:-5556}
SERIAL="emulator-$PORT"
RAM_MB=${E2E_RAM_MB:-3072}
SKIP_BUILD=0
for arg in "$@"; do
    case "$arg" in
        --no-build) SKIP_BUILD=1 ;;
        -h|--help)
            sed -n '2,20p' "$0"
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
    if [ "${E2E_KEEP_EMULATOR:-0}" != "1" ]; then
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
        if [ -n "$EMU_PID" ]; then
            kill "$EMU_PID" >/dev/null 2>&1 || true
        fi
    else
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

check_disk() {
    clean_kb=$(( ${E2E_CLEAN_THRESHOLD_GB:-20} * 1024 * 1024 ))
    min_kb=$(( ${E2E_MIN_FREE_GB:-10} * 1024 * 1024 ))
    free=$(free_kb)
    if [ "$free" -lt "$clean_kb" ]; then
        echo "disk: ${free}KB free is below the cleanup threshold ${clean_kb}KB; cleaning build and AVD snapshots." >&2
        "$ROOT/scripts/local-test.sh" --clean
        rm -rf "$HOME/.android/avd/$AVD.avd/snapshots"
        free=$(free_kb)
    fi
    if [ "$free" -lt "$min_kb" ]; then
        echo "E2E FAILED: ${free}KB free < hard minimum ${min_kb}KB." >&2
        exit 1
    fi
    echo "disk: ${free}KB free"
}

ensure_sdk() {
    if [ ! -x "$SDKMANAGER" ] || [ ! -x "$AVDMANAGER" ] || [ ! -x "$ADB" ] || [ ! -x "$EMULATOR" ] ||
        [ ! -d "$IMAGE_DIR" ]; then
        [ -x "$JAVA_HOME/bin/java" ] || {
            echo "E2E FAILED: JDK 17 not found at $JAVA_HOME." >&2
            exit 1
        }
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

check_memory
check_disk
ensure_sdk
check_disk
ensure_avd
mkdir -p "$OUT"
: > "$LOG"

kill_stale_emulator
echo "booting $AVD on $SERIAL..." >&2
"$EMULATOR" -avd "$AVD" -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -no-snapshot -no-metrics \
    -port "$PORT" -memory "$RAM_MB" >> "$LOG" 2>&1 &
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
