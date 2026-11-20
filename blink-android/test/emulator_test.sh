#!/usr/bin/env bash
# End-to-end check of Sweety on an Android emulator:
#   1. the chat brain (Qwen3-0.6B on LiteRT-LM) answers questions,
#   2. "Hey Sweety" works while Sweety is in the BACKGROUND (home screen showing), in one go and in two steps,
#   3. ordinary speech doesn't wake her.
# Usage: emulator_test.sh arm64.apk x86_64.apk chat.litertlm speech_dir out_dir
set -u
APK_ARM=$1; APK_X86=$2; MODEL=$3; SPEECH=$4; OUT=$5
PKG=com.blink.buddy
mkdir -p "$OUT"
SUM="$OUT/summary.txt"; : > "$SUM"
FAILED=0
pass() { echo "PASS  $*" | tee -a "$SUM"; }
fail() { echo "FAIL  $*" | tee -a "$SUM"; FAILED=1; }
note() { echo "NOTE  $*" | tee -a "$SUM"; }
LOG="$OUT/logcat.txt"

# wait_for <from-line> <regex> <seconds>: true when a log line after <from-line> matches
wait_for() {
  local from=$1 re=$2 secs=$3 t=0
  while [ $t -lt "$secs" ]; do
    tail -n +"$from" "$LOG" | grep -qE "$re" && return 0
    sleep 3; t=$((t + 3))
  done
  return 1
}
lines() { wc -l < "$LOG"; }
found() { tail -n +"$1" "$LOG" | grep -E "$2" | head -n "${3:-1}" | sed -E 's/^.{0,40}\): //'; }

adb wait-for-device
abis=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
note "Android API $sdk, ABIs: $abis"
adb root >/dev/null 2>&1; sleep 4; adb wait-for-device

FLAVOR=x86_64
if [[ "$abis" == *arm64-v8a* ]] && adb install -r -g "$APK_ARM"; then FLAVOR=arm64
else adb install -r -g "$APK_X86" || { fail "could not install Sweety"; exit 1; }; fi
note "installed the $FLAVOR build"
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell settings put global stay_on_while_plugged_in 7
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard 2>/dev/null

# Turn on "Hey Sweety" and put the chat brain where the in-app download would.
cat > "$OUT/blink.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="wake" value="true" />
</map>
EOF
adb push "$OUT/blink.xml" /data/local/tmp/blink.xml >/dev/null
adb shell run-as $PKG mkdir -p shared_prefs files
adb shell run-as $PKG cp /data/local/tmp/blink.xml shared_prefs/blink.xml
adb push "$MODEL" /data/local/tmp/sweety-chat.litertlm >/dev/null
adb shell chmod 644 /data/local/tmp/sweety-chat.litertlm
adb shell run-as $PKG cp /data/local/tmp/sweety-chat.litertlm files/sweety-chat.litertlm
adb shell rm -f /data/local/tmp/sweety-chat.litertlm
adb shell run-as $PKG ls -la files | tee -a "$OUT/files.txt"

adb logcat -c
adb logcat -v time > "$LOG" 2>&1 &
LOGPID=$!
trap 'kill $LOGPID 2>/dev/null' EXIT
sleep 2

adb shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 15
adb exec-out screencap -p > "$OUT/1-app.png"
adb shell dumpsys activity services $PKG | grep -q WakeService && pass "Hey Sweety listener is running" || fail "Hey Sweety listener did not start"

# ---------------------------------------------------------------- 1. chat brain
ask() {
  local q=$1 from
  from=$(lines)
  adb shell am start -n $PKG/.TalkActivity --es ask "'$q'" >/dev/null 2>&1
  if wait_for "$from" "SweetyChat.*(chat reply in|chat engine failed|chat failed|could not)|Sweety .*chat brain gave no answer" 900; then
    local r; r=$(found "$from" "SweetyChat.*chat reply in")
    if [ -n "$r" ] && ! echo "$r" | grep -qE "ms \([a-z]+\): *$"; then pass "chat brain answered \"$q\": $r"
    else fail "chat brain did not answer \"$q\": $(found "$from" "SweetyChat|chat brain" 3)"; fi
  else fail "no chat answer to \"$q\" within 15 minutes"; fi
  found "$from" "Sweety *\(.* -> needle" | sed 's/^/      routing: /' | tee -a "$SUM"
}
ask "Why is the sky blue?"
ask "Are you dumb?"
ask "Tell me about the moon."
found 1 "SweetyChat.*ready in" | sed 's/^/      /' | tee -a "$SUM"
adb exec-out screencap -p > "$OUT/2-chat.png"

