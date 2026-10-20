#!/usr/bin/env python3
"""Aggregate arena results from downloaded artifacts. usage: aggregate.py DIR > summary.md
Primary metric: wave of death (core destroyed = server printed 'Game over' on the test map). Survivors = no Game over in all waves."""
import json, glob, os, sys, collections, statistics as st
root = sys.argv[1] if len(sys.argv) > 1 else "."
rows = collections.defaultdict(list)
for f in glob.glob(os.path.join(root, "**", "result-*.json"), recursive=True):
    d = json.load(open(f)); d["dir"] = os.path.dirname(f); rows[(d["bot"], d.get("mode", ""))].append(d)
def m(v):
    v = [x for x in v if x is not None]; return round(st.mean(v), 1) if v else "n/a"
out = ["| bot | mode | runs | survived all | death wave histogram (wave: runs) | mean duos at wave 1 / 3 start | survivors' mean core hp | mean death t (s) |", "|---|---|---|---|---|---|---|---|"]
for (b, mode), rs in sorted(rows.items()):
    surv = [r for r in rs if not r.get("core_destroyed")]
    hist = collections.Counter(r["death_wave"] for r in rs if r.get("core_destroyed"))
    h = ", ".join(f"{k}: {v}" for k, v in sorted(hist.items())) or "none"
    d1 = [r["duos_at_wave"][0] for r in rs if r.get("duos_at_wave")]
    d3 = [r["duos_at_wave"][2] for r in rs if len(r.get("duos_at_wave", [])) > 2]
    out.append(f"| {b} | {mode} | {len(rs)} | {len(surv)}/{len(rs)} | {h} | {m(d1)} / {m(d3)} | {m([r['core_hp_final'] for r in surv])} | {m([r.get('death_t') for r in rs if r.get('core_destroyed')])} |")
print("\n".join(out))
print("\nPer-run (waves survived/final hp; X = died, number = wave of death):")
for (b, mode), rs in sorted(rows.items()):
    rs.sort(key=lambda r: r.get("run", 0))
    print(f"- {b} {mode}: " + " ".join((f"X{r['death_wave']}" if r.get("core_destroyed") else f"ok/{r['core_hp_final']}") for r in rs))
