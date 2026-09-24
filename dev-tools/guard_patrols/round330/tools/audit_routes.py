"""audit_routes.py - every patrol route in the plane, checked for the shapes that park a patroller beside loot
or beside another enemy (round 329).

User, play-testing two dungeons: "The two enemies I highlighted have patrol paths, but when they get to the
chest, they stop/guard, even though there is already a guard there. This is probably widespread."

For every enemy with a `waypoints` route (all of them - hand-authored, round 258's chest patrols, round 319's)
on each difficulty (Hard = the user's save is the headline; the others are reported where they differ):

  (a) CONVERGING  a stop (waypoint) of the route lies within 2 tiles (32 px) of a chest or booster while
                  ANOTHER enemy stands within 3 tiles (48 px) of it - a non-dialog enemy that stays at its post
                  (no route, an inert route, a pinned booster guard; hidden ambushers counted and marked).
                  Shapes: ALL (every stop is by the loot - the patroller lives there, the lodge case),
                  FAR-END (the stop farthest from its post is by the loot - it walks over and turns there),
                  PASSES (another stop of a longer route is by the loot).
  (b) STUCK       FREEZE: the game's NavigationMap has no path for a leg (replayed with tfrmaps.NavSim) - the
                  walker idles at the leg's origin for good; the deque never advances. IN-WALL: a stop on a
                  tile the player box cannot stand on (waypoint_routes_qa's "blocked"), in the sealed band
                  outside the room ("outside"), or inside an object-layer collision box (tfrmaps' strict grid)
                  - enemies ignore collision (Actor.moveBy), so these are walked INTO rather than frozen at.
                  ON-LOOT: a stop within 10 px of a chest/booster - it stands on the loot.
  (c) OTHER       BESIDE-ENEMY: a stop within 1.5 tiles (24 px) of another visible enemy standing at its post.
                  SHARED-LOOT: two or more patrollers stop by the same chest/booster that has no standing guard
                  (they take turns standing there as a pair). FROZEN-BESIDE: a freeze point beside loot or an
                  enemy. MEET: two short routes' stops within 1 tile of each other (informational).
                  INERT: a route that never runs (empty, only dangling ids, pinned booster guard, inactive).

usage: python audit_routes.py <plane folder> --out DIR [--dev-tools DIR] [--provenance provenance.json]
                              [--route-provenance route_provenance.json] [--maps rel ...]
Writes audit.json and audit.md in --out. Reads only.
"""
import argparse
import collections
import json
import math
import os
import sys
import time

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pmodel as P                                          # noqa: E402

dist = P.dist


