#!/usr/bin/env bash
#
# Drives the Plan 3 step-detector experiment over adb.
#
# The four runs have to start and stop at known instants, with the screen off
# and the phone pocketed for the whole interval. Tapping through the dev screen
# mid-run means unlocking the phone, which wakes the application processor and
# destroys the very thing being measured. So the service is started from the
# host instead, and the phone is never touched between start and stop.
#
# Usage:
#   scripts/step-spike.sh run <wake|nowake> <media|health> <nobatch|batch60> <minutes>
#   scripts/step-spike.sh stop
#   scripts/step-spike.sh pull [dest]
#   scripts/step-spike.sh summary [dest] [expected-steps]
#   scripts/step-spike.sh status
#
# Untethered over WiFi, set ANDROID_SERIAL so the link can be re-established
# after the phone sleeps and drops it:
#
#   export ANDROID_SERIAL=10.0.0.121:5555
#
set -euo pipefail

PKG=lol.alphaliu01.runningmusic.debug
SERVICE="$PKG/lol.alphaliu01.runningmusic.steps.StepRecorderService"
DEVICE_DIR="/sdcard/Android/data/$PKG/files/step-recordings"
DEST="${DEST:-build/step-recordings}"

# Over WiFi the link drops as soon as the phone sleeps in a pocket, which is
# exactly the state we want it in. The recording does not care -- it keeps
# writing to the file with nothing attached -- but every command after the start
# has to be able to find the device again.
reconnect() {
  adb get-state > /dev/null 2>&1 && return 0
  [ -n "${ANDROID_SERIAL:-}" ] || return 1
  echo "adb link dropped, reconnecting to $ANDROID_SERIAL ..."
  for _ in $(seq 1 30); do
    adb connect "$ANDROID_SERIAL" > /dev/null 2>&1
    adb get-state > /dev/null 2>&1 && { echo "reconnected"; return 0; }
    sleep 2
  done
  echo "could not reconnect; wake the phone and run: $0 stop" >&2
  return 1
}

# Prints the comment block above, so the usage text cannot drift from the docs.
usage() {
  awk 'NR>2 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "$0"
  exit 1
}

cmd_run() {
  [ $# -eq 4 ] || usage
  local variant="$1" fgs="$2" batch="$3" minutes="$4"

  case "$variant" in
    wake)   wake=true ;;
    nowake) wake=false ;;
    *) echo "variant must be wake or nowake" >&2; exit 1 ;;
  esac
  case "$fgs" in
    media)  fgs_type=mediaPlayback ;;
    health) fgs_type=health ;;
    *) echo "fgs must be media or health" >&2; exit 1 ;;
  esac
  case "$batch" in
    nobatch)  latency=0 ;;
    batch60)  latency=60000000 ;;
    *) echo "batch must be nobatch or batch60" >&2; exit 1 ;;
  esac

  adb shell pm grant $PKG android.permission.ACTIVITY_RECOGNITION 2>/dev/null || true
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true

  echo "Starting: $variant / $fgs_type / $batch for ${minutes}m"
  adb shell am start-foreground-service -n "$SERVICE" \
    --ez wakeUp "$wake" --ei batchLatencyUs "$latency" \
    --ez accelerometer false --es fgsType "$fgs_type" > /dev/null

  sleep 2
  adb shell dumpsys sensorservice | grep -i runningmusic | head -2

  echo
  echo "Screen off, phone in pocket, start walking. Count your steps out loud."
  echo "Stopping automatically in ${minutes} minutes."
  adb shell input keyevent KEYCODE_SLEEP

  sleep "$((minutes * 60))"

  cmd_stop
}

cmd_stop() {
  reconnect || return 1
  adb shell am stopservice -n "$SERVICE" > /dev/null
  sleep 2
  adb logcat -d -s StepRecorder:I | tail -2
}

cmd_pull() {
  local dest="${1:-$DEST}"
  mkdir -p "$dest"
  adb pull "$DEVICE_DIR/." "$dest" > /dev/null
  ls -la "$dest"
}

cmd_summary() {
  local dest="${1:-$DEST}" expected="${2:-}"
  local args="$dest"
  if [ -n "$expected" ]; then
    args="$args --expected-steps $expected"
  fi
  ./gradlew -q :cadence:recordingSummary --args="$args"
}

cmd_status() {
  echo "--- foreground service ---"
  adb shell dumpsys activity services $PKG | grep -E "isForeground|types=" || echo "not running"
  echo "--- sensor registrations ---"
  adb shell dumpsys sensorservice | grep -i runningmusic | head -5
  echo "--- files ---"
  adb shell ls -la "$DEVICE_DIR" 2>/dev/null || echo "no recordings yet"
}

case "${1:-}" in
  run)     shift; cmd_run "$@" ;;
  stop)    cmd_stop ;;
  pull)    shift; cmd_pull "$@" ;;
  summary) shift; cmd_summary "$@" ;;
  status)  cmd_status ;;
  *) usage ;;
esac
