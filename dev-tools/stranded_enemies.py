"""stranded_enemies.py - tell a swimming creature from a walled-off one (round 278).

Round 275 swept every dungeon map for enemy placements the player can never walk into and found 52. Reading the
list, it looked like a bestiary rather than a bug report - jellyfish, crocodiles, griffins, bats, farmers - so the
conclusion was "they swim, fly or stand in crops, leave them alone". That conclusion was too generous, and one
map proved it: `magetower_11_dreamhalls.tmx`, a "Blue Tower" dungeon, has one entry, NO door or teleport objects,
and only 32% of its legal player positions reachable from that entry. Its two Jellyfish sit in the sealed band of
drawn-but-unreachable floor between the room and the outer wall - which is exactly the "enemy stranded outside the
wall" the user described, not a creature in water.

The split that actually means something is not the creature's name, it is whether the tile could hold the PLAYER:

  stranded   the enemy's own tile is a LEGAL player position (the 10x6 box fits there) that is simply not
             REACHABLE from the entry. Nothing stops a person standing there except that no route exists - so the
             enemy is walled off, and if the map's clear condition counts it, the dungeon can never be cleared.

  in place   the tile is NOT a legal player position at all: collision covers it. Water, a chasm, a rooftop, the
             inside of a rock. A swimmer or a flier belongs there, the player was never meant to stand on it, and
             round 252's reaction range brings the creature out to meet them.

usage: python dev-tools/stranded_enemies.py            # every dungeon map
       python dev-tools/stranded_enemies.py <map.tmx> [...]
"""
import glob
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pixel_collision_qa as q


def classify(tmx):
    """[(enemy, verdict, detail)] for every unreachable placement in one map."""
    free, reach, wpx, hpx, tw, th, objs = q.reachable_from_entries(tmx)
    if reach is None:
        return None
    out = []
    for e in [o for o in objs if o["kind"] == "enemy"]:
        if e["x"] < 0 or e["y"] < 0 or e["x"] >= wpx or e["y"] > hpx:
            out.append((e, "off-map", "outside the %dx%d px rectangle" % (wpx, hpx)))
            continue
        if q.engageable(reach, wpx, hpx, e["x"], e["y"]):
            continue
        # Could the player stand on the enemy's own tile, if only a route existed? The box corner convention is
        # pixel_collision_qa's: free[] is indexed by the 10x6 box's own top-left.
        tx0, ty0 = int(e["x"] // tw) * tw, (int(e["y"] // th) - 1) * th
        legal = any(free[by * wpx + bx]
                    for by in range(max(0, ty0), min(hpx, ty0 + th))
                    for bx in range(max(0, tx0), min(wpx, tx0 + tw)))
        if legal:
            out.append((e, "STRANDED", "its tile holds a legal player position with no route to it"))
        else:
            out.append((e, "in place", "collision covers its tile - water, a chasm or a roof"))
    return out


def main():
    targets = sys.argv[1:] or sorted(glob.glob(os.path.join(q.DEFAULT_ROOT, "**", "*.tmx"), recursive=True))
    tally = {"STRANDED": 0, "in place": 0, "off-map": 0}
    rows = []
    for tmx in targets:
        try:
            res = classify(tmx)
        except Exception as ex:
            print("%-46s SKIPPED (%s)" % (os.path.basename(tmx), ex))
            continue
        if not res:
            continue
        # A map the user has looked at and accepted is not a finding - see q.ACCEPTED_UNREACHABLE.
        if os.path.basename(tmx) in q.ACCEPTED_UNREACHABLE:
            if res:
                print("%-44s ACCEPTED by the user as designed - %d placement(s) not reported"
                      % (os.path.basename(tmx), len(res)))
            continue
        for e, verdict, detail in res:
            tally[verdict] += 1
            rows.append((os.path.relpath(tmx, q.DEFAULT_ROOT).replace("\\", "/"), e, verdict, detail))
    for verdict in ("STRANDED", "off-map", "in place"):
        picked = [r for r in rows if r[2] == verdict]
        if not picked:
            continue
        print("\n=== %s (%d) ===" % (verdict, len(picked)))
        for rel, e, _v, detail in picked:
            print("  %-44s obj %-5d %-24s tile (%d,%d)  %s"
                  % (rel[:44], e["id"], (e["name"] or "?")[:24], int(e["x"] // 16), int(e["y"] // 16) - 1, detail))
    print("\n%d map(s): %d stranded, %d off-map, %d standing where the player cannot"
          % (len(targets), tally["STRANDED"], tally["off-map"], tally["in place"]))


if __name__ == "__main__":
    main()
