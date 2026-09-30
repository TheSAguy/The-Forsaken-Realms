"""lists.py - turn audit.json into the deliverables (READ-ONLY on the repo):

  removal_list.json / .csv   our added guards of gated boosters/chests (scope ROOM = recommended, SECTION = ask)
  marker_list.json           every gated reward (any kind) per scope, for the noGuard=true marker
  restore_moved.json         authored enemies round 257-258 MOVED from outside into a gated room (optional revert)
  per_map_audit.txt          every booster and chest in every map: state, gate(s), runtime guard(s) + origin

usage: python lists.py      (after python run_audit.py)
"""
import csv
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from tmxobj import MAP_ROOT  # noqa: E402

DEBUG_MAPS = {"debug_map.tmx"}


def raw_xy(rel, oid):
    root = ET.parse(os.path.join(MAP_ROOT, rel)).getroot()
    for og in root.findall("objectgroup"):
        for o in og.findall("object"):
            if o.get("id") == str(oid):
                return o.get("x"), o.get("y"), os.path.basename(o.get("template") or "")
    return None, None, None


def gate_text(g, here):
    where = "" if g["map"] == here else g["map"] + " "
    return "%s#%s %s '%s' (opens by: %s)" % (where, g["id"], g.get("kind", "?"), g.get("name", ""), g.get("opens_by", "?"))


