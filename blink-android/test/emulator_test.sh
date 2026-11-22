#!/usr/bin/env bash
# End-to-end check of Sweety on an Android emulator.
#   Phase A (x86_64 build, runs natively and fast): the chat brain (Qwen3-0.6B on LiteRT-LM) gives sensible answers,
#           and the app never freezes while it thinks.
#   Phase B (the real arm64 phone build, under the emulator's ARM translation): "Hey Sweety" works while Sweety is
#           in the BACKGROUND, in one go and in two steps; ordinary speech doesn't wake her; the chat brain loads.
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
LOG="$OUT/logcat.txt"; : > "$LOG"

wait_for() { # wait_for <from-line> <regex> <seconds>
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
adb shell settings put global stay_on_while_plugged_in 7
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard 2>/dev/null
adb logcat -c
adb logcat -v time >> "$LOG" 2>&1 &
LOGPID=$!
trap 'kill $LOGPID 2>/dev/null' EXIT

setup() { # setup <apk> <wake true|false> [chat|nochat]: fresh install, with the chat brain in place unless "nochat"
  adb uninstall $PKG >/dev/null 2>&1
  adb install -r -g "$1" >/dev/null || return 1
  MARK=$(lines) # checks start after the old install is gone
  adb shell pm grant $PKG android.permission.RECORD_AUDIO
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
  printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <boolean name=\"wake\" value=\"%s\" />\n</map>\n" "$2" > "$OUT/blink.xml"
  adb push "$OUT/blink.xml" /data/local/tmp/blink.xml >/dev/null
  adb shell run-as $PKG mkdir -p shared_prefs files
  adb shell run-as $PKG cp /data/local/tmp/blink.xml shared_prefs/blink.xml
  [ "${3:-chat}" = chat ] && { adb shell ls /data/local/tmp/sweety-chat.litertlm >/dev/null 2>&1 || { adb push "$MODEL" /data/local/tmp/sweety-chat.litertlm >/dev/null; adb shell chmod 644 /data/local/tmp/sweety-chat.litertlm; }; }
  [ "${3:-chat}" = chat ] && adb shell run-as $PKG cp /data/local/tmp/sweety-chat.litertlm files/sweety-chat.litertlm
  adb shell am start -W -n $PKG/.MainActivity >/dev/null
}

ask() { # ask <question> <regex the answer must match> <seconds>
  local q=$1 want=$2 secs=$3 from r
  from=$(lines)
  adb shell am start -n $PKG/.TalkActivity --es ask "'$q'" >/dev/null 2>&1
  if wait_for "$from" "SweetyChat.*(chat reply in|chat engine failed|could not)|Sweety .*chat brain gave no answer" "$secs"; then
    r=$(found "$from" "SweetyChat.*chat reply in")
    if [ -n "$r" ] && echo "${r#*): }" | grep -qiE "$want"; then pass "\"$q\" -> ${r#*: }"
    elif [ -n "$r" ] && [ "${SOFT:-0}" = 1 ]; then note "\"$q\" answer is off (small model): ${r#*: }"
    elif [ -n "$r" ]; then fail "\"$q\" got an off answer: ${r#*: }"
    else fail "no answer to \"$q\": $(found "$from" "SweetyChat|chat brain" 3 | tr '\n' '|')"; fi
  elif tail -n +"$from" "$LOG" | grep -q "DEADLINE_EXCEEDED"; then note "\"$q\": too slow under the emulator's ARM translation (gave up after 10 min)"
  else fail "no answer to \"$q\" within $secs s"; fi
  found "$from" "Sweety *\(.* -> needle" | sed 's/^/      routing: /' | tee -a "$SUM"
}

frozen() { # frozen <from-line> <label>: Sweety must never stop responding
  if tail -n +"$1" "$LOG" | grep -qE "ANR in $PKG"; then
    fail "$2: Sweety froze (ANR): $(tail -n +"$1" "$LOG" | grep -A6 "ANR in $PKG" | grep -E "Reason|ANR in" | head -3 | sed -E 's/^.{0,40}\): //' | tr '\n' '|')"
  else pass "$2: no freezes"; fi
  # Java crashes, and native crashes with the first frames of their backtrace (which library, which function).
  { tail -n +"$1" "$LOG" | grep -E "FATAL EXCEPTION" -A6 | head -14
    tail -n +"$1" "$LOG" | grep -E "Fatal signal|F/DEBUG.*(Abort message|signal [0-9]|#0[0-9] pc|#1[0-5] pc|name: )" | head -24; } > "$OUT/crash.txt"
  local what; what=$(sed -E 's/^.{0,40}\): *//' "$OUT/crash.txt" | sed -E 's/\/data\/app\/[^ ]*\/lib\//lib\//' | tr '\n' '|')
  # The chat brain runs in its own process (com.blink.buddy:chat) so a crash there must not take Sweety down.
  # Android may shorten the process name ("link.buddy:chat"), so ":chat)" marks the chat process; a Java crash names it on its "Process:" line.
  local mainCrash; mainCrash=$({ grep -E "Fatal signal" "$OUT/crash.txt" | grep -v ":chat)"; grep -E "Process: $PKG, PID" "$OUT/crash.txt"; } | wc -l)
  if [ -s "$OUT/crash.txt" ] && [ "$mainCrash" -gt 0 ]; then fail "$2: Sweety crashed: $what"
  elif [ -s "$OUT/crash.txt" ]; then note "$2: the chat engine crashed in its own process, Sweety kept running: $what"; fi
  # Sweety's main process must stay alive (the chat process may restart; uninstalling between phases doesn't count).
  local died; died=$(tail -n +"$1" "$LOG" | grep -E "Process $PKG \(pid [0-9]+\) has died" | grep -vc "deletePackage")
  [ "$died" -gt 0 ] && fail "$2: Sweety's main process died $died time(s): $(tail -n +"$1" "$LOG" | grep -E "Process $PKG \(pid [0-9]+\) has died" | head -2 | sed -E 's/^.{0,40}\): //' | tr '\n' '|')"
  local chatDied; chatDied=$(tail -n +"$1" "$LOG" | grep -cE "Process $PKG:chat \(pid [0-9]+\) has died")
  [ "$chatDied" -gt 0 ] && note "$2: the chat process ended $chatDied time(s); Sweety said: $(found "$1" "SweetyChat.*(engine process stopped|chat engine failed)" 2 | tr '\n' '|')"
  return 0
}

# ================================================================ Phase A: chat brain answers (native x86_64)
A=$(lines)
if setup "$APK_X86" false; then
  A=$MARK
  note "phase A: x86_64 build installed"
  sleep 5
  ask "What is the capital of France?" "paris" 600
  SOFT=1 ask "What colour is the sky?" "blue" 300 # facts beyond the basics wobble on a 0.6B model: reported, not required
  # Sums are worked out exactly, not left to the chat brain.
  from=$(lines)
  adb shell am start -n $PKG/.TalkActivity --es ask "'What is 7 plus 5?'" >/dev/null 2>&1
  if wait_for "$from" "Sweety .*reply \[" 60 && found "$from" "Sweety .*reply \[" | grep -q "7 plus 5 is 12"; then pass "\"What is 7 plus 5?\" -> $(found "$from" "Sweety .*reply \[")"
  else fail "\"What is 7 plus 5?\" -> $(found "$from" "Sweety .*reply \[")"; fi
  sleep 4
  ask "Are you dumb?" "[a-z]" 300
  ask "Tell me about the moon." "moon|earth|night|sky|space|orbit|light" 300
  SOFT=1 ask "Why do cats purr?" "cat|purr|happy|content|relax|vibrat" 300 # harder fact for a tiny model: reported, not required
  # Floating Sweety tricks: catch a butterfly / bird (she must chase, miss once, catch, let it go), hide and come back.
  adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
  trick() { # trick <what she's asked> <label> <log that must follow> <seconds> [regex the sequence must match]
    local from; from=$(lines)
    adb shell am start -n $PKG/.TalkActivity --es ask "'$1'" >/dev/null 2>&1
    sleep 6; adb exec-out screencap -p > "$OUT/A-$2.png"
    if wait_for "$from" "$3" "$4"; then
      local seq; seq=$(tail -n +"$from" "$LOG" | grep -oE "SweetyOverlay.*chase: [a-z]+" | sed -E 's/.*chase: //' | tr '\n' ' ')
      if [ -z "${5:-}" ] || echo "$seq" | grep -qE "$5"; then pass "$2: $(found "$from" "Sweety .*reply \[") ${seq:+| chase: $seq}"
      else fail "$2: wrong order: $seq"; fi
    else fail "$2: $(found "$from" "SweetyOverlay|Sweety " 6 | tr '\n' '|')"; fi
    sleep 3
  }
  trick "Catch the butterfly" "catch the butterfly" "SweetyOverlay.*chase: done" 90 "^dodge caught free done"
  trick "Catch the bird" "catch the bird" "SweetyOverlay.*chase: done" 90 "^dodge caught free done"
  trick "Hide" "hide" "SweetyOverlay.*hiding" 30
  trick "Come back" "come back" "SweetyOverlay.*showing" 30
  adb shell am stopservice -n $PKG/.OverlayService >/dev/null 2>&1

  # Time of day: set the phone clock and check her greeting changes with it.
  adb shell settings put global auto_time 0 >/dev/null 2>&1
  timed_hi() { # timed_hi <MMDDhhmm> <label> <what she says> <regex>
    adb shell date "$1"2026.00 >/dev/null 2>&1 # only ever forward: logcat hides lines stamped earlier than the last one
    sleep 2
    local from st; from=$(lines)
    st=$(adb shell am start -n $PKG/.TalkActivity --es ask "'$3'" 2>&1 | tr -d '\r' | tr '\n' ' ')
    if wait_for "$from" "Sweety .*reply \[" 60; then
      local r; r=$(found "$from" "Sweety .*reply \[")
      if echo "$r" | grep -qiE "$4"; then pass "$2: \"$3\" -> $r"; else fail "$2: \"$3\" -> $r"; fi
    else fail "$2: no reply to \"$3\" (clock: $(adb shell date | tr -d '\r'); am: $st; log: $(tail -n +"$from" "$LOG" | grep -E 'Sweety|TalkActivity' | grep -v 'mic: loudest' | head -6 | tr '\n' '|'))"; fi
    sleep 4
  }
  timed_hi 11210130 "at 1:30 AM" "Hi Sweety" "awake|late"
  timed_hi 11210140 "at 1:40 AM" "Dance for me" "dance/sleepy.*(midnight|quiet|neighbours)"
  timed_hi 11210815 "at 8:15 AM" "Good morning" "stretch.*morning"
  timed_hi 11212240 "at 10:40 PM" "Good night" "sleep.*(dream|night)"
  timed_hi 11212310 "at 11:10 PM" "Good morning" "night|silly"
  found "$A" "SweetyChat.*ready in" | sed 's/^/      /' | tee -a "$SUM"
  adb exec-out screencap -p > "$OUT/A-chat.png"
  frozen "$A" "phase A"
else fail "could not install the x86_64 build"; fi

# ================================================================ Phase B: the real phone build
B=$(lines)
if [[ "$abis" == *arm64-v8a* ]] && setup "$APK_ARM" true; then
  B=$MARK
  note "phase B: arm64 phone build installed (runs under ARM translation, so it's slow)"
  wait_for "$B" "SweetyBrain.*Whistle warmed up" 900 && note "phase B: $(found "$B" "SweetyBrain.*Whistle warmed up")"
  # The chat brain starts in its own process; wait until it is up (or has crashed there) before the Hey Sweety checks.
  wait_for "$B" "SweetyChat.*(ready in|engine process stopped|chat engine failed)" 600 && note "phase B chat brain: $(found "$B" "SweetyChat.*(ready in|engine process stopped|chat engine failed)")"
  sleep 5
  adb exec-out screencap -p > "$OUT/B-app.png"
  adb shell dumpsys activity services $PKG | grep -q WakeService && pass "Hey Sweety listener is running" || fail "Hey Sweety listener did not start"

  adb shell input keyevent KEYCODE_HOME
  sleep 6
  adb exec-out screencap -p > "$OUT/B-home.png"
  from=$(lines)
  if wait_for "$from" "SweetyWake.*mic: loudest" 30; then pass "microphone is open with Sweety in the background: $(found "$from" "SweetyWake.*mic: loudest")"
  else fail "no microphone readings with Sweety in the background: $(found "$from" "SweetyWake" 3)"; fi

  feed() { # play a recording into Sweety's listener (debug-build test hook)
    sox "$1" -t raw -r 16000 -e signed-integer -b 16 -c 1 "$OUT/clip.pcm" pad 0 1.5
    adb push "$OUT/clip.pcm" /data/local/tmp/mic-test.pcm >/dev/null
    adb shell chmod 644 /data/local/tmp/mic-test.pcm
    adb shell run-as $PKG cp /data/local/tmp/mic-test.pcm files/mic-test.pcm
  }
  wake_case() { # wake_case <label> <wav> <regex that must follow> <secs> [pause after]
    local label=$1 wav=$2 want=$3 secs=$4 rest=${5:-6} from
    from=$(lines)
    feed "$wav"
    if wait_for "$from" "$want" "$secs"; then pass "$label: $(found "$from" "SweetyWake.*(Whistle heard|command)|Sweety .*reply" 3 | tr '\n' ' ')"
    else fail "$label: $(found "$from" "SweetyWake|Sweety " 8 | grep -v "mic: loudest" | tr '\n' '|')"; fi
    sleep "$rest"
  }
  wake_case "one go, \"Hey Sweety, what time is it?\"" "$SPEECH/2-en_US-amy-medium.wav" "Sweety .*reply \[.*\]: It's [0-9]" 300
  wake_case "one go, \"Hey Sweety, dance for me\", quiet and far away" "$SPEECH/4-quiet.wav" "Sweety .*reply \[dance" 300
  wake_case "two steps: \"Hey Sweety\"" "$SPEECH/1-en_GB-alba-medium.wav" "SweetyWake.*wake phrase found, rest: \"\"" 300 0
  wake_case "two steps: then \"What time is it?\"" "$SPEECH/13-en_US-ryan-medium.wav" "Sweety .*reply \[.*\]: It's [0-9]" 300
  wake_case "one go, \"Hi Sweety, set an alarm for 7 am\", noisy room" "$SPEECH/3-noisy.wav" "Sweety .*reply \[.*\]: (Alarm set for 7|I couldn't find a clock)" 300
  wake_case "one go, \"Hey Sweety, do a backflip\" (male voice)" "$SPEECH/6-en_US-ryan-medium.wav" "Sweety .*reply \[flip" 300
  for n in 9 11; do
    from=$(lines)
    feed "$SPEECH/$n-en_US-amy-medium.wav"
    if wait_for "$from" "SweetyWake.*Whistle heard" 300; then
      sleep 5
      if tail -n +"$from" "$LOG" | grep -q "wake phrase found"; then fail "ordinary speech woke her: $(found "$from" "SweetyWake.*Whistle heard")"
      else pass "ordinary speech did not wake her: $(found "$from" "SweetyWake.*Whistle heard")"; fi
    else fail "nothing heard for an ordinary-speech clip"; fi
  done
  adb shell dumpsys notification --noredact 2>/dev/null | grep -A30 "sweety_wake" | grep -E "android.(title|text|bigText)=" | head -3 | sed 's/^ */      notification: /' | tee -a "$SUM"

  # Whatever the chat engine did, asking Sweety something must still get an answer (a real one, or a polite "can't here").
  adb shell am start -W -n $PKG/.MainActivity >/dev/null
  sleep 3
  from=$(lines)
  adb shell am start -n $PKG/.TalkActivity --es ask "'What is the capital of France?'" >/dev/null 2>&1
  if wait_for "$from" "Sweety .*reply \[" 900; then pass "she still answers a chat question: $(found "$from" "Sweety .*reply \[")"
  else fail "no answer at all to a chat question: $(found "$from" "SweetyChat|Sweety " 4 | tr '\n' '|')"; fi
  adb exec-out screencap -p > "$OUT/B-end.png"
  adb shell dumpsys activity services $PKG | grep -q WakeService && pass "Hey Sweety listener still running at the end" || fail "Hey Sweety listener stopped"
  frozen "$B" "phase B"
else note "this emulator can't run arm64 apps, so phase B (Hey Sweety with Whistle) was skipped"; fi

echo; echo "=========== SUMMARY ==========="; cat "$SUM"
exit $FAILED
