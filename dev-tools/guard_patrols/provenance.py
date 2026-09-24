"""provenance.py - which enemy placements did a TOOL put there, and in which round? (round 316)

The user's report - *"some dungeons now have two enemies standing right next to each other"* - has a "now" in
it, and the history says why: rounds 279, 284, 286b and 287 ADDED 445 enemies to the plane's maps (69 booster
guards, 1 Basilica guard, 238 chest guards, 137 booster/chest guards), each at the nearest free tile to a piece of
loot - which is very often right beside the enemy that already stood there. Knowing which placements are
hand-authored and which a script dropped is what lets a cluster fix take the newcomer rather than the original.

Read-only: one `git cat-file --batch` process reads each map at each commit below; nothing in the repo is
written. Output is a JSON {"<rel map path>#<object id>": {"first": label, "moved": bool}} for every enemy
object in the CURRENT maps, where `first` is the earliest listed commit containing that object id as an enemy
("r256" = present before any guard tool ran) and `moved` says its x/y differ from the r256 copy (rounds 258,
275 and 278 moved 128 authored enemies next to loot or back inside rooms).

usage: python provenance.py <repo root> --out provenance.json
"""
import argparse
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

COMMITS = [                                    # (label, commit) - the rounds that ADDED enemy objects
    ("r256", "6a0ccad189e"),                   # before rounds 257-258's first guard pass
    ("r279", "6c106dd0c68"),                   # 69 new booster guards
    ("r284", "aac4e42ad80"),                   # 1 guard for the Basilica's chests
    ("r286b", "dda3df5ac9e"),                  # 238 new chest guards (Apprentice, threatRange 20)
    ("r287", "e0db036c457"),                   # 12 booster + 125 chest guards (the matching replay)
]
SUB = "forge-gui/res/adventure/The Forsaken Realms/maps/map"


def enemies_in(xml_bytes):
    out = {}
    try:
        root = ET.fromstring(xml_bytes)
    except ET.ParseError:
        return out
    for o in root.iter("object"):
        tpl = os.path.basename(o.get("template") or "")
        if not tpl.startswith("enemy") and (o.get("type") or "") != "enemy":
            continue
        try:
            out[int(o.get("id"))] = (float(o.get("x", 0)), float(o.get("y", 0)))
        except (TypeError, ValueError):
            pass
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("repo", help="repo root (required; read only)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    root = os.path.join(a.repo, *SUB.split("/"))
    rels = []
    for d, _s, files in os.walk(root):
        for f in files:
            if f.endswith(".tmx"):
                rels.append(os.path.relpath(os.path.join(d, f), root).replace("\\", "/"))
    rels.sort()
    proc = subprocess.Popen(["git", "-C", a.repo, "cat-file", "--batch"], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE)

    def show(spec):
        proc.stdin.write((spec + "\n").encode("utf-8"))
        proc.stdin.flush()
        header = proc.stdout.readline().decode("utf-8", "replace").split()
        if len(header) < 3 or header[1] == "missing":
            return None
        size = int(header[2])
        data = proc.stdout.read(size)
        proc.stdout.read(1)
        return data

    hist = {label: {} for label, _c in COMMITS}
    for label, c in COMMITS:
        for rel in rels:
            data = show("%s:%s/%s" % (c, SUB, rel))
            hist[label][rel] = enemies_in(data) if data else {}
    proc.stdin.close()
    proc.wait()

    out = {}
    for rel in rels:
        cur = enemies_in(open(os.path.join(root, *rel.split("/")), "rb").read())
        for eid, (x, y) in cur.items():
            first = next((label for label, _c in COMMITS if eid in hist[label].get(rel, {})), "after-r287")
            base = hist["r256"].get(rel, {}).get(eid)
            out["%s#%d" % (rel, eid)] = {"first": first,
                                         "moved": bool(base and (abs(base[0] - x) > 0.01 or abs(base[1] - y) > 0.01))}
    json.dump(out, open(a.out, "w", encoding="utf-8"), indent=0, sort_keys=True)
    tally = {}
    for v in out.values():
        tally[v["first"]] = tally.get(v["first"], 0) + 1
    print("%d enemy placements labelled: %s" % (len(out), ", ".join("%s %d" % kv for kv in sorted(tally.items()))))


if __name__ == "__main__":
    sys.exit(main())
