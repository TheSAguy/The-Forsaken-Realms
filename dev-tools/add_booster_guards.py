"""add_booster_guards.py - give every unguarded booster a guard (round 279).

`booster_guards.py` audits; this writes. The audit found **71 of 261 boosters with no guard within three tiles**,
across maps round 258's pass never covered (the Strixhaven-style Classroom maps, the Gitrog Bogs, the groves).

A new guard is a fresh `<object>` from the same `enemy.tx` template the maps already use, so it loads through
exactly the path every other enemy does - no new code, no new properties the engine does not already read:

  position   a whole-tile offset from the BOOSTER's own authored x/y, out to --radius tiles, nearest first. No
             coordinate conversion is involved in a write, which is what rounds 269 and 275 paid for twice. The
             tile has to hold a player position reachable from an entry (so the guard can be fought) and must not
             already hold another object.
  enemy      chosen from the enemies ALREADY IN THAT MAP, so a guard never looks out of place - the map's own
             roster is the best possible theme match. The most common non-dialog, non-boss name wins; a map with
             no enemies at all falls back to --fallback.
  ranges     threatRange from --threat (default 30, about two tiles: the user asked for "a small reaction
             radius"), and NO pursueRange, because MapStage.applyDefaultReactionRange() now fills that in from
             the plane's own tuning - which is the round-279 fix and the reason these guards will actually chase.
  id         max(existing object id) + 1, and nextobjectid on the <map> element is bumped to match, so Tiled can
             open the file afterwards without renumbering anything.

usage: python dev-tools/add_booster_guards.py --list
       python dev-tools/add_booster_guards.py --apply [--radius 3] [--threat 30]
"""
import argparse
import collections
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pixel_collision_qa as q
import booster_guards as bg

TEMPLATE_BY_DEPTH = "../" * 4 + "common/maps/obj/enemy.tx"
FALLBACK_ENEMY = "Skeleton"


def map_roster(tmx, want_rank=1):
    """The most plausible guard name for this map: its commonest non-dialog enemy of Adept rank or better.

    Round 279, user: *"make booster guards minimum level be Adept, so no Apprentices."* The first cut took the
    map's commonest enemy outright, which in most rooms is an Apprentice, so every one of those 69 new guards
    then needed promoting by `upgrade_booster_guards.py`. Filtering here means a fresh run never adds one.
    Falls back to the commonest enemy of any rank if the map has no Adept+ at all - the caller's --fallback is
    for a map with no enemies whatsoever - and `upgrade_booster_guards.py` remains the backstop either way.
    """
    import upgrade_booster_guards as ug
    enemies = ug.load_enemies()
    ranked, any_rank = collections.Counter(), collections.Counter()
    for o in bg.objects_of(tmx):
        if o["kind"] != "enemy" or o["has_dialog"] or not o["name"]:
            continue
        any_rank[o["name"]] += 1
        rank = enemies.get(o["name"], {}).get("rank", 0)
        # want_rank 1 = Adept or better (boosters, round 279). want_rank 0 = Apprentice EXACTLY, which
        # is round 286b's chest rule: the user asked for chests to be guarded too but deliberately
        # weaker than boosters - "All, but make them Apprentice level."
        if (rank >= 1) if want_rank else (rank == 0):
            ranked[o["name"]] += 1
    if ranked:
        return ranked.most_common(1)[0][0]
    # Round 287: the any-rank fallback is right for BOOSTERS (want Adept+, settle for whatever the
    # room has - still themed, and upgrade_booster_guards is the backstop) but backwards for CHESTS.
    # Asked for an Apprentice in a map whose roster has none, it returned the map's commonest enemy of
    # ANY rank - which put a MYTHIC Phoenix on templeofchandra's chests and an Adept Demon on
    # unhallowed_abbey_2F's, against an explicit "make them Apprentice level". Settling UP is worse
    # than being off-theme, so the caller's --fallback (Skeleton, Common) wins instead.
    if not want_rank:
        return None
    return any_rank.most_common(1)[0][0] if any_rank else None


def template_for(tmx):
    """enemy.tx relative to this map, matching how the map's own objects reference it."""
    t = open(tmx, encoding="utf-8", errors="replace").read()
    m = re.search(r'template="((?:\.\./)+common/maps/obj/enemy\.tx)"', t)
    if m:
        return m.group(1)
    m = re.search(r'template="((?:\.\./)+common/maps/obj/[^"]+\.tx)"', t)
    if m:
        return re.sub(r'[^/]+\.tx$', 'enemy.tx', m.group(1))
    return TEMPLATE_BY_DEPTH


