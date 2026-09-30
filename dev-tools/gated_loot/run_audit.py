"""run_audit.py - the gated-loot audit driver (READ-ONLY on the repo). Writes audit.json next to itself.

usage: python run_audit.py            full audit -> audit.json (+ summary on stdout)
"""
import collections
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from audit import World, describe_opener, norm_map, origin_of, replay_guards  # noqa: E402

ROUND_KINDS = {"R279": ("booster",), "R284": ("booster", "chest"), "R286b": ("chest",), "R287": ("booster", "chest")}
# ROOM = the loot's sealed area (every gate shut) holds at most this many authored, non-dialog enemies: a vault, a
# boss room with its retinue, a titan's cell. More = a locked WING of the dungeon with its own population (SECTION).
ROOM_MAX_AUTHORED = 4


def intended_target(m, e, kinds):
    """the booster/chest an added guard was placed FOR: add_booster_guards.plan() puts a guard at a whole-tile
    offset (<= 3 tiles) from its loot's authored x/y, nearest first."""
    best = None
    for L in m.rewards:
        if L["_kind"] not in kinds:
            continue
        dx, dy = (e["x"] - L["x"]) / 16.0, (e["y"] - L["y"]) / 16.0
        if abs(dx - round(dx)) > 0.01 or abs(dy - round(dy)) > 0.01:
            continue
        if max(abs(dx), abs(dy)) > 3.01:
            continue
        d = dx * dx + dy * dy
        if best is None or d < best[0]:
            best = (d, L)
    return best[1] if best else None


