"""booster_guards.py - is every booster guarded, and by whom? (round 279)

User, entering the Blue Tower: *"there are two unguarded boosters. I'd like to make sure all boosters are
protected, even if you have to add new mages to do so."* This is the third report about booster guarding - round
258 placed 101 guards, round 269 reverted ten that landed outside the room, round 275 re-placed those - so the
question deserves a standing audit rather than another one-off pass.

A booster counts as GUARDED when an enemy stands within `--radius` tiles of it (default 3) AND that enemy can
actually be engaged from a reachable player position, because round 275's whole lesson was that a guard nothing
can reach guards nothing. Enemies carrying a `dialog` are not guards: round 253 exempted them from reaction
ranges precisely because a quest NPC that charges you is a bug, so they are counted separately.

usage: python dev-tools/booster_guards.py [--radius 3] [--verbose]
       python dev-tools/booster_guards.py <map.tmx> [...]
"""
import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pixel_collision_qa as q


def objects_of(tmx):
    """[{kind, id, x, y, name, has_dialog}] for boosters and enemies, x/y exactly as the .tmx stores them."""
    root = ET.parse(tmx).getroot()
    base = os.path.dirname(os.path.abspath(tmx))
    out = []
    for o in root.findall(".//object"):
        tpl = os.path.basename(o.get("template") or "")
        kind = None
        if tpl.startswith("booster"):
            kind = "booster"
        elif tpl.startswith("enemy"):
            kind = "enemy"
        if kind is None:
            continue
        try:
            x, y = float(o.get("x")), float(o.get("y"))
        except (TypeError, ValueError):
            continue
        props = {p.get("name"): p.get("value") for p in o.findall(".//property")}
        out.append({"kind": kind, "id": int(o.get("id", 0)), "x": x, "y": y,
                    "name": props.get("enemy") or "", "has_dialog": bool(props.get("dialog")),
                    "threat": props.get("threatRange"), "pursue": props.get("pursueRange")})
    return out


def audit(tmx, radius, verbose=False):
    """(guarded, unguarded, rows) for one map."""
    objs = objects_of(tmx)
    boosters = [o for o in objs if o["kind"] == "booster"]
    if not boosters:
        return 0, 0, []
    enemies = [o for o in objs if o["kind"] == "enemy"]
    free, reach, wpx, hpx, tw, th, _o = q.reachable_from_entries(tmx)
    rows = []
    guarded = unguarded = 0
    for b in boosters:
        near = []
        for e in enemies:
            d = max(abs(e["x"] - b["x"]), abs(e["y"] - b["y"])) / float(tw)
            if d <= radius:
                engage = reach is not None and q.engageable(reach, wpx, hpx, e["x"], e["y"])
                near.append((d, e, engage))
        real = [n for n in near if not n[1]["has_dialog"] and n[2]]
        if real:
            guarded += 1
        else:
            unguarded += 1
        rows.append({"booster": b, "near": sorted(near, key=lambda n: n[0]), "ok": bool(real),
                     "tile": (int(b["x"] // tw), int(b["y"] // th) - 1)})
    return guarded, unguarded, rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("maps", nargs="*")
    ap.add_argument("--radius", type=float, default=3)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()
    targets = args.maps or sorted(glob.glob(os.path.join(q.DEFAULT_ROOT, "**", "*.tmx"), recursive=True))
    tg = tu = 0
    for tmx in targets:
        try:
            g, u, rows = audit(tmx, args.radius, args.verbose)
        except Exception as ex:
            print("%-46s ERROR %r" % (os.path.basename(tmx), ex))
            continue
        if not rows:
            continue
        tg += g
        tu += u
        rel = os.path.relpath(tmx, q.DEFAULT_ROOT).replace("\\", "/")
        for r in rows:
            if r["ok"] and not args.verbose:
                continue
            who = ", ".join("%s%s @%.1ft%s" % (n[1]["name"] or "?", " [dialog]" if n[1]["has_dialog"] else "",
                                               n[0], "" if n[2] else " [UNREACHABLE]")
                            for n in r["near"]) or "nobody within %g tiles" % args.radius
            print("%-42s booster obj %-5d tile %-9s %-9s %s"
                  % (rel[:42], r["booster"]["id"], "(%d,%d)" % r["tile"], "GUARDED" if r["ok"] else "UNGUARDED",
                     who))
    print("\n%d booster(s): %d guarded, %d UNGUARDED (guard = a non-dialog enemy within %g tiles that a "
          "reachable player can engage)" % (tg + tu, tg, tu, args.radius))


if __name__ == "__main__":
    main()
