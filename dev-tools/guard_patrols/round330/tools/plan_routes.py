"""plan_routes.py - a fix for every patrol the audit flags (round 329). Writes plan.json + plan.md; never
touches a map (apply.py does that, from plan.json).

THE FIXES, per flagged patroller (the audit is recomputed from the maps here, never read stale):

  RESHUTTLE  a 2-waypoint back-and-forth: A = its post (or the nearest good tile within 2.5 tiles), B 3-6 tiles
             from A. For every 2-stop route whose stops are by guarded loot (round 258's chest patrols, round
             319's cluster splits, hand-authored shuttles that now end at a tool-placed guard), for a tool route
             that freezes, and as the fallback for a 3-stop route. The route's own waypoint objects MOVE (its
             `waypoints` value is untouched) when no other route uses them; otherwise new waypoint objects are
             added (ids from nextobjectid) and the value is rewritten.
  NUDGE      one waypoint of a longer route (a hand-authored circuit, often shared by several enemies) moves to
             the nearest good tile within 4 tiles - every route that walks through it is re-checked, so a shared
             corner is fixed once for all of them.
  DROP-TOKEN the waypoint leaves THIS enemy's route (the object stays for the others) when no nudge fits.
  DROP       the `waypoints` property goes and the enemy stands at its post - only when nothing above fits and
             the post is not beside another enemy or guarded loot (else it is reported and left).

"GOOD" for a stop, for every difficulty the patroller walks on:
  - more than 2 tiles (32 px) from any chest/booster that another enemy guards: a standing non-dialog enemy
    within 3 tiles of it, the loot's registered guard being another enemy, or another patroller stopping by it;
  - at least 1.5 tiles (24 px) from every visible enemy that stands at its post (never onto / beside a post);
  - at least a tile (16 px) from another patroller's stop (no new pair at a turn);
  - round 319's Planner.wp_ok(): inside the map, its 16x16 footprint overlaps no other object (loot, enemies,
    doors padded a tile), open floor in the strict grid (object-layer collision included), and
    waypoint_routes_qa.standable() legal AND reachable.
A new shuttle's legs (post -> A, A -> B, B -> A) must each have a path in the replayed NavigationMap no longer
than 11 tiles nor 1.5 x the straight distance + 1 tile, every path vertex standable (it stays in the room), and
the walked path keeps 1.5 tiles from guarded loot (relaxed to 1 tile on a second pass). Fliers fly straight, so
their lines must be clear. A nudge keeps every affected leg walkable and no more than 3 tiles longer than it was.

NEVER TOUCHED: bosses, legends, story-tagged or spawnRate-0 placements, dialog NPCs, defeatDialog scripts,
anything a map script deletes/activates, questStageID/spawnCondition placements, inactive set-pieces, booster
guards (pinned - their routes never run, so they are never flagged), the player towns and the debug map (round
319's rule), and phyrexian_black1 (the user accepted that map as designed). IN-WALL stops that round 286b left on
purpose (lava / rock / water under a creature that belongs there) are reported, not moved.

usage: python plan_routes.py <plane folder> --out DIR [--provenance ..] [--route-provenance ..] [--maps rel ...]
"""
import argparse
import collections
import hashlib
import json
import math
import os
import sys
import time

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pmodel as P                                          # noqa: E402
import audit_routes as AR                                   # noqa: E402

GP = None                                                   # round 319's plan.py (Planner, constants)
dist = P.dist
LOOT_CLEAR = 32.0
ENEMY_CLEAR = 24.0
STOP_CLEAR = 16.0
PATH_LOOT = (24.0, 16.0)
MIN_LEN, MAX_LEN = 3.0, 6.0
A_RADIUS = 2.5
A_RADIUS_WIDE = 3.5       # second pass, when nothing starts within 2.5 tiles
NUDGE_R = 4
NUDGE_EXTRA = 3.0          # tiles a nudged leg may grow
ACCEPTED = {"phyrexian_black1.tmx"}


