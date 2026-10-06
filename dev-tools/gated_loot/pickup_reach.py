"""pickup_reach.py - can a WALKING player touch every pickup in every map? (READ-ONLY on the repo)

Round 449 (the user, of a Wood pile outside Mercenary Barracks' west wall: "audit all dungeons and make sure there are
no resources we added that can't be reached by walking"). Uses audit.World's global reach model - from every POI map's
world arrival across stairs/portals, over 10x6 player boxes, tile + object collision and blocking actors - and sorts
every `reward` object (wood, stone, gold, shards, chests, boosters, scrolls) into:
    open         touchable with every gate shut
    GATED        touchable only once a gate/locked portal opens (a key, a boss, a switch) - by design
    UNREACHABLE  no walking route even with every gate open - a placement bug

usage: python dev-tools/gated_loot/pickup_reach.py [--all]     (--all also lists the GATED ones)
exit code 1 when anything is UNREACHABLE.
"""
import collections
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from audit import World  # noqa: E402

KINDS = ("wood", "stone", "gold", "shard", "booster", "treasure", "scroll")


def kind_of(sprite):
    return next((k for k in KINDS if k in sprite), sprite)


def main(show_gated=False):
    W = World()
    all_gates = {(r, i) for (r, i, k) in W.gate_list if k == "blocker"}
    authored_active = {(r, p["id"]) for r, m in W.maps.items() for p in m.portals if p["_portal_active"]}
    all_portals = {(r, p["id"]) for r, m in W.maps.items() for p in m.portals}
    closed_vis, closed_sums = W.reach(all_gates, authored_active)
    open_vis, open_sums = W.reach(set(), all_portals)

    def reach(vis, sums, r, oid):
        s = sums.get(r)
        return bool(s and (s["touch"].get(oid, set()) & vis.get(r, set())))

    count = collections.Counter()
    listed = []
    for r, m in W.maps.items():
        for o in m.rewards:
            k = kind_of(o["_sprite"])
            st = "open" if reach(closed_vis, closed_sums, r, o["id"]) else (
                "GATED" if reach(open_vis, open_sums, r, o["id"]) else "UNREACHABLE")
            count[(k, st)] += 1
            if st == "UNREACHABLE" or (show_gated and st == "GATED"):
                yb = o["y"] if o["is_tile"] else o["y"] + o["h"]
                listed.append((st, k, r, o["id"], o["x"], yb - o["h"]))
    for k in KINDS:
        row = ["%s %d" % (st, count[(k, st)]) for st in ("open", "GATED", "UNREACHABLE") if count[(k, st)]]
        print("%-9s %s" % (k, ", ".join(row)))
    for st, k, r, oid, x, yt in sorted(listed):
        print("%-12s %-8s %-50s id=%-4d x=%.0f y_top=%.0f" % (st, k, r, oid, x, yt))
    bad = sum(v for (k, st), v in count.items() if st == "UNREACHABLE")
    print("UNREACHABLE: %d" % bad)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main("--all" in sys.argv))
