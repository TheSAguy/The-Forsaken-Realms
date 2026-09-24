"""audit.py - chest guards and crowded enemies, per map (round 316).

Two user reports:
  1. "296 chests have no guard - see if you can move existing enemies to patrol around these."
  2. "some dungeons now have two enemies standing right next to each other or very close to each other."

For every .tmx under <plane>/maps/map this reports size, enemies (and which already patrol), chests, the chests
with no guard - by ROUND 286'S OWN DEFINITION, i.e. dev-tools/booster_guards.audit(loot="treasure"): no
non-dialog enemy within 3 tiles (Chebyshev) that a reachable player can engage - with the distance to the nearest
enemy, the chests the GAME leaves without a dedicated guard (MapStage.assignLootGuards() is a one-enemy-per-piece
matching, booster > chest > pickup), and the clusters: enemies standing within --threshold tiles of each other,
center to center.

A cluster is counted among enemies that actually STAND somewhere: not dialog NPCs, not hidden ambushers, not
inactive set-pieces, and not ones that walk away on a patrol. An authored route does not count when the enemy is
a booster's guard - the runtime pins those to their post (EnemySprite.guardPost) and ignores the route.

usage: python audit.py <plane folder> [--dev-tools <repo>/dev-tools] [--provenance provenance.json]
                       [--threshold 2.0] [--out DIR] [--maps rel/path.tmx ...]
The plane folder is REQUIRED (e.g. "C:/TFR/repo/forge-gui/res/adventure/The Forsaken Realms"). Nothing is written
except audit.json and audit.md in --out (default: the current folder).
"""
import argparse
import collections
import json
import math
import os
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tfrmaps as T                                          # noqa: E402

DEFAULT_THRESHOLD = 2.0
BINS = [1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 6.0]
EXCLUDE_FROM_PLAN = ("towns/", "debug_map.tmx")               # player towns and the debug POI: reported, not planned


def category(rel):
    return rel.split("/")[0] if "/" in rel else "(root)"


DIFFICULTIES = ("Easy", "Normal", "Hard", "Insane")


def coexist(a, b):
    """True when some difficulty spawns both (MapStage.canSpawn); a pair stacked on one tile with disjoint
    spawn.X flags is ONE placement authored per difficulty, not two enemies."""
    return any(T.truthy(a.props.get("spawn." + d), True) and T.truthy(b.props.get("spawn." + d), True)
               for d in DIFFICULTIES)


