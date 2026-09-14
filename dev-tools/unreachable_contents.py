#!/usr/bin/env python3
"""What does a map LOSE to its unreachable regions?

`cave_connectivity_qa.py` answers "is this map split into pieces the player cannot walk between".
That alone does not say whether a split matters. Maps here are routinely painted with no collision
outside the play area, so the border strip is "walkable" by omission - a map can report 42% of its
walkable tiles cut off and lose the player precisely nothing.

What decides it is CONTENT. This places every object from the map's object layers into its walkable
region and reports what each cut-off region holds, so a map is worth opening in Tiled only when the
answer is "a chest" or "the exit" rather than "nothing".

An object is matched to the region under its own tile; when that tile is not walkable (a chest
sitting in a wall recess, say) the search widens ring by ring to the nearest walkable tile, because
what matters is the region the player must stand in to use it.

Usage
-----
    python dev-tools/unreachable_contents.py [map.tmx ...]

With no paths it scans the plane's cave/dungeon/fort/grove maps, like the connectivity QA does.
"""
import argparse
import os
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cave_connectivity_qa as qa   # noqa: E402


def object_label(obj):
    """A short, human-readable name for a Tiled object - Tiled's own name/type first, then the
    properties the adventure maps actually key off."""
    props = {p.get("name"): p.get("value") for p in obj.iter("property")}
    for key in ("name", "type"):
        val = obj.get(key)
        if val:
            return val
    for key in ("type", "Type", "enemy", "reward", "item", "Name"):
        if props.get(key):
            return "%s=%s" % (key, props[key])
    return "(unnamed)"


def scan(path, max_coverage, min_island):
    found, width, height, labels, comp_info = qa.components(
        path, max_coverage, min_island, with_labels=True)
    if len(found) < 2:
        return None

    largest = max(range(len(comp_info)), key=lambda c: comp_info[c][0]) if comp_info else -1

    root = ET.parse(path).getroot()
    tw = int(root.get("tilewidth"))
    th = int(root.get("tileheight"))

    def region_at(tx, ty):
        """The region of (tx,ty), widening to the nearest walkable tile when it sits in a wall."""
        for ring in range(0, 4):
            for dy in range(-ring, ring + 1):
                for dx in range(-ring, ring + 1):
                    if ring and max(abs(dx), abs(dy)) != ring:
                        continue
                    x, y = tx + dx, ty + dy
                    if 0 <= x < width and 0 <= y < height:
                        lab = labels[y * width + x]
                        if lab >= 0:
                            return lab
        return -1

    stranded = {}
    for group in root.iter("objectgroup"):
        for obj in group.iter("object"):
            tx = int(float(obj.get("x", 0)) // tw)
            ty = int(float(obj.get("y", 0)) // th)
            comp = region_at(tx, ty)
            if comp < 0 or comp == largest:
                continue
            stranded.setdefault(comp, []).append("%s@(%d,%d)" % (object_label(obj), tx, ty))
    return width, height, comp_info, largest, stranded


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*")
    ap.add_argument("--max-coverage", type=float, default=0.6)
    ap.add_argument("--min-island", type=int, default=4)
    args = ap.parse_args()

    paths = args.paths or list(qa.iter_maps(qa.DEFAULT_ROOT))
    real = 0
    for path in paths:
        try:
            result = scan(path, args.max_coverage, args.min_island)
        except Exception as exc:                                # noqa: BLE001
            print("%-52s (%s: %s)" % (os.path.basename(path), type(exc).__name__, exc))
            continue
        if not result:
            continue
        width, height, comp_info, largest, stranded = result
        name = os.path.basename(path)
        if not stranded:
            print("%-52s %dx%d  split, but every object is in the main region - COSMETIC"
                  % (name, width, height))
            continue
        real += 1
        total = sum(len(v) for v in stranded.values())
        print("%-52s %dx%d  %d object(s) stranded outside the main region:"
              % (name, width, height, total))
        for comp, items in sorted(stranded.items(), key=lambda kv: -len(kv[1])):
            size, seed = comp_info[comp]
            print("    region of %4d tile(s) near (%d,%d): %s"
                  % (size, seed[0], seed[1], ", ".join(items[:10])
                     + (" ... +%d more" % (len(items) - 10) if len(items) > 10 else "")))
    print("\n%d map(s) strand at least one object; the rest of any split is cosmetic." % real)
    return 0


if __name__ == "__main__":
    sys.exit(main())
