#!/usr/bin/env python3
"""
compare.py - compare bot telemetry runs against human timing checkpoints.

Usage:
  compare.py RUN.jsonl [RUN2.jsonl ...] [--profiles survival_generic pvp_ranked_historical]
             [--checkpoints human_checkpoints.json] [--out report.md]

Reads Telemetry.java JSONL samples, evaluates checkpoint first-achievement times,
runs weakness detectors on the time series, and prints a markdown report with
ranked, evidence-linked fixes. Data only: every claim cites sample timestamps.
"""
import json, sys, argparse

KNOWN_AIR = {"flare", "horizon", "zenith", "quad", "mega", "poly", "mono", "antumbra", "eclipse", "avert", "crawler-air"}
OPS = {">=": lambda a, b: a >= b, ">": lambda a, b: a > b, "<=": lambda a, b: a <= b, "<": lambda a, b: a < b, "==": lambda a, b: a == b}

def load_run(path):
    header, samples, waves = {}, [], []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line: continue
            try: j = json.loads(line)
            except json.JSONDecodeError: continue
            t = j.get("type")
            if t == "header": header = j
            elif t == "sample": samples.append(j)
            elif t == "wave": waves.append(j)
    return {"path": path, "header": header, "samples": samples, "waves": waves}

def metric_value(m, s):
    if "blocks_sum" in m: return sum(s.get("blocks", {}).get(b, 0) for b in m["blocks_sum"])
    if "own_units_sum" in m: return sum(s.get("own_units", {}).get(u, 0) for u in m["own_units_sum"])
    if "block" in m: return s.get("blocks", {}).get(m["block"], 0)
    if "item" in m: return s.get("items", {}).get(m["item"], 0)
    if "own_unit" in m: return s.get("own_units", {}).get(m["own_unit"], 0)
    if "enemy_unit" in m: return s.get("enemy_units", {}).get(m["enemy_unit"], 0)
    if "turret" in m: return s.get("turrets", {}).get(m["turret"], {}).get(m.get("field", "n"), 0)
    if "power_satisfaction" in m: return s.get("power", {}).get("satisfaction", 1.0)
    raise ValueError(f"unknown metric {m}")

def metric_ok(m, s):
    for op, v in m.items():
        if op in ("block", "blocks_sum", "own_units_sum", "item", "turret", "field", "own_unit", "enemy_unit", "power_satisfaction"): continue
        if not OPS[op](metric_value(m, s), v): return False
    return True

def first_achieved(samples, metrics):
    for s in samples:
        if all(metric_ok(m, s) for m in metrics): return s["t"]
    return None

def air_seen(samples):
    return any(any(k in KNOWN_AIR for k in s.get("enemy_units", {})) for s in samples)

def eval_checkpoints(run, cp, profiles):
    rows = []
    for prof in profiles:
        p = cp["profiles"][prof]
        for c in p["checkpoints"]:
            if c.get("conditional") == "air" and not air_seen(run["samples"]):
                rows.append((prof, c["name"], c["window_min"], None, "SKIP (no air threat observed)", c.get("desc", ""), p["label"]))
                continue
            t = first_achieved(run["samples"], c["all"])
            w0, w1 = c["window_min"][0] * 60, c["window_min"][1] * 60
            if t is None: status = "MISSING"
            elif t <= w1: status = "ON" if t >= w0 else "AHEAD"
            else: status = f"LATE +{(t - w1) / 60:.1f}m"
            rows.append((prof, c["name"], c["window_min"], t, status, c.get("desc", ""), p["label"]))
    return rows

def engagements(samples):
    """Contiguous enemies>0 spans."""
    out, cur = [], None
    for s in samples:
        if s["enemies"] > 0:
            if cur is None: cur = {"t0": s["t"], "t1": s["t"], "peak": 0, "hp0": s["core_hp"], "hp1": s["core_hp"], "foes": {}}
            cur["t1"] = s["t"]; cur["peak"] = max(cur["peak"], s["enemies"]); cur["hp1"] = s["core_hp"]
            for k, v in s.get("enemy_units", {}).items(): cur["foes"][k] = max(cur["foes"].get(k, 0), v)
        elif cur is not None:
            out.append(cur); cur = None
    if cur is not None: out.append(cur)
    return out