def plan(tmx, radius, fallback, loot="booster", want_rank=1, need_override=None):
    """[(loot, x, y, enemy name)] for each unguarded piece of loot that can be given a guard.

    `need_override` (round 287) supplies the work list directly, as [{...loot object...}]. Without it
    this derives the list from bg.audit(), which is a PROXIMITY test - "is any non-dialog enemy within
    3 tiles" - and one enemy answers yes for every chest in the room. add_loot_guards.py replays the
    game's actual MATCHING instead (one enemy per piece) and passes the result in, which is the only
    way loot that has a neighbour but no dedicated guard ever reaches the placement code below.
    """
    if need_override is not None:
        need = [{"booster": p, "ok": False} for p in need_override]
    else:
        _g, _u, rows = bg.audit(tmx, radius, loot=loot)
        need = [r for r in rows if not r["ok"]]
    if not need:
        return [], []
    free, reach, wpx, hpx, tw, th, _o = q.reachable_from_entries(tmx)
    if reach is None:
        return [], [(r["booster"], "no entry object to flood-fill from") for r in need]
    taken = {(int(o["x"] // tw), int(o["y"] // th)) for o in bg.objects_of(tmx)}
    name = map_roster(tmx, want_rank) or fallback
    def standable_here(cx, cy):
        """The guard's OWN tile must hold a player position reachable from an entry.

        `engageable` alone is not enough and the first run proved it: it asks whether any reachable position is
        within 20 px, which a tile just inside the map satisfies for a candidate just OUTSIDE it - and it put a
        Dwarf Demolisher at x = -6.66 in zedruu_f0. Exactly round 269's bug, which round 275 was supposed to have
        taught me. So: inside the rectangle, and its own tile reachable.
        """
        if cx < 0 or cy < 0 or cx + tw > wpx or cy > hpx:
            return False
        tx0, ty0 = int(cx // tw) * tw, (int(cy // th) - 1) * th
        for by in range(max(0, ty0), min(hpx, ty0 + th)):
            row = by * wpx
            for bx in range(max(0, tx0), min(wpx, tx0 + tw)):
                if reach[row + bx]:
                    return True
        return False

    made, failed = [], []
    for r in need:
        b = r["booster"]
        best = None
        for dx in range(-radius, radius + 1):
            for dy in range(-radius, radius + 1):
                if dx == 0 and dy == 0:
                    continue
                cx, cy = b["x"] + dx * tw, b["y"] + dy * th
                if (int(cx // tw), int(cy // th)) in taken:
                    continue
                if not standable_here(cx, cy):
                    continue
                d = dx * dx + dy * dy
                if best is None or d < best[0]:
                    best = (d, cx, cy)
        if best is None:
            failed.append((b, "no reachable free tile within %d tiles" % radius))
            continue
        _d, cx, cy = best
        taken.add((int(cx // tw), int(cy // th)))
        made.append((b, cx, cy, name))
    return made, failed


def fmt(v):
    return str(int(v)) if float(v) == int(v) else ("%.4f" % v).rstrip("0").rstrip(".")


def apply(tmx, made, threat):
    """Insert one <object> per planned guard into the same objectgroup the boosters live in."""
    text = open(tmx, encoding="utf-8", errors="replace").read()
    ids = [int(m) for m in re.findall(r'<object id="(\d+)"', text)]
    next_id = (max(ids) + 1) if ids else 1
    tpl = template_for(tmx)
    blocks = []
    for (_b, cx, cy, name) in made:
        blocks.append(
            '  <object id="%d" template="%s" x="%s" y="%s">\n'
            '   <properties>\n'
            '    <property name="enemy" value="%s"/>\n'
            '    <property name="threatRange" type="int" value="%d"/>\n'
            '   </properties>\n'
            '  </object>\n' % (next_id, tpl, fmt(cx), fmt(cy), name, threat))
        next_id += 1
    # into the LAST objectgroup, which is where these maps keep their objects
    idx = text.rfind("</objectgroup>")
    if idx < 0:
        return 0
    text = text[:idx] + "".join(blocks) + text[idx:]
    text = re.sub(r'(<map\b[^>]*?\bnextobjectid=")\d+(")', lambda m: m.group(1) + str(next_id) + m.group(2),
                  text, count=1)
    open(tmx, "w", encoding="utf-8", newline="\n").write(text)
    return len(blocks)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("maps", nargs="*")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--radius", type=int, default=3)
    ap.add_argument("--threat", type=int, default=30)
    ap.add_argument("--fallback", default=FALLBACK_ENEMY)
    ap.add_argument("--loot", default="booster", choices=["booster", "treasure"],
                    help="which loot template to guard (treasure = chests)")
    ap.add_argument("--rank", type=int, default=1,
                    help="1 = pick an Adept+ guard (boosters), 0 = pick an Apprentice (chests)")
    args = ap.parse_args()
    targets = args.maps or sorted(glob.glob(os.path.join(q.DEFAULT_ROOT, "**", "*.tmx"), recursive=True))
    total = skipped = 0
    for tmx in targets:
        try:
            made, failed = plan(tmx, args.radius, args.fallback, args.loot, args.rank)
        except Exception as ex:
            print("%-44s ERROR %r" % (os.path.basename(tmx), ex))
            continue
        if not made and not failed:
            continue
        rel = os.path.relpath(tmx, q.DEFAULT_ROOT).replace("\\", "/")
        for (b, cx, cy, name) in made:
            print("%-40s booster %-5d -> guard %-22s at (%s,%s)"
                  % (rel[:40], b["id"], name[:22], fmt(cx), fmt(cy)))
        for (b, why) in failed:
            print("%-40s booster %-5d -> NONE  %s" % (rel[:40], b["id"], why))
            skipped += 1
        if args.apply and made:
            total += apply(tmx, made, args.threat)
        else:
            total += len(made)
    print("\n%d guard(s) %s, %d booster(s) left without one"
          % (total, "written" if args.apply else "placeable", skipped))


if __name__ == "__main__":
    main()
