"""write_plan_md.py - plan.md from plan.json + the before/after audits + the QA outputs (round 329).

usage: python write_plan_md.py --plan plan.json --before audit_before.json --after audit_after.json --qa qa.json --out plan.md
"""
import argparse
import collections
import json
import os

DIFFS = ("Easy", "Normal", "Hard", "Insane")


def tl(t):
    return "(%d,%d)" % (t[0], t[1])


def per_patroller(audit, diff="Hard"):
    """{(map, pid): {"kinds": set, "cases": [..]}} for one difficulty."""
    out = collections.defaultdict(lambda: {"kinds": set(), "cases": []})
    for r in audit["maps"]:
        if "error" in r:
            continue
        for c in r["cases"]:
            if c["difficulty"] != diff:
                continue
            k = c["kind"] + ("/" + c["sub"] if c.get("sub") else "")
            pids = [c["patroller"]] if c.get("patroller") else c.get("patrollers", [])
            for pid in pids:
                out[(r["map"], pid)]["kinds"].add(k)
                out[(r["map"], pid)]["cases"].append(c)
    return out


def counts(audit):
    tot = collections.Counter()
    for r in audit["maps"]:
        if "error" in r:
            continue
        seen = set()
        for c in r["cases"]:
            k = c["kind"] + ("/" + c["sub"] if c.get("sub") else "")
            pids = [c["patroller"]] if c.get("patroller") else [tuple(c.get("patrollers", []))]
            for pid in pids:
                if (k, c["difficulty"], pid) not in seen:
                    seen.add((k, c["difficulty"], pid))
                    tot[(k, c["difficulty"])] += 1
    return tot


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", required=True)
    ap.add_argument("--before", required=True)
    ap.add_argument("--after", required=True)
    ap.add_argument("--qa", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    plan = json.load(open(a.plan, encoding="utf-8"))
    ab = json.load(open(a.before, encoding="utf-8"))
    aa = json.load(open(a.after, encoding="utf-8"))
    qa = json.load(open(a.qa, encoding="utf-8"))
    cb, ca = counts(ab), counts(aa)
    pb = per_patroller(ab)
    routes_by = {}
    for r in ab["maps"]:
        if "error" in r:
            continue
        for x in r["routes"]:
            routes_by[(r["map"], x["enemy"]["id"])] = x
    T = plan["totals"]
    L = []
    w = L.append
    w("# Round 329: patrols that park beside guarded loot - audit and plan\n")
    w("Generated %s from `%s`. **Nothing here has been applied to the repo.** `apply.py <repo root>` writes it "
      "(tested on a scratch copy - results below).\n" % (plan["generated"], plan["plane"]))
    w("User, play-testing two dungeons: *\"The two enemies I highlighted have patrol paths, but when they get to the "
      "chest, they stop/guard, even though there is already a guard there. This is probably widespread.\"* It is: "
      "**%d patrollers do exactly that on Hard** (the user's save), out of %d routes in the plane.\n"
      % (cb[("CONVERGING", "Hard")], ab["totals"]["routes"]))

    # ------------------------------------------------------------------ short version
    w("## The short version\n")
    t = ab["totals"]
    w("* **Where it comes from.** Round 258 (\"put a patrol route near Chests\") gave ~320 enemies a two-waypoint "
      "route whose BOTH waypoints sit on the chest's neighboring tiles - 176 new routes, and 144 hand-authored "
      "routes it overwrote. The walker goes post -> waypoint 1 -> waypoint 2 -> waypoint 1 ... and never returns "
      "to its post, so it lives at the chest. Rounds 286b/287 then put a standing guard beside most of those "
      "chests. Result: two enemies at one chest - the lodge's Wolfkin Outcast #95 (route 107,108, 0.9 and 2.1 "
      "tiles from chest 93) beside round 286b's Wolfkin Outcast #109.")
    dist = ab["distributions"]
    w("* **Audit (Hard):** %d routes in %d maps, %d run on Hard, %d never run (pinned booster guards, inactive, "
      "no resolvable waypoint). **CONVERGING %d**, **STUCK: FREEZE %d, ON-LOOT %d, IN-WALL %d**, "
      "OTHER: BESIDE-ENEMY %d (most of them the same patrollers as CONVERGING), SHARED-LOOT %d, MEET %d "
      "(informational)." % (t["routes"], t["maps"], t["routes_running_on_hard"], t["routes_inert"],
                            cb[("CONVERGING", "Hard")], cb[("STUCK/FREEZE", "Hard")], cb[("STUCK/ON-LOOT", "Hard")],
                            cb[("STUCK/IN-WALL", "Hard")], cb[("OTHER/BESIDE-ENEMY", "Hard")],
                            cb[("OTHER/SHARED-LOOT", "Hard")], cb[("OTHER/MEET", "Hard")]))
    w("* **CONVERGING by who wrote the route:** %s. **By shape:** %s. **By who placed the standing guard:** %s."
      % (", ".join("%s %d" % kv for kv in sorted(dist["converging_by_route_origin"].items(), key=lambda kv: -kv[1])),
         ", ".join("%s %d" % kv for kv in sorted(dist["converging_by_shape"].items(), key=lambda kv: -kv[1])),
         ", ".join("%s %d" % kv for kv in sorted(dist["converging_guard_placed_by"].items(), key=lambda kv: -kv[1]))))
    fx = collections.Counter()
    for rel, e in plan["maps"].items():
        for x in e["actions"]:
            fx[x["fix"]] += 1
    w("* **Plan: %d patrollers fixed in %d maps** - RESHUTTLE %d (a new 2-waypoint back-and-forth from its post, "
      "away from guarded loot), NUDGE %d (one waypoint of a longer route moved) + %d more fixed by the same shared-"
      "waypoint move, DROP-TOKEN %d (a waypoint taken out of that one route), DROP %d (route removed, it stands). "
      "Edits: **%d waypoint objects moved, %d added, %d route values rewritten, %d routes dropped**; no enemy moves, "
      "no enemy is added or removed."
      % (sum(fx.values()), T["maps_changed"], fx["RESHUTTLE"], fx["NUDGE"] + fx.get("NUDGE+DROP-TOKEN", 0),
         fx["COVERED"], fx["DROP-TOKEN"], fx["DROP"], T["waypoints_moved"], T["waypoints_new"],
         T["routes_rewritten"], T["routes_dropped"]))
    w("* **After (the audit re-run on the patched copy, Hard):** CONVERGING %d -> **%d**, FREEZE %d -> **%d**, "
      "ON-LOOT %d -> **%d**, IN-WALL %d -> **%d**, BESIDE-ENEMY %d -> %d, SHARED-LOOT %d -> %d. Every one left is "
      "listed under \"Left as they are\" - untouchable (bosses, a Challenger, dialog/scripted NPCs), the debug map, "
      "the map the user accepted as is, long hand-authored circuits that only brush past an enemy at a corner, "
      "and 5 of round 319's own cluster splits in rooms too cramped for any other route."
      % (cb[("CONVERGING", "Hard")], ca[("CONVERGING", "Hard")], cb[("STUCK/FREEZE", "Hard")],
         ca[("STUCK/FREEZE", "Hard")], cb[("STUCK/ON-LOOT", "Hard")], ca[("STUCK/ON-LOOT", "Hard")],
         cb[("STUCK/IN-WALL", "Hard")], ca[("STUCK/IN-WALL", "Hard")], cb[("OTHER/BESIDE-ENEMY", "Hard")],
         ca[("OTHER/BESIDE-ENEMY", "Hard")], cb[("OTHER/SHARED-LOOT", "Hard")], ca[("OTHER/SHARED-LOOT", "Hard")]))
    w("* **QA on the patched copy:** %s" % qa["summary"])
    w("")

    # ------------------------------------------------------------------ screenshots
    w("## The two screenshots\n")
    w("* **Hunting Lodge** (`hunting_lodge/inn_lodge_1.tmx`): Wolfkin Outcast #95, post tile (9,7), route `107,108` "
      "(round 258) -> waypoints at tiles (15,6) and (16,7), i.e. 2.1 and 0.9 tiles from chest 93, and 108 lies ON "
      "the post of round 286b's Wolfkin Outcast #109 (1 tile from the chest, its registered guard). Fix: 107 moves "
      "onto #95's post, 108 four tiles west of it - it now paces the west half of the room. "
      "`review/hunting_lodge__inn_lodge_1.png`.")
    w("* **The sandy cave** is most likely **`main_story_explore/bandit_cave.tmx`**: Bandit Archer #24 (Adept, red "
      "hood and mask) walks route `32,33` (round 258) to the tiles beside chest 17, where round 287's Skeleton #34 "
      "(Apprentice - the smaller sprite) already stands. Other sandy caves with a hooded/robed patroller doing the "
      "same, all fixed too: `cave/cave_red_05.tmx` (hooded Novice Pyromancer #6 beside round 287's Novice Pyromancer "
      "#13), `cave/cave_red_12.tmx` (Shaman #10 beside round 286b's Pyromancer #21), "
      "`barbariancamp/barbariancamp_bandit.tmx` (hooded Bandit Trapmaster #59, Master-sized, beside round 286b's "
      "small Cutpurse #96), and the sand-floored `lavaforge/lavaforge_2_burningsands.tmx` (four of them, including "
      "a crowned robed Pyromancer #59 that is actually frozen at its post right under the chest room - see FREEZE). "
      "All have before/after pictures in `review/`.")
    w("")

    # ------------------------------------------------------------------ mechanics
    w("## What the Java does (checked, not assumed)\n")
    w("* `MapStage.loadObjects()` reads `waypoints` (exact lower-case name; the plane has no other spelling today) "
      "into `EnemySprite.parseWaypoints()`: a deque. `getTargetVector()` pops a waypoint to the BACK once the enemy "
      "is within 2 px of it, so the walk is post -> w1 -> w2 -> ... -> wn -> w1: the post is visited once. `waitN`/"
      "`wN` pause, `rA-B` picks one at random each lap, an id with no waypoint object is dropped.")
    w("* A booster's registered guard is pinned (`guardPost` wins over the deque) - its route never runs, so the "
      "audit ignores it. A chest's guard keeps its route (`assignLootGuards()` pins boosters only). A hidden "
      "ambusher with a route springs on its first step; an `inactive` one never moves.")
    w("* **Correction to the working theory:** enemies are NOT stopped by walls. `EnemySprite.moveBy()` ends in "
      "scene2d's `Actor.moveBy()` - only `PlayerSprite` calls `prepareCollision/adjustMovement`. A waypoint inside a "
      "wall is walked INTO (the sprite overlaps the wall), not frozen short of. What freezes a walker is the "
      "pathfinder: `MapStage.onActing()` asks `NavigationMap.findShortestPath()` every frame; an empty path idles "
      "it (`navPath.getCount() == 0 -> Idle; continue`), the waypoint is never reached and the deque never "
      "advances. The audit replays that with round 319's `tfrmaps.NavSim` (the same graph: tile-center vertices "
      "minus inflated collision, 8-way edges, waypoints joined to their 4 nearest by clear rays, the walker's "
      "position to its 10 nearest - except when the walker stands EXACTLY on a waypoint, e.g. spawning on a route "
      "whose first waypoint is its post: then `findShortestPath()` finds it already in the graph and paths from "
      "that waypoint's own 4 edges; the replay does the same, and every new route that starts on its post was "
      "checked that way). Example found: `cave/dark_forest.tmx`'s Hobbling Zombie #7 - its two waypoints "
      "sit in two graph components that only its post bridges, so it walks to waypoint 30 and stands there for "
      "good. A flier never pathfinds (it flies straight).")
    w("")

    # ------------------------------------------------------------------ rules
    R = plan["rules"]
    w("## The rules the plan follows\n")
    w("* **A stop (waypoint) is good** when, on every difficulty the patroller walks: it is more than %g px (2 "
      "tiles) from any chest/booster another enemy guards (a standing non-dialog enemy within 3 tiles of it, its "
      "registered guard being another enemy whose route comes by, or another patroller stopping by it); at least "
      "%g px (1.5 tiles) from any visible standing enemy - never on or beside another post; at least %g px from "
      "another patroller's stop; never on (or half on) any chest/booster; and round 319's `Planner.wp_ok()`: "
      "inside the map, footprint clear of every object, open floor in the strict grid (object-layer collision "
      "included), `waypoint_routes_qa.standable()` legal and reachable."
      % (R["loot_clear_px"], R["enemy_clear_px"], R["stop_clear_px"]))
    w("* **RESHUTTLE** (2-stop routes that are the problem as a whole - round 258/319 routes, or both ends bad): "
      "A = its post, else the nearest good tile within %g tiles (%g on a second pass); B %g-%g tiles from A. "
      "Each leg (post->A, A->B, B->A) must have a path in the replayed NavigationMap no longer than %g tiles or "
      "%g x the straight line + 1 tile, every path vertex standable (it stays in the room), and the walked path keeps "
      "1.5 tiles (then 1) from guarded loot. A flier's shuttle line must be over open floor (its one flight from "
      "its post may cross what it was authored over). A route that is the only presence at some other unguarded "
      "chest keeps a stop by it when it can. The route's own two waypoint objects MOVE when no other route uses "
      "them (the `waypoints` value is untouched); otherwise two new objects are added."
      % (R["start_radius_tiles"][0], R["start_radius_tiles"][1], R["shuttle_tiles"][0], R["shuttle_tiles"][1],
         R["max_leg_tiles"], R["detour"]))
    w("* **NUDGE** (routes of 3+ stops, and a hand-authored shuttle with one bad end): only the bad waypoint moves, "
      "to the nearest good tile within %d tiles; when other routes walk the same waypoint (the generated caves "
      "share one circuit among 3-5 enemies) every one of their legs is re-checked and keeps a path no more than 3 "
      "tiles longer - one move fixes them all. A waypoint ON a chest the patroller alone looks after steps off it "
      "but stays within 2 tiles (the patrol keeps its chest). Failing that: **DROP-TOKEN** (the waypoint leaves "
      "that one route; the object stays for the others) - or, for a waypoint shared with the loot's own patroller, "
      "that is the first choice." % R["nudge_radius_tiles"])
    w("* **DROP** only when nothing fits and the post is not beside another visible enemy; a route that can never "
      "run (the walker is frozen at its post, no nav path out) is dropped as dead weight - no change on screen, "
      "and no per-frame pathfinding for a walker that cannot move.")
    w("* **Never touched:** bosses, legends, story-tagged or spawnRate-0 placements, dialog NPCs, defeatDialog "
      "scripts, anything a map script deletes/activates, questStageID/spawnCondition, inactive set-pieces, pinned "
      "booster guards, the player towns and the debug map (round 319's rule), phyrexian_black1 (the user accepted "
      "it as designed). Crowned / named / own-reward enemies are changed like the rest and marked `special` below.")
    w("")

    # ------------------------------------------------------------------ left
    w("## Left as they are\n")
    w("| map | enemy | why |")
    w("|---|---|---|")
    for rel, e in plan["maps"].items():
        for x in e["left"]:
            w("| %s | #%d %s | %s |" % (rel, x["enemy_id"], x["enemy"], x["why"].replace("|", "/")))
    w("\nAlso reported, not changed: the 4 IN-WALL findings left, all in phyrexian_black1 (its Dross Gladiators' "
      "circuits read 'outside' only because the flood fill cannot see that accepted map's floor), and BESIDE-ENEMY "
      "corners of long hand-authored "
      "circuits (4+ stops, no pause there) that brush 1-1.5 tiles past a standing enemy - the templeofchandra "
      "Magma Elementals, the library_of_varsil golems, the aeries. MEET (two short routes sharing a turn) is "
      "informational: 2 appear after the patch only because a route lost a waypoint and dropped under the "
      "3-stop threshold (cave_goblin #57 and crypt_2 #65 already shared those waypoints with #62).\n")
    w("Two routes round 286b left over lava/rock on purpose ARE changed, for other reasons: barbariancamp_goblin_2's "
      "Earth Elemental #54 (frozen at its post, no nav path out: dead route dropped) and lavaforge_1's Fire Giant "
      "#58 (round 258's chest patrol, frozen at its post: now a shuttle by its post).\n")
    w("Round 258 also gave chest patrols to three protected enemies in real maps (left alone here) - djinnpalace_entrance's boss "
      "Ancient Silver Dragon #105, unhallowed_abbey_2F's boss Valyx #116, magetower_13's Challenger Doppelganger "
      "#75 - plus the debug map's NPCs. Their routes are tool-made, not authored - worth a decision of their own.\n")

    # ------------------------------------------------------------------ per-map table
    w("## Every change, by map\n")
    w("`post` and waypoint positions are tiles (x, y from the top-left). Problems: CONV = converging on guarded "
      "loot (shape), ONLOOT = a stop on a chest/booster, BESIDE = a stop beside a standing enemy, SHARED = two "
      "patrollers on one unguarded chest, FREEZE = no nav path for a leg. `origin` = who wrote the route "
      "(stock = hand-authored, r258 / r319 = those rounds' tools).\n")
    w("| map | enemy (post) | origin | problem, loot and its guard (Hard) | fix | route before -> after |")
    w("|---|---|---|---|---|---|")
    for rel, e in plan["maps"].items():
        for x in e["actions"]:
            pid = x["enemy_id"]
            rt = routes_by.get((rel, pid)) or {}
            stops = " ".join("%d@%s" % (s["id"], tl(s["tile"])) for s in rt.get("stops", []))
            probs = []
            info = pb.get((rel, pid), {"cases": []})
            conv = {}
            for c in info["cases"]:
                if c["kind"] == "CONVERGING":
                    conv[c["loot"]["id"]] = c
            for lid, c in conv.items():
                g = ", ".join("#%d %s%s %.1ft" % (gg["id"], gg["name"], " [%s]" % gg["provenance"]
                                                  if gg.get("provenance") and gg["provenance"] != "r256" else "",
                                                  gg["dist_tiles"]) for gg in c["guards"][:2])
                probs.append("CONV %s: %s %d %s, guard %s" % (c["shape"], "chest" if c["loot"]["tier"] == "treasure"
                                                               else "booster", lid, tl(c["loot"]["tile"]), g))
            p = x["problems"]
            if p.get("on_loot"):
                probs.append("ONLOOT wp %s" % ", ".join("%s->%s" % kv for kv in p["on_loot"].items()))
            if p.get("beside_enemy") and not conv:
                probs.append("BESIDE " + ", ".join("wp %s ~ #%s" % (k, "/#".join(map(str, v)))
                                                   for k, v in p["beside_enemy"].items()))
            if p.get("shared_loot"):
                probs.append("SHARED " + ", ".join("wp %s ~ chest %s" % (k, v[0] if isinstance(v, list) else v)
                                                   for k, v in p["shared_loot"].items()))
            if p.get("freeze"):
                probs.append("FREEZE %s" % ("at its post" if any(l[0] == "post" for l in p["freeze"]) else
                                            "legs " + ", ".join("%s->%s" % tuple(l) for l in p["freeze"][:3])))
            if not probs:
                probs.append(x["why"])
            fix = x["fix"]
            if x.get("pass") and x["pass"] != "normal":
                fix += " (%s)" % x["pass"].split(":")[0]
            if x.get("special"):
                fix += " *special: %s*" % ", ".join(x["special"])
            if fix.startswith("RESHUTTLE"):
                after = "%s <-> %s, %.1f tiles%s%s" % (tl(x["a"]["tile"]), tl(x["b"]["tile"]), x["len_tiles"],
                                                       "" if x["write"] == "move" else " (new objects %s)" % x["new_value"],
                                                       "" if x.get("straight_clear") else ", walks round an obstacle")
            elif fix.startswith("NUDGE") or fix.startswith("DROP-TOKEN") or fix.startswith("PARTIAL"):
                bits = ["wp %d %s->%s (%.1ft)" % (n["waypoint"], tl(n["from_tile"]), tl(n["to_tile"]), n["moved_tiles"])
                        for n in x.get("nudges", [])]
                bits += ["wp %d out of this route" % d["waypoint"] for d in x.get("dropped_tokens", [])]
                if x.get("new_value") != x.get("old_value"):
                    bits.append("`%s`" % x["new_value"])
                after = "; ".join(bits)
            elif fix.startswith("COVERED"):
                after = "fixed by the move of wp %s" % ", ".join(str(c["waypoint"]) for c in x.get("covered", []))
            elif fix.startswith("DROP"):
                after = "stands at its post" + (" (%s)" % x["note"] if x.get("note") else "")
            else:
                after = ""
            w("| %s | #%d %s %s | %s | %s | %s | `%s` %s -> %s |" % (
                rel, pid, x["enemy"], tl(x.get("post_tile") or [0, 0]), x["problems"].get("route_origin", "?"),
                "; ".join(probs).replace("|", "/"), fix, x["old_value"], stops, after.replace("|", "/")))
    w("")
    w("## Files\n")
    w("* `plan.json` - per map: the SHA-1 it was planned against, `edits` (exactly what apply.py writes: "
      "move_waypoints, new_waypoints, set_route, drop_route) and `actions` (why, problems, legs, alternatives tried).")
    w("* `apply.py <repo root>` - the root is REQUIRED; refuses a map whose SHA-1 changed since planning (`--force` "
      "overrides), checks every object against the plan, edits raw text (line endings, BOM, indentation kept), "
      "re-parses and verifies before writing. `--dry-run`, `--log`, `--maps`.")
    w("* `tools/` - `audit_routes.py` (the audit), `plan_routes.py` (the planner), `pmodel.py` (the route model on "
      "top of round 319's tfrmaps), `route_provenance.py`, `review.py`, `check_bytes.py`, `presence.py`, "
      "`verify_post_start.py`, `chest_cov.py`, `write_plan_md.py`.")
    w("* `audit_before.json` / `audit_after.json` - every case per difficulty; `review/` - before/after pictures.")
    open(a.out, "w", encoding="utf-8").write("\n".join(L) + "\n")
    print("wrote", a.out, len("\n".join(L)), "chars")


if __name__ == "__main__":
    main()
