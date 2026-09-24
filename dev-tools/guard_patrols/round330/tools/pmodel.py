"""pmodel.py - patrol routes as MapStage runs them (round 329), on top of round 319's tfrmaps.

Everything map-related comes from dev-tools/guard_patrols/tfrmaps.py (object merging, loot tiers, the guard
matching, the two walk grids, the NavigationMap replay) and the repo tools it loads (waypoint_routes_qa.legs /
standable, pixel_collision_qa). This file adds only the ROUTE questions, each from the Java:

  runs      MapStage.loadObjects() parses `waypoints` (exact, lower-case name) for every spawned enemy;
            EnemySprite.getTargetVector() follows it unless the enemy is a booster's registered guard
            (guardPost wins - assignLootGuards pins boosters only; a chest's guard keeps its route) or
            `inactive` (onActing skips it). A hidden ambusher WITH a route springs on its first step
            (CharacterSprite.moveBy clears `hidden`), so it is a patroller like any other.
  order     parseWaypoints() -> a deque; a waypoint counts as reached within 2 px and goes to the BACK, so the
            walk is post -> t1 -> t2 ... -> tn -> t1 ... : the post is visited once, at the start, and a
            2-waypoint route is a shuttle between its two waypoints. "waitN"/"wN" pause, "rA-B-C" picks one
            of those waypoints each lap, an id with no waypoint object is dropped (the leg reads as reached).
  stops     where a patroller comes to a halt: every waypoint is a turn (the walker arrives, pops, heads off
            elsewhere) - in a 2-waypoint shuttle both ends are the only places it ever pauses.
  freeze    MapStage.onActing(): a non-flier asks NavigationMap.findShortestPath() for a path to its target
            every frame; an EMPTY path idles it (`navPath.getCount() == 0 -> Idle; continue`) and the waypoint
            is never reached, so the deque never advances: it stands there for good. Enemies are NOT stopped by
            walls - EnemySprite.moveBy() is Actor.moveBy() with no collision (only PlayerSprite calls
            prepareCollision/adjustMovement) - so a waypoint inside a wall is walked INTO, not frozen short of;
            only a missing nav path freezes. A flier never pathfinds (onActing hands it a straight line).

Coordinates: .tmx pixels, y down, tile objects by their (x, y) = bottom-left, exactly as the runtime compares
them (enemy pos(), loot pos and waypoint vectors all come from the same TiledMapTileMapObject x/y).
"""
import collections
import math
import os
import sys

sys.dont_write_bytecode = True

T = None                                  # tfrmaps, set by setup()
DIFFS = ("Easy", "Normal", "Hard", "Insane")
TILE = 16.0
NEAR_LOOT = 32.0          # "within ~2 tiles" of a chest/booster (waypoint -> loot, Euclidean)
GUARD_REACH = 48.0        # MapStage.assignLootGuards(): tileSize * 3
BESIDE = 24.0             # a stop this close to another standing enemy's post = parked beside it (1.5 tiles)
ON_LOOT = 10.0            # a stop this close to a chest/booster = standing ON it
ARRIVE = 2.0              # getTargetVector(): reached within 2 px
SAME_SPOT = 16.0          # two patrollers' stops this close = they meet there
UNTOUCHABLE = {"dialog NPC", "defeatDialog script", "named by a map script (delete/activateMapObject)",
               "unknown enemy name", "boss", "legend", "scripted placement (spawnRate 0)", "questStageID",
               "spawnCondition", "inactive set-piece"}
SPECIAL = {"crowned (effect)", "carries its own reward", "named (displayNameOverride)", "deckOverride"}
EXCLUDED_MAPS = ("towns/", "debug_map.tmx")      # round 319's rule: player towns and the debug POI, report only


def setup(plane, dev_tools=None):
    """Import tfrmaps from <repo>/dev-tools/guard_patrols (found above the plane) and the repo tools it loads."""
    global T
    root = None
    p = os.path.abspath(plane)
    if os.path.isdir(os.path.join(p, "maps", "map")):
        root = os.path.join(p, "maps", "map")
    if root is None:
        raise SystemExit("%s is not a plane folder (no maps/map inside)" % plane)
    if dev_tools is None:
        d = p
        for _ in range(8):
            cand = os.path.join(d, "dev-tools")
            if os.path.isfile(os.path.join(cand, "guard_patrols", "tfrmaps.py")):
                dev_tools = cand
                break
            d = os.path.dirname(d)
    if dev_tools is None:
        raise SystemExit("no dev-tools/guard_patrols/tfrmaps.py above %s - pass --dev-tools" % plane)
    gp = os.path.join(dev_tools, "guard_patrols")
    if gp not in sys.path:
        sys.path.insert(0, gp)
    import tfrmaps
    T = tfrmaps
    T.load_tools(dev_tools)
    edata = T.load_enemy_data(p)
    return root, edata, dev_tools


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def spawns(o, d):
    return T.truthy(o.props.get("spawn." + d), True)