def tile_of(p):
    return [int(p[0] // 16), int(p[1] // 16) - 1]


def r2(p):
    return [round(p[0], 4), round(p[1], 4)]


def file_sha1(path):
    return hashlib.sha1(open(path, "rb").read()).hexdigest()


class MapPlanner(object):
    def __init__(self, mi, audit_res):
        self.mi = mi
        m = mi.m
        self.m = m
        facts = []
        for i, f in mi.facts.items():
            g = dict(f)
            g["tile"] = list(mi.by_id[i].tile())
            facts.append(g)
        self.pl = GP.Planner(m, {"enemies": facts}, 2.0, 8)
        mi._nav = self.pl.nav                         # one NavSim, updated as waypoints move / appear
        self.nav = self.pl.nav
        self.res = audit_res
        self.users = collections.defaultdict(list)     # waypoint id -> [enemy ids whose route names it]
        for pid, r in mi.routes.items():
            for wid in r.ids:
                self.users[wid].append(pid)
        self.wpos = {wid: (o.x, o.y) for wid, o in m.waypoints().items()}
        self.seq = {pid: [([t[1]] if t[0] == "wp" else list(t[1])) for t in r.move_tokens]
                    for pid, r in mi.routes.items()}
        self.runs = {pid: [d for d in P.DIFFS if mi.patrols_on(pid, d)] for pid in mi.routes}
        self.patrollers = [pid for pid in sorted(mi.routes) if self.runs[pid]]
        self.dropped = set()
        self.new_wps = collections.OrderedDict()       # temp id (negative) -> [x, y]
        self.temp = 0
        self.moves = collections.OrderedDict()         # wid -> {"from", "to"}
        self.values = {pid: r.value for pid, r in mi.routes.items()}   # current (planned) route values
        self.actions = []
        self.refd = mi.ref
        self.lootpos = [(o.id, (o.x, o.y), t) for o, t in mi.loot_all if t in ("booster", "treasure")]
        self.problems = self.collect()

    # ------------------------------------------------------------------------------------------ problems
    def collect(self):
        """{pid: problems} from the audit's cases, all difficulties merged."""
        out = collections.defaultdict(lambda: {"conv": {}, "shape": set(), "onloot": {}, "beside": {},
                                               "shared": {}, "freeze": [], "inwall": {}, "difficulties": set()})
        byid = {x["enemy"]["id"]: x for x in self.res["routes"]}
        for c in self.res["cases"]:
            k = c["kind"] + ("/" + c["sub"] if c.get("sub") else "")
            if k == "CONVERGING":
                pr = out[c["patroller"]]
                for s in c["near_stops"]:
                    pr["conv"].setdefault(s["id"], set()).add(c["loot"]["id"])
                pr["shape"].add(c["shape"])
                pr["difficulties"].add(c["difficulty"])
            elif k == "STUCK/ON-LOOT":
                out[c["patroller"]]["onloot"][c["waypoint"]] = c["loot"]
                out[c["patroller"]]["difficulties"].add(c["difficulty"])
            elif k == "STUCK/FREEZE":
                pr = out[c["patroller"]]
                if tuple(c["leg"]) not in [tuple(x) for x in pr["freeze"]]:
                    pr["freeze"].append(list(c["leg"]))
                pr["difficulties"].add(c["difficulty"])
            elif k == "STUCK/IN-WALL":
                out[c["patroller"]]["inwall"][c["waypoint"]] = c["flags"]
            elif k == "OTHER/BESIDE-ENEMY":
                n = len(byid[c["patroller"]]["stops"])
                worthy = (n <= 2 and c["dist_tiles"] <= 1.5 + 1e-6) or c["dist_tiles"] < 1.0 or c["wait"] > 0
                if worthy:
                    out[c["patroller"]]["beside"].setdefault(c["waypoint"], set()).add(c["enemy"]["id"])
                    out[c["patroller"]]["difficulties"].add(c["difficulty"])
            elif k == "OTHER/SHARED-LOOT":
                keep = c.get("registered_guard") if c.get("registered_guard") in c["patrollers"] else \
                    min(c["patrollers"])
                for pid in c["patrollers"]:
                    if pid == keep:
                        continue
                    r = self.mi.routes[pid]
                    lp = next((lp for lid, lp, _t in self.lootpos if lid == c["loot"]["id"]), None)
                    for wid, p in r.stops():
                        if lp and dist(p, lp) <= P.NEAR_LOOT + 1e-6:
                            out[pid]["shared"][wid] = (c["loot"]["id"], keep)
                    out[pid]["difficulties"].add(c["difficulty"])
        return out

    def bad_stops(self, pid):
        pr = self.problems.get(pid)
        if not pr:
            return set()
        bad = set(pr["conv"]) | set(pr["onloot"]) | set(pr["beside"]) | set(pr["shared"])
        return bad

    # ------------------------------------------------------------------------------------------ context
    def standing(self, d):
        mi = self.mi
        out = [e for e in mi.standing_on(d)]
        for x in self.dropped:
            e = mi.by_id[x]
            if P.spawns(e, d):
                out.append(e)
        return out

    def stops_of(self, pid):
        """Current (planned) stop positions of a patroller."""
        if pid in self.dropped:
            return []
        out = []
        for ids in self.seq[pid]:
            for w in ids:
                p = self.new_wps.get(w) if w < 0 else self.wpos.get(w)
                if p is not None:
                    out.append(tuple(p))
        return out

    def context(self, pids):
        """What the stops shared by these patrollers must keep clear of."""
        mi = self.mi
        avoid, vis, stops = {}, {}, []
        for pid in pids:
            for d in self.runs.get(pid, []):
                st = self.standing(d)
                lg, _go = mi.match[d]
                for o, t in mi.loot_on(d):
                    lp = (o.x, o.y)
                    why = None
                    for g in st:
                        if g.id != pid and not (g.props.get("dialog") or "").strip() and \
                                not P.T.truthy(g.props.get("inactive"), False) and dist((g.x, g.y), lp) <= P.GUARD_REACH + 1e-6:
                            why = "enemy %d stands by it" % g.id
                            break
                    if why is None and lg.get(o.id) not in (None, pid) and lg.get(o.id) not in self.dropped:
                        q = lg.get(o.id)
                        # a registered guard that walks elsewhere is no presence; one whose stops come here is
                        if q in self.runs and self.runs[q] and any(dist(s, lp) <= P.NEAR_LOOT for s in self.stops_of(q)):
                            why = "its registered guard %d patrols by it" % q
                    if why is None:
                        for q in self.patrollers:
                            if q == pid or q in pids or q in self.dropped:
                                continue
                            if not set(self.runs[q]) & {d}:
                                continue
                            if any(dist(s, lp) <= P.NEAR_LOOT for s in self.stops_of(q)):
                                why = "patroller %d stops by it" % q
                                break
                    if why:
                        avoid[o.id] = (lp, why)
                for g in st:
                    if g.id == pid or mi.facts[g.id]["hidden"] or mi.facts[g.id]["inactive"]:
                        continue
                    vis[g.id] = (g.x, g.y)
            for q in self.patrollers:
                if q in pids or q in self.dropped:
                    continue
                if set(self.runs[q]) & set(self.runs.get(pid, [])):
                    stops.extend(self.stops_of(q))
        return {"avoid": avoid, "vis": vis, "stops": stops}

    def clear_of(self, p, ctx):
        """(ok, clearance px) of a stop against the context (not the grid)."""
        c = 1e9
        for _lid, (lp, _w) in ctx["avoid"].items():
            dd = dist(p, lp)
            if dd <= LOOT_CLEAR + 1e-6:
                return False, dd
            c = min(c, dd - LOOT_CLEAR)
        for _eid, ep in ctx["vis"].items():
            dd = dist(p, ep)
            if dd < ENEMY_CLEAR - 1e-6:
                return False, dd
            c = min(c, dd - ENEMY_CLEAR)
        for sp in ctx["stops"]:
            if dist(p, sp) < STOP_CLEAR - 1e-6:
                return False, 0.0
        for _lid, lp, _t in self.lootpos:
            if dist(p, lp) < 16 - 0.5:                  # never on (or half on) any chest/booster
                return False, 0.0
        return True, c

    def sole_loot(self, pid):
        """Loot this patroller is the ONLY presence at: one of its stops within 2 tiles, nobody standing within 3,
        no other patroller stopping within 2, and not guarded by another enemy. A new route should keep it."""
        mi = self.mi
        ctx = self.context([pid])
        mine = self.stops_of(pid)
        out = {}
        for d in self.runs.get(pid, []):
            st = self.standing(d)
            for o, t in mi.loot_on(d):
                lp = (o.x, o.y)
                if o.id in ctx["avoid"] or o.id in out:
                    continue
                if not any(dist(q, lp) <= P.NEAR_LOOT + 1e-6 for q in mine):
                    continue
                if any(dist((g.x, g.y), lp) <= P.GUARD_REACH and not (g.props.get("dialog") or "").strip()
                       for g in st if g.id != pid):
                    continue
                if any(dist(q, lp) <= P.NEAR_LOOT for x in self.patrollers if x != pid and x not in self.dropped
                       for q in self.stops_of(x)):
                    continue
                out[o.id] = lp
        return out

    def grid_ok(self, p, owner):
        return self.pl.wp_ok(p, owner)

    # ------------------------------------------------------------------------------------------ legs
    def leg(self, a, b, fly, post, b_key=None, first=False):
        """(ok, info, points) for walking a -> b, Planner.leg()'s rules, returning the path."""
        m = self.m
        straight = dist(a, b)
        clear = P.T.segment_clear(m, a, b)
        info = {"straight": round(straight / 16, 2), "nav": None, "clear": clear}
        if fly:
            # a flier flies straight; its shuttle must be over open floor, but the one flight from its post to the
            # start (a tile or two) may cross whatever it was authored over - a drake on a cliff, a bat over water
            return ((clear or first) and straight <= GP.MAX_LEG * 16), info, [a, b]
        if straight < 0.5:
            info["nav"] = 0.0
            return True, info, [a, b]
        L, pts = self.mi.nav_path(a, b_key=b_key, b=None if b_key else b)
        info["nav"] = None if L is None else round(L / 16, 2)
        if L is None or L > GP.MAX_LEG * 16 or L > GP.DETOUR * straight + 16:
            return False, info, pts
        for q in pts[1:-1]:
            if dist(q, post) <= GP.NEAR_POST * 16:
                continue
            if not P.T.repo_standable(m, q[0], q[1]):
                info["leaves_room_at"] = [round(q[0]), round(q[1])]
                return False, info, pts
        return True, info, pts

    def vertex_leg_ok(self, a, b):
        """A start waypoint ON the post: at spawn findShortestPath() finds the walker's position already in the graph
        (that waypoint's vertex) and paths from its own 4 init edges, not 10 fresh links - replay exactly that."""
        hpx = self.m.hpx
        ka, kb = ("probeA",), ("probeB",)
        self.nav.add_waypoint(ka, (a[0], hpx - a[1]))
        self.nav.add_waypoint(kb, (b[0], hpx - b[1]))
        try:
            L, _pts = self.nav.path((a[0], hpx - a[1]), kb, origin_key=ka)
        finally:
            self.nav_remove(kb)
            self.nav_remove(ka)
        return L is not None

    def path_clear(self, pts, ctx, limit, post=None):
        for q in pts:
            if post is not None and dist(q, post) <= 1.5 * 16:
                continue
            for _lid, (lp, _w) in ctx["avoid"].items():
                if dist(q, lp) < limit - 1e-6:
                    return False
        return True

    # ------------------------------------------------------------------------------------------ nav updates
    def nav_remove(self, key):
        nav = self.nav
        for v in list(nav.adj.get(key, {})):
            nav.adj[v].pop(key, None)
        nav.adj.pop(key, None)
        nav.pos.pop(key, None)

    def nav_place(self, key, p):
        self.nav_remove(key)
        self.nav.add_waypoint(key, (p[0], self.m.hpx - p[1]))

    # ------------------------------------------------------------------------------------------ RESHUTTLE
    def reshuttle(self, pid, why, radius=A_RADIUS, min_len=MIN_LEN, path_limits=PATH_LOOT):
        mi = self.mi
        r = mi.routes[pid]
        post = r.post
        fly = mi.flying(pid)
        ctx = self.context([pid])
        A_c = []
        R = int(math.ceil(radius))
        for dx in range(-R, R + 1):
            for dy in range(-R, R + 1):
                off = math.hypot(dx, dy)
                if off > radius + 1e-6:
                    continue
                p = (post[0] + dx * 16, post[1] + dy * 16)
                ok, c = self.clear_of(p, ctx)
                if not ok:
                    continue
                A_c.append((off, -min(c, 48.0) / 64.0, p, c))
        A_c.sort()
        A_ok = [(off, p, c) for off, _cc, p, c in A_c if self.grid_ok(p, pid)][:5]
        if not A_ok:
            return None, "no good start tile within %.1f tiles of its post" % radius
        near_loot = [lp for _l, (lp, _w) in ctx["avoid"].items()]
        sole = list(self.sole_loot(pid).values())
        cands = []
        for off, a, ca in A_ok:
            for dx in range(-6, 7):
                for dy in range(-6, 7):
                    L = math.hypot(dx, dy)
                    if L < min_len - 1e-6 or L > MAX_LEN + 1e-6:
                        continue
                    b = (a[0] + dx * 16, a[1] + dy * 16)
                    if b[0] < 0 or b[1] < 16 or b[0] + 16 > self.m.wpx or b[1] > self.m.hpx:
                        continue
                    ok, cb = self.clear_of(b, ctx)
                    if not ok:
                        continue
                    away = 0.0
                    if near_loot:
                        da = min(dist(a, lp) for lp in near_loot)
                        db = min(dist(b, lp) for lp in near_loot)
                        away = 0.0 if db >= da else 0.6
                    clear = P.T.segment_clear(self.m, a, b, strict=True)
                    keeps = all(min(dist(a, lp), dist(b, lp)) <= P.NEAR_LOOT for lp in sole)
                    score = (off * 1.0 + (0.0 if clear else 0.8) + abs(L - 4.0) * 0.25 + away
                             - min(cb, 64.0) / 64.0 * 0.6 - min(ca, 64.0) / 64.0 * 0.3
                             - (3.0 if sole and keeps else 0.0))
                    cands.append((score, a, b))
        cands.sort(key=lambda t: t[0])
        tried = 0
        for limit in path_limits:
            for score, a, b in cands:
                if tried > 400:
                    break
                if not self.grid_ok(b, pid):
                    continue
                tried += 1
                legs, pts_all, ok = [], [], True
                for x, y, first in ((post, a, True), (a, b, False), (b, a, False)):
                    good, info, pts = self.leg(x, y, fly, post, first=first)
                    legs.append(info)
                    if not good:
                        ok = False
                        break
                    pts_all.append((pts, first))
                if not ok:
                    continue
                if not all(self.path_clear(pts, ctx, limit, post if first else None) for pts, first in pts_all):
                    continue
                if not fly and dist(a, post) < 0.01 and not self.vertex_leg_ok(a, b):
                    continue
                return {"a": a, "b": b, "legs": legs, "path_loot_limit": limit,
                        "keeps_sole_presence": (all(min(dist(a, lp), dist(b, lp)) <= P.NEAR_LOOT for lp in sole)
                                                if sole else None),
                        "a_offset_tiles": round(dist(a, post) / 16, 2), "len_tiles": round(dist(a, b) / 16, 2),
                        "straight_clear": P.T.segment_clear(self.m, a, b)}, None
        return None, "no shuttle passes the checks (%d candidate ends, %d walked)" % (len(cands), tried)

    def commit_shuttle(self, pid, sh, why, problems):
        """Write the shuttle: move the route's own objects when nobody else uses them, else new objects."""
        mi = self.mi
        r = mi.routes[pid]
        value = self.values[pid]
        plain = [t for t in r.tokens]
        two_wp = (len(r.move_tokens) == 2 and all(t[0] == "wp" for t in r.move_tokens)
                  and all(t[0] in ("wp", "wait") for t in plain) and r.move_tokens[0][1] != r.move_tokens[1][1])
        excl = two_wp and all(self.users[t[1]] == [pid] for t in r.move_tokens) and \
            not any(t[1] in self.refd for t in r.move_tokens) and \
            not any(t[1] in self.moves for t in r.move_tokens)
        rec = {"enemy_id": pid, "enemy": mi.facts[pid]["name"], "fix": "RESHUTTLE", "why": why,
               "problems": problems, "post": r2(r.post), "post_tile": tile_of(r.post),
               "old_value": value, "old_stops": [{"id": i, "xy": r2(p), "tile": tile_of(p)} for i, p in r.stops()],
               "flying": mi.flying(pid), "legs": sh["legs"], "len_tiles": sh["len_tiles"],
               "a_offset_tiles": sh["a_offset_tiles"], "straight_clear": sh["straight_clear"],
               "path_loot_limit_px": sh["path_loot_limit"]}
        if excl:
            (_k1, w1), (_k2, w2) = r.move_tokens
            for wid, p in ((w1, sh["a"]), (w2, sh["b"])):
                self.moves[wid] = {"from": r2(self.wpos[wid]), "to": r2(p), "owner": pid}
                self.wpos[wid] = tuple(p)
                self.nav_place(("w", wid), p)
                self.pl.reserved.append((self.pl.fp(p), pid))
            rec["write"] = "move"
            rec["moves"] = [{"waypoint": w1, "to": r2(sh["a"]), "to_tile": tile_of(sh["a"])},
                            {"waypoint": w2, "to": r2(sh["b"]), "to_tile": tile_of(sh["b"])}]
            rec["new_value"] = value
        else:
            ids = []
            for p in (sh["a"], sh["b"]):
                self.temp -= 1
                self.new_wps[self.temp] = list(p)
                self.nav_place(("w", self.temp), p)
                self.pl.reserved.append((self.pl.fp(p), pid))
                ids.append(self.temp)
            self.seq[pid] = [[ids[0]], [ids[1]]]
            rec["write"] = "new-objects"
            rec["new_waypoints"] = [{"temp_id": ids[0], "xy": r2(sh["a"]), "tile": tile_of(sh["a"])},
                                    {"temp_id": ids[1], "xy": r2(sh["b"]), "tile": tile_of(sh["b"])}]
            rec["new_value"] = "%d,%d" % (ids[0], ids[1])        # temp ids, resolved by apply.py
            self.values[pid] = rec["new_value"]
        rec["a"] = {"xy": r2(sh["a"]), "tile": tile_of(sh["a"])}
        rec["b"] = {"xy": r2(sh["b"]), "tile": tile_of(sh["b"])}
        rec["pass"] = sh.get("pass", "normal")
        if rec["write"] == "new-objects":
            rec["orphaned_waypoints"] = [w for w in r.ids if self.users[w] == [pid]]
        self.actions.append(rec)
        return rec

    # ------------------------------------------------------------------------------------------ undo
    def snapshot(self):
        return {"moves": collections.OrderedDict(self.moves), "wpos": dict(self.wpos),
                "reserved": len(self.pl.reserved), "values": dict(self.values),
                "seq": {k: [list(x) for x in v] for k, v in self.seq.items()}}

    def rollback(self, snap):
        for wid in list(self.moves):
            if wid not in snap["moves"]:
                self.nav_place(("w", wid), snap["wpos"][wid])
        self.moves = snap["moves"]
        self.wpos = snap["wpos"]
        del self.pl.reserved[snap["reserved"]:]
        self.values = snap["values"]
        self.seq = snap["seq"]

    # ------------------------------------------------------------------------------------------ NUDGE
    def legs_through(self, pid, wid, at):
        """[(from pos, to pos, old length px or None, to_key)] - every leg of pid's walk that ends or starts at
        waypoint wid, with wid placed at `at`."""
        r = self.mi.routes[pid]
        seq = self.seq[pid]
        n = len(seq)
        out = []

        def pos(w):
            if w == wid:
                return at
            return tuple(self.new_wps[w]) if w < 0 else self.wpos[w]
        for k in range(n):
            if wid not in seq[k]:
                continue
            if k == 0:
                out.append((r.post, at, "post", wid))
            if n > 1:
                for a in seq[k - 1]:
                    if a != wid and not (k == 0 and n == 1):
                        out.append((pos(a), at, a, wid))
                for b in seq[(k + 1) % n]:
                    if b != wid:
                        out.append((at, pos(b), wid, b))
        return out

    def old_len(self, pid, a_label, b_id):
        for a, b, L in self.res["walks"].get(str(pid), []):
            if (a == a_label or str(a) == str(a_label)) and b == b_id:
                return None if L is None else L * 16
        return None

    def nudge(self, wid, why, keep_near=None):
        """Move waypoint wid for every running route that uses it."""
        mi = self.mi
        users = [u for u in self.users[wid] if u in self.patrollers and u not in self.dropped
                 and any(wid in ids for ids in self.seq[u])]
        if not users:
            return None, "no running route uses it"
        if wid in self.refd:
            return None, "named by a map script"
        blocked = [u for u in users if P.protection(mi.facts[u])[0]]
        if blocked:
            return None, "also walked by untouchable %s" % ", ".join("#%d" % u for u in blocked)
        ctx = self.context(users)
        old = self.wpos[wid]
        cands = []
        for dx in range(-NUDGE_R, NUDGE_R + 1):
            for dy in range(-NUDGE_R, NUDGE_R + 1):
                mv = math.hypot(dx, dy)
                if mv < 0.5 or mv > NUDGE_R + 1e-6:
                    continue
                p = (old[0] + dx * 16, old[1] + dy * 16)
                if keep_near and any(dist(p, lp) > P.NEAR_LOOT + 1e-6 for lp in keep_near):
                    continue                              # it must stay by the loot it alone looks after
                ok, c = self.clear_of(p, ctx)
                if ok:
                    cands.append((mv - min(c, 32.0) / 64.0, p))
        cands.sort(key=lambda t: t[0])
        for _s, p in cands[:80]:
            if not self.grid_ok(p, None):
                continue
            fine = True
            infos = []
            # place it for the path queries, restore after
            self.nav_place(("w", wid), p)
            try:
                for u in users:
                    fly = mi.flying(u)
                    for a, b, a_lab, b_id in self.legs_through(u, wid, p):
                        if fly:
                            good = dist(a, b) <= max(GP.MAX_LEG * 16, dist(a, b))   # fliers fly straight
                            infos.append({"enemy": u, "from": a_lab, "to": b_id, "straight": round(dist(a, b) / 16, 2)})
                            continue
                        key = ("w", b_id) if b_id != "post" else None
                        L, pts = self.mi.nav_path(a, b_key=key) if key else (None, [])
                        prev = self.old_len(u, a_lab, b_id if b_id != wid else wid)
                        limit = (prev + NUDGE_EXTRA * 16) if prev is not None else (GP.DETOUR * dist(a, b) + 16)
                        if L is None or L > limit + 1e-6:
                            fine = False
                            break
                        near_new = [q for q in pts[1:-1] if dist(q, p) <= 2 * 16]
                        if any(not P.T.repo_standable(self.m, q[0], q[1]) for q in near_new):
                            fine = False
                            break
                        infos.append({"enemy": u, "from": a_lab, "to": b_id, "nav": round(L / 16, 2),
                                      "was": None if prev is None else round(prev / 16, 2)})
                    if not fine:
                        break
            finally:
                if not fine:
                    self.nav_place(("w", wid), old)
            if fine:
                self.moves[wid] = {"from": r2(old), "to": r2(p), "owner": users}
                self.wpos[wid] = p
                self.pl.reserved.append((self.pl.fp(p), None))
                return {"waypoint": wid, "from": r2(old), "from_tile": tile_of(old), "to": r2(p),
                        "to_tile": tile_of(p), "moved_tiles": round(dist(old, p) / 16, 2), "users": users,
                        "legs": infos}, None
        return None, "no tile within %d of it keeps every leg walkable (%d candidates)" % (NUDGE_R, len(cands))

    def drop_token(self, pid, wid):
        """Take waypoint wid out of pid's route only."""
        seq = [ids for ids in self.seq[pid]]
        new = []
        for ids in seq:
            keep = [w for w in ids if w != wid]
            if keep:
                new.append(keep)
        if len(new) < 2:
            return None, "fewer than two stops would remain"
        fly = self.mi.flying(pid)
        r = self.mi.routes[pid]

        def pos(w):
            return tuple(self.new_wps[w]) if w < 0 else self.wpos[w]
        for k in range(len(new)):
            for a in (new[k - 1] if k else []):
                for b in new[k]:
                    if a == b or fly:
                        continue
                    L, _pts = self.mi.nav_path(pos(a), b_key=("w", b))
                    if L is None:
                        return None, "the leg %d -> %d would have no path" % (a, b)
        for b in new[0]:
            if not fly and self.mi.nav_path(r.post, b_key=("w", b))[0] is None:
                return None, "no path from its post to %d" % b
        for a in new[-1]:
            for b in new[0]:
                if a != b and not fly and self.mi.nav_path(pos(a), b_key=("w", b))[0] is None:
                    return None, "the leg %d -> %d would have no path" % (a, b)
        # rebuild the value: drop the tokens naming wid (a random token loses that option)
        toks = []
        skip_wait = False
        for kind, payload in r.tokens:
            if kind == "wp" and payload == wid:
                skip_wait = True
                continue
            if kind == "wait" and skip_wait:
                skip_wait = False
                continue                                    # its pause goes with it
            skip_wait = False
            if kind == "random":
                keep = [w for w in payload if w != wid]
                toks.append("r" + "-".join(str(w) for w in keep) if len(keep) > 1 else str(keep[0]))
            elif kind == "wp":
                toks.append(str(payload))
            elif kind == "wait":
                toks.append(str(payload))
            elif kind == "dangling":
                toks.append(str(payload) if not isinstance(payload, list) else "r" + "-".join(map(str, payload)))
            else:
                toks.append(str(payload))
        self.seq[pid] = new
        return ",".join(toks), None

    # ------------------------------------------------------------------------------------------ DROP
    def drop(self, pid, why, problems):
        mi = self.mi
        e = mi.by_id[pid]
        post = (e.x, e.y)
        ctx = self.context([pid])
        near, by_loot = [], []
        for eid, ep in ctx["vis"].items():
            if dist(post, ep) < ENEMY_CLEAR - 1e-6:
                near.append("enemy #%d %s %.1f tiles away" % (eid, mi.facts[eid]["name"], dist(post, ep) / 16))
        for lid, (lp, w) in ctx["avoid"].items():
            if dist(post, lp) <= LOOT_CLEAR + 1e-6:
                by_loot.append("loot %d (%s) %.1f tiles away" % (lid, w, dist(post, lp) / 16))
        frozen_post = any(l[0] == "post" for l in problems.get("freeze", []))
        if near and not frozen_post:
            return None, "no route fits and dropping it would park it beside " + "; ".join(near) +                 ("; its post is also by " + "; ".join(by_loot) if by_loot else "")
        near = near + by_loot
        rec = {"enemy_id": pid, "enemy": mi.facts[pid]["name"], "fix": "DROP", "why": why, "problems": problems,
               "post": r2(post), "post_tile": tile_of(post), "old_value": self.values[pid], "new_value": None,
               "flying": mi.flying(pid),
               "note": ("it already stands frozen at its post (no nav path out) - dropping the dead route changes "
                        "nothing on screen" + (" (beside: %s)" % "; ".join(near) if near else "")) if frozen_post
               else ("stands at its post from now on" + (" - where it was authored, by %s; no enemy within 1.5 tiles"
                                                         % "; ".join(by_loot) if by_loot else ""))}
        self.dropped.add(pid)
        self.actions.append(rec)
        return rec, None


def plan_map(mi, res, log):
    mp = MapPlanner(mi, res)
    out = {"fixed": [], "left": []}
    flagged = [pid for pid in mp.patrollers if pid in mp.problems and (mp.bad_stops(pid) or mp.problems[pid]["freeze"])]
    # the lodge shape first (ALL), then the rest, lowest id first
    flagged.sort(key=lambda pid: (0 if "ALL" in mp.problems[pid]["shape"] else 1, pid))
    for pid in flagged:
        pr = mp.problems[pid]
        f = mi.facts[pid]
        r = mi.routes[pid]
        rp = f.get("route_provenance") or {}
        origin = "r258" if (rp.get("first") == "r258" or "r258" in (rp.get("changed") or [])) else (rp.get("first") or "?")
        probs = {"converging": {str(k): sorted(v) for k, v in pr["conv"].items()}, "shape": sorted(pr["shape"]),
                 "on_loot": {str(k): v for k, v in pr["onloot"].items()},
                 "beside_enemy": {str(k): sorted(v) for k, v in pr["beside"].items()},
                 "shared_loot": {str(k): v for k, v in pr["shared"].items()}, "freeze": pr["freeze"],
                 "in_wall": {str(k): v for k, v in pr["inwall"].items()},
                 "difficulties": [d for d in P.DIFFS if d in pr["difficulties"]], "route_origin": origin,
                 "stops": len(r.ids)}
        hard, soft = P.protection(f)
        base = {"enemy_id": pid, "enemy": f["name"], "tier": f["tier"], "problems": probs, "special": soft,
                "old_value": r.value}
        if mi.excluded or os.path.basename(mi.rel) in ACCEPTED:
            out["left"].append(dict(base, why="map not planned (%s)" % ("accepted by the user as designed"
                                                                        if os.path.basename(mi.rel) in ACCEPTED
                                                                        else "player town / debug map, round 319's rule")))
            continue
        if hard:
            out["left"].append(dict(base, why="untouchable: " + ", ".join(hard)))
            continue
        bad = mp.bad_stops(pid)
        freeze = pr["freeze"]
        frozen_post = any(l[0] == "post" for l in freeze)
        why_bits = []
        if pr["conv"]:
            why_bits.append("converging (%s) on guarded loot %s" % ("/".join(sorted(pr["shape"])),
                                                                  sorted({x for v in pr["conv"].values() for x in v})))
        if pr["onloot"]:
            why_bits.append("stands on loot %s" % sorted(set(pr["onloot"].values())))
        if pr["beside"]:
            why_bits.append("stops beside enemy %s" % sorted({x for v in pr["beside"].values() for x in v}))
        if pr["shared"]:
            why_bits.append("shares loot %s with another patroller" % sorted(set(pr["shared"].values())))
        if freeze:
            why_bits.append("freezes (%s)" % ("at its post" if frozen_post else "mid-route"))
        why = "; ".join(why_bits)
        n = len(r.ids)
        tool = origin in ("r258", "r319")
        done = None
        notes = []
        # the waypoints to move: the bad stops, plus - for a freeze - the ones the walker cannot reach from its
        # post (a leg whose ends are both reachable but still fails names its destination)
        targets = set(bad)
        if freeze:
            fly = mi.flying(pid)
            unreach = [w for w in r.ids if not fly and mi.nav_path(r.post, b_key=("w", w))[0] is None]
            targets |= set(unreach)
            for a_, b_ in freeze:
                if a_ != "post" and a_ not in unreach and b_ not in unreach:
                    targets.add(b_)
        targets = sorted(targets)
        all_bad = set(targets) >= set(r.ids)
        # standing ON a chest it alone looks after: step the waypoint off the chest, keep the patrol there
        onloot_only = bool(pr["onloot"]) and not (pr["conv"] or pr["beside"] or pr["shared"] or freeze)

        def try_shuttle():
            sh, err = mp.reshuttle(pid, why)
            if not sh:
                notes.append("reshuttle (start within %.1f): %s" % (A_RADIUS, err))
                sh, err = mp.reshuttle(pid, why, radius=A_RADIUS_WIDE)
                if sh:
                    sh["pass"] = "wide start (within %.1f tiles)" % A_RADIUS_WIDE
            if not sh:
                notes.append("reshuttle (start within %.1f): %s" % (A_RADIUS_WIDE, err))
                sh, err = mp.reshuttle(pid, why, radius=A_RADIUS_WIDE, min_len=2.0, path_limits=(0.0,))
                if sh:
                    sh["pass"] = "cramped: 2+ tiles long, may walk past guarded loot (never stops there)"
            if sh:
                rec = mp.commit_shuttle(pid, sh, why, probs)
                rec.update({k: v for k, v in base.items() if k not in rec})
                return rec
            notes.append("reshuttle (cramped pass): %s" % err)
            return None

        covered = []

        def try_nudges():
            nudges, dropped_tokens, failed = [], [], []
            for wid in targets:
                if wid in mp.moves:
                    # already moved for another enemy that walks it - re-check it is good for this one too
                    walkers = sorted(set(u for u in mp.users[wid] if u in mp.patrollers) | {pid})
                    ctx = mp.context(walkers)            # its fellow walkers' stops are the same point
                    okc, _c = mp.clear_of(mp.wpos[wid], ctx)
                    if okc:
                        covered.append({"waypoint": wid, "moved_for": mp.moves[wid]["owner"]})
                        continue
                    failed.append("%d: already moved for #%s but still not clear for this enemy"
                                  % (wid, mp.moves[wid]["owner"]))
                    continue
                keeper = pr["shared"].get(wid, (None, None))[1] if wid in pr["shared"] else None
                if keeper is not None and keeper in mp.users[wid]:
                    # the loot's own patroller walks this waypoint too - it stays; only THIS route lets go of it
                    nd, err = None, "its keeper #%d walks it too" % keeper
                else:
                    near = None
                    if wid in pr["onloot"] and onloot_only:
                        lp = next((q for lid, q, _t in mp.lootpos if lid == pr["onloot"][wid]), None)
                        near = [lp] if lp else None
                    nd, err = mp.nudge(wid, why, keep_near=near)
                if nd:
                    nudges.append(nd)
                    continue
                v, err2 = (mp.drop_token(pid, wid) if n >= 3 else (None, "a 2-stop route cannot lose a stop"))
                if v:
                    mp.values[pid] = v
                    dropped_tokens.append({"waypoint": wid, "nudge_failed": err})
                    continue
                failed.append("%d: nudge - %s; drop - %s" % (wid, err, err2))
            return nudges, dropped_tokens, failed

        isolated = False
        if frozen_post and not mi.flying(pid):
            # is the post itself cut off (no nav path to any standable tile within 3 tiles)? Then no route can
            # ever run from it, and it already stands there for good - take the dead route away.
            isolated = True
            for dx in range(-3, 4):
                for dy in range(-3, 4):
                    q = (r.post[0] + dx * 16, r.post[1] + dy * 16)
                    if (dx or dy) and P.T.repo_standable(mi.m, q[0], q[1]) and \
                            mi.nav_path(r.post, b=q)[0] is not None:
                        isolated = False
                        break
                if not isolated:
                    break
        if isolated:
            notes.append("its post has no nav path to any floor within 3 tiles - no route can run from it")
        elif n <= 2 and not onloot_only and (tool or all_bad or len(targets) != 1):
            # ---- a 2-stop route that is the problem as a whole (round 258/319, or both ends bad): a new shuttle
            done = try_shuttle()
        else:
            # ---- a hand-authored route with some bad stops: move only those (each re-checked for every user)
            snap = mp.snapshot()
            nudges, dropped_tokens, failed = try_nudges()
            if failed and (frozen_post or n <= 3):
                # half a fix is no fix here: a walker frozen at its post stays frozen, a 3-stop route gets a new
                # shuttle instead - so the partial moves are undone first
                mp.rollback(snap)
                notes.extend(failed)
                nudges, dropped_tokens, failed = [], [], ["rolled back"]
                covered[:] = []
            if not failed and (nudges or dropped_tokens):
                fix = "NUDGE" if not dropped_tokens else ("NUDGE+DROP-TOKEN" if nudges else "DROP-TOKEN")
                rec = dict(base, fix=fix, why=why, nudges=nudges, dropped_tokens=dropped_tokens,
                           covered=covered, new_value=mp.values[pid], post=r2(r.post), post_tile=tile_of(r.post))
                mp.actions.append(rec)
                done = rec
            elif not failed and covered:
                # every bad waypoint was already moved for another enemy on the same circuit: nothing to write
                rec = dict(base, fix="COVERED", why=why, covered=covered, new_value=mp.values[pid],
                           post=r2(r.post), post_tile=tile_of(r.post))
                mp.actions.append(rec)
                done = rec
            elif not failed and not targets:
                notes.append("nothing to move")
            else:
                if failed != ["rolled back"]:
                    notes.extend(failed)
                if n <= 3:
                    done = try_shuttle()
                    if done and (nudges or dropped_tokens):
                        done["also_nudged_for_other_routes"] = nudges
                if not done and (nudges or dropped_tokens):
                    rec = dict(base, fix="PARTIAL", why=why, nudges=nudges, dropped_tokens=dropped_tokens,
                               new_value=mp.values[pid], post=r2(r.post), post_tile=tile_of(r.post), failed=failed)
                    mp.actions.append(rec)
                    done = rec
        # ---- 3. nothing fits: stand at the post (unless that parks it beside something)
        if not done:
            rec, err = mp.drop(pid, why, probs)
            if rec:
                rec.update({k: v for k, v in base.items() if k not in rec})
                rec["tried"] = notes
                done = rec
            else:
                out["left"].append(dict(base, why=why + " - LEFT: " + err, tried=notes))
                continue
        if notes and "tried" not in done:
            done["tried"] = notes
        out["fixed"].append(pid)
    # routes the audit flags only as IN-WALL (round 286b's deliberate lava/rock/water) or MEET: reported
    reported = []
    for pid in mp.patrollers:
        pr = mp.problems.get(pid)
        if pr and pr["inwall"] and pid not in out["fixed"] and not mp.bad_stops(pid) and not pr["freeze"]:
            reported.append({"enemy_id": pid, "enemy": mi.facts[pid]["name"], "in_wall": {str(k): v for k, v in pr["inwall"].items()}})
    # ---- the exact edits apply.py makes (everything else in plan.json is explanation)
    edits = {"move_waypoints": [], "new_waypoints": [], "set_route": [], "drop_route": []}
    for wid, v in mp.moves.items():
        edits["move_waypoints"].append({"id": wid, "from": v["from"], "to": v["to"]})
    owner_of_new = {}
    for a in mp.actions:
        for w in a.get("new_waypoints", []):
            owner_of_new[w["temp_id"]] = a["enemy_id"]
    for k, v in mp.new_wps.items():
        edits["new_waypoints"].append({"temp_id": k, "xy": r2(v), "layer_of_enemy": owner_of_new.get(k)})
    for a in mp.actions:
        if a["fix"] == "DROP":
            edits["drop_route"].append({"enemy_id": a["enemy_id"], "enemy": a["enemy"], "old": a["old_value"]})
        elif a.get("new_value") is not None and a["new_value"] != a["old_value"]:
            edits["set_route"].append({"enemy_id": a["enemy_id"], "enemy": a["enemy"], "old": a["old_value"],
                                       "new": a["new_value"]})
    entry = {"sha1": file_sha1(mi.m.path), "nextobjectid": mi.m.nextobjectid,
             "actions": mp.actions, "left": out["left"], "in_wall_reported": reported, "edits": edits}
    return entry


def main():
    global GP
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("plane")
    ap.add_argument("--out", required=True)
    ap.add_argument("--dev-tools")
    ap.add_argument("--provenance")
    ap.add_argument("--route-provenance")
    ap.add_argument("--maps", nargs="*")
    a = ap.parse_args()
    root, edata, dt = P.setup(a.plane, a.dev_tools)
    sys.path.insert(0, os.path.join(dt, "guard_patrols"))
    import plan as gplan                                      # round 319's planner: Planner.wp_ok & constants
    GP = gplan
    prov = json.load(open(a.provenance, encoding="utf-8")) if a.provenance else None
    rprov = json.load(open(a.route_provenance, encoding="utf-8")) if a.route_provenance else None
    t0 = time.time()
    maps = collections.OrderedDict()
    tot = collections.Counter()
    files = P.T.all_maps(root) if not a.maps else [os.path.join(root, *x.split("/")) for x in a.maps]
    for f in files:
        mi = P.MapInfo(f, root, edata, prov, rprov)
        if not mi.routes:
            continue
        res = AR.audit_map(mi)
        if not res["cases"]:
            continue
        entry = plan_map(mi, res, None)
        if not entry["actions"] and not entry["left"] and not entry["in_wall_reported"]:
            continue
        maps[mi.rel] = entry
        for x in entry["actions"]:
            tot["fix_" + x["fix"]] += 1
        tot["left"] += len(entry["left"])
        tot["waypoints_moved"] += len(entry["edits"]["move_waypoints"])
        tot["waypoints_new"] += len(entry["edits"]["new_waypoints"])
        tot["routes_rewritten"] += len(entry["edits"]["set_route"])
        tot["routes_dropped"] += len(entry["edits"]["drop_route"])
        tot["maps_changed"] += 1 if entry["actions"] else 0
        print("%-55s %s" % (mi.rel, ", ".join("%s #%d" % (x["fix"], x["enemy_id"]) for x in entry["actions"])
                                     + ("  | left %s" % [x["enemy_id"] for x in entry["left"]] if entry["left"] else "")))
    plan = {"generated": time.strftime("%Y-%m-%d %H:%M:%S"), "plane": os.path.abspath(a.plane),
            "rules": {"loot_clear_px": LOOT_CLEAR, "enemy_clear_px": ENEMY_CLEAR, "stop_clear_px": STOP_CLEAR,
                      "path_loot_px": list(PATH_LOOT), "shuttle_tiles": [MIN_LEN, MAX_LEN], "start_radius_tiles": [A_RADIUS, A_RADIUS_WIDE],
                      "nudge_radius_tiles": NUDGE_R, "max_leg_tiles": GP.MAX_LEG, "detour": GP.DETOUR},
            "seconds": round(time.time() - t0), "totals": dict(tot), "maps": maps}
    os.makedirs(a.out, exist_ok=True)
    json.dump(plan, open(os.path.join(a.out, "plan.json"), "w", encoding="utf-8"), indent=1)
    print(json.dumps(dict(tot), indent=1, sort_keys=True))
    print("%.0f s" % (time.time() - t0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