def main():
    rep = json.load(open(os.path.join(HERE, "audit.json"), encoding="utf-8"))
    hist = json.load(open(os.path.join(HERE, "enemy_history.json"), encoding="utf-8"))

    removal = []
    for src in ("removal", "edge"):
        for x in rep[src]:
            rt = x["runtime_guards"]
            # an edge case joins the list only when EVERY runtime pairing (any difficulty) is gated loot
            if src == "edge" and not all(p[2] == "GATED" for p in rt):
                continue
            notes = []
            if src == "edge":
                for i in x["issues"]:
                    if i.startswith("stands OUTSIDE"):
                        notes.append("straddles the room's wall - fightable from outside without the gate, "
                                     "placed for and paired only with the gated loot")
                    elif i.startswith("pairing differs by difficulty") or i.startswith("also paired"):
                        notes.append("runtime pairing varies by difficulty, but always with gated loot: %s"
                                     % ", ".join("%s #%s %s" % (p[0], p[1], p[3]) for p in rt))
            if x["map"].split("/")[-1] in DEBUG_MAPS:
                notes.append("debug/test map")
            fx, fy, tpl = raw_xy(x["map"], x["id"])
            removal.append({
                "scope": "room" if x["category"] == "ROOM" else "section",
                "map": x["map"], "id": x["id"], "enemy": x["enemy"], "round": x["round"],
                "template": tpl, "x": fx, "y": fy,
                "guards": ["%s #%s" % (p[0], p[1]) for p in rt],
                "placed_for": ("%s #%s" % (x["placed_for"][0], x["placed_for"][1])) if x["placed_for"] else None,
                "inside_gated_area": x["inside_gated_area"],
                "gates": [gate_text(g, x["map"]) for g in x["gates"]],
                "room_authored_enemies": len(x["room_authored_enemies"]),
                "notes": notes})
    removal.sort(key=lambda r: (r["scope"] != "room", r["map"], r["id"]))
    json.dump(removal, open(os.path.join(HERE, "removal_list.json"), "w", encoding="utf-8"), indent=1)
    with open(os.path.join(HERE, "removal_list.csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["scope", "map", "object_id", "enemy", "added_by", "guards_at_runtime", "gate(s)", "notes"])
        for r in removal:
            w.writerow([r["scope"], r["map"], r["id"], r["enemy"], r["round"], "; ".join(r["guards"]),
                        " | ".join(r["gates"]), " | ".join(r["notes"])])

    marker = []
    for m in rep["marker"]:
        fx, fy, tpl = raw_xy(m["map"], m["id"])
        marker.append({"scope": "room" if m["category"] == "ROOM" else "section", "map": m["map"], "id": m["id"],
                       "kind": m["kind"], "template": tpl, "x": fx, "y": fy})
    json.dump(marker, open(os.path.join(HERE, "marker_list.json"), "w", encoding="utf-8"), indent=1)

    restore = []
    for x in rep["moved_authored"]:
        if "R257-258" not in (x.get("moved_by") or []):
            continue
        rec = hist[x["map"]][str(x["id"])]
        first = [c for c in rec["changes"] if c[0].startswith("386cc9b2670") and "moved" in c[2]][0]
        mm = re.search(r"moved \(([-\d.]+),([-\d.]+)\) -> \(([-\d.]+),([-\d.]+)\)", first[2])
        fx, fy, tpl = raw_xy(x["map"], x["id"])
        restore.append({"map": x["map"], "id": x["id"], "enemy": x["enemy"], "template": tpl, "x": fx, "y": fy,
                        "restore_x": mm.group(1), "restore_y": mm.group(2),
                        "scope": "room" if x["category"] == "ROOM" else "section",
                        "guards": ["%s #%s" % (p[0], p[1]) for p in x["runtime_guards"]]})
    # is the pre-round-258 spot reachable with every gate SHUT? (= round 258 pulled it INTO the locked area)
    import audit
    import geom
    W = audit.World()
    all_gates = {(r, i) for (r, i, k) in W.gate_list if k == "blocker"}
    aa = {(r, p["id"]) for r, m in W.maps.items() for p in m.portals if p["_portal_active"]}
    cv, _cs = W.reach(all_gates, aa)
    for x in restore:
        m = W.maps[x["map"]]
        blocked = m.base()[0].copy()
        for b in m.blockers:
            if b["cls"] == "GATE" and (x["map"], b["id"]) in all_gates:
                geom.add_rect(blocked, *b["box"])
        lab = geom.label(geom.free_positions(blocked))
        fake = dict(m.byid[x["id"]])
        fake["x"], fake["y"] = float(x["restore_x"]), float(x["restore_y"])
        bx, by, bw, bh = geom.actor_box(fake, True)
        t = geom.labels_touching_rect(lab, bx, by, bw, bh) or geom.labels_near(lab, bx, by, 20)
        x["restore_is_outside_gate"] = bool(t & cv.get(x["map"], set()))
        wp = m.byid[x["id"]]["props"].get("waypoints") or ""
        x["waypoints"] = wp
    json.dump(restore, open(os.path.join(HERE, "restore_moved.json"), "w", encoding="utf-8"), indent=1)

    with open(os.path.join(HERE, "per_map_audit.txt"), "w", encoding="utf-8") as f:
        f.write("Every booster (B) and chest (C) in every TFR map. state: open | GATED (ROOM/SECTION) | unreachable.\n"
                "guards = what MapStage.assignLootGuards() pairs it with (difficulties 0-3 = Easy..Insane), origin OURS "
                "(round that added it) or AUTHORED; 'in'/'OUT' = inside / outside the gated area.\n"
                "Gates list the one(s) that must be opened (required) or, failing that, any one that suffices.\n\n")
        for r, mm in sorted(rep["maps"].items()):
            rows = [x for x in mm["rewards"] if x["kind"] in ("booster", "chest")]
            if not rows:
                continue
            f.write("== %s%s\n" % (r, "" if mm["poi"] else "  (sub-level)"))
            gates_here = [b for b in mm["blockers"] if b["cls"] == "GATE"]
            for b in gates_here:
                f.write("   gate #%d %s '%s': opens by %s\n" % (b["id"], b["kind"], b["name"], b["opens_by"]))
            for x in rows:
                gl = x["required"] or x["sufficient"]
                gtxt = ",".join(("#%d" % g[1]) if g[0] == r else ("%s#%d" % (g[0], g[1])) for g in gl)
                st = x["state"] + ("/" + x["category"] if x["category"] else "")
                gs = "; ".join("#%d %s %s%s%s%s" % (g["id"], g["enemy"], g["origin"],
                                                    ("[" + g["round"] + "]") if g["origin"] == "OURS" else "",
                                                    (" in" if (not g["reach_closed"] and g["reach_open"]) else " OUT")
                                                    if x["state"] == "GATED" else "",
                                                    (" diff%s" % g["difficulties"]) if len(g["difficulties"]) < 4 else "")
                               for g in x["guards"]) or "-"
                f.write("   %s#%-4d %-16s gates=%-24s guards: %s\n" % (x["kind"][0].upper(), x["id"], st,
                                                                       gtxt or "-", gs))
    print("removal:", len(removal), "(room %d, section %d)" % (sum(r["scope"] == "room" for r in removal),
                                                            sum(r["scope"] == "section" for r in removal)))
    print("marker:", len(marker), "(room %d, section %d)" % (sum(m["scope"] == "room" for m in marker),
                                                          sum(m["scope"] == "section" for m in marker)))
    print("restore_moved:", len(restore))


if __name__ == "__main__":
    main()