def detectors(run):
    ss = run["samples"]
    if not ss: return []
    findings = []
    end = ss[-1]
    total_t = max(1, end["t"])

    # W1 core damage
    eng = engagements(ss)
    hp_lost = sum(max(0, e["hp0"] - e["hp1"]) for e in eng)
    if hp_lost > 500:
        findings.append({"sev": 10 if hp_lost > 2500 else 8, "name": "core damage taken",
            "evidence": f"{hp_lost} core hp lost across {len(eng)} engagements; worst: " +
                        "; ".join(f"t={e['t0']}-{e['t1']}s -{e['hp0'] - e['hp1']}hp peak={e['peak']}" for e in sorted(eng, key=lambda e: e['hp0'] - e['hp1'], reverse=True)[:3]),
            "fix": "defenses must be finished before contact with margin (playbook: derive time-to-contact from wave timer; supplied compatible DPS + repair reserve). Raise early duo/scatter counts and wall layers; check per-engagement composition below."})
    # W2 turret starvation during contact
    contact = [s for s in ss if s["enemies"] > 0]
    dry = [s for s in contact if any(t.get("low", 0) > 0 for t in s.get("turrets", {}).values()) or
           (s.get("turrets") and sum(t.get("ammo", 0) for t in s["turrets"].values()) == 0)]
    if contact and len(dry) / len(contact) > 0.15:
        r = len(dry) / len(contact)
        findings.append({"sev": min(9, 3 + 6 * r), "name": "turret ammo starvation during contact",
            "evidence": f"{len(dry)}/{len(contact)} contact samples ({r:.0%}) had a turret with <10 ammo or zero total ammo; first at t={dry[0]['t']}s",
            "fix": "an unfed turret is scenery (research 1.1 [V/C]). Feed lines before count: place duos only when ammo route exists, switch duo ammo to graphite once presses run (2x damage per shot [C]), hand-feed scatters."})
    # W3 unspent stockpile
    span, best = 0, (0, None)
    for i, s in enumerate(ss):
        cu = s.get("items", {}).get("copper", 0)
        if s["t"] > 300 and cu > 1500:
            span += 1
            if span > best[0]: best = (span, s)
        else: span = 0
    if best[0] * 2 >= 120:  # samples every 2s
        mins = best[0] * 2 / 60
        maxcu = max(s.get("items", {}).get("copper", 0) for s in ss)
        findings.append({"sev": min(8, 2 + mins / 2), "name": "unspent copper stockpile",
            "evidence": f"copper>1500 continuously for {mins:.1f} min ending t={best[1]['t']}s; max copper seen {maxcu}; final turrets={sum(v.get('n',0) for v in end.get('turrets',{}).values())}",
            "fix": "known v3 gap: 'did not spend its 10k copper stockpile' (v3 README). Cap scaling: research says bounded expansion (~8 duos/3 scatters) then economy (AdvancedEconomy gates); also wall layers and hail spend copper/graphite."})
    # W4 conveyor sprawl
    conv = end.get("blocks", {}).get("conveyor", 0) + end.get("blocks", {}).get("titanium-conveyor", 0)
    tur = sum(v.get("n", 0) for v in end.get("turrets", {}).values())
    if tur and conv / tur > 12:
        findings.append({"sev": 6 if conv / tur > 20 else 4, "name": "conveyor sprawl vs defense",
            "evidence": f"{conv} conveyors for {tur} turrets ({conv / tur:.1f}/turret) at t={end['t']}s",
            "fix": "v3 economy module spent copper on ~140 conveyors while building only 5-7 duos (v3 README). Gate route length; prefer drill sites near core; spend the copper on supplied turrets first (research: speed beats placement quality early)."})
    # W5 idle mining while rich
    rich_mine = [s for s in ss if s["t"] > 300 and str(s.get("goal", "")).startswith("mine") and s.get("items", {}).get("copper", 0) > 800]
    if len(rich_mine) / max(1, len([s for s in ss if s["t"] > 300])) > 0.2:
        findings.append({"sev": 5, "name": "idle mining while rich",
            "evidence": f"{len(rich_mine)} samples after t=300s mining with copper>800; e.g. t={rich_mine[0]['t']}s goal='{rich_mine[0]['goal']}'",
            "fix": "goal selection lacks useful work when stockpiled: add wall thickening, second graphite press, or scatter coverage as fill goals (research checkpoints 6-9min)."})
    # W6 losses & rebuild churn
    lost, built = {}, {}
    for s in ss:
        for k, v in s.get("lost", {}).items():
            if not k.startswith("build"): lost[k] = lost.get(k, 0) + v
        for k, v in s.get("built", {}).items():
            if not k.startswith("build"): built[k] = built.get(k, 0) + v
    churn = {k: v for k, v in built.items() if v >= 3 and lost.get(k, 0) >= 1 and k in ("duo", "scatter", "hail", "copper-wall", "conveyor")}
    if churn:
        findings.append({"sev": 6, "name": "rebuild churn (blocks lost repeatedly)",
            "evidence": "built counts: " + ", ".join(f"{k}x{v}" for k, v in sorted(churn.items())) + "; lost totals: " + ", ".join(f"{k}x{v}" for k, v in sorted(lost.items())[:8]),
            "fix": "repeated losses at the same sites mean the firing line is underpowered for the wave size, not unlucky: add wall layers in front (mace flame pierces ONE layer [V]) and menders behind the line [C]."})
    # W7 power brownout
    if end.get("power", {}).get("graphs", 0) > 0:
        brown = [s for s in ss if s.get("power", {}).get("satisfaction", 1.0) < 0.95]
        if brown:
            findings.append({"sev": 7, "name": "power brownout",
                "evidence": f"{len(brown)} samples with satisfaction<0.95; worst {min(s['power']['satisfaction'] for s in brown):.2f} at t={min(brown, key=lambda s: s['power']['satisfaction'])['t']}s",
                "fix": "brownouts slow ALL turrets/menders at once (research 2.5 [V]): fix generation before anything else; batteries are not generation (playbook L3)."})
    # W8 drill:press ratio (economy ratio check)
    first_press = first_achieved(ss, [{"block": "graphite-press", ">=": 1}])
    if first_press:
        at = next(s for s in ss if s["t"] >= first_press)
        coal_drills = at.get("blocks", {}).get("mechanical-drill", 0)
        findings.append({"sev": 3, "name": "note: drill count at first graphite press",
            "evidence": f"first press at t={first_press}s with {coal_drills} total mech drills (all ores); ratio guidance: ~4 mech coal drills per press [V/C]",
            "fix": "informational: dedicated coal drills per press; see human_checkpoints.json economy_ratios."})
    findings.sort(key=lambda f: -f["sev"])
    return findings

