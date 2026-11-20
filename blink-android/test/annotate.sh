#!/usr/bin/env bash
# Posts test logs as GitHub annotations (readable on the run page without signing in).
#   annotate.sh notice|warning|error "title" file [max-bytes]
kind=$1; title=$2; file=$3; max=${4:-6000}
[ -s "$file" ] || { echo "::$kind title=$title::(empty)"; exit 0; }
body=$(tail -c "$max" "$file" | sed -e 's/%/%25/g' -e 's/\r//g' | awk 'BEGIN{ORS="%0A"}{print}')
echo "::$kind title=$title::$body"