class Route(object):
    """One enemy's `waypoints`, resolved against its map. tokens: [(kind, payload)] with kind 'wp' (id),
    'random' ([ids]), 'wait' (text), 'dangling' (id with no waypoint object), 'bad' (unparsable)."""

    def __init__(self, mi, e):
        self.e = e
        self.value = (e.props.get("waypoints") or "").strip()
        wps = mi.m.waypoints()
        self.tokens = []
        for kind, payload in T.W.legs(self.value):
            if kind == "wp":
                self.tokens.append(("wp", payload) if payload in wps else ("dangling", payload))
            elif kind == "random":
                ok = [i for i in payload if i in wps]
                self.tokens.append(("random", ok) if ok else ("dangling", payload))
            else:
                self.tokens.append((kind, payload))
        self.move_tokens = [t for t in self.tokens if t[0] in ("wp", "random")]
        ids = []
        for kind, payload in self.move_tokens:
            for i in ([payload] if kind == "wp" else payload):
                if i not in ids:
                    ids.append(i)
        self.ids = ids                                           # every waypoint id it can walk to, first-seen
        self.pts = {i: (wps[i].x, wps[i].y) for i in ids}
        self.post = (e.x, e.y)

    def stops(self):
        return [(i, self.pts[i]) for i in self.ids]

    def far_end(self):
        """The waypoint farthest from its post."""
        if not self.ids:
            return None
        return max(self.ids, key=lambda i: dist(self.pts[i], self.post))

    def span(self):
        """Largest distance between two of its stops (0 for a single stop)."""
        ps = list(self.pts.values())
        return max([dist(a, b) for a in ps for b in ps] or [0.0])

    def waits_after(self):
        """{waypoint id: seconds} of a wait token right after a movement token (a pause AT that waypoint)."""
        out = {}
        prev = None
        for kind, payload in self.tokens:
            if kind == "wait" and prev is not None:
                try:
                    secs = float(str(payload).lstrip("wait").lstrip("w") or 0)
                except ValueError:
                    secs = 0.0
                for i in prev:
                    out[i] = out.get(i, 0.0) + secs
            elif kind in ("wp", "random"):
                prev = [payload] if kind == "wp" else payload
        return out