def tile_of(p):
    return [int(p[0] // 16), int(p[1] // 16) - 1]


def enemy_brief(mi, eid):
    f = mi.facts[eid]
    e = mi.by_id[eid]
    return {"id": eid, "name": f["name"], "tier": f["tier"], "post": [round(e.x, 2), round(e.y, 2)],
            "tile": tile_of((e.x, e.y)), "provenance": f.get("provenance"),
            "hidden": f["hidden"], "dialog": f["dialog"], "inactive": f["inactive"]}


def stop_flags(mi, eid, wid, p):
    """IN-WALL flags of one stop: blocked/outside on the repo grid, inside a collision object (strict grid)."""
    m = mi.m
    g = m.grids()
    legal, reach = P.T.W.standable(g["free_q"], g["reach_qw"], m.wpx, m.hpx, m.th, p[0], p[1])
    out = []
    if not legal:
        out.append("blocked")
    elif g["reach_qw"] is not None and not reach:
        out.append("outside")
    elif g["collision_objects_inside"] and not P.T.open_at(m, p[0], p[1], strict=True, margin=0) \
            and P.T.open_at(m, p[0], p[1], strict=False, margin=0):
        out.append("collision-object")
    return out


def audit_map(mi, want_nav=True):
    m = mi.m
    res = {"map": mi.rel, "excluded": mi.excluded, "routes": [], "cases": [], "inert": [],
           "case_variants": mi.case_variants}
    walk_cache = {}
    per_route = {}
    for eid, r in sorted(mi.routes.items()):
        f = mi.facts[eid]
        hard, soft = P.protection(f)
        info = {"enemy": enemy_brief(mi, eid), "value": r.value, "stops": [
            {"id": i, "xy": [round(p[0], 2), round(p[1], 2)], "tile": tile_of(p)} for i, p in r.stops()],
            "dangling": [t[1] for t in r.tokens if t[0] == "dangling"],
            "bad": [t[1] for t in r.tokens if t[0] == "bad"],
            "flying": mi.flying(eid), "untouchable": hard, "special": soft,
            "route_provenance": f.get("route_provenance"),
            "runs_on": [d for d in P.DIFFS if mi.patrols_on(eid, d)],
            "spawns_on": [d for d in P.DIFFS if P.spawns(mi.by_id[eid], d)],
            "pinned_on": [d for d in P.DIFFS if mi.pinned(eid, d) and P.spawns(mi.by_id[eid], d)],
            "span_tiles": round(r.span() / 16.0, 2)}
        if not info["runs_on"]:
            why = []
            if not r.ids:
                why.append("no resolvable waypoint" + (" (dangling %s)" % info["dangling"] if info["dangling"] else ""))
            if info["pinned_on"]:
                why.append("pinned booster guard on %s" % "/".join(info["pinned_on"]))
            if f["inactive"]:
                why.append("inactive set-piece")
            if not info["spawns_on"]:
                why.append("spawns on no difficulty")
            info["inert"] = why
            res["inert"].append(info)
        per_route[eid] = info
        res["routes"].append(info)

    for d in P.DIFFS:
        standing = mi.standing_on(d)
        loot = mi.loot_on(d)
        lg, go = mi.match[d]
        patrollers = [eid for eid in sorted(mi.routes) if mi.patrols_on(eid, d)]
        # stops by loot, per loot, for SHARED-LOOT
        by_loot = collections.defaultdict(list)
        for pid in patrollers:
            r = mi.routes[pid]
            stops = r.stops()
            far = r.far_end()
            post = r.post
            waits = r.waits_after()
            # ---- walk replay (independent of difficulty; cached)
            if want_nav and pid not in walk_cache:
                walk_cache[pid] = mi.walk(pid)
            legs = walk_cache.get(pid, [])
            freeze_at = []
            for a, b, L in legs:
                if L is None:
                    freeze_at.append((a, b))
            # ---- (a) converging, SHARED-LOOT bookkeeping
            for o, tier in loot:
                lp = (o.x, o.y)
                near = [(i, p) for i, p in stops if dist(p, lp) <= P.NEAR_LOOT + 1e-6]
                if not near:
                    continue
                by_loot[o.id].append(pid)
                guards = [g for g in standing if g.id != pid and not (g.props.get("dialog") or "").strip()
                          and not P.T.truthy(g.props.get("inactive"), False)
                          and dist((g.x, g.y), lp) <= P.GUARD_REACH + 1e-6]
                # ALL: every stop is by the loot - within 2 tiles, or within the 3-tile guard radius with at
                # least one stop inside 2 (the lodge's 107 sits 2.09 tiles out, 1.2 from the standing guard)
                allnear = all(dist(p, lp) <= P.GUARD_REACH + 1e-6 for _i, p in stops)
                shape = "ALL" if allnear else ("FAR-END" if any(i == far for i, _p in near) else "PASSES")
                if guards:
                    visible = [g for g in guards if not mi.facts[g.id]["hidden"]]
                    res["cases"].append({
                        "kind": "CONVERGING", "shape": shape, "difficulty": d, "patroller": pid,
                        "loot": {"id": o.id, "tier": tier, "xy": [round(o.x, 2), round(o.y, 2)], "tile": tile_of(lp)},
                        "registered_guard": lg.get(o.id),
                        "guards": [dict(enemy_brief(mi, g.id), dist_tiles=round(dist((g.x, g.y), lp) / 16, 2))
                                   for g in sorted(guards, key=lambda g: dist((g.x, g.y), lp))],
                        "guard_visible": bool(visible),
                        "near_stops": [{"id": i, "dist_tiles": round(dist(p, lp) / 16, 2),
                                        "wait": waits.get(i, 0.0)} for i, p in near],
                        "post_to_loot_tiles": round(dist(post, lp) / 16, 2)})
            # ---- (b) stuck
            for a, b in freeze_at:
                where = post if a == "post" else r.pts[a]
                beside = []
                for o, tier in loot:
                    if dist(where, (o.x, o.y)) <= P.NEAR_LOOT:
                        beside.append("%s %d" % (tier, o.id))
                for g in standing:
                    if g.id != pid and dist(where, (g.x, g.y)) <= P.BESIDE and not mi.facts[g.id]["hidden"] \
                            and not mi.facts[g.id]["inactive"]:
                        beside.append("enemy %d" % g.id)
                res["cases"].append({"kind": "STUCK", "sub": "FREEZE", "difficulty": d, "patroller": pid,
                                     "leg": [a, b], "freezes_at": "its post" if a == "post" else "waypoint %d" % a,
                                     "freeze_xy": [round(where[0], 2), round(where[1], 2)], "beside": beside})
            for i, p in stops:
                fl = stop_flags(mi, pid, i, p)
                if fl and not mi.flying(pid):
                    res["cases"].append({"kind": "STUCK", "sub": "IN-WALL", "difficulty": d, "patroller": pid,
                                         "waypoint": i, "xy": [round(p[0], 2), round(p[1], 2)], "flags": fl})
                for o, tier in mi.loot_all:
                    if tier in ("booster", "treasure") and P.spawns(o, d) and \
                            dist(p, (o.x, o.y)) <= P.ON_LOOT + 1e-6:
                        res["cases"].append({"kind": "STUCK", "sub": "ON-LOOT", "difficulty": d, "patroller": pid,
                                             "waypoint": i, "loot": o.id, "tier": tier,
                                             "dist_px": round(dist(p, (o.x, o.y)), 2)})
            # ---- (c) beside a standing enemy
            for i, p in stops:
                for g in standing:
                    if g.id == pid or mi.facts[g.id]["hidden"] or mi.facts[g.id]["inactive"]:
                        continue
                    dd = dist(p, (g.x, g.y))
                    if dd <= P.BESIDE + 1e-6:
                        res["cases"].append({"kind": "OTHER", "sub": "BESIDE-ENEMY", "difficulty": d,
                                             "patroller": pid, "waypoint": i, "enemy": enemy_brief(mi, g.id),
                                             "dist_tiles": round(dd / 16, 2), "wait": waits.get(i, 0.0)})
        # ---- SHARED-LOOT: 2+ patrollers stop by loot nobody stands at
        for o, tier in loot:
            ps = by_loot.get(o.id, [])
            if len(ps) < 2:
                continue
            lp = (o.x, o.y)
            guards = [g for g in standing if not (g.props.get("dialog") or "").strip()
                      and not P.T.truthy(g.props.get("inactive"), False) and dist((g.x, g.y), lp) <= P.GUARD_REACH]
            if guards:
                continue          # already CONVERGING for each of them
            res["cases"].append({"kind": "OTHER", "sub": "SHARED-LOOT", "difficulty": d, "patrollers": ps,
                                 "loot": {"id": o.id, "tier": tier, "tile": tile_of(lp)},
                                 "registered_guard": lg.get(o.id)})
        # ---- MEET: two short routes whose stops coincide (informational)
        for i1, a in enumerate(patrollers):
            for b in patrollers[i1 + 1:]:
                ra, rb = mi.routes[a], mi.routes[b]
                if len(ra.ids) > 3 or len(rb.ids) > 3:
                    continue
                for wa, pa in ra.stops():
                    hit = [wb for wb, pb in rb.stops() if dist(pa, pb) <= P.SAME_SPOT]
                    if hit:
                        res["cases"].append({"kind": "OTHER", "sub": "MEET", "difficulty": d, "patrollers": [a, b],
                                             "waypoints": [wa] + hit})
                        break
    res["walks"] = {str(k): [[a, b, None if L is None else round(L / 16, 2)] for a, b, L in v]
                    for k, v in walk_cache.items()}
    return res


def summarize(results):
    tot = collections.Counter()
    dist_ = collections.defaultdict(collections.Counter)
    for r in results:
        if "error" in r:
            continue
        tot["maps"] += 1
        tot["routes"] += len(r["routes"])
        tot["routes_inert"] += len(r["inert"])
        tot["routes_running_on_hard"] += sum(1 for x in r["routes"] if "Hard" in x["runs_on"])
        tot["case_variant_properties"] += len(r["case_variants"])
        seen = collections.defaultdict(set)
        for c in r["cases"]:
            key = c["kind"] + ("/" + c["sub"] if c.get("sub") else "")
            pid = c.get("patroller") or tuple(c.get("patrollers", []))
            seen[(key, c["difficulty"])].add(pid)
        for (key, d), ps in seen.items():
            tot["%s @%s" % (key, d)] += len(ps)
        byid = {x["enemy"]["id"]: x for x in r["routes"]}
        conv = {c["patroller"] for c in r["cases"] if c["kind"] == "CONVERGING" and c["difficulty"] == "Hard"}
        for pid in conv:
            x = byid[pid]
            rp = (x.get("route_provenance") or {}).get("first") or "?"
            ch = (x.get("route_provenance") or {}).get("changed") or []
            dist_["converging_by_route_origin"]["r258" if (rp == "r258" or "r258" in ch) else rp] += 1
            dist_["converging_by_map_folder"][r["map"].split("/")[0]] += 1
            shapes = sorted({c["shape"] for c in r["cases"] if c["kind"] == "CONVERGING" and c["patroller"] == pid
                             and c["difficulty"] == "Hard"})
            dist_["converging_by_shape"][shapes[0] if len(shapes) == 1 else "+".join(shapes)] += 1
            gprov = set()
            for c in r["cases"]:
                if c["kind"] == "CONVERGING" and c["patroller"] == pid and c["difficulty"] == "Hard":
                    for g in c["guards"]:
                        gprov.add(g.get("provenance") or "?")
            dist_["converging_guard_placed_by"]["+".join(sorted(gprov))] += 1
            dist_["converging_route_waypoints"][len(x["stops"])] += 1
            if x["untouchable"]:
                dist_["converging_untouchable"][", ".join(x["untouchable"])] += 1
            if x["special"]:
                dist_["converging_special"][", ".join(x["special"])] += 1
    return tot, dist_


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("plane")
    ap.add_argument("--out", required=True)
    ap.add_argument("--dev-tools")
    ap.add_argument("--provenance")
    ap.add_argument("--route-provenance")
    ap.add_argument("--maps", nargs="*")
    ap.add_argument("--no-nav", action="store_true", help="skip the NavigationMap replay (no FREEZE check)")
    a = ap.parse_args()
    root, edata, dt = P.setup(a.plane, a.dev_tools)
    prov = json.load(open(a.provenance, encoding="utf-8")) if a.provenance else None
    rprov = json.load(open(a.route_provenance, encoding="utf-8")) if a.route_provenance else None
    t0 = time.time()
    results = []
    for f in (P.T.all_maps(root) if not a.maps else [os.path.join(root, *x.split("/")) for x in a.maps]):
        rel = os.path.relpath(f, root).replace("\\", "/")
        try:
            mi = P.MapInfo(f, root, edata, prov, rprov)
            if not mi.routes:
                continue
            results.append(audit_map(mi, want_nav=not a.no_nav))
        except Exception as ex:                                        # noqa: BLE001
            import traceback
            traceback.print_exc()
            results.append({"map": rel, "error": repr(ex)})
    tot, dist_ = summarize(results)
    os.makedirs(a.out, exist_ok=True)
    json.dump({"generated": time.strftime("%Y-%m-%d %H:%M:%S"), "plane": os.path.abspath(a.plane),
               "seconds": round(time.time() - t0), "totals": dict(tot),
               "distributions": {k: dict(v) for k, v in dist_.items()}, "maps": results},
              open(os.path.join(a.out, "audit.json"), "w", encoding="utf-8"), indent=1)
    for k in sorted(tot):
        print("%-40s %d" % (k, tot[k]))
    for k, v in dist_.items():
        print(k, dict(v))
    print("%.0f s" % (time.time() - t0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