def report(run, cp, profiles):
    h, ss = run["header"], run["samples"]
    o = []
    o.append(f"# Run report: {run['path'].split('/')[-1]}")
    o.append(f"config: style={h.get('style')} diff={h.get('diff')} economy={h.get('economy')} tactics={h.get('tactics')} units={h.get('units')} adapt={h.get('adapt')} map={h.get('map')} wall={h.get('wall')}")
    if ss:
        end = ss[-1]
        o.append(f"duration={end['t']}s ({end['t']//60}min) final core_hp={end['core_hp']}/{end['core_maxhp']} samples={len(ss)}")
        o.append(f"final blocks: " + ", ".join(f"{k}x{v}" for k, v in sorted(end.get("blocks", {}).items(), key=lambda x: -x[1]) if k != "core-nucleus"))
        o.append(f"final items: " + ", ".join(f"{k}={v}" for k, v in sorted(end.get("items", {}).items(), key=lambda x: -x[1])[:8]))
    o.append("\n## Checkpoints vs human benchmarks")
    o.append("| profile | checkpoint | human window (min) | bot first achieved | status | note |")
    o.append("|---|---|---|---|---|---|")
    for prof, name, win, t, status, desc, label in eval_checkpoints(run, cp, profiles):
        o.append(f"| {prof} [{label}] | {name} | {win[0]}-{win[1]} | {'-' if t is None else f'{t//60}:{t%60:02d}'} | {status} | {desc} |")
    eng = engagements(ss)
    o.append(f"\n## Engagements ({len(eng)})")
    for i, e in enumerate(eng):
        o.append(f"- #{i+1} t={e['t0']}-{e['t1']}s peak_enemies={e['peak']} core {e['hp0']}->{e['hp1']} foes=" +
                 ", ".join(f"{k}x{v}" for k, v in sorted(e["foes"].items(), key=lambda x: -x[1])[:6]))
    o.append("\n## Weaknesses (ranked by severity)")
    f = detectors(run)
    if not f: o.append("- none detected")
    for i, w in enumerate(f):
        o.append(f"{i+1}. **[sev {w['sev']:.0f}] {w['name']}**")
        o.append(f"   - evidence: {w['evidence']}")
        o.append(f"   - fix: {w['fix']}")
    return "\n".join(o)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--profiles", nargs="+", default=["survival_generic"])
    ap.add_argument("--checkpoints", default="human_checkpoints.json")
    ap.add_argument("--out")
    a = ap.parse_args()
    cp = json.load(open(a.checkpoints))
    parts = []
    if len(a.runs) > 1:
        parts.append("# Multi-run summary\n")
        parts.append("| run | econ | adapt | duration | final core | hp lost | conveyors:turrets |")
        parts.append("|---|---|---|---|---|---|---|")
        for r in a.runs:
            run = load_run(r)
            h, ss = run["header"], run["samples"]
            if not ss: continue
            end = ss[-1]
            eng = engagements(ss)
            hp = sum(max(0, e["hp0"] - e["hp1"]) for e in eng)
            conv = end.get("blocks", {}).get("conveyor", 0)
            tur = max(1, sum(v.get("n", 0) for v in end.get("turrets", {}).values()))
            parts.append(f"| {r.split('/')[-1]} | {h.get('economy')} | {h.get('adapt')} | {end['t']}s | {end['core_hp']}/{end['core_maxhp']} | {hp} | {conv}:{tur} |")
        parts.append("")
    for r in a.runs:
        parts.append(report(load_run(r), cp, a.profiles))
        parts.append("\n---\n")
    text = "\n".join(parts)
    if a.out:
        open(a.out, "w").write(text)
        print(f"wrote {a.out}")
    else:
        print(text)

if __name__ == "__main__":
    main()
