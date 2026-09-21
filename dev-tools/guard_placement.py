"""guard_placement.py - put a booster's guard somewhere the player can actually reach it (round 275).

Round 258 moved 101 enemies to stand beside boosters. Ten of them landed on the wrong side of a wall, because
the placement took the tile ADJACENT TO THE LOOT without asking whether a player could ever stand there; round
269 reverted those ten to their hand-authored pre-258 positions, which left those ten boosters unguarded, and
recorded what a proper re-placement needs: "a reachability test - flood-fill from the entry over walkable tiles -
which also has to settle what the Collision layer actually means here".

Both halves now exist. `pixel_collision_qa.py` had the flood fill all along (pixel resolution, the player's real
10x6 box, collision rectangles rebuilt exactly as `MapStage.loadCollision()` builds them - from EVERY tile layer's
tileset geometry, not from the layer named "Collision"), and round 275 gave it entry seeding and an
`--enemies` reachability report. This script is the writer on top of it.

The rule for a new guard position:
  1. candidate tiles are whole-tile offsets from the BOOSTER's own authored x/y, out to --radius tiles. Offsetting
     by tile multiples from a coordinate the map already contains is deliberate: it needs no coordinate conversion
     at all, so the 16px "is a tile object's y its top or its bottom edge" question cannot corrupt a write.
  2. the tile must hold a legal player position that is REACHABLE from an entry - that is the whole point.
  3. it must not land on another object (enemy, treasure, booster, entry) already there.
  4. nearest to the booster wins; ties break toward the guard's current position, so a guard moves as little as
     the geometry allows.

usage: python guard_placement.py --list                       the ten round-269 guards and where they could go
       python guard_placement.py --apply                      write the moves
       python guard_placement.py --list --radius 4
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pixel_collision_qa as q

ROOT = os.path.normpath(os.path.join(HERE, "..", "forge-gui", "res", "adventure", "The Forsaken Realms",
                                     "maps", "map"))

# (map file, enemy object id) - the ten round 269 reverted, from MOD_CHANGELOG round 269.
GUARDS = [
    ("factory/factory_2_ooze.tmx", 69),
    ("factory/factory_3_wizard.tmx", 68),
    ("barbariancamp/barbariancamp_kobold.tmx", 79),
    ("fort/fort_blue_2_canyon.tmx", 76),
    ("grove/grove_9_eldrazi.tmx", 71),
    ("magetower/magetower_8_illusion_basement.tmx", 68),
    ("primal/primal_jungle.tmx", 36),
    ("merfolkpool/merfolkpool_1.tmx", 61),
    ("merfolkpool/merfolkpool_3.tmx", 57),
    ("phyrexia/phyrexian_w1.tmx", 68),
]


def find_map(rel):
    p = os.path.join(ROOT, rel)
    if os.path.exists(p):
        return p
    base = os.path.basename(rel)
    for folder, _d, files in os.walk(ROOT):
        if base in files:
            return os.path.join(folder, base)
    return None


def all_objects(tmx):
    """Every object with a position, plus what kind of template it came from."""
    root = ET.parse(tmx).getroot()
    base = os.path.dirname(os.path.abspath(tmx))
    out = []
    for o in root.findall(".//object"):
        try:
            x, y = float(o.get("x")), float(o.get("y"))
        except (TypeError, ValueError):
            continue
        tpl = os.path.basename(o.get("template") or "")
        kind = o.get("type") or (q.template_kind(os.path.join(base, o.get("template")))[0]
                                 if o.get("template") else None)
        out.append({"id": int(o.get("id", 0)), "x": x, "y": y, "tpl": tpl, "kind": kind})
    return out


def plan(tmx, enemy_id, radius):
    free, reach, wpx, hpx, tw, th, _objs = q.reachable_from_entries(tmx)
    if reach is None:
        return None, "no entry object to flood-fill from"
    objs = all_objects(tmx)
    enemy = next((o for o in objs if o["id"] == enemy_id), None)
    if enemy is None:
        return None, "no object %d in this map" % enemy_id
    boosters = [o for o in objs if o["tpl"].startswith("booster")]
    if not boosters:
        return None, "no booster in this map"
    # the booster this guard belongs to: the nearest one to where round 258 had put it, which is where the
    # guard sat before round 269 reverted it - but the revert means we only know the booster by distance now.
    booster = min(boosters, key=lambda b: (b["x"] - enemy["x"]) ** 2 + (b["y"] - enemy["y"]) ** 2)

    taken = {(int(o["x"] // tw), int(o["y"] // th)) for o in objs if o["id"] != enemy_id}
    best = None
    for dx in range(-radius, radius + 1):
        for dy in range(-radius, radius + 1):
            if dx == 0 and dy == 0:
                continue                      # the booster's own tile
            cx, cy = booster["x"] + dx * tw, booster["y"] + dy * th
            if (int(cx // tw), int(cy // th)) in taken:
                continue
            # a guard must stand where the player can come and fight it
            if not q.engageable(reach, wpx, hpx, cx, cy):
                continue
            d_booster = dx * dx + dy * dy
            d_home = ((cx - enemy["x"]) ** 2 + (cy - enemy["y"]) ** 2) / float(tw * th)
            key = (d_booster, d_home)
            if best is None or key < best[0]:
                best = (key, cx, cy, dx, dy)
    if best is None:
        return None, "no reachable tile within %d tiles of the booster" % radius
    _k, cx, cy, dx, dy = best
    return {"enemy": enemy, "booster": booster, "x": cx, "y": cy, "dx": dx, "dy": dy,
            "moved_tiles": round(((cx - enemy["x"]) ** 2 + (cy - enemy["y"]) ** 2) ** 0.5 / tw, 1)}, ""


def apply_move(tmx, enemy_id, x, y):
    """Rewrite just that object's x/y attributes, touching nothing else in the file."""
    text = open(tmx, encoding="utf-8", errors="replace").read()
    pat = re.compile(r'(<object id="%d"[^>]*?)\bx="[-\d.]+" y="[-\d.]+"' % enemy_id)
    if not pat.search(text):
        return False
    new = pat.sub(lambda m: '%sx="%s" y="%s"' % (m.group(1), fmt(x), fmt(y)), text, count=1)
    if new == text:
        return False
    open(tmx, "w", encoding="utf-8", newline="\n").write(new)
    return True


