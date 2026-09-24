"""peek.py - print audit cases for inspection. usage: peek.py audit.json KIND[/SUB] [filter-key=value ...]"""
import json
import sys

d = json.load(open(sys.argv[1], encoding="utf-8"))
kind = sys.argv[2]
flt = dict(a.split("=", 1) for a in sys.argv[3:])
diff = flt.pop("difficulty", "Hard")
n = 0
for r in d["maps"]:
    if "error" in r:
        continue
    byid = {x["enemy"]["id"]: x for x in r["routes"]}
    for c in r["cases"]:
        k = c["kind"] + ("/" + c["sub"] if c.get("sub") else "")
        if k != kind or c["difficulty"] != diff:
            continue
        pid = c.get("patroller")
        x = byid.get(pid) if pid else None
        rp = (x or {}).get("route_provenance") or {}
        origin = rp.get("first", "?") + ("(" + ",".join(rp.get("changed", [])) + ")" if rp.get("changed") else "")
        if "origin" in flt and flt["origin"] not in origin:
            continue
        if "shape" in flt and c.get("shape") != flt["shape"]:
            continue
        if "map" in flt and flt["map"] not in r["map"]:
            continue
        n += 1
        if x:
            e = x["enemy"]
            head = "%s #%d %s [%s] post t%s route %s stops %s%s%s" % (
                r["map"], pid, e["name"], e["tier"], e["tile"], origin,
                " ".join("%d@t%s" % (s["id"], s["tile"]) for s in x["stops"]),
                " FLY" if x["flying"] else "", (" UNTOUCHABLE " + ",".join(x["untouchable"])) if x["untouchable"] else "")
        else:
            head = "%s %s" % (r["map"], c.get("patrollers"))
        rest = {k2: v for k2, v in c.items() if k2 not in ("kind", "sub", "difficulty", "patroller")}
        if "guards" in rest:
            rest["guards"] = ["#%d %s %s d%.2f%s" % (g["id"], g["name"], g["provenance"], g["dist_tiles"],
                                                    " HIDDEN" if g["hidden"] else "") for g in rest["guards"]]
        print(head)
        print("     ", json.dumps(rest))
print(n, "cases")
