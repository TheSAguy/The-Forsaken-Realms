#!/usr/bin/env python3
"""Round 194: remove null entries from questTags arrays in plane data.

Why
---
`MapStage.isScriptedPlacement()` tests each of an enemy's questTags against STORY_TAGS, which is a
`Set.of(...)` - java.util.ImmutableCollections, whose `contains()` THROWS NullPointerException on a
null argument where HashSet would simply answer false. A null tag therefore killed `loadMap()`
outright: the map never finished loading and the player was stuck on "Autosaving", unable to leave
the cave.

The code now null-guards that tag, which is the actual fix and covers upstream data we do not own.
This script removes the cause from the data we DO own, so the arrays mean what they say.

A null lands in these arrays from JSON like ["Undead", null] or a trailing comma in the source
editor; it has no meaning and nothing reads it.

Usage
-----
    python dev-tools/strip_null_quest_tags.py [--all-planes] [--dry-run]

Defaults to The Forsaken Realms (the shipping plane). `--all-planes` also cleans common/ and the
other bundled planes. Writes a .bak beside each file it changes, once.
"""

import argparse
import json
import os
import shutil
import sys

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                   "forge-gui", "res", "adventure")
SHIPPING_PLANE = "The Forsaken Realms"


def strip(node):
    """Remove null entries from every questTags list reachable from `node`. Returns the count."""
    removed = 0
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "questTags" and isinstance(value, list):
                cleaned = [t for t in value if t is not None]
                if len(cleaned) != len(value):
                    removed += len(value) - len(cleaned)
                    node[key] = cleaned
            else:
                removed += strip(value)
    elif isinstance(node, list):
        for item in node:
            removed += strip(item)
    return removed


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--all-planes", action="store_true",
                    help="also clean common/ and the other bundled planes")
    ap.add_argument("--dry-run", action="store_true", help="report without writing")
    args = ap.parse_args()

    root = os.path.normpath(RES if args.all_planes else os.path.join(RES, SHIPPING_PLANE))
    total_files = total_removed = 0

    for dirpath, _, files in os.walk(root):
        for name in sorted(files):
            if not name.endswith(".json"):
                continue
            path = os.path.join(dirpath, name)
            try:
                raw = open(path, encoding="utf-8").read()
                data = json.loads(raw)
            except (OSError, ValueError):
                continue  # not our JSON (comments, templates) - leave it alone

            # Only rewrite a file whose own formatting round-trips byte-for-byte, so a clean-up never
            # reformats a large data file as a side effect. This tree is not consistent - quests.json
            # is tab-indented, enemies.json uses four spaces - so the style is DETECTED per file
            # rather than assumed, and a file matching none of them is skipped rather than rewritten.
            indent = next((i for i in ("\t", "    ", "  ")
                           if json.dumps(data, indent=i, ensure_ascii=False) + "\n" == raw), None)
            if indent is None:
                probe = json.loads(raw)
                if strip(probe):
                    print("  SKIPPED (would reformat): %s" % os.path.relpath(path, RES))
                continue

            removed = strip(data)
            if not removed:
                continue
            total_files += 1
            total_removed += removed
            print("  %-60s %d null tag(s)" % (os.path.relpath(path, RES), removed))
            if args.dry_run:
                continue
            backup = path + ".bak"
            if not os.path.exists(backup):
                shutil.copy2(path, backup)
            with open(path, "w", encoding="utf-8", newline="") as fh:
                fh.write(json.dumps(data, indent=indent, ensure_ascii=False) + "\n")

    print("\n%s %d null questTag entr(ies) across %d file(s)"
          % ("would remove" if args.dry_run else "removed", total_removed, total_files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
