#!/usr/bin/env python3
"""NoRotate audit (round 228): which rotatable dungeon/cave POIs really lock part of themselves behind an item?

Usage (from the repo root):
    python dev-tools/norotate_scan.py ["forge-gui/res/adventure/The Forsaken Realms"]
Exit code 0 when the tags and the maps agree, 1 when they do not. Read-only.

WHY THIS EXISTS. DungeonRotation despawns a rotatable dungeon on its timer, when it is cleared, and at once
when the player LOSES a duel inside it. A map that hides a key in one room and consumes it at a sealed door
in another must not vanish with the key already in the player's pack, so such POIs carry the "NoRotate"
quest tag (round 184). Round 184 chose its twelve by the map's FOLDER, which was wrong eleven times out of
twelve and missed two real ones; the user found it as "I lost a duel in this dungeon, but it did not
disappear". This script decides it from the maps themselves, following every linked map:

  a POI needs NoRotate  <=>  some map in its own tree has a removeItem action or an "item" condition.

A reward of type "item" is NOT a gate (that is what fooled a looser pattern into flagging the vampire
castles), so the condition pattern requires the key form  "item":  and not the value form  :"item".
POIs that can never rotate anyway (not dungeon/cave, no Hostile tag, a Story or Quest_ tag, Quest_/Test
names) are skipped - the tag is moot for them.
"""
import io
import json
import os
import re
import sys

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PLANE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms")
COMMON = os.path.normpath(os.path.join(PLANE, "..", "common"))

REMOVE_ITEM = re.compile(r"removeItem")
ITEM_CONDITION = re.compile(r"&quot;item&quot;\s*:")
LINKED_MAP = re.compile(r'value="([^"]+\.tmx)"')


def resolve(map_value, from_path=None):
    """POI and teleport map paths are written relative to the common folder."""
    value = map_value.replace("\\", "/")
    cand = os.path.normpath(os.path.join(COMMON, value))
    if os.path.exists(cand):
        return cand
    if from_path:
        cand = os.path.normpath(os.path.join(os.path.dirname(from_path), value))
        if os.path.exists(cand):
            return cand
    return None


def scan(path, seen, hits):
    if path is None or path in seen:
        return
    seen.add(path)
    with io.open(path, encoding="utf-8", errors="replace") as f:
        text = f.read()
    removes = len(REMOVE_ITEM.findall(text))
    conditions = len(ITEM_CONDITION.findall(text))
    if removes or conditions:
        hits.append("%s (removeItem x%d, item condition x%d)" % (os.path.basename(path), removes, conditions))
    for linked in LINKED_MAP.findall(text):
        scan(resolve(linked, path), seen, hits)


def can_rotate_without_the_tag(poi, tags):
    """DungeonRotation.isRotatableData() minus its NoRotate line."""
    name = poi.get("name") or ""
    if (poi.get("type") or "").lower() not in ("dungeon", "cave"):
        return False
    if name.startswith("Quest_") or name in ("DEBUGZONE", "Test"):
        return False
    if "Story" in tags or any(t.startswith("Quest_") for t in tags):
        return False
    return "Hostile" in tags


def main():
    with io.open(os.path.join(PLANE, "world", "points_of_interest.json"), encoding="utf-8") as f:
        pois = json.load(f)
    agree, needless, missing, unresolved = [], [], [], []
    for poi in pois:
        tags = [t for t in (poi.get("questTags") or []) if t]
        if not can_rotate_without_the_tag(poi, tags):
            continue
        root = resolve(poi.get("map") or "")
        if root is None:
            unresolved.append(poi.get("name"))
            continue
        seen, hits = set(), []
        scan(root, seen, hits)
        row = "%-18s %-30s maps=%-2d %s" % (poi.get("name"), poi.get("displayName"), len(seen),
                                           "; ".join(hits) if hits else "-")
        tagged = "NoRotate" in tags
        if tagged and hits:
            agree.append(row)
        elif tagged:
            needless.append(row)
        elif hits:
            missing.append(row)
    print("NoRotate and the maps agree (%d):" % len(agree))
    for row in agree:
        print("   " + row)
    print("NoRotate but NO item gate anywhere in the map tree - will never despawn for no reason (%d):" % len(needless))
    for row in needless:
        print("   " + row)
    print("An item gate but NO NoRotate - can vanish with its key in the player's pack (%d):" % len(missing))
    for row in missing:
        print("   " + row)
    if unresolved:
        print("map path not resolved (%d): %s" % (len(unresolved), ", ".join(str(n) for n in unresolved)))
    return 1 if (needless or missing or unresolved) else 0


if __name__ == "__main__":
    sys.exit(main())
