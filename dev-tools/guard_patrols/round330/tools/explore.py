"""quick look: every route, how close its waypoints come to loot and to other enemies."""
import collections
import math
import os
import sys

sys.dont_write_bytecode = True
GP = r"C:\TFR\repo\dev-tools\guard_patrols"
sys.path.insert(0, GP)
import tfrmaps as T  # noqa

PLANE = r"C:\TFR\repo\forge-gui\res\adventure\The Forsaken Realms"
root = T.maps_root(PLANE)
T.load_tools(T.find_dev_tools(root))
edata = T.load_enemy_data(T.plane_of(root))

n_routes = 0
rows = []
case_props = collections.Counter()
for f in T.all_maps(root):
    m = T.TMap(f, root)
    wps = m.waypoints()
    loot = [(o, t) for o, t in m.loot(spawned_only=False) if t in ("booster", "treasure")]
    for e in m.enemies():
        # property name variants
        for k in e.own:
            if k.lower() == "waypoints" and k != "waypoints":
                case_props[k] += 1
        v = (e.props.get("waypoints") or "").strip()
        if not v:
            continue
        n_routes += 1
        pts, legs = T.route_points(m, e)
        near = []
        for o, t in loot:
            ds = [math.hypot(x - o.x, y - o.y) for _w, x, y in pts]
            if ds and min(ds) <= 32:
                near.append((o.id, t, round(min(ds), 1), round(max(ds), 1)))
        rows.append((m.rel, e.id, e.props.get("enemy"), v, len(pts), near))

print("routes", n_routes, "case variants", dict(case_props))
allnear = [r for r in rows if r[5] and all(nn[3] <= 32 for nn in r[5])]
anynear = [r for r in rows if r[5]]
print("routes with some waypoint within 32px of chest/booster:", len(anynear))
print("routes with ALL waypoints within 32px of one loot:", len(allnear))
for r in allnear[:15]:
    print(r)
