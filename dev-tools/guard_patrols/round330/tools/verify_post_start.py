"""verify_post_start.py - routes whose first waypoint sits exactly on the enemy's post: at spawn the game's
findShortestPath() finds the origin already in the graph (the waypoint vertex) and paths from that vertex's own
4 init edges. Replay exactly that (NavSim.path with origin_key) for every such running route in a plane."""
import os, sys
sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pmodel as P

root, edata, _ = P.setup(sys.argv[1])
only = set(sys.argv[2:])
n = bad = 0
for f in P.T.all_maps(root):
    mi = P.MapInfo(f, root, edata)
    if only and mi.rel not in only:
        continue
    for pid, r in mi.routes.items():
        if not any(mi.patrols_on(pid, d) for d in P.DIFFS) or mi.flying(pid) or not r.move_tokens:
            continue
        first = r.move_tokens[0]
        if first[0] != "wp" or P.dist(r.pts[first[1]], r.post) > 0.01:
            continue
        if len(r.move_tokens) < 2 or r.move_tokens[1][0] != "wp":
            continue
        a, b = first[1], r.move_tokens[1][1]
        n += 1
        L, _pts = mi.nav().path((r.post[0], mi.m.hpx - r.post[1]), ("w", b), origin_key=("w", a))
        if L is None:
            bad += 1
            print("NO PATH from the post vertex: %s #%d (%s -> %s)" % (mi.rel, pid, a, b))
print("%d route(s) start on their post; %d without a path from the post's waypoint vertex" % (n, bad))