def analyze_map(m, edata, prov, threshold):
    g = m.grids()
    ref = m.referenced_ids()
    enemies = m.enemies()
    rows, guard_of = T.match_guards(m)
    facts = {}
    for e in enemies:
        f = T.enemy_facts(m, e, edata, ref)
        pts, legs = T.route_points(m, e)
        role = guard_of.get(e.id)
        pinned = bool(role and role[1] == "booster")
        far = [p for p in pts if math.hypot(p[1] - e.x, p[2] - e.y) >= m.tw]
        f.update({
            "tile": list(e.tile()),
            "provenance": (prov or {}).get("%s#%d" % (m.rel, e.id), {}).get("first"),
            "moved_by_tool": (prov or {}).get("%s#%d" % (m.rel, e.id), {}).get("moved"),
            "guards": {"loot": role[0], "tier": role[1]} if role else None,
            "pinned": pinned,
            "route_waypoints": [p[0] for p in pts],
            "patrols": bool(far) and not pinned and not f["inactive"],
            "route_ignored": bool(pts) and pinned,
            "spawns_on_hard": m.spawns(e),
            "spawn_on": [d for d in DIFFICULTIES if T.truthy(e.props.get("spawn." + d), True)],
            "home_open": T.open_at(m, e.x, e.y),
            "home_standable": T.repo_standable(m, e.x, e.y),
            "engageable": bool(g["reach_q"] is not None and T.Q.engageable(g["reach_q"], m.wpx, m.hpx, e.x, e.y)),
        })
        facts[e.id] = f
    fighters = [e for e in enemies if not facts[e.id]["dialog"] and not facts[e.id]["inactive"]]
    for e in fighters:
        best = None
        for o in fighters:
            if o.id != e.id and coexist(o, e):
                d = math.hypot(o.x - e.x, o.y - e.y) / m.tw
                if best is None or d < best[0]:
                    best = (d, o.id)
        facts[e.id]["nearest"] = {"tiles": round(best[0], 3), "id": best[1]} if best else None
    standing = [e for e in fighters if not facts[e.id]["hidden"] and not facts[e.id]["patrols"]]
    pairs = []
    for i, a in enumerate(standing):
        for b in standing[i + 1:]:
            if not coexist(a, b):
                continue      # difficulty variants of one placement (spawn.X flags disjoint) never meet
            d = math.hypot(a.x - b.x, a.y - b.y) / m.tw
            if d <= threshold + 1e-6:
                pairs.append((a.id, b.id, round(d, 3)))
    # single-linkage groups
    parent = {e.id: e.id for e in standing}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for a, b, _d in pairs:
        parent[find(a)] = find(b)
    groups = collections.defaultdict(list)
    for a, b, _d in pairs:
        groups[find(a)]
    for e in standing:
        r = find(e.id)
        if r in groups:
            groups[r].append(e.id)
    clusters = [{"members": sorted(v), "pairs": [p for p in pairs if p[0] in v and p[1] in v]}
                for v in groups.values()]
    clusters.sort(key=lambda c: c["members"][0])

    # loot
    bg_g, bg_u, bg_rows = T.BG.audit(m.path, 3, loot="treasure")
    mine = T.proximity_guards(m)
    mismatch = [r["booster"]["id"] for r in bg_rows if bool(r["ok"]) != bool(mine.get(r["booster"]["id"]))]
    chests, boosters, others = [], [], 0
    for o, tier, gid, d in rows:
        if tier == "other":
            others += 1
            continue
        near = None
        for e in fighters:
            dd = math.hypot(e.x - o.x, e.y - o.y) / m.tw
            if near is None or dd < near[0]:
                near = (dd, e.id)
        rec = {"id": o.id, "tile": list(o.tile()), "runtime_guard": gid,
               "nearest_enemy": {"tiles": round(near[0], 3), "id": near[1]} if near else None}
        if tier == "treasure":
            bgr = next((r for r in bg_rows if r["booster"]["id"] == o.id), None)
            rec["r286_guarded"] = bool(bgr and bgr["ok"]) if bgr else None
            rec["r286_template_chest"] = bgr is not None
            if bgr is not None and not bgr["ok"]:
                if not g["has_entry"]:
                    rec["why"] = "no entry object - reachability unknowable, so the round-286 audit counts it unguarded"
                elif not bgr["near"]:
                    rec["why"] = "no enemy within 3 tiles"
                else:
                    rec["why"] = "enemies within 3 tiles are dialog NPCs or cannot be engaged"
            chests.append(rec)
        else:
            boosters.append(rec)
    # a chest the tool counts (treasure.tx) that the runtime does not (custom sprite) or vice versa
    runtime_ids = {c["id"] for c in chests}
    for r in bg_rows:
        if r["booster"]["id"] not in runtime_ids:
            o = m.by_id.get(r["booster"]["id"])
            near = None
            for e in fighters:
                dd = math.hypot(e.x - o.x, e.y - o.y) / m.tw
                if near is None or dd < near[0]:
                    near = (dd, e.id)
            rec = {"id": o.id, "tile": list(o.tile()), "runtime_guard": None,
                   "nearest_enemy": {"tiles": round(near[0], 3), "id": near[1]} if near else None,
                   "r286_guarded": bool(r["ok"]), "r286_template_chest": True, "runtime_chest": False,
                   "absent_on_hard": not m.spawns(o)}
            if not r["ok"]:
                rec["why"] = ("no entry object - reachability unknowable" if not g["has_entry"] else
                              "no enemy within 3 tiles" if not r["near"] else
                              "enemies within 3 tiles are dialog NPCs or cannot be engaged")
            chests.append(rec)
    return {
        "map": m.rel, "category": category(m.rel), "size": [m.width, m.height],
        "planned": not any(m.rel.startswith(x) or m.rel == x for x in EXCLUDE_FROM_PLAN),
        "has_entry": g["has_entry"], "collision_objects_inside": g["collision_objects_inside"],
        "enemies": [facts[e.id] for e in enemies],
        "counts": {
            "enemies": len(enemies), "fighters": len(fighters),
            "fighters_on_hard": sum(1 for e in fighters if facts[e.id]["spawns_on_hard"]),
            "dialog": sum(1 for e in enemies if facts[e.id]["dialog"]),
            "hidden": sum(1 for e in fighters if facts[e.id]["hidden"]),
            "inactive": sum(1 for e in enemies if facts[e.id]["inactive"]),
            "patrolling": sum(1 for e in enemies if facts[e.id]["patrols"]),
            "route_ignored_pinned": sum(1 for e in enemies if facts[e.id]["route_ignored"]),
            "standing": len(standing), "chests": len(chests), "boosters": len(boosters), "other_loot": others,
            "r286_unguarded": sum(1 for c in chests if c.get("r286_guarded") is False),
            "runtime_unmatched_chests": sum(1 for c in chests if c.get("runtime_chest", True) and not c["runtime_guard"]),
            "runtime_unmatched_boosters": sum(1 for b in boosters if not b["runtime_guard"]),
        },
        "chests": chests, "boosters": boosters, "clusters": clusters, "all_pairs": pairs,
        "self_check_r286_mismatch": mismatch,
    }


