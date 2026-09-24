"""presence.py - every chest/booster's presence on Hard: standing non-dialog enemies within 3 tiles, patroller
stops within 2 tiles. Compare two planes: which loot had presence before and none after."""
import json, os, sys
sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pmodel as P


def presence(plane):
    root, edata, _dt = P.setup(plane)
    out = {}
    for f in P.T.all_maps(root):
        mi = P.MapInfo(f, root, edata)
        st = [e for e in mi.standing_on("Hard") if not (e.props.get("dialog") or "").strip()
              and not P.T.truthy(e.props.get("inactive"), False)]
        pat = [pid for pid in mi.routes if mi.patrols_on(pid, "Hard")]
        for o, t in mi.loot_on("Hard"):
            lp = (o.x, o.y)
            s = [e.id for e in st if P.dist((e.x, e.y), lp) <= 48]
            p = [pid for pid in pat if any(P.dist(q, lp) <= 32 for _i, q in mi.routes[pid].stops())]
            out["%s#%d" % (mi.rel, o.id)] = {"tier": t, "standing": s, "patrol": p}
    return out


if __name__ == "__main__":
    b = presence(sys.argv[1])
    a = presence(sys.argv[2])
    lost = [k for k in b if (b[k]["standing"] or b[k]["patrol"]) and not (a[k]["standing"] or a[k]["patrol"])]
    only_patrol_before = [k for k in b if not b[k]["standing"] and b[k]["patrol"]]
    print("loot on Hard: %d; with presence before %d, after %d; lost all presence: %d %s" % (
        len(b), sum(1 for k in b if b[k]["standing"] or b[k]["patrol"]),
        sum(1 for k in a if a[k]["standing"] or a[k]["patrol"]), len(lost), lost[:20]))
    print("loot whose only presence before was a patroller: %d; still patrolled after: %d" % (
        len(only_patrol_before), sum(1 for k in only_patrol_before if a[k]["patrol"])))
    json.dump({"lost": lost, "only_patrol_before": only_patrol_before}, open(sys.argv[3], "w"), indent=1)
