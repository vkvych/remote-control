#!/usr/bin/env bash
#
# Spin up two Android emulators — one "child" (agent) and one "parent" (controller) —
# build and install both APKs, and bridge their networks so the parent can reach the
# child's Ktor server. Meant for local end-to-end testing without physical devices.
#
# The two emulators each sit behind their own NAT and cannot see each other directly.
# We bridge them through the host: `adb forward` exposes the child's server port on the
# host loopback, and the parent reaches the host loopback via the emulator alias 10.0.2.2.
#
#   child guest :8765  --adb forward-->  host 127.0.0.1:8765
#   parent  --> 10.0.2.2:8765 --> host loopback --> child guest :8765
#
# So in the parent app you pair against host 10.0.2.2, port 8765.
#
# Requirements: hardware virtualization (/dev/kvm), a JDK 17 in JAVA_HOME, and an Android
# SDK in ANDROID_HOME. See docs/SETUP.md § Prerequisites and § Testing on emulators.
#
# Usage:
#   scripts/run-emulators.sh          # set everything up and boot both emulators
#   scripts/run-emulators.sh --stop   # tear down: kill emulators, drop the forward
#
set -euo pipefail

# --- Configuration -----------------------------------------------------------
SYSTEM_IMAGE="system-images;android-36;google_apis;x86_64"
DEVICE_PROFILE="pixel_6"
CHILD_AVD="rc_child"
PARENT_AVD="rc_parent"
CHILD_PORT=5554          # emulator console port -> serial emulator-5554
PARENT_PORT=5556         # emulator console port -> serial emulator-5556
CHILD_SERIAL="emulator-${CHILD_PORT}"
PARENT_SERIAL="emulator-${PARENT_PORT}"
AGENT_PORT=8765          # DEFAULT_PORT from the shared protocol

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHILD_APK="$REPO_ROOT/app-child/build/outputs/apk/debug/app-child-debug.apk"
PARENT_APK="$REPO_ROOT/app-parent/build/outputs/apk/debug/app-parent-debug.apk"
CHILD_PKG="com.vkvych.remotecontrol.child"
PARENT_PKG="com.vkvych.remotecontrol.parent"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

# --- Locate the SDK tools ----------------------------------------------------
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[[ -d "$ANDROID_HOME" ]] || die "ANDROID_HOME ($ANDROID_HOME) not found. See docs/SETUP.md § Prerequisites."
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

stop_all() {
    log "Stopping emulators and removing the port forward"
    "$ADB" -s "$CHILD_SERIAL" forward --remove "tcp:${AGENT_PORT}" 2>/dev/null || true
    for s in "$CHILD_SERIAL" "$PARENT_SERIAL"; do
        "$ADB" -s "$s" emu kill 2>/dev/null || true
    done
    log "Done."
}

if [[ "${1:-}" == "--stop" ]]; then
    stop_all
    exit 0
fi

# --- Preflight ---------------------------------------------------------------
[[ -e /dev/kvm ]] || die "/dev/kvm is missing — hardware virtualization is off. See docs/SETUP.md § Prerequisites (enabling VT-x)."
[[ -n "${JAVA_HOME:-}" ]] || warn "JAVA_HOME is unset; the SDK tools and Gradle need a JDK 17. See docs/SETUP.md § Prerequisites."
command -v "$ADB" >/dev/null 2>&1 || [[ -x "$ADB" ]] || die "adb not found at $ADB (install the platform-tools SDK package)."

# --- Install SDK packages if needed ------------------------------------------
if [[ ! -x "$EMULATOR" ]]; then
    log "Installing the emulator and system image ($SYSTEM_IMAGE) — this is a large download"
    yes | "$SDKMANAGER" "emulator" "$SYSTEM_IMAGE" >/dev/null
fi
if [[ ! -d "$ANDROID_HOME/system-images/android-36" ]]; then
    log "Installing system image $SYSTEM_IMAGE"
    yes | "$SDKMANAGER" "$SYSTEM_IMAGE" >/dev/null
fi

# --- Create the two AVDs (idempotent) ----------------------------------------
create_avd() {
    local name="$1"
    if "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: ${name}$"; then
        log "AVD $name already exists"
    else
        log "Creating AVD $name"
        echo "no" | "$AVDMANAGER" create avd -n "$name" -k "$SYSTEM_IMAGE" -d "$DEVICE_PROFILE" --force
    fi
}
create_avd "$CHILD_AVD"
create_avd "$PARENT_AVD"

# --- Boot an emulator headless and wait for it -------------------------------
boot_emulator() {
    local avd="$1" port="$2" serial="$3"
    if "$ADB" devices | grep -q "^${serial}\b"; then
        log "$serial already running"
        return
    fi
    log "Booting $avd on port $port (headless)"
    "$EMULATOR" -avd "$avd" -port "$port" \
        -no-window -no-audio -no-boot-anim -no-snapshot \
        -gpu swiftshader_indirect >/dev/null 2>&1 &
    log "Waiting for $serial to come online"
    "$ADB" -s "$serial" wait-for-device
    log "Waiting for $avd to finish booting"
    until [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
        sleep 2
    done
    log "$avd booted"
}
boot_emulator "$CHILD_AVD"  "$CHILD_PORT"  "$CHILD_SERIAL"
boot_emulator "$PARENT_AVD" "$PARENT_PORT" "$PARENT_SERIAL"

# --- Build the APKs if they are missing --------------------------------------
if [[ ! -f "$CHILD_APK" || ! -f "$PARENT_APK" ]]; then
    log "Building debug APKs"
    (cd "$REPO_ROOT" && ./gradlew :app-child:assembleDebug :app-parent:assembleDebug)
fi

# --- Install ------------------------------------------------------------------
log "Installing the agent on the child emulator"
"$ADB" -s "$CHILD_SERIAL" install -r "$CHILD_APK"
log "Installing the controller on the parent emulator"
"$ADB" -s "$PARENT_SERIAL" install -r "$PARENT_APK"

# --- Bridge the child's server port onto the host loopback -------------------
log "Forwarding $CHILD_SERIAL guest :$AGENT_PORT -> host 127.0.0.1:$AGENT_PORT"
"$ADB" -s "$CHILD_SERIAL" forward "tcp:${AGENT_PORT}" "tcp:${AGENT_PORT}"

# --- Launch both apps ---------------------------------------------------------
log "Launching the apps"
"$ADB" -s "$CHILD_SERIAL"  shell monkey -p "$CHILD_PKG"  -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
"$ADB" -s "$PARENT_SERIAL" shell monkey -p "$PARENT_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

cat <<EOF

--------------------------------------------------------------------------------
Both emulators are up and the apps are installed.

Finish the pairing by hand (the agent only starts its server once you tap it):

  1. On the CHILD emulator ($CHILD_SERIAL): open "Remote Control Agent",
     tap "Start agent", then "Show pairing code" and note the six digits.
       - Verify the server is reachable from the host:
           curl -s http://127.0.0.1:${AGENT_PORT}/health
  2. On the PARENT emulator ($PARENT_SERIAL): open "Remote Control", and pair with
           Host: 10.0.2.2
           Port: ${AGENT_PORT}
           Code: the six digits from step 1

Tear everything down with:
   scripts/run-emulators.sh --stop
--------------------------------------------------------------------------------
EOF