def hist(values, bins):
    out = collections.OrderedDict()
    for b in bins:
        out["<=%g" % b] = sum(1 for v in values if v <= b + 1e-6)
    out["all"] = len(values)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("plane", help="the plane folder (REQUIRED), e.g. .../adventure/The Forsaken Realms")
    ap.add_argument("--dev-tools", help="the repo's dev-tools folder (default: found above the plane)")
    ap.add_argument("--provenance", help="provenance.json from provenance.py (optional: labels tool-placed enemies)")
    ap.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD,
                    help="cluster distance in tiles, center to center (default %g)" % DEFAULT_THRESHOLD)
    ap.add_argument("--out", default=".", help="where audit.json / audit.md go (default: current folder)")
    ap.add_argument("--maps", nargs="*", help="limit to these maps (paths relative to maps/map)")
    a = ap.parse_args()

    root = T.maps_root(a.plane)
    T.load_tools(T.find_dev_tools(root, a.dev_tools))
    edata = T.load_enemy_data(T.plane_of(root))
    prov = json.load(open(a.provenance, encoding="utf-8")) if a.provenance else None
    files = [os.path.join(root, *p.split("/")) for p in a.maps] if a.maps else T.all_maps(root)

    results = []
    for f in files:
        try:
            m = T.TMap(f, root)
            results.append(analyze_map(m, edata, prov, a.threshold))
        except Exception as ex:                                    # noqa: BLE001
            results.append({"map": os.path.relpath(f, root).replace("\\", "/"), "error": repr(ex)})
            print("ERROR %s: %r" % (f, ex), file=sys.stderr)
    ok = [r for r in results if "error" not in r]

    # ---- global numbers ---------------------------------------------------------------------------------
    nn_all, nn_standing = [], []
    pair_prov = collections.Counter()
    for r in ok:
        standing_ids = {e["id"] for e in r["enemies"] if not e["dialog"] and not e["inactive"] and not e["hidden"]
                        and not e["patrols"]}
        byid = {e["id"]: e for e in r["enemies"]}
        for e in r["enemies"]:
            if e.get("nearest"):
                nn_all.append(e["nearest"]["tiles"])
                if e["id"] in standing_ids:
                    best = None
                    for o in r["enemies"]:
                        if o["id"] != e["id"] and o["id"] in standing_ids and set(o["spawn_on"]) & set(e["spawn_on"]):
                            d = math.hypot(o["x"] - e["x"], o["y"] - e["y"]) / 16.0
                            best = d if best is None or d < best else best
                    if best is not None:
                        nn_standing.append(best)
        for a_, b_, d in r["all_pairs"]:
            pa = "tool" if (byid[a_].get("provenance") or "r256") != "r256" else "authored"
            pb = "tool" if (byid[b_].get("provenance") or "r256") != "r256" else "authored"
            pair_prov["+".join(sorted((pa, pb)))] += 1
    tot = collections.Counter()
    for r in ok:
        for k, v in r["counts"].items():
            tot[k] += v
        tot["maps"] += 1
        tot["clusters"] += len(r["clusters"])
        tot["clustered_enemies"] += sum(len(c["members"]) for c in r["clusters"])
        tot["pairs"] += len(r["all_pairs"])
        tot["maps_with_clusters"] += 1 if r["clusters"] else 0
        tot["maps_no_entry"] += 0 if r["has_entry"] else 1
        tot["self_check_mismatch"] += len(r["self_check_r286_mismatch"])
    fighters_per_map = sorted(r["counts"]["fighters"] for r in ok if r["planned"] and r["counts"]["fighters"])
    summary = {
        "threshold_tiles": a.threshold,
        "totals": dict(tot),
        "nearest_enemy_tiles_all_fighters": hist(nn_all, BINS),
        "nearest_enemy_tiles_standing": hist(nn_standing, BINS),
        "cluster_pairs_by_provenance": dict(pair_prov) if prov else None,
        "fighters_per_map": {"maps": len(fighters_per_map),
                             "median": fighters_per_map[len(fighters_per_map) // 2] if fighters_per_map else 0,
                             "p75": fighters_per_map[(3 * len(fighters_per_map)) // 4] if fighters_per_map else 0,
                             "at_least_8": sum(1 for v in fighters_per_map if v >= 8),
                             "histogram": dict(collections.Counter(fighters_per_map))},
    }
    os.makedirs(a.out, exist_ok=True)
    json.dump({"summary": summary, "maps": results}, open(os.path.join(a.out, "audit.json"), "w", encoding="utf-8"),
              indent=1)
    write_md(os.path.join(a.out, "audit.md"), summary, results, prov is not None)
    t = summary["totals"]
    print("maps %d | chests %d, round-286 unguarded %d, runtime-unmatched %d | boosters %d, unmatched %d"
          % (t["maps"], t["chests"], t["r286_unguarded"], t["runtime_unmatched_chests"], t["boosters"],
             t["runtime_unmatched_boosters"]))
    print("enemies %d (fighters %d, patrolling %d, standing %d) | clusters %d in %d maps (%d enemies, %d pairs) "
          "at <= %g tiles" % (t["enemies"], t["fighters"], t["patrolling"], t["standing"], t["clusters"],
                              t["maps_with_clusters"], t["clustered_enemies"], t["pairs"], a.threshold))
    print("nearest-enemy distance, standing fighters:", dict(summary["nearest_enemy_tiles_standing"]))
    if prov:
        print("cluster pairs by provenance:", dict(pair_prov))
    if t["self_check_mismatch"]:
        print("WARNING: %d chest(s) where tfrmaps' round-286 mirror disagrees with booster_guards.audit"
              % t["self_check_mismatch"])
    return 0


def write_md(path, summary, results, have_prov):
    L = []
    t = summary["totals"]
    L.append("# Chest guards and crowded enemies - audit (round 316)\n")
    L.append("Threshold: enemies within **%g tiles** center to center count as standing together.\n"
             % summary["threshold_tiles"])
    L.append("| | count |\n|---|---|")
    for k in ("maps", "maps_no_entry", "enemies", "fighters", "dialog", "hidden", "inactive", "patrolling",
              "route_ignored_pinned", "standing", "chests", "r286_unguarded", "runtime_unmatched_chests", "boosters",
              "runtime_unmatched_boosters", "clusters", "maps_with_clusters", "clustered_enemies", "pairs"):
        L.append("| %s | %s |" % (k, t.get(k, 0)))
    L.append("\n## Nearest other enemy, center to center (tiles) - cumulative counts\n")
    L.append("| subset | " + " | ".join(summary["nearest_enemy_tiles_all_fighters"].keys()) + " |")
    L.append("|---|" + "---|" * len(summary["nearest_enemy_tiles_all_fighters"]))
    for label, key in (("all fighters", "nearest_enemy_tiles_all_fighters"),
                       ("standing fighters", "nearest_enemy_tiles_standing")):
        L.append("| %s | %s |" % (label, " | ".join(str(v) for v in summary[key].values())))
    if have_prov and summary.get("cluster_pairs_by_provenance"):
        L.append("\nPairs within the threshold, by who placed them: %s\n"
                 % ", ".join("%s %d" % kv for kv in sorted(summary["cluster_pairs_by_provenance"].items())))
    fp = summary["fighters_per_map"]
    L.append("\nFighters per planned map: median %d, 75th percentile %d, %d of %d maps have 8+.\n"
             % (fp["median"], fp["p75"], fp["at_least_8"], fp["maps"]))
    L.append("\n## Per map\n")
    L.append("`E` enemies (fighters/dialog), `P` patrolling, `C` chests, `U286` unguarded by round 286's definition, "
             "`UM` chests with no dedicated runtime guard, `K` clusters.\n")
    L.append("| map | size | E | P | C | U286 | UM | K |\n|---|---|---|---|---|---|---|---|")
    for r in results:
        if "error" in r:
            L.append("| %s | ERROR %s | | | | | | |" % (r["map"], r["error"]))
            continue
        c = r["counts"]
        L.append("| %s%s | %dx%d | %d (%d/%d) | %d | %d | %d | %d | %d |" % (
            r["map"], "" if r["planned"] else " *(not planned)*", r["size"][0], r["size"][1], c["enemies"],
            c["fighters"], c["dialog"], c["patrolling"], c["chests"], c["r286_unguarded"],
            c["runtime_unmatched_chests"], len(r["clusters"])))
    L.append("\n## Details\n")
    for r in results:
        if "error" in r:
            continue
        ung = [c for c in r["chests"] if c.get("r286_guarded") is False]
        um = [c for c in r["chests"] if c.get("runtime_chest", True) and not c["runtime_guard"] and c.get("r286_guarded")]
        if not ung and not um and not r["clusters"]:
            continue
        byid = {e["id"]: e for e in r["enemies"]}
        L.append("### %s\n" % r["map"])
        L.append("%dx%d tiles, %d enemies (%d patrolling), %d chests, %d boosters%s\n" % (
            r["size"][0], r["size"][1], r["counts"]["enemies"], r["counts"]["patrolling"], r["counts"]["chests"],
            r["counts"]["boosters"], "" if r["has_entry"] else ", NO ENTRY OBJECT"))
        for c in ung:
            ne = c.get("nearest_enemy")
            L.append("- chest %d at tile (%d,%d): **no guard** (round 286) - %s; nearest enemy %s" % (
                c["id"], c["tile"][0], c["tile"][1], c.get("why", "?"),
                "%s #%d at %.1f tiles" % (byid[ne["id"]]["name"], ne["id"], ne["tiles"]) if ne else "none"))
        for c in um:
            ne = c.get("nearest_enemy")
            L.append("- chest %d at tile (%d,%d): guarded by proximity but no dedicated runtime guard; nearest %s" % (
                c["id"], c["tile"][0], c["tile"][1],
                "%s #%d at %.1f tiles" % (byid[ne["id"]]["name"], ne["id"], ne["tiles"]) if ne else "none"))
        for k in r["clusters"]:
            desc = []
            for i in k["members"]:
                e = byid[i]
                role = ""
                if e["guards"]:
                    role = " guards %s %d%s" % (e["guards"]["tier"], e["guards"]["loot"], " (pinned)" if e["pinned"] else "")
                desc.append("%s #%d %s tile(%d,%d)%s%s%s" % (
                    e["name"], i, e["tier"], e["tile"][0], e["tile"][1], role,
                    " [%s]" % e["provenance"] if e.get("provenance") and e["provenance"] != "r256" else "",
                    " {%s}" % ", ".join(e["protected"]) if e["protected"] else ""))
            L.append("- cluster: " + "; ".join(desc) + " - distances " +
                     ", ".join("%d-%d %.2f" % p for p in k["pairs"]))
        L.append("")
    open(path, "w", encoding="utf-8").write("\n".join(L) + "\n")


if __name__ == "__main__":
    sys.exit(main())
