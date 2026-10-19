#!/usr/bin/env python3
"""Aggregate arena results from downloaded artifacts. usage: aggregate.py DIR > summary.md
Each artifact dir holds <bot>.json (arena result) and optionally telemetry.jsonl, samples.csv."""
import json, glob, os, sys, re, statistics as st
root = sys.argv[1] if len(sys.argv) > 1 else "."
rows = {}
for f in glob.glob(os.path.join(root, "**", "result-*.json"), recursive=True):
    d = json.load(open(f)); d["dir"] = os.path.dirname(f)
    # telemetry: ammo starvation = share of samples where >=1 turret type has low ammo turrets
    low = tot = lostsum = 0; duos_end = None; lost = None
    t = os.path.join(d["dir"], "telemetry.jsonl")
    if os.path.exists(t):
        for line in open(t):
            try: j = json.loads(line)
            except Exception: continue
            if j.get("type") != "sample": continue
            lostsum += sum(v for v in (j.get("lost") or {}).values() if isinstance(v,(int,float)))
            tur = j.get("turrets") or j.get("turret_ammo") or {}
            if isinstance(tur, dict) and tur:
                n = sum(v.get("n", 0) for v in tur.values()); l = sum(v.get("low", 0) for v in tur.values())
                if n: low += l; tot += n
                if "duo" in tur: duos_end = tur["duo"].get("n")
    d["low_ammo_pct"] = round(100 * low / tot, 1) if tot else None
    d["duos_end"] = duos_end; d["lost"] = lostsum if os.path.exists(t) else None
    rows.setdefault((d["bot"], d.get("mode", "")), []).append(d)
out = ["| bot | mode | runs | survived all waves | mean waves | mean core hp final | mean buildings | turrets low-ammo % | mean duos at end | mean blocks lost |", "|---|---|---|---|---|---|---|---|---|---|"]
def m(v):
    v = [x for x in v if x is not None]; return round(st.mean(v), 1) if v else "n/a"
for (b, mode), rs in sorted(rows.items()):
    full = sum(1 for r in rs if not r["core_destroyed"] and r["waves_survived"] >= r["waves_total"])
    out.append(f"| {b} | {mode} | {len(rs)} | {full}/{len(rs)} | {m([r['waves_survived'] for r in rs])} | {m([r['core_hp_final'] for r in rs])} | {m([r['buildings_final'] for r in rs])} | {m([r['low_ammo_pct'] for r in rs])} | {m([r['duos_end'] for r in rs])} | {m([r['lost'] for r in rs])} |")
print("\n".join(out))