def fmt(v):
    return str(int(v)) if float(v) == int(v) else ("%.4f" % v).rstrip("0").rstrip(".")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--radius", type=int, default=3)
    args = ap.parse_args()
    ok = fail = 0
    for rel, oid in GUARDS:
        tmx = find_map(rel)
        if not tmx:
            print("%-44s obj %-4d MAP NOT FOUND" % (rel, oid))
            fail += 1
            continue
        try:
            p, why = plan(tmx, oid, args.radius)
        except Exception as ex:
            print("%-44s obj %-4d ERROR %r" % (os.path.basename(rel), oid, ex))
            fail += 1
            continue
        if p is None:
            print("%-44s obj %-4d NO PLACEMENT - %s" % (os.path.basename(rel), oid, why))
            fail += 1
            continue
        print("%-44s obj %-4d booster (%.0f,%.0f) -> guard (%.0f,%.0f)  offset %+d,%+d tiles  "
              "moves %.1f tiles from its authored spot"
              % (os.path.basename(rel), oid, p["booster"]["x"], p["booster"]["y"], p["x"], p["y"],
                 p["dx"], p["dy"], p["moved_tiles"]))
        if args.apply:
            if apply_move(tmx, oid, p["x"], p["y"]):
                ok += 1
            else:
                print("      !! could not rewrite object %d" % oid)
                fail += 1
    if args.apply:
        print("\n%d moved, %d not placed" % (ok, fail))
    else:
        print("\n%d placeable, %d not" % (len(GUARDS) - fail, fail))


if __name__ == "__main__":
    main()