def run(hist_path=None, maps_root=None):
    W = World()
    hist = json.load(open(hist_path or os.path.join(HERE, "enemy_history.json"), encoding="utf-8"))
    all_gates = {(r, i) for (r, i, k) in W.gate_list if k == "blocker"}
    authored_active = {(r, p["id"]) for r, m in W.maps.items() for p in m.portals if p["_portal_active"]}
    all_portals = {(r, p["id"]) for r, m in W.maps.items() for p in m.portals}

    closed_vis, closed_sums = W.reach(all_gates, authored_active)
    open_vis, open_sums = W.reach(set(), all_portals)
    for r, m in W.maps.items():  # closed-state geometry of EVERY map (rooms)
        if r not in closed_sums:
            closed_sums[r] = m.summary({i for (rr, i) in all_gates if rr == r})

    def reachable(vis, sums, r, oid):
        s = sums.get(r)
        return bool(s and (s["touch"].get(oid, set()) & vis.get(r, set())))

    gated = []
    for r, m in W.maps.items():
        for o in m.rewards:
            rc = reachable(closed_vis, closed_sums, r, o["id"])
            ro = reachable(open_vis, open_sums, r, o["id"])
            o["_state"] = "open" if rc else ("GATED" if ro else "unreachable")
            if o["_state"] == "GATED":
                gated.append((r, o["id"]))
        for o in m.enemies:
            ct = closed_sums[r]["touch"].get(o["id"], set()) if r in closed_sums else set()
            ot = open_sums[r]["touch"].get(o["id"], set()) if r in open_sums else set()
            if ct:
                o["_closed"] = bool(ct & closed_vis.get(r, set()))
            elif ot:
                o["_closed"] = False  # stands inside a gate's own footprint: shut in with it
            else:  # on a wall: engageable from within 20 px
                o["_closed"] = bool(closed_sums.get(r, {}).get("near", {}).get(o["id"], set()) & closed_vis.get(r, set()))
            if ot:
                o["_open"] = bool(ot & open_vis.get(r, set()))
            else:
                o["_open"] = bool(open_sums.get(r, {}).get("near", {}).get(o["id"], set()) & open_vis.get(r, set()))

    gated_set = set(gated)
    suff = collections.defaultdict(list)
    req = collections.defaultdict(list)
    for (gr, gid, kind) in W.gate_list:
        if kind == "blocker":
            c1, a1 = all_gates - {(gr, gid)}, authored_active
            c2, a2 = {(gr, gid)}, all_portals
        else:
            c1, a1 = all_gates, authored_active | {(gr, gid)}
            c2, a2 = set(), all_portals - {(gr, gid)}
        v1, s1 = W.reach(c1, a1)
        v2, s2 = W.reach(c2, a2)
        for (r, oid) in gated_set:
            if reachable(v1, s1, r, oid):
                suff[(r, oid)].append([gr, gid, kind])
            if not reachable(v2, s2, r, oid):
                req[(r, oid)].append([gr, gid, kind])

    rep = {"maps": {}, "gates": [], "removal": [], "edge": [], "moved_authored": [], "marker": [],
           "after_removal_still_guarded": []}
    gate_desc = {}
    for (gr, gid, kind) in W.gate_list:
        m = W.maps[gr]
        if kind == "blocker":
            b = next(x for x in m.blockers if x["id"] == gid)
            o = b["obj"]
            rec = {"map": gr, "id": gid, "kind": b["kind"], "name": o["name"], "template": o["template"],
                   "opens_by": describe_opener(m, b)}
        else:
            p = m.byid[gid]
            how = ["%s #%d%s" % (a["src_type"], a["src"], (" needing " + " & ".join(a["conds"])) if a["conds"] else "")
                   for a in m.activators.get(gid, [])]
            if p["props"].get("activeQuestFlag"):
                how.append("quest flag " + p["props"]["activeQuestFlag"])
            rec = {"map": gr, "id": gid, "kind": "portal(%s)" % (p["props"].get("portalState") or "closed"),
                   "name": p["name"], "template": p["template"], "opens_by": "; ".join(how) or "?",
                   "to": norm_map(p["props"].get("teleport") or "")}
        rep["gates"].append(rec)
        gate_desc[(gr, gid)] = rec

    def room_of(r, oid):
        return closed_sums[r]["touch"].get(oid, set())

    per_map_assign = {}
    for r, m in W.maps.items():
        if not m.rewards:
            continue
        assign = {}
        for rank in range(4):
            for rid, (eid, d) in replay_guards(m, rank).items():
                assign.setdefault(rid, {}).setdefault(eid, []).append(rank)
        per_map_assign[r] = assign
        rows = []
        for o in m.rewards:
            room = room_of(r, o["id"])
            area = sum(closed_sums[r]["area"].get(l, 0) for l in room)
            inside_auth, inside_ours = [], []
            for e in m.enemies:
                if (e["props"].get("dialog") or "").strip():
                    continue
                if closed_sums[r]["touch"].get(e["id"], set()) & room:
                    who, _lab, _mv = origin_of(hist, r, e["id"])
                    (inside_ours if who == "OURS" else inside_auth).append(e["id"])
            guards = []
            for eid, ranks in assign.get(o["id"], {}).items():
                e = m.byid[eid]
                who, lab, moved = origin_of(hist, r, eid)
                guards.append({"id": eid, "enemy": e["props"].get("enemy"), "origin": who, "round": lab,
                               "moved_by": moved, "difficulties": ranks,
                               "dist": round(math.hypot(e["x"] - o["x"], e["y"] - o["y"]), 1),
                               "reach_closed": e["_closed"], "reach_open": e["_open"],
                               "same_room": bool(closed_sums[r]["touch"].get(eid, set()) & room)})
            st = o["_state"]
            gates = req.get((r, o["id"])) or suff.get((r, o["id"])) or []
            cross_map = any(g[0] != r for g in gates)
            cat = None
            if st == "GATED":
                cat = "ROOM" if len(inside_auth) <= ROOM_MAX_AUTHORED else "SECTION"
            rows.append({"id": o["id"], "kind": o["_kind"], "sprite": o["_sprite"], "x": o["x"], "y": o["y"],
                         "state": st, "category": cat, "sufficient": suff.get((r, o["id"]), []),
                         "required": req.get((r, o["id"]), []), "room_area_px": area,
                         "room_authored_enemies": inside_auth, "room_our_enemies": inside_ours, "guards": guards})
        rep["maps"][r] = {"poi": r in W.pois, "rewards": rows,
                          "blockers": [{"id": b["id"], "kind": b["kind"], "cls": b["cls"], "name": b["obj"]["name"],
                                        "opens_by": describe_opener(m, b), "note": b.get("note", "")}
                                       for b in m.blockers if b["cls"] != "INACTIVE"]}

    removal_ids = set()
    for r, m in W.maps.items():
        if r not in rep["maps"]:
            continue
        rows = {row["id"]: row for row in rep["maps"][r]["rewards"]}
        guarded_by = collections.defaultdict(list)
        for rid, g in per_map_assign[r].items():
            for eid, ranks in g.items():
                guarded_by[eid].append((rid, ranks))
        for e in m.enemies:
            who, lab, moved = origin_of(hist, r, e["id"])
            runtime = [(rows[rid]["kind"], rid, rows[rid]["state"], ranks) for rid, ranks in guarded_by.get(e["id"], [])]
            tgt = intended_target(m, e, ROUND_KINDS.get(lab, ("booster", "chest"))) if who == "OURS" else None
            tgt_row = rows.get(tgt["id"]) if tgt else None
            gated_rt = [x for x in runtime if x[2] == "GATED" and x[0] in ("booster", "chest")]
            other_rt = [x for x in runtime if not (x[2] == "GATED" and x[0] in ("booster", "chest"))]
            tgt_gated = bool(tgt_row and tgt_row["state"] == "GATED")
            if not gated_rt and not tgt_gated:
                continue
            anchor = rows[gated_rt[0][1]] if gated_rt else tgt_row
            room = room_of(r, anchor["id"])
            gl = anchor["required"] or anchor["sufficient"]
            rec = {"map": r, "id": e["id"], "enemy": e["props"].get("enemy"), "origin": who, "round": lab,
                   "x": e["x"], "y": e["y"], "category": anchor["category"],
                   "runtime_guards": runtime,
                   "placed_for": [tgt_row["kind"], tgt_row["id"], tgt_row["state"]] if tgt_row else None,
                   "inside_gated_area": (not e["_closed"]) and e["_open"],
                   "same_room_as_loot": bool(closed_sums[r]["touch"].get(e["id"], set()) & room),
                   "reachable_without_gate": e["_closed"],
                   "anchor_reward": anchor["id"], "anchor_kind": anchor["kind"],
                   "gates": [gate_desc.get((g[0], g[1]), {"map": g[0], "id": g[1]}) for g in gl],
                   "gates_all_required": bool(anchor["required"]),
                   "room_area_px": anchor["room_area_px"], "room_authored_enemies": anchor["room_authored_enemies"]}
            issues = []
            if other_rt:
                issues.append("also paired at runtime with loot that is NOT a gated booster/chest: %s" % other_rt)
            if not gated_rt:
                issues.append("placed for gated %s #%s, but the runtime pairs it with %s"
                              % (tgt_row["kind"], tgt_row["id"], runtime or "nothing"))
            if tgt_row and not tgt_gated:
                issues.append("placed for NON-gated %s #%s" % (tgt_row["kind"], tgt_row["id"]))
            if e["_closed"]:
                issues.append("stands OUTSIDE the gated area (reachable with every gate shut)")
            if gated_rt and any(len(x[3]) < 4 for x in gated_rt):
                issues.append("pairing differs by difficulty: %s" % gated_rt)
            rec["issues"] = issues
            if who == "OURS":
                if issues:
                    rep["edge"].append(rec)
                else:
                    rep["removal"].append(rec)
                    removal_ids.add((r, e["id"]))
            else:
                rec["moved_by"] = moved
                rep["moved_authored"].append(rec)

    # after removing the clean list: which gated boosters/chests does the runtime still hand a guard?
    for r, m in W.maps.items():
        if r not in rep["maps"]:
            continue
        saved = m.enemies
        m.enemies = [e for e in saved if (r, e["id"]) not in removal_ids]
        rows = {row["id"]: row for row in rep["maps"][r]["rewards"]}
        for rid, (eid, d) in replay_guards(m, 1).items():
            row = rows[rid]
            if row["state"] == "GATED" and row["kind"] in ("booster", "chest"):
                who, lab, _mv = origin_of(hist, r, eid)
                rep["after_removal_still_guarded"].append({
                    "map": r, "reward": rid, "kind": row["kind"], "category": row["category"], "guard": eid,
                    "enemy": m.byid[eid]["props"].get("enemy"), "origin": who, "round": lab, "dist": round(d, 1),
                    "guard_reachable_without_gate": m.byid[eid]["_closed"]})
        m.enemies = saved

    for r, mm in rep["maps"].items():
        for row in mm["rewards"]:
            if row["state"] == "GATED":
                rep["marker"].append({"map": r, "id": row["id"], "kind": row["kind"], "category": row["category"],
                                      "sprite": row["sprite"], "room_area_px": row["room_area_px"]})
    return W, rep


if __name__ == "__main__":
    import time
    t = time.time()
    W, rep = run()
    json.dump(rep, open(os.path.join(HERE, "audit.json"), "w", encoding="utf-8"), indent=1, default=list)
    n = collections.Counter()
    for r, mm in rep["maps"].items():
        for row in mm["rewards"]:
            n[(row["kind"], row["state"], row["category"])] += 1
    for k in sorted(n, key=str):
        print(k, n[k])
    print("gates:", len(rep["gates"]), "removal:", len(rep["removal"]), "edge:", len(rep["edge"]),
          "moved authored:", len(rep["moved_authored"]), "marker:", len(rep["marker"]),
          "still guarded after removal:", len(rep["after_removal_still_guarded"]), "%.1fs" % (time.time() - t))