class MapInfo(object):
    """A map with its enemies, loot, per-difficulty guard matching and routes."""

    def __init__(self, path, root, edata, prov=None, rprov=None):
        self.m = T.TMap(path, root)
        m = self.m
        self.rel = m.rel
        self.edata = edata
        self.ref = m.referenced_ids()
        self.enemies = m.enemies()
        self.by_id = {e.id: e for e in self.enemies}
        self.facts = {e.id: T.enemy_facts(m, e, edata, self.ref) for e in self.enemies}
        for e in self.enemies:
            key = "%s#%d" % (self.rel, e.id)
            self.facts[e.id]["provenance"] = ((prov or {}).get(key) or {}).get("first")
            self.facts[e.id]["route_provenance"] = (rprov or {}).get(key)
        self.loot_all = m.loot(spawned_only=False)
        self.routes = {e.id: Route(self, e) for e in self.enemies if (e.props.get("waypoints") or "").strip()}
        self.case_variants = [(e.id, k) for e in self.enemies for k in e.own
                              if k.lower() == "waypoints" and k != "waypoints" and (e.own[k] or "").strip()]
        self.match = {d: self.match_guards(d) for d in DIFFS}
        self._nav = None
        self.excluded = any(self.rel.startswith(x) or self.rel == x for x in EXCLUDED_MAPS)

    # ---- guards ------------------------------------------------------------------------------------------
    def match_guards(self, d, moved=None):
        """MapStage.assignLootGuards() on difficulty d: {loot id: guard id}, {guard id: (loot id, tier)}."""
        loot = [(o, t) for o, t in self.loot_all if spawns(o, d)]
        pri = {"booster": 2, "treasure": 1, "other": 0}
        order = sorted(range(len(loot)), key=lambda i: -pri[loot[i][1]])
        mobs = [e for e in self.enemies if spawns(e, d) and not (e.props.get("dialog") or "").strip()]
        pos = {e.id: (moved or {}).get(e.id, (e.x, e.y)) for e in mobs}
        taken, loot_guard, guard_of = set(), {}, {}
        for i in order:
            o, tier = loot[i]
            best, bd = None, None
            for e in mobs:
                if e.id in taken:
                    continue
                dd = dist(pos[e.id], (o.x, o.y))
                if dd <= GUARD_REACH and (bd is None or dd < bd):
                    best, bd = e, dd
            if best is not None:
                taken.add(best.id)
                loot_guard[o.id] = best.id
                guard_of[best.id] = (o.id, tier)
        return loot_guard, guard_of

    def pinned(self, eid, d):
        g = self.match[d][1].get(eid)
        return bool(g and g[1] == "booster")

    # ---- who walks, who stands -------------------------------------------------------------------------
    def patrols_on(self, eid, d):
        """The enemy spawns on d and its route runs there."""
        e = self.by_id[eid]
        r = self.routes.get(eid)
        if r is None or not spawns(e, d) or not r.ids:
            return False
        if T.truthy(e.props.get("inactive"), False) or self.pinned(eid, d):
            return False
        return True

    def standing_on(self, d):
        """[(enemy, why)] of everything that stays at its post on d: no route, an inert route, pinned, inactive."""
        out = []
        for e in self.enemies:
            if not spawns(e, d) or self.patrols_on(e.id, d):
                continue
            out.append(e)
        return out

    def loot_on(self, d, tiers=("booster", "treasure")):
        return [(o, t) for o, t in self.loot_all if t in tiers and spawns(o, d)]

    # ---- navigation ------------------------------------------------------------------------------------
    def nav(self):
        if self._nav is None:
            self._nav = T.NavSim(self.m)
        return self._nav

    def nav_path(self, a, b_key=None, b=None):
        """(length px or None, [points .tmx]) from position a to a waypoint vertex ("w", id) or to a new point b
        (added the way the game adds a waypoint vertex, then removed)."""
        nav = self.nav()
        hpx = self.m.hpx
        tmp = None
        if b_key is None:
            tmp = ("probe",)
            nav.add_waypoint(tmp, (b[0], hpx - b[1]))
            b_key = tmp
        try:
            L, pts = nav.path((a[0], hpx - a[1]), b_key)
        finally:
            if tmp is not None:
                for v in list(nav.adj.get(tmp, {})):
                    nav.adj[v].pop(tmp, None)
                nav.adj.pop(tmp, None)
                nav.pos.pop(tmp, None)
        return L, [(x, hpx - y) for (x, y) in pts]

    def flying(self, eid):
        return bool(self.facts[eid]["flying"])

    def walk(self, eid):
        """Replay the walk: [(from label, to id, length px or None)] for post -> first stop and every consecutive
        pair of movement tokens (cyclic, random tokens expanded). None = no nav path = the walker freezes at the
        leg's origin. Fliers fly straight: every leg 'reached'."""
        r = self.routes[eid]
        seq = [([t[1]] if t[0] == "wp" else t[1]) for t in r.move_tokens]
        legs = []
        if not seq:
            return legs
        fly = self.flying(eid)
        first = seq[0]
        n = len(seq)
        for b in first:
            L = dist(r.post, r.pts[b]) if fly else self.nav_path(r.post, b_key=("w", b))[0]
            legs.append(("post", b, L))
            if not fly and n > 1 and dist(r.post, r.pts[b]) < 0.01:
                # it spawns ON this waypoint: findShortestPath() finds its position already in the graph and paths
                # from that waypoint's own 4 init edges (not 10 fresh links) - the first real leg, replayed so
                hp = self.m.hpx
                for c in seq[1]:
                    if c != b and self.nav().path((r.post[0], hp - r.post[1]), ("w", c), origin_key=("w", b))[0] is None:
                        legs.append(("post", c, None))
        if n == 1 and len(seq[0]) == 1:
            return legs
        for k in range(n):
            A, B = seq[k], seq[(k + 1) % n]
            for a in A:
                for b in B:
                    if a == b:
                        continue
                    L = dist(r.pts[a], r.pts[b]) if fly else self.nav_path(r.pts[a], b_key=("w", b))[0]
                    legs.append((a, b, L))
        return legs


def load_maps(root, edata, rels=None, prov=None, rprov=None):
    files = T.all_maps(root) if not rels else [os.path.join(root, *r.split("/")) for r in rels]
    for f in files:
        yield MapInfo(f, root, edata, prov, rprov)


def protection(facts):
    """(untouchable reasons, special reasons) for an enemy."""
    hard = [r for r in facts["protected"] if r in UNTOUCHABLE or r.startswith("story tag")]
    soft = [r for r in facts["protected"] if r in SPECIAL]
    return hard, soft
