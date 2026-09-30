"""survey.py - what blocks and what opens it, across every map (read-only)."""
import collections
import json
import sys
from tmxobj import *


def walk_actions(dlg, out, path=""):
    """collect (deleteMapObject/activateMapObject targets, conditions) from a dialog tree"""
    if isinstance(dlg, list):
        for d in dlg:
            walk_actions(d, out, path)
    elif isinstance(dlg, dict):
        for a in dlg.get("action", []) or []:
            if isinstance(a, dict):
                if a.get("deleteMapObject"):
                    out.append(("delete", a["deleteMapObject"], json.dumps(dlg.get("condition", []))[:160]))
                if a.get("activateMapObject"):
                    out.append(("activate", a["activateMapObject"], json.dumps(dlg.get("condition", []))[:160]))
        for o in dlg.get("options", []) or []:
            walk_actions(o, out, path)


def main():
    kinds = collections.Counter()
    for tmx in all_maps():
        objs = map_objects(tmx)
        byid = {o["id"]: o for o in objs}
        lines = []
        for o in objs:
            t = o["type"]
            kinds[t] += 1
            acts = []
            for key in ("dialog", "defeatDialog"):
                if o["props"].get(key):
                    d = parse_json_loose(o["props"][key])
                    if d is None:
                        lines.append("   !! %s #%d %s unparsable" % (t, o["id"], key))
                        continue
                    walk_actions(d, acts)
            if t in ("dummy",) or o["template"].startswith("gate") or acts or (t == "portal"):
                desc = "%s #%d tpl=%s name=%r gid=%s at(%.0f,%.0f %gx%g) vis=%s" % (
                    t, o["id"], o["template"], o["name"], o["gid"], o["x"], o["y"], o["w"], o["h"], o["visible"])
                if t == "portal":
                    desc += " state=%s tele=%s:%s aqf=%s" % (o["props"].get("portalState"), o["props"].get("teleport"),
                                                            o["props"].get("teleportObjectId"), o["props"].get("activeQuestFlag"))
                if t == "enemy":
                    desc += " enemy=%s" % o["props"].get("enemy")
                lines.append("   " + desc)
                for kind, tid, cond in acts:
                    tgt = byid.get(tid)
                    tdesc = ("%s tpl=%s name=%r" % (tgt["type"], tgt["template"], tgt["name"])) if tgt else ("SELF" if tid == -1 else "??missing")
                    lines.append("       %s -> %s (%s)  cond=%s" % (kind, tid, tdesc, cond))
        if lines:
            print(rel(tmx))
            print("\n".join(lines))
    print(kinds.most_common())


main()