# ---------------------------------------------------------------- 2. Hey Sweety in the background
adb shell input keyevent KEYCODE_HOME
sleep 6
adb exec-out screencap -p > "$OUT/3-home.png"
from=$(lines)
if wait_for "$from" "SweetyWake.*mic: loudest" 30; then pass "microphone is open with Sweety in the background: $(found "$from" "SweetyWake.*mic: loudest")"
else fail "no microphone readings with Sweety in the background: $(found "$from" "SweetyWake" 3)"; fi

feed() { # feed <wav>: play a recording into Sweety's listener (test hook)
  sox "$1" -t raw -r 16000 -e signed-integer -b 16 -c 1 "$OUT/clip.pcm" pad 0 1.5
  adb push "$OUT/clip.pcm" /data/local/tmp/mic-test.pcm >/dev/null
  adb shell chmod 644 /data/local/tmp/mic-test.pcm
  adb shell run-as $PKG cp /data/local/tmp/mic-test.pcm files/mic-test.pcm
}

wake_case() { # wake_case <label> <wav> <regex that must follow> <secs> [pause after]
  local label=$1 wav=$2 want=$3 secs=$4 rest=${5:-6} from
  from=$(lines)
  feed "$wav"
  if wait_for "$from" "$want" "$secs"; then pass "$label: $(found "$from" "SweetyWake.*(Whistle heard|command)" 2 | tr '\n' ' ')"
  else fail "$label: $(found "$from" "SweetyWake|Sweety " 6 | tr '\n' '|')"; fi
  sleep "$rest"
}

if [ "$FLAVOR" = arm64 ]; then
  wake_case "one-go \"Hey Sweety, what time is it?\" in background" "$SPEECH/2-en_US-amy-medium.wav" "Sweety .*reply \[.*\]: It's [0-9]" 300
  wake_case "one-go \"Hey Sweety, dance for me\" (quiet, far away)" "$SPEECH/4-quiet.wav" "Sweety .*reply \[dance" 300
  wake_case "two-step: \"Hey Sweety\"" "$SPEECH/1-en_GB-alba-medium.wav" "SweetyWake.*wake phrase found, rest: \"\"" 300 0
  wake_case "two-step: then \"What time is it?\"" "$SPEECH/13-en_US-ryan-medium.wav" "Sweety .*reply \[.*\]: It's [0-9]" 300
  wake_case "one-go \"Hi Sweety, set an alarm for 7 am\" (noisy room)" "$SPEECH/3-noisy.wav" "Sweety .*(reply \[.*\]: Alarm set|\"set_alarm\"|=> set_alarm)" 300
  from=$(lines)
  feed "$SPEECH/9-en_US-amy-medium.wav"
  if wait_for "$from" "SweetyWake.*Whistle heard" 300; then
    sleep 5
    if tail -n +"$from" "$LOG" | grep -q "wake phrase found"; then fail "\"What a sweet day it is\" woke her up"
    else pass "\"What a sweet day it is\" did not wake her: $(found "$from" "SweetyWake.*Whistle heard")"; fi
  else fail "nothing heard for the no-wake clip"; fi
  adb shell dumpsys notification --noredact 2>/dev/null | grep -A2 "sweety_wake" | grep -E "android.text|android.title" | head -4 | sed 's/^/      notification: /' | tee -a "$SUM"
else
  note "x86_64 build: Whistle (arm64 only) can't load here, so only the background microphone was checked"
fi
adb exec-out screencap -p > "$OUT/4-end.png"
adb shell dumpsys activity services $PKG | grep -q WakeService && pass "Hey Sweety listener still running at the end" || fail "Hey Sweety listener stopped"
grep -E "FATAL EXCEPTION|AndroidRuntime|SIGSEGV|SIGILL|ANR in $PKG" "$LOG" | head -20 > "$OUT/crashes.txt"
[ -s "$OUT/crashes.txt" ] && fail "crash or freeze in the log: $(head -3 "$OUT/crashes.txt" | tr '\n' '|')"

echo; echo "=========== SUMMARY ==========="; cat "$SUM"
exit $FAILED
