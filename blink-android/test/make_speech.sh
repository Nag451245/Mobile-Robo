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
  espeak-ng -v en-us+f3 -s 150 -w "$out/tmp.wav" "$text"
  to16k "$out/tmp.wav" "$out/$i-espeak.wav"
  echo -e "$expect\t$out/$i-espeak.wav" >> "$list"
done < "$phrases"
rm -f "$out/tmp.wav" "$out/noise.wav"
echo "made $(wc -l < "$list") recordings"
