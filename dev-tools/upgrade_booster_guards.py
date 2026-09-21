"""upgrade_booster_guards.py - no Apprentice guards on a booster (round 279).

User: *"make booster guards minimum level be Adept, so no Apprentices."*

Rank is `tier` in `world/enemies.json`: Common = Apprentice, Uncommon = Adept, Rare = Master, Mythic Rare =
Archmage. So this finds every booster's guard (the nearest non-dialog enemy within --radius tiles, the same
definition `booster_guards.py` audits with and `MapStage.assignBoosterGuards()` pins at runtime) and, where ALL of
a booster's guards are Common, promotes one.

Choosing the promotion matters more than it looks - a guard that does not belong to its room reads as a bug. The
replacement is picked by:
  1. an Uncommon+ enemy ALREADY IN THAT MAP, if there is one. The map's own roster is the best theme match there
     can be, and it adds nothing new to the room.
  2. otherwise the Uncommon whose `questTags` overlap the Apprentice's most - which is how "Rusted Golem"
     (Common, tags Golem/Construct) finds "Clay Golem" rather than a Yeti.
Uncommon is preferred over Rare and Mythic at equal overlap: the user asked for a floor, not an escalation.

usage: python dev-tools/upgrade_booster_guards.py --list
       python dev-tools/upgrade_booster_guards.py --apply
"""
import argparse
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pixel_collision_qa as q
import booster_guards as bg

ENEMIES_JSON = os.path.normpath(os.path.join(
    HERE, "..", "forge-gui", "res", "adventure", "The Forsaken Realms", "world", "enemies.json"))
RANK = {"common": 0, "uncommon": 1, "rare": 2, "mythic rare": 3, "mythic": 3}
RANK_NAME = {0: "Apprentice", 1: "Adept", 2: "Master", 3: "Archmage"}


def load_enemies():
    d = json.load(open(ENEMIES_JSON, encoding="utf-8"))
    items = d if isinstance(d, list) else d.get("enemies", [])
    by_name = {}
    for e in items:
        if not isinstance(e, dict) or not e.get("name"):
            continue
        by_name[e["name"]] = {"tier": (e.get("tier") or "").strip(),
                              "rank": RANK.get((e.get("tier") or "").strip().lower(), 0),
                              "tags": set(e.get("questTags") or []),
                              "boss": bool(e.get("boss"))}
    return by_name


def creature_tags(tags):
    """The tags that say what a creature IS, not where it lives.

    `Biome*` and `Identity*` are placement and colour metadata - nearly every enemy in a white cave shares
    BiomeWhite/IdentityWhite - so counting them as theme put a "Market Trader" on a booster in cave_white_13 on
    the strength of sharing a biome with a skeleton. They still break ties (below), they just do not decide.
    """
    return {t for t in tags if not t.startswith("Biome") and not t.startswith("Identity")}


def promotion_for(apprentice, in_map_names, enemies):
    """The Adept-or-better replacement for an Apprentice guard, and why."""
    src = enemies.get(apprentice, {"tags": set()})
    # 1. the map's own roster - but only a THEMATIC member of it. Sorting by rank first (the first cut) put a
    #    "Market Trader" on a booster in cave_white_13 and a "Hellion" on one in cave_snake, because an Adept
    #    with nothing in common outranked a Master that fit the room. Overlap decides, then the lowest rank that
    #    still fits, so the floor stays Adept without escalating to Archmage for no reason. A map whose Adept+
    #    roster shares nothing with the Apprentice falls through to the global thematic search below.
    src_theme = creature_tags(src["tags"])
    local = [(n, enemies[n]) for n in in_map_names
             if n in enemies and enemies[n]["rank"] >= 1 and not enemies[n]["boss"]
             and (creature_tags(enemies[n]["tags"]) & src_theme)]
    if local:
        local.sort(key=lambda p: (-len(creature_tags(p[1]["tags"]) & src_theme),
                                  -len(p[1]["tags"] & src["tags"]), p[1]["rank"], p[0]))
        shared = sorted(creature_tags(local[0][1]["tags"]) & src_theme)
        return local[0][0], "in this map, shares %s" % ", ".join(shared[:3])
    # 2. the closest thematic Adept in the whole roster
    pool = [(n, v) for n, v in enemies.items() if v["rank"] >= 1 and not v["boss"] and v["tags"]]
    if not pool:
        return None, "no Adept+ enemy anywhere"
    pool.sort(key=lambda p: (-len(creature_tags(p[1]["tags"]) & src_theme),
                             -len(p[1]["tags"] & src["tags"]), p[1]["rank"], p[0]))
    best, v = pool[0]
    shared = sorted(creature_tags(v["tags"]) & src_theme) or sorted(v["tags"] & src["tags"])
    return best, ("shares %s" % ", ".join(shared[:3])) if shared else "no shared tags - nearest Adept by name"


