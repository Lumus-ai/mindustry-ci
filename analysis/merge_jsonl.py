#!/usr/bin/env python3
"""Merge split telemetry files (bot restart mid-run) into one JSONL.
Usage: merge_jsonl.py out.jsonl part1.jsonl part2.jsonl [part3...]
Offsets each later part's t by the last t of the previous part; keeps the first header."""
import json, sys

def main():
    out_path, parts = sys.argv[1], sys.argv[2:]
    offset, out, header_done = 0, [], False
    for p in parts:
        last_t = None
        lines = []
        for line in open(p):
            line = line.strip()
            if not line: continue
            try: j = json.loads(line)
            except json.JSONDecodeError: continue
            if j.get("type") == "header":
                if not header_done: out.append(line); header_done = True
                continue
            if "t" in j:
                j["t"] += offset
                last_t = j["t"]
            lines.append(json.dumps(j))
        out.extend(lines)
        if last_t is not None: offset = last_t
    open(out_path, "w").write("\n".join(out) + "\n")
    print(f"merged {len(parts)} parts -> {out_path} ({len(out)} lines)")

if __name__ == "__main__":
    main()
