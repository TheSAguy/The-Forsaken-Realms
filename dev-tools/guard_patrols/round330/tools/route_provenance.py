"""route_provenance.py - who wrote each patrol route, from git history (round 329, read-only).

For every enemy that carries a `waypoints` route in the CURRENT maps: the first listed commit where it had a
non-empty route, the commits that changed its route value, and for each waypoint object it names, the commit
that created that object and the commits that moved it. One `git cat-file --batch` process; nothing written in
the repo.

usage: python route_provenance.py <repo root> --out route_provenance.json
"""
import argparse
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

COMMITS = [                                   # (label, commit), oldest first
    ("stock", "6a0ccad189e"),                 # round 256: before rounds 257-258's first guard pass (= hand-authored)
    ("r258", "386cc9b2670"),                  # rounds 257-258: 393 chest patrols, 786 waypoints
    ("r279", "6c106dd0c68"),
    ("r283", "e651f026e30"),
    ("r286", "5f755e034d5"),                  # fix_routes.py: stray waypoints pulled inside
    ("r286b", "dda3df5ac9e"),                 # terrestrial blocked legs moved
    ("r286c", "04843ddec3d"),
    ("r287", "e0db036c457"),
    ("r319", "ae591d54aa2"),                  # 33 patrols (guard_patrols/)
    ("r324", "3a90230b83d"),
]
SUB = "forge-gui/res/adventure/The Forsaken Realms/maps/map"


def parse(xml_bytes):
    """({enemy id: route value}, {waypoint id: (x, y)}) - waypoints = objects whose template is waypoint.tx or
    whose type is waypoint (every waypoint in the plane uses the template)."""
    routes, wps = {}, {}
    try:
        root = ET.fromstring(xml_bytes)
    except ET.ParseError:
        return routes, wps
    for o in root.iter("object"):
        tpl = os.path.basename(o.get("template") or "")
        try:
            oid = int(o.get("id"))
        except (TypeError, ValueError):
            continue
        if tpl == "waypoint.tx" or (o.get("type") or o.get("class") or "") == "waypoint":
            try:
                wps[oid] = (float(o.get("x", 0)), float(o.get("y", 0)))
            except ValueError:
                pass
            continue
        p = o.find("properties")
        if p is None:
            continue
        for pr in p.findall("property"):
            if pr.get("name") == "waypoints":
                v = (pr.get("value") if pr.get("value") is not None else (pr.text or "")).strip()
                if v:
                    routes[oid] = v
    return routes, wps


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("repo")
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

    hist = {}
    for label, c in COMMITS:
        hist[label] = {}
        for rel in rels:
            data = show("%s:%s/%s" % (c, SUB, rel))
            hist[label][rel] = parse(data) if data else ({}, {})
    proc.stdin.close()
    proc.wait()

    out = {}
    tally = {}
    for rel in rels:
        cur_routes, cur_wps = parse(open(os.path.join(root, *rel.split("/")), "rb").read())
        for eid, value in cur_routes.items():
            first, changed = None, []
            prev = None
            for label, _c in COMMITS:
                v = hist[label][rel][0].get(eid)
                if v and first is None:
                    first = label
                if first is not None and v != prev and prev is not None:
                    changed.append(label)
                prev = v
            if first is None:
                first = "after-r324"
            elif prev != value:
                changed.append("after-r324")
            wp_info = {}
            for tok in value.replace(" ", "").split(","):
                ids = []
                if tok.startswith("r"):
                    ids = [int(x) for x in tok[1:].split("-") if x.isdigit()]
                elif tok.lstrip("-").isdigit():
                    ids = [int(tok)]
                for wid in ids:
                    wfirst, moved, last = None, [], None
                    for label, _c in COMMITS:
                        pos = hist[label][rel][1].get(wid)
                        if pos is not None and wfirst is None:
                            wfirst = label
                        if pos is not None and last is not None and (abs(pos[0] - last[0]) > 0.01
                                                                      or abs(pos[1] - last[1]) > 0.01):
                            moved.append(label)
                        if pos is not None:
                            last = pos
                    cur = cur_wps.get(wid)
                    if cur is not None and last is not None and (abs(cur[0] - last[0]) > 0.01
                                                                 or abs(cur[1] - last[1]) > 0.01):
                        moved.append("after-r324")
                    wp_info[str(wid)] = {"first": wfirst or ("after-r324" if cur else None), "moved": moved}
            key = "%s#%d" % (rel, eid)
            out[key] = {"first": first, "changed": changed, "waypoints": wp_info}
            tally[first] = tally.get(first, 0) + 1
    json.dump(out, open(a.out, "w", encoding="utf-8"), indent=0, sort_keys=True)
    print("%d routes labelled: %s" % (len(out), ", ".join("%s %d" % kv for kv in sorted(tally.items()))))


if __name__ == "__main__":
    sys.exit(main())