def plan(tmx, radius, enemies):
    """[(booster id, guard object, new name, why)] for boosters whose only guards are Apprentices."""
    _g, _u, rows = bg.audit(tmx, radius)
    if not rows:
        return []
    in_map = {o["name"] for o in bg.objects_of(tmx) if o["kind"] == "enemy" and o["name"]}
    out = []
    for r in rows:
        guards = [n for n in r["near"] if not n[1]["has_dialog"] and n[2]]
        if not guards:
            continue  # unguarded - add_booster_guards.py's job, not this one
        if any(enemies.get(n[1]["name"], {}).get("rank", 0) >= 1 for n in guards):
            continue  # already has an Adept or better standing by it
        weakest = guards[0][1]  # nearest, and they are all Apprentices
        new, why = promotion_for(weakest["name"], in_map, enemies)
        if new and new != weakest["name"]:
            out.append((r["booster"]["id"], weakest, new, why))
    return out


def apply(tmx, changes):
    """Rewrite just the `enemy` property value of each named object."""
    text = open(tmx, encoding="utf-8", errors="replace").read()
    done = 0
    for (_bid, guard, new, _why) in changes:
        # Two things this pattern has to get right, both learned by getting them wrong:
        #  - NO `\b` after id="N". A closing quote followed by a space is not a word boundary, so
        #    `id="%d"\b` never matches anything and --apply silently rewrote nothing while --list kept
        #    reporting 49 changes to make.
        #  - the match must not leave THIS object. A tempered `(?:(?!</object>).)*?` stops at the object's own
        #    end tag, so an object with no inline `enemy` property cannot reach forward and rename the next
        #    enemy in the file.
        pat = re.compile(r'(<object id="%d"(?:(?!</object>).)*?<property name="enemy" value=")([^"]*)(")'
                         % guard["id"], re.S)
        new_text, n = pat.subn(lambda m: m.group(1) + new + m.group(3), text, count=1)
        if n:
            text = new_text
            done += 1
    if done:
        open(tmx, "w", encoding="utf-8", newline="\n").write(text)
    return done


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("maps", nargs="*")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--radius", type=float, default=3)
    args = ap.parse_args()
    enemies = load_enemies()
    targets = args.maps or sorted(glob.glob(os.path.join(q.DEFAULT_ROOT, "**", "*.tmx"), recursive=True))
    total = 0
    for tmx in targets:
        try:
            changes = plan(tmx, args.radius, enemies)
        except Exception as ex:
            print("%-42s ERROR %r" % (os.path.basename(tmx), ex))
            continue
        if not changes:
            continue
        rel = os.path.relpath(tmx, q.DEFAULT_ROOT).replace("\\", "/")
        for (bid, guard, new, why) in changes:
            old_rank = RANK_NAME.get(enemies.get(guard["name"], {}).get("rank", 0), "?")
            new_rank = RANK_NAME.get(enemies.get(new, {}).get("rank", 0), "?")
            print("%-38s booster %-5d guard %-20s (%s) -> %-20s (%s)  [%s]"
                  % (rel[:38], bid, guard["name"][:20], old_rank, new[:20], new_rank, why))
        total += apply(tmx, changes) if args.apply else len(changes)
    print("\n%d booster guard(s) %s to Adept or better"
          % (total, "promoted" if args.apply else "would be promoted"))


if __name__ == "__main__":
    main()
