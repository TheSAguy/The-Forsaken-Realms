"""unstrand_enemies.py - move a walled-off enemy somewhere the player can actually fight it (round 278).

`stranded_enemies.py` splits round 275's 52 unreachable placements into 28 STRANDED (the enemy's own tile is a
legal player position with no route to it - walled off) and 23 standing where the player was never meant to go
(collision covers the tile: water, a chasm, a roof - where a swimmer or flier belongs).

The 28 are a real bug, not set dressing. `AdventureQuestController.updateQuestsWin()` sets `allEnemiesCleared`
false if ANY enemy is still on the map, exempting only those carrying a `defeatDialog`, so one unreachable enemy
means:
  * the dungeon never despawns (the 2026-08-18 "silly to have an empty dungeon on the map" behaviour never fires),
  * a ClearDungeons objective on it can never complete,
  * "Sweep the Wilds" and any quest stage keyed to clearing it stall forever.

The move is the one round 275 used for the ten booster guards, and it is deliberately conservative: candidates are
whole-tile offsets from the enemy's OWN authored x/y (no coordinate conversion, so the "is a tile object's y its
top or bottom edge" question cannot corrupt a write), out to --radius tiles, the tile must hold a player position
reachable from an entry, it must not already hold another object, and the nearest one wins. An enemy that has no
reachable tile within the radius is left alone and reported.

`--skip-map` leaves a whole file out. `phyrexian_black1.tmx` is skipped by default, and that skip is now
SETTLED rather than pending: all five of its enemies are stranded and its single entry reaches only 28,653 of
75,489 legal positions, which is why rounds 278 and 279 both left it for the user to judge. They judged it -
"I checked, it looks good as is. Leave alone" (2026-09-21) - so it stays skipped on purpose, not on suspicion.
See `pixel_collision_qa.ACCEPTED_UNREACHABLE`.

usage: python dev-tools/unstrand_enemies.py --list
       python dev-tools/unstrand_enemies.py --apply [--radius 8]
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pixel_collision_qa as q
import stranded_enemies as se
import guard_placement as gp

SKIP_BY_DEFAULT = ["phyrexian_black1.tmx"]


def plan_map(tmx, radius):
    """[(enemy, newx, newy)] for the stranded enemies of one map, plus [(enemy, why)] for the ones left."""
    res = se.classify(tmx)
    if not res:
        return [], []
    stranded = [e for e, verdict, _d in res if verdict == "STRANDED"]
    if not stranded:
        return [], []
    free, reach, wpx, hpx, tw, th, objs = q.reachable_from_entries(tmx)
    taken = {(int(o["x"] // tw), int(o["y"] // th)) for o in gp.all_objects(tmx)}
    moves, left = [], []
    for e in stranded:
        best = None
        for dx in range(-radius, radius + 1):
            for dy in range(-radius, radius + 1):
                if dx == 0 and dy == 0:
                    continue
                cx, cy = e["x"] + dx * tw, e["y"] + dy * th
                if (int(cx // tw), int(cy // th)) in taken:
                    continue
                if not q.engageable(reach, wpx, hpx, cx, cy):
                    continue
                d = dx * dx + dy * dy
                if best is None or d < best[0]:
                    best = (d, cx, cy)
        if best is None:
            left.append((e, "no reachable tile within %d tiles" % radius))
            continue
        _d, cx, cy = best
        taken.add((int(cx // tw), int(cy // th)))
        moves.append((e, cx, cy))
    return moves, left


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--radius", type=int, default=8)
    ap.add_argument("--skip-map", action="append", default=[])
    ap.add_argument("--include-skipped", action="store_true",
                    help="also process the maps skipped by default (see the module docstring)")
    args = ap.parse_args()

    skip = set(args.skip_map) | (set() if args.include_skipped else set(SKIP_BY_DEFAULT))
    res = se.classify  # touch, so a missing import fails early
    maps = sorted({os.path.join(q.DEFAULT_ROOT, r) for r in []}) or None

    import glob
    targets = sorted(glob.glob(os.path.join(q.DEFAULT_ROOT, "**", "*.tmx"), recursive=True))
    moved = notmoved = skipped = 0
    for tmx in targets:
        base = os.path.basename(tmx)
        try:
            moves, left = plan_map(tmx, args.radius)
        except Exception as ex:
            print("%-44s ERROR %r" % (base, ex))
            continue
        if not moves and not left:
            continue
        if base in skip:
            print("%-44s SKIPPED by policy - %d stranded left alone" % (base, len(moves) + len(left)))
            skipped += len(moves) + len(left)
            continue
        for e, cx, cy in moves:
            print("%-44s obj %-5d %-22s (%.0f,%.0f) -> (%.0f,%.0f)  %+d,%+d tiles"
                  % (base, e["id"], (e["name"] or "?")[:22], e["x"], e["y"], cx, cy,
                     int((cx - e["x"]) // 16), int((cy - e["y"]) // 16)))
            if args.apply:
                if gp.apply_move(tmx, e["id"], cx, cy):
                    moved += 1
                else:
                    print("      !! could not rewrite object %d" % e["id"])
                    notmoved += 1
            else:
                moved += 1
        for e, why in left:
            print("%-44s obj %-5d %-22s LEFT - %s" % (base, e["id"], (e["name"] or "?")[:22], why))
            notmoved += 1
    print("\n%d %s, %d left where they are, %d skipped by policy"
          % (moved, "moved" if args.apply else "placeable", notmoved, skipped))


if __name__ == "__main__":
    main()
