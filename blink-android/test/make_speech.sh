#!/usr/bin/env bash
# Records every test phrase with several voices (Piper neural voices plus robotic espeak-ng), clean, quiet and noisy,
# as 16 kHz mono WAVs, and writes list.tsv ("expect<TAB>file") for the Whistle check.
#   make_speech.sh phrases.tsv outdir
set -euo pipefail
phrases=$1
out=$2
mkdir -p "$out/voices"
list="$out/list.tsv"
: > "$list"
hf=https://huggingface.co/rhasspy/piper-voices/resolve/main/en
voices="en_US/amy/medium/en_US-amy-medium en_US/ryan/medium/en_US-ryan-medium en_GB/alba/medium/en_GB-alba-medium"
for v in $voices; do
  n=$(basename "$v")
  [ -f "$out/voices/$n.onnx" ] || curl -fsSL --retry 3 -o "$out/voices/$n.onnx" "$hf/$v.onnx"
  [ -f "$out/voices/$n.onnx.json" ] || curl -fsSL --retry 3 -o "$out/voices/$n.onnx.json" "$hf/$v.onnx.json"
done

to16k() { sox "$1" -r 16000 -c 1 -b 16 -e signed-integer "$2" pad 0.4 0.6; }

i=0
while IFS=$'\t' read -r expect text; do
  [ -z "$text" ] && continue
  i=$((i + 1))
  for v in $voices; do
    n=$(basename "$v")
    echo "$text" | piper --model "$out/voices/$n.onnx" --output_file "$out/tmp.wav" >/dev/null 2>&1
    to16k "$out/tmp.wav" "$out/$i-$n.wav"
    echo -e "$expect\t$out/$i-$n.wav" >> "$list"
  done
  # Phone-like versions of the first voice: far away (quiet) and in a noisy room.
  base="$out/$i-en_US-amy-medium.wav"
  sox "$base" "$out/$i-quiet.wav" vol 0.12
  echo -e "$expect\t$out/$i-quiet.wav" >> "$list"
  d=$(soxi -D "$base")
  sox -n -r 16000 -c 1 -b 16 -e signed-integer "$out/noise.wav" synth "$d" pinknoise vol 0.06
  sox -m "$base" "$out/noise.wav" "$out/$i-noisy.wav"
  echo -e "$expect\t$out/$i-noisy.wav" >> "$list"
  # Real-phone-like: a male voice through a narrow phone mic, in a room with echo and background hum, not too loud.
  sox -m "$out/$i-en_US-ryan-medium.wav" "$out/noise.wav" "$out/tmp2.wav" 2>/dev/null || cp "$out/$i-en_US-ryan-medium.wav" "$out/tmp2.wav"
  sox "$out/tmp2.wav" -r 16000 -c 1 -b 16 -e signed-integer "$out/$i-phone.wav" sinc 250-3600 reverb 35 50 60 vol 0.45
  echo -e "$expect\t$out/$i-phone.wav" >> "$list"
  espeak-ng -v en-us+f3 -s 150 -w "$out/tmp.wav" "$text"
  to16k "$out/tmp.wav" "$out/$i-espeak.wav"
  echo -e "$expect\t$out/$i-espeak.wav" >> "$list"
done < "$phrases"
rm -f "$out/tmp.wav" "$out/tmp2.wav" "$out/noise.wav"

# About two minutes of ordinary chatter (with near-misses like "sweet", "sweater", "hey there") to count false wake-ups.
chatter="Hey there, how was your day? It was sweet of you to call. I bought a new sweater and some sweets for the kids.
Okay, so the meeting is at three. Hello, can you hear me? The street was busy and the weather was hot and sweaty.
Hi everyone, welcome back to the show. Let's see what the switch does. My sister Swati is coming over on Sunday.
Okay, let's go. Hey, wait for me! She said it was the sweetest thing. Honestly the suite at the hotel was amazing.
Turn the volume down a little. What time does the train leave? I think we should order pizza tonight.
Can you send me the report? Sure, I'll do it after lunch. Hey Siri and Alexa are both listening, apparently.
The kids played in the garden all afternoon. We watched a movie and went to sleep early."
: > "$out/chatter.list"
k=0
for v in $voices; do
  n=$(basename "$v"); k=$((k + 1))
  echo "$chatter" | piper --model "$out/voices/$n.onnx" --output_file "$out/chat$k.wav" >/dev/null 2>&1
  sox "$out/chat$k.wav" -r 16000 -c 1 -b 16 -e signed-integer "$out/chat$k-16k.wav"
  echo "$out/chat$k-16k.wav" >> "$out/chatter.list"
done
sox $(cat "$out/chatter.list") "$out/long_negative.wav"
rm -f "$out"/chat*.wav "$out/chatter.list"
echo "made $(wc -l < "$list") recordings and $(soxi -d "$out/long_negative.wav") of ordinary chatter"
