#!/usr/bin/env python3
"""good_enough.py <metrics.json>: exit 0 if the trained Hey Sweety model may ship, else 1 (and say why).

A model ships only when, at its chosen threshold, it wakes on most held-out "Hey Sweety" clips and almost never on
everyday speech; otherwise the app keeps the keyword spotter."""
import json, sys

MAX_FALSE_PER_HOUR = 2.0
MIN_WAKE = 0.80

m = json.load(open(sys.argv[1]))
row = next((r for r in m["rows"] if abs(r["threshold"] - m["threshold"]) < 1e-6), None)
if row is None:
    print("no row for the chosen threshold"); sys.exit(1)
ok = row["false_wakes_per_hour"] <= MAX_FALSE_PER_HOUR and row["wake_held_out"] >= MIN_WAKE
print(f"threshold {m['threshold']}: wakes {row['wake_held_out']:.1%} (need {MIN_WAKE:.0%}), "
      f"false wake-ups {row['false_wakes_per_hour']:.2f}/hour (need <= {MAX_FALSE_PER_HOUR}) -> {'ships' if ok else 'not good enough'}")
sys.exit(0 if ok else 1)
