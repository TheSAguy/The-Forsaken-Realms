"""plan.py - patrols for unguarded chests, and one fix per crowded pair (round 316).

Reads the maps (the same model as audit.py) and writes plan.json + plan.md. It never touches a map; apply.py
does that, from plan.json.

THE RULES

Unguarded chests (round 286's definition, see audit.py). For each one, an EXISTING enemy of the same map gets
a back-and-forth patrol: a waypoint on its own post and one beside the chest. The patroller is, in order of
preference, a member of a crowded cluster (one change, two fixes), then the nearest enemy that is
  - a fighter (not a dialog NPC, hidden ambusher or inactive set-piece) standing on or beside reachable floor,
  - not a boss, legend, story-tagged or spawnRate-0 placement, not named by a map script (delete/activate
    MapObject), no questStageID/spawnCondition,
  - not already walking a route (an authored `waypoints`, even an ignored one, is left as authored),
  - not a booster's guard (MapStage pins those to their post, so a route would never run),
  - not the only enemy guarding another chest (round 286's definition again).
A patroller keeps its home, so it is not the chest's REGISTERED guard at runtime (assignLootGuards() matches on
the authored position): the patrol adds presence, not the chase-on-theft hook. Said here rather than hidden.

Crowded clusters (audit.py's clusters). Per group, repeatedly, until no two members stand within the threshold:
  * map has CUTOFF+ fighters on Hard -> REMOVE one member. Who may go: in a group that mixes hand-authored and
    tool-placed enemies, a tool-placed one (the rounds 279-287 additions made most of these pairs; round 286b
    even put Rares and Mythics on chests it meant to guard with Apprentices) or an exact duplicate; in a group
    of tool placements, the weaker/duplicate; in an all-hand-authored group, only an exact duplicate (and a
    group with a crowned/own-reward/named/scripted member is a set-piece, left alone). Order: the member in
    the most pairs, tool-placed first, lowest rank, a shared name, the newest. Only when nothing is lost:
    every booster and chest keeps a dedicated runtime guard (the matching is replayed without it), no booster
    changes guard (they were tuned: Adept+, threatRange 40), and every chest keeps an enemy within 3 tiles
    (round 286's definition, planned patrol waypoints counted). Never a protected enemy: the patrol
    exclusions below plus crowned, own reward, named, deckOverride, defeatDialog.
  * otherwise, or when no removal is safe -> PATROL one member: a route that takes it SEP+ tiles from the rest of
    the group, stays within POST_RADIUS tiles of its post, and - for a chest's registered guard - passes within
    PRESENCE tiles of that chest (its home is unchanged, so it stays registered).
  * neither possible -> unresolved, with the reason. Most are pairs of booster guards, which the runtime pins
    (no patrol can run) and which the matching needs (no removal is safe). For those the plan offers a
    RELOCATION - the guard's home moved to another open tile beside its own booster, away from the group, with
    the whole matching replayed to prove every booster and chest keeps its guard. That is a move, which the
    request did not name, so apply.py leaves relocations out unless given --with-relocations.

A ROUTE IS ONLY ACCEPTED when (see tfrmaps' module docstring for why each test is what the game does):
  - every waypoint is on open floor in the strict model, legal AND reachable by waypoint_routes_qa.standable()
    (the repo's own route check), and its 16x16 footprint overlaps no other object (entries padded a tile);
  - every leg has a path in the simulated NavigationMap - the enemy PATHFINDS between waypoints - no longer than
    MAX_LEG tiles nor DETOUR x the straight distance + 1 tile, and every path vertex further than a tile and a
    half from the enemy's post is a standable, reachable position (it never cuts through a sealed band);
  - a flying enemy (no pathfinding - it flies straight) also needs the straight segment clear;
  - "beside the chest" (a chest patrol's far end, a chest guard's presence point, a relocated guard's new post)
    means a SHORT WALK to the loot on the same simulated graph, not a short distance - a tile beside a chest can
    be the far side of its wall.
Legs whose straight segment is clear as well are preferred.

usage: python plan.py <plane folder> [--dev-tools DIR] [--provenance provenance.json] [--out DIR]
                      [--threshold 2.0] [--cutoff 8] [--maps rel ...]
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
import tfrmaps as T                                          # noqa: E402
import audit as A                                            # noqa: E402

CUTOFF = 8               # fighters on Hard; 8+ = "a lot of enemies" (top ~quarter of maps; median is 5)
SEP = 2.2                # tiles a split patrol's far end keeps from the rest of its group (just past 2.0)
OTHERS = 1.5             # tiles a new waypoint keeps from any OTHER standing enemy (never parks beside one)
POST_RADIUS = 4          # tiles: a split patrol stays within this of its post
CHEST_FROM = 9.0         # tiles: an unguarded chest's patroller stands within this (straight) of the chest
BESIDE = 1.5             # tiles: "beside the chest" (orthogonal or diagonal neighbor)
PRESENCE = 2.0           # tiles: a chest guard's split route passes this close to its own chest
MAX_LEG = 11.0           # tiles of walking per leg
DETOUR = 1.5             # nav length <= DETOUR x straight + 1 tile: no walking round the houses
NEAR_POST = 1.5          # tiles around the post where path vertices are not required to be standable
PATROL_BLOCK = {"dialog NPC", "named by a map script (delete/activateMapObject)", "unknown enemy name", "boss",
                "legend", "scripted placement (spawnRate 0)", "questStageID", "spawnCondition",
                "hidden ambusher", "inactive set-piece"}
PROV_ORDER = {"r287": 0, "after-r287": 0, "r286b": 1, "r284": 2, "r279": 3}
SET_PIECE = {"crowned (effect)", "carries its own reward", "named (displayNameOverride)", "defeatDialog script",
             "deckOverride"}
RANK_WORD = {"Uncommon": "Adept", "Rare": "Master", "Mythic": "Archmage", "Mythic Rare": "Archmage"}


def covered(x0, y0, x1, y1, tw):
    """Every tile a .tmx-pixel rectangle (y down) touches; a point touches one."""
    tx0, ty0 = int(math.floor(x0 / tw)), int(math.floor(y0 / tw))
    tx1 = max(tx0, int(math.ceil(x1 / tw)) - 1)
    ty1 = max(ty0, int(math.ceil(y1 / tw)) - 1)
    return [(tx, ty) for tx in range(tx0, tx1 + 1) for ty in range(ty0, ty1 + 1)]


def patrol_block(f):
    out = [r for r in f["protected"] if r in PATROL_BLOCK or r.startswith("story tag")]
    if f["route"]:
        out.append("already has a waypoints route")
    if f["pinned"]:
        out.append("pinned booster guard")
    return out


def overlaps(a, b, slack=1.0):
    return a[0] < b[2] - slack and b[0] < a[2] - slack and a[1] < b[3] - slack and b[1] < a[3] - slack


class Planner(object):
    def __init__(self, m, rec, threshold, cutoff, exclude=()):
        self.m, self.rec = m, rec
        self.thr, self.cutoff = threshold, cutoff
        self.tw = float(m.tw)
        self.facts = {e["id"]: e for e in rec["enemies"]}
        for eid in exclude:                       # --exclude: the user's veto - treated as untouchable
            if eid in self.facts:
                self.facts[eid]["protected"] = list(self.facts[eid]["protected"]) + ["excluded by --exclude"]
        PATROL_BLOCK.add("excluded by --exclude")
        self.obj = {e.id: e for e in m.enemies()}
        self.nav = T.NavSim(m)
        self.removed = set()
        self.patrols = collections.OrderedDict()     # enemy id -> route record
        self.moves = collections.OrderedDict()       # enemy id -> relocation record (optional class)
        self.temp_id = 0
        self.notes = []
        # footprints holding something: every small non-waypoint, non-collision object; doors padded a tile
        self.occ = []
        for o in m.objects:
            if o.type in ("waypoint", "collision"):
                continue          # a waypoint is a point walked THROUGH; the maps already share them freely
            x0, y0, x1, y1 = o.footprint()
            door = o.type in ("entry", "portal", "exit")
            if not door and ((x1 - x0) > 2 * m.tw or (y1 - y0) > 2 * m.th):
                continue                                  # a big trigger AREA, not something standing there
            if x1 - x0 < 1 or y1 - y0 < 1:                # a point: give it a tile
                x0, y0, x1, y1 = x0 - m.tw / 2.0, y0 - m.th / 2.0, x0 + m.tw / 2.0, y0 + m.th / 2.0
            pad = m.tw if door else 0
            self.occ.append(((x0 - pad, y0 - pad, x1 + pad, y1 + pad), o.id))
        self.reserved = []                                # footprints of planned waypoints / moved homes

    # ---------------------------------------------------------------- state
    def fighters_on_hard(self):
        return sum(1 for f in self.facts.values() if not f["dialog"] and not f["inactive"] and f["spawns_on_hard"]
                   and f["id"] not in self.removed)

    def standing(self):
        return [i for i, f in self.facts.items() if not f["dialog"] and not f["inactive"] and not f["hidden"]
                and not f["patrols"] and i not in self.patrols and i not in self.removed]

    def pos(self, eid):
        if eid in self.moves:
            return tuple(self.moves[eid]["to"])
        o = self.obj[eid]
        return (o.x, o.y)

    def patrol_points(self):
        out = []
        for eid, r in self.patrols.items():
            for w in r["waypoints"]:
                out.append((eid, w["x"], w["y"]))
        return out

    def moved_positions(self):
        return {eid: tuple(r["to"]) for eid, r in self.moves.items()}

    # ---------------------------------------------------------------- waypoint tests
    def tile_of(self, p):
        return int(p[0] // self.tw), int(p[1] // self.tw) - 1

    def fp(self, p):
        return (p[0], p[1] - self.m.th, p[0] + self.m.tw, p[1])

    def wp_ok(self, p, owner):
        m = self.m
        x, y = p
        if x < 0 or y < m.th or x + m.tw > m.wpx or y > m.hpx:
            return False
        f = self.fp(p)
        for rect, oid in self.occ:
            if oid != owner and overlaps(f, rect):
                return False
        for rect, oid in self.reserved:
            if overlaps(f, rect):
                return False
        return T.open_at(m, x, y) and T.repo_standable(m, x, y)

    def near_floor(self, eid):
        """Its post is on, or within a tile and a half of, a standable reachable position."""
        x, y = self.pos(eid)
        if T.repo_standable(self.m, x, y):
            return True
        return any(T.repo_standable(self.m, x + dx * 8.0, y + dy * 8.0)
                   for dx in range(-3, 4) for dy in range(-3, 4))

    def leg(self, a, b, flying, post):
        """(ok, info) for walking a -> b (.tmx coordinates)."""
        m = self.m
        straight = T.dist(a, b)
        clear = T.segment_clear(m, a, b)
        info = {"straight": round(straight / self.tw, 2), "nav": None, "clear": clear}
        if flying:
            return (clear and straight <= MAX_LEG * self.tw), info
        key = ("probe",)
        self.nav.add_waypoint(key, (b[0], m.hpx - b[1]))
        try:
            L, pts = self.nav.path((a[0], m.hpx - a[1]), key)
        finally:
            for v in list(self.nav.adj.get(key, {})):
                self.nav.adj[v].pop(key, None)
            self.nav.adj.pop(key, None)
            self.nav.pos.pop(key, None)
        info["nav"] = None if L is None else round(L / self.tw, 2)
        if L is None or L > MAX_LEG * self.tw or L > DETOUR * straight + self.tw:
            return False, info
        for (px, py) in pts[1:-1]:
            q = (px, m.hpx - py)
            if T.dist(q, post) <= NEAR_POST * self.tw:
                continue
            if not T.repo_standable(m, q[0], q[1]):
                info["leaves_room_at"] = [round(q[0]), round(q[1])]
                return False, info
        return True, info

    def walk_to(self, p, target, max_tiles):
        """True when an enemy standing at p reaches `target` (loot) in at most max_tiles of pathfinding - i.e. the
        same room. Distance alone is not enough: a tile 'beside' a chest can be the far side of its wall."""
        m = self.m
        if T.dist(p, target) < 0.5:
            return True
        key = ("probe-loot",)
        self.nav.add_waypoint(key, (target[0], m.hpx - target[1]))
        try:
            L, _pts = self.nav.path((p[0], m.hpx - p[1]), key)
        finally:
            for v in list(self.nav.adj.get(key, {})):
                self.nav.adj[v].pop(key, None)
            self.nav.adj.pop(key, None)
            self.nav.pos.pop(key, None)
        return L is not None and L <= max_tiles * self.tw

    def route_ok(self, eid, pts):
        """Validate post -> pts[0] -> pts[1] ... -> pts[0] (the movement deque loops)."""
        flying = self.facts[eid]["flying"]
        post = self.pos(eid)
        legs = []
        seq = [post] + list(pts) + [pts[0]]
        for a, b in zip(seq, seq[1:]):
            if T.dist(a, b) < 0.5:
                legs.append({"straight": 0.0, "nav": 0.0, "clear": True})
                continue
            ok, info = self.leg(a, b, flying, post)
            legs.append(info)
            if not ok:
                return False, legs
        return True, legs

    def commit(self, eid, pts, legs, purpose, extra):
        wps = []
        m = self.m
        for p in pts:
            self.temp_id -= 1
            wps.append({"temp_id": self.temp_id, "x": round(p[0], 4), "y": round(p[1], 4),
                        "tile": list(self.tile_of(p))})
            self.reserved.append((self.fp(p), eid))
            self.nav.add_waypoint(("w", self.temp_id), (p[0], m.hpx - p[1]))
        f = self.facts[eid]
        home = self.pos(eid)
        rec = {"enemy_id": eid, "enemy": f["name"], "tier": f["tier"], "provenance": f.get("provenance"),
               "home": [round(f["x"], 4), round(f["y"], 4)], "home_tile": f["tile"],
               "purpose": purpose, "waypoints": wps,
               "route": "back-and-forth" if len(pts) == 2 else "loop",
               "returns_to_post": any(T.dist(p, home) <= 1.5 * self.tw for p in pts),
               "flying": f["flying"],
               "legs": legs, "all_legs_straight_clear": all(l.get("clear") for l in legs)}
        rec.update(extra)
        self.patrols[eid] = rec
        return rec

    def home_point(self, eid, avoid=()):
        """The post waypoint: the authored position itself when it is open floor, else the nearest open tile
        within two (an enemy authored half into a wall, or - garruk's Viper - on a tile nobody can reach)."""
        h = self.pos(eid)
        if self.wp_ok(h, eid):
            return h
        best = None
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                if dx or dy:
                    p = (h[0] + dx * self.tw, h[1] + dy * self.tw)
                    if any(T.dist(p, q) < 0.5 for q in avoid):
                        continue
                    if self.wp_ok(p, eid):
                        d = math.hypot(dx, dy)
                        if best is None or d < best[0]:
                            best = (d, p)
        return best[1] if best else None

    def far_enough(self, p, eid, skip=()):
        """Not beside another standing enemy, nor beside a waypoint this plan gave ANOTHER enemy (two new
        patrols must not turn round at the same spot and make a pair of their own)."""
        for o in self.standing():
            if o == eid or o in skip:
                continue
            if T.dist(p, self.pos(o)) < OTHERS * self.tw - 1e-6:
                return False
        for other, r in self.patrols.items():
            if other == eid:
                continue
            for w in r["waypoints"]:
                if T.dist(p, (w["x"], w["y"])) < OTHERS * self.tw - 1e-6:
                    return False
        return True

    # ---------------------------------------------------------------- eligibility
    def only_guard_of(self, eid):
        """Chests for which this enemy is the ONLY round-286 guard."""
        prox = T.proximity_guards(self.m, removed=self.removed, extra=self.patrol_points())
        return [cid for cid, ids in prox.items() if ids == [eid]]

    def patrol_eligible(self, eid):
        f = self.facts[eid]
        why = patrol_block(f)
        if eid in self.removed:
            why.append("removed")
        if eid in self.patrols:
            why.append("already given a patrol by this plan")
        if eid in self.moves:
            why.append("relocated by this plan")
        if not why and not self.near_floor(eid):
            why.append("stands where the player can never reach (no floor within 1.5 tiles)")
        return why

    # ---------------------------------------------------------------- chests
    def guard_chest(self, chest, cluster_members):
        m = self.m
        c = (chest.x, chest.y)
        if not any(T.repo_standable(m, chest.x + dx * self.tw, chest.y + dy * self.tw)
                   for dx in (-1, 0, 1) for dy in (-1, 0, 1)):
            return None, ["the chest has no reachable floor within a tile - nothing to patrol to"]
        cands, skipped = [], []
        for eid, f in self.facts.items():
            d = T.dist(self.pos(eid), c) / self.tw
            if d > CHEST_FROM or f["dialog"]:
                continue
            blk = self.patrol_eligible(eid)
            if not blk:
                only = [x for x in self.only_guard_of(eid) if x != chest.id]
                if only:
                    blk = ["the only guard of chest %s" % ", ".join(map(str, only))]
            if blk:
                skipped.append("%s #%d (%.1f tiles): %s" % (f["name"], eid, d, ", ".join(blk)))
                continue
            cands.append((0 if eid in cluster_members else 1, 0 if f["spawns_on_hard"] else 1, d, eid))
        cands.sort()
        tried = []
        # "beside the chest" on the chest's own tile grid - adjacent footprints touch, never overlap
        beside = []
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                if dx or dy:
                    beside.append(((0 if dx == 0 or dy == 0 else 1), (c[0] + dx * self.tw, c[1] + dy * self.tw)))
        for _cl, _h, d, eid in cands[:8]:
            opts = [(k, p) for k, p in beside if self.wp_ok(p, eid) and self.far_enough(p, eid)
                    and self.walk_to(p, c, 2.5)]
            if not opts:
                tried.append("%s #%d: no open, free tile beside the chest" % (self.facts[eid]["name"], eid))
                continue
            opts.sort(key=lambda kp: (kp[0], T.dist(self.pos(eid), kp[1])))
            last = None
            for _k, p in opts:
                # the post end of the walk: its own spot, or - authored off the floor, like garruk's Viper - the
                # nearest open tile, which must not be the chest-side tile itself (that is a move, not a patrol)
                h = self.home_point(eid, avoid=[p])
                if h is None or T.dist(h, p) < self.tw - 1e-6:
                    continue
                ok, legs = self.route_ok(eid, [h, p])
                if ok:
                    return self.commit(eid, [h, p], legs, "guard chest %d" % chest.id,
                                       {"chest": chest.id, "chest_tile": list(chest.tile()),
                                        "home_to_chest_tiles": round(d, 2),
                                        "also_splits_cluster": eid in cluster_members}), tried
                last = legs
            tried.append("%s #%d: no valid walk post <-> chest (%s)" % (
                self.facts[eid]["name"], eid, ("last leg %s" % last[-1]) if last else
                "no second open tile near its post to walk from"))
        if not cands:
            tried.append("no eligible enemy within %g tiles%s" % (
                CHEST_FROM, (" (" + "; ".join(skipped) + ")") if skipped else ""))
        # say whether an existing route already passes it - that is presence the round-286 count cannot see
        for eid, f in self.facts.items():
            if f["route_waypoints"] and not f["pinned"] and not f["dialog"]:
                pts, _legs = T.route_points(m, self.obj[eid])
                if pts:
                    dmin = min(T.dist((x, y), c) for _w, x, y in pts) / self.tw
                    if dmin <= 3:
                        tried.append("note: %s #%d already patrols past it - its route comes %.1f tiles from "
                                     "the chest" % (f["name"], eid, dmin))
        return None, tried

    def move_guard_beside(self, chest):
        """Optional class, for a chest whose REGISTERED guard stands where nobody can reach it (garruk's Viper,
        placed by round 286b one tile into the rocks): move that guard's home onto an open tile beside the chest.
        It stays the chest's registered guard (replayed), and no booster changes hands."""
        rows0, g0 = T.match_guards(self.m, removed=frozenset(self.removed), extra_positions=self.moved_positions())
        gid = next((g for o, tier, g, _d in rows0 if o.id == chest.id), None)
        if gid is None:
            return None, "the chest has no registered guard to move"
        f = self.facts[gid]
        hard = [r for r in f["protected"] if r in PATROL_BLOCK or r.startswith("story tag")]
        if f["route"]:
            hard.append("it walks an authored route")
        if hard or gid in self.patrols or gid in self.moves:
            return None, "its registered guard %s #%d cannot be moved (%s)" % (
                f["name"], gid, ", ".join(hard) or "already changed by this plan")
        c = (chest.x, chest.y)
        best = None
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                if not (dx or dy):
                    continue
                p = (c[0] + dx * self.tw, c[1] + dy * self.tw)
                if not self.wp_ok(p, gid) or not self.far_enough(p, gid) or not self.walk_to(p, c, 2.5):
                    continue
                mv2 = dict(self.moved_positions())
                mv2[gid] = p
                _rows1, g1 = T.match_guards(self.m, removed=frozenset(self.removed), extra_positions=mv2)
                if g1.get(gid) != g0.get(gid) or self.booster_guard_changes(self.removed, mv2):
                    continue
                key = (0 if dx == 0 or dy == 0 else 1, T.dist(p, self.pos(gid)))
                if best is None or key < best[0]:
                    best = (key, p)
        if best is None:
            return None, "no open tile beside the chest for its registered guard %s #%d" % (f["name"], gid)
        p = best[1]
        home = self.pos(gid)
        self.moves[gid] = {"enemy_id": gid, "enemy": f["name"], "tier": f["tier"], "provenance": f.get("provenance"),
                           "from": [round(home[0], 4), round(home[1], 4)], "from_tile": f["tile"],
                           "to": [round(p[0], 4), round(p[1], 4)], "to_tile": list(self.tile_of(p)),
                           "loot": chest.id, "loot_kind": "chest", "loot_tiles": round(T.dist(p, c) / self.tw, 2),
                           "partners": [], "purpose": "its registered guard onto reachable floor beside chest %d"
                           % chest.id}
        self.reserved.append((self.fp(p), gid))
        return self.moves[gid], None

    # ---------------------------------------------------------------- clusters
    def pairs(self, members):
        st = set(self.standing())
        st = [i for i in members if i in st]
        out = []
        for i, a in enumerate(st):
            for b in st[i + 1:]:
                if not (set(self.facts[a]["spawn_on"]) & set(self.facts[b]["spawn_on"])):
                    continue
                d = T.dist(self.pos(a), self.pos(b)) / self.tw
                if d <= self.thr + 1e-6:
                    out.append((a, b, round(d, 3)))
        return out

    def coverage(self, removed, moved=None):
        rows, _g = T.match_guards(self.m, removed=frozenset(removed), extra_positions=moved)
        matched = {o.id for o, tier, gid, _d in rows if tier in ("booster", "treasure") and gid}
        return matched

    def booster_guard_changes(self, removed, moved=None):
        """Boosters whose guard differs from the map as authored (removals/moves in `removed`/`moved`)."""
        rows0, _g0 = T.match_guards(self.m)
        rows1, _g1 = T.match_guards(self.m, removed=frozenset(removed), extra_positions=moved)
        before = {o.id: gid for o, tier, gid, _d in rows0 if tier == "booster"}
        after = {o.id: gid for o, tier, gid, _d in rows1 if tier == "booster"}
        return sorted(b for b, gid in before.items() if gid and after.get(b) != gid)

    def removal_block(self, eid):
        f = self.facts[eid]
        why = list(f["protected"])
        if eid in self.patrols:
            why.append("patrols in this plan")
        if eid in self.moves:
            why.append("relocated in this plan")
        if self.fighters_on_hard() < self.cutoff:
            why.append("map has fewer than %d fighters" % self.cutoff)
        if why:
            return why
        m = self.m
        mv = self.moved_positions()
        had = self.coverage(self.removed, mv)
        has = self.coverage(self.removed | {eid}, mv)
        lost = sorted(had - has)
        if lost:
            return ["loot %s would lose its dedicated guard" % ", ".join(str(x) for x in lost)]
        # A booster's guard is pinned and was TUNED - Adept+ (round 279), threatRange 40+ (round 286). A removal
        # that hands a booster to a different enemy would quietly undo that, so it must change no booster's guard.
        changed = self.booster_guard_changes(self.removed | {eid}, mv)
        if changed:
            return ["booster %s would pass to a different guard" % ", ".join(str(x) for x in changed)]
        p0 = T.proximity_guards(m, removed=self.removed, extra=self.patrol_points())
        p1 = T.proximity_guards(m, removed=self.removed | {eid}, extra=self.patrol_points())
        lost = sorted(c for c, ids in p0.items() if ids and not p1.get(c))
        if lost:
            return ["chest %s would have no enemy within 3 tiles" % ", ".join(str(x) for x in lost)]
        return []

    def split_route(self, eid, partners):
        f = self.facts[eid]
        home = self.pos(eid)
        h = self.home_point(eid)
        chest = None
        if f["guards"] and f["guards"]["tier"] == "treasure":
            co = self.m.by_id.get(f["guards"]["loot"])
            chest = (co.x, co.y) if co else None
        pts = []
        R = POST_RADIUS
        for dx in range(-R, R + 1):
            for dy in range(-R, R + 1):
                if not (dx or dy) or math.hypot(dx, dy) > R + 0.01:
                    continue
                p = (home[0] + dx * self.tw, home[1] + dy * self.tw)
                sep = min(T.dist(p, self.pos(q)) for q in partners) / self.tw
                if sep < SEP - 1e-6:
                    continue
                if not self.wp_ok(p, eid) or not self.far_enough(p, eid, skip=partners):
                    continue
                pts.append((p, sep))
        cache = {}

        def near_chest(p):
            if chest is None:
                return True
            if p not in cache:
                cache[p] = (T.dist(p, chest) <= PRESENCE * self.tw + 1e-6
                            and self.walk_to(p, chest, PRESENCE + 1.5))
            return cache[p]
        m = self.m
        routes = []
        # shape B: two far points - it never comes back beside the group. Ranked: straight legs first, then
        # the shorter walk, then the wider berth (separation already clears SEP).
        for i, (a, sa) in enumerate(pts):
            for b, sb in pts[i + 1:]:
                ab = T.dist(a, b) / self.tw
                if ab < 2 - 1e-6 or ab > 4 + 1e-6:
                    continue
                if not (near_chest(a) or near_chest(b)):
                    continue
                first, other = (a, b) if T.dist(home, a) <= T.dist(home, b) else (b, a)
                clear = T.segment_clear(m, home, first) and T.segment_clear(m, first, other)
                length = T.dist(home, first) + T.dist(first, other)
                routes.append(((0, 0 if clear else 1, round(length / self.tw), -min(sa, sb, 3.0)), [first, other]))
        # shape A: its post and one far point - it stands beside the group half the time
        if h is not None:
            for a, sa in pts:
                if T.dist(h, a) < 2 * self.tw - 1e-6:
                    continue
                if not (near_chest(h) or near_chest(a)):
                    continue
                clear = T.segment_clear(m, h, a)
                routes.append(((1, 0 if clear else 1, round(T.dist(h, a) / self.tw), -min(sa, 3.0)), [h, a]))
        routes.sort(key=lambda r: r[0])
        for _k, rpts in routes[:40]:
            ok, legs = self.route_ok(eid, rpts)
            if ok:
                return rpts, legs, None
        if routes:
            return None, None, "no candidate route passes the walk checks (%d tried)" % min(len(routes), 40)
        return None, None, "no open floor %.1f+ tiles from the group within %d tiles of its post%s" % (
            SEP, POST_RADIUS, " that also keeps it within %g tiles of its chest" % PRESENCE if chest else "")

    def relocate(self, eid, partners):
        """Optional class: move a registered loot guard's HOME to another open tile within two tiles of its own
        booster/chest, SEP+ tiles from the group, proving by replay that it keeps that loot and every booster and
        chest keeps a dedicated guard. For a booster's guard (pinned - no patrol can run) it is the only fix
        short of removal; for a chest's guard it is the fallback when no patrol fits."""
        f = self.facts[eid]
        if not f["guards"] or f["guards"]["tier"] not in ("booster", "treasure"):
            return None, "guards no booster or chest"
        if eid in self.moves or eid in self.patrols or eid in self.removed:
            return None, "already changed by this plan"
        hard = [r for r in f["protected"] if r in PATROL_BLOCK or r.startswith("story tag")]
        tool_placed = (f.get("provenance") or "r256") != "r256"
        if hard and not tool_placed:
            # a tool-placed copy of a named character is still a copy; an AUTHORED boss stays where it was put
            return None, "protected (%s)" % ", ".join(hard)
        b = self.m.by_id.get(f["guards"]["loot"])
        bp = (b.x, b.y)
        home = self.pos(eid)
        mv = self.moved_positions()
        base = self.coverage(self.removed, mv)
        _rows0, g0 = T.match_guards(self.m, removed=frozenset(self.removed), extra_positions=mv)
        best = None
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                if not (dx or dy):
                    continue
                p = (bp[0] + dx * self.tw, bp[1] + dy * self.tw)
                if T.dist(p, bp) > 2.25 * self.tw + 1e-6:        # up to a knight's move from its loot
                    continue
                sep = min(T.dist(p, self.pos(q)) for q in partners) / self.tw
                if sep < SEP - 1e-6 or not self.wp_ok(p, eid) or not self.far_enough(p, eid, skip=partners):
                    continue
                if not self.walk_to(p, bp, 3.5):
                    continue                              # the same room as its loot, not behind the wall
                mv2 = dict(mv)
                mv2[eid] = p
                rows1, g1 = T.match_guards(self.m, removed=frozenset(self.removed), extra_positions=mv2)
                has = {o.id for o, tier, gid, _d in rows1 if tier in ("booster", "treasure") and gid}
                if base - has or g1.get(eid) != g0.get(eid):
                    continue
                if self.booster_guard_changes(self.removed, mv2):
                    continue
                key = (T.dist(p, bp), T.dist(p, home))
                if best is None or key < best[0]:
                    best = (key, p)
        if best is None:
            return None, "no open tile within 2 tiles of its %s %.1f+ tiles from the group" % (
                f["guards"]["tier"].replace("treasure", "chest"), SEP)
        p = best[1]
        self.moves[eid] = {"enemy_id": eid, "enemy": f["name"], "tier": f["tier"],
                           "provenance": f.get("provenance"), "from": [round(home[0], 4), round(home[1], 4)],
                           "from_tile": f["tile"], "to": [round(p[0], 4), round(p[1], 4)],
                           "to_tile": list(self.tile_of(p)), "loot": b.id,
                           "loot_kind": f["guards"]["tier"].replace("treasure", "chest"),
                           "loot_tiles": round(T.dist(p, bp) / self.tw, 2), "partners": partners}
        self.reserved.append((self.fp(p), eid))
        return self.moves[eid], None

    def resolve_group(self, members):
        actions = []
        for _step in range(12):
            pairs = self.pairs(members)
            if not pairs:
                return actions, None
            ids = sorted({a for a, b, _d in pairs} | {b for a, b, _d in pairs})
            names = collections.Counter(self.facts[i]["name"] for i in ids)
            degree = collections.Counter(j for a, b, _d in pairs for j in (a, b))
            done = False
            # A group that is ALL hand-authored predates every guard tool - it is somebody's layout, not a
            # side effect. With a special member (crowned, own reward, named, scripted defeat, own deck) it is a
            # set-piece - the story temple's line of guardians on the carpet to Chandra - and is left as designed.
            # Otherwise only a true duplicate (same enemy name) may be removed; a pair of different creatures
            # gets a patrol at most.
            authored = all((self.facts[i].get("provenance") or "r256") == "r256" for i in ids)
            if authored:
                special = {i: [r for r in self.facts[i]["protected"] if r in SET_PIECE or r in PATROL_BLOCK
                               or r.startswith("story tag")] for i in ids}
                special = {i: v for i, v in special.items() if v}
                if special:
                    return actions, {i: "hand-authored set-piece (%s) - left as designed" % "; ".join(
                        "#%d %s" % (k, ", ".join(v)) for k, v in special.items()) for i in ids}
            if self.fighters_on_hard() >= self.cutoff:
                # Only "the weaker/duplicate one" of a pair is ever a candidate - the weaker by rank, both when
                # they are equal (a duplicate always is). The stronger of a pair is never removed, even when the
                # weaker cannot be: that pair gets a patrol instead. Among candidates: the member in the most
                # pairs first (the middle of a line of three settles both), tool-placed before hand-authored,
                # a shared name first, the newest first.
                weak = set()
                for a_, b_, _d in pairs:
                    ra, rb = self.facts[a_]["rank"], self.facts[b_]["rank"]
                    if ra <= rb:
                        weak.add(a_)
                    if rb <= ra:
                        weak.add(b_)
                tool = {i for i in ids if (self.facts[i].get("provenance") or "r256") != "r256"}
                if authored:
                    weak = {i for i in weak if names[self.facts[i]["name"]] > 1}      # duplicates only
                else:
                    # a mixed group: the newcomer goes, whatever its rank (round 286b put Rares and Mythics on
                    # chests it meant to guard with Apprentices); a hand-authored member only as a duplicate
                    weak = tool | {i for i in weak if names[self.facts[i]["name"]] > 1}
                order = sorted(weak, key=lambda i: (
                    -degree[i],
                    0 if (self.facts[i].get("provenance") or "r256") != "r256" else 1,
                    self.facts[i]["rank"],
                    0 if names[self.facts[i]["name"]] > 1 else 1,
                    PROV_ORDER.get(self.facts[i].get("provenance"), 9), -i))
                blocks = {}
                for i in order:
                    why = self.removal_block(i)
                    if not why:
                        self.removed.add(i)
                        f = self.facts[i]
                        actions.append({"action": "remove", "enemy_id": i, "enemy": f["name"],
                                        "partners": [j for j in ids if j != i]})
                        done = True
                        break
                    blocks[i] = why
                if not done:
                    self.notes.append("no safe removal among %s: %s" % (ids, "; ".join(
                        "#%d %s" % (k, ", ".join(v)) for k, v in blocks.items()) or
                        "hand-authored and not duplicates of each other, so patrol only"))
            if done:
                continue
            order = sorted(ids, key=lambda i: (
                -degree[i],
                0 if not self.facts[i]["guards"] else 1,
                0 if (self.facts[i].get("provenance") or "r256") != "r256" else 1,
                self.facts[i]["rank"], -i))
            why_not = {}
            for i in order:
                blk = self.patrol_eligible(i)
                if blk:
                    why_not[i] = ", ".join(blk)
                    continue
                partners = sorted({j for a, b, _d in pairs for j in (a, b) if i in (a, b) and j != i})
                rpts, legs, err = self.split_route(i, partners)
                if rpts:
                    rec = self.commit(i, rpts, legs, "split cluster %s" % ids, {"partners": partners})
                    actions.append({"action": "patrol", "enemy_id": i, "enemy": rec["enemy"], "partners": partners})
                    done = True
                    break
                why_not[i] = err
            if not done:
                return actions, why_not
        return actions, {"loop": "gave up after 12 steps"}

    def existing_patrol_near(self, c):
        """(distance tiles, enemy id) of the closest AUTHORED route waypoint of a walking fighter to point c."""
        best = None
        for eid, f in self.facts.items():
            if f["pinned"] or f["dialog"] or f["inactive"] or not f["patrols"]:
                continue
            pts, _legs = T.route_points(self.m, self.obj[eid])
            for _w, x, y in pts:
                d = T.dist((x, y), c) / self.tw
                if best is None or d < best[0]:
                    best = (d, eid)
        return best

    def tradeoffs(self, members):
        """For a cluster nothing safe can fix: what removing its weaker member would COST, so the user can
        choose. Nothing here is planned."""
        pairs = self.pairs(members)
        out = []
        weak = set()
        for a_, b_, _d in pairs:
            ra, rb = self.facts[a_]["rank"], self.facts[b_]["rank"]
            if ra <= rb:
                weak.add(a_)
            if rb <= ra:
                weak.add(b_)
        rows0, g0 = T.match_guards(self.m, removed=frozenset(self.removed))
        for i in sorted(weak):
            f = self.facts[i]
            hard = [r for r in f["protected"] if r in PATROL_BLOCK or r.startswith("story tag")]
            if hard:
                out.append({"remove": i, "enemy": f["name"], "impossible": ", ".join(hard)})
                continue
            rem = frozenset(self.removed | {i})
            rows1, g1 = T.match_guards(self.m, removed=rem)
            had = {o.id for o, tier, gid, _d in rows0 if tier in ("booster", "treasure") and gid}
            has = {o.id for o, tier, gid, _d in rows1 if tier in ("booster", "treasure") and gid}
            before = {o.id: gid for o, tier, gid, _d in rows0 if tier == "booster"}
            after = {o.id: gid for o, tier, gid, _d in rows1 if tier == "booster"}
            handed = []
            for b, gid in before.items():
                if gid and after.get(b) != gid:
                    ng = after.get(b)
                    handed.append({"booster": b, "to": ng, "to_name": self.facts[ng]["name"] if ng else None,
                                   "to_tier": self.facts[ng]["tier"] if ng else None,
                                   "to_threatRange": self.facts[ng]["threat"] if ng else None})
            p0 = T.proximity_guards(self.m, removed=self.removed, extra=self.patrol_points())
            p1 = T.proximity_guards(self.m, removed=set(rem), extra=self.patrol_points())
            out.append({"remove": i, "enemy": f["name"], "tier": f["tier"], "provenance": f.get("provenance"),
                        "loot_losing_dedicated_guard": sorted(had - has), "boosters_changing_guard": handed,
                        "chests_left_with_no_enemy_within_3": sorted(c for c, ids in p0.items()
                                                                     if ids and not p1.get(c)),
                        "fighters_after": self.fighters_on_hard() - (1 if f["spawns_on_hard"] else 0),
                        "blocked_by_protection": [r for r in f["protected"] if r not in hard]})
        return out

    def relocate_group(self, members):
        """Second pass, OPTIONAL class, run only on what the base pass left unresolved - so the base plan's
        removals and patrols never depend on a relocation apply.py may skip. Registered guards only, boosters'
        guards first (nothing else can move them)."""
        actions = []
        for _step in range(12):
            pairs = self.pairs(members)
            if not pairs:
                return actions, None
            ids = sorted({a for a, b, _d in pairs} | {b for a, b, _d in pairs})
            degree = collections.Counter(j for a, b, _d in pairs for j in (a, b))
            why_not = {}
            done = False
            for i in sorted(ids, key=lambda i: (-degree[i], 0 if self.facts[i]["pinned"] else 1,
                                                0 if (self.facts[i].get("provenance") or "r256") != "r256" else 1,
                                                self.facts[i]["rank"], -i)):
                partners = sorted({j for a, b, _d in pairs for j in (a, b) if i in (a, b) and j != i})
                mrec, err = self.relocate(i, partners)
                if mrec:
                    actions.append({"action": "relocate", "enemy_id": i, "enemy": mrec["enemy"],
                                    "partners": partners, "optional": True})
                    done = True
                    break
                why_not[i] = err
            if not done:
                return actions, why_not
        return actions, {"loop": "gave up after 12 steps"}


def file_sha1(path):
    return hashlib.sha1(open(path, "rb").read()).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("plane", help="the plane folder (REQUIRED)")
    ap.add_argument("--dev-tools")
    ap.add_argument("--provenance")
    ap.add_argument("--out", default=".")
    ap.add_argument("--threshold", type=float, default=A.DEFAULT_THRESHOLD)
    ap.add_argument("--cutoff", type=int, default=CUTOFF)
    ap.add_argument("--maps", nargs="*")
    ap.add_argument("--exclude", action="append", default=[],
                    help="rel/map.tmx#id - never move or remove this enemy (repeatable); re-plans around it")
    a = ap.parse_args()
    excluded = collections.defaultdict(set)
    for x in a.exclude:
        rel_, _h, oid = x.rpartition("#")
        excluded[rel_.replace("\\", "/")].add(int(oid))

    root = T.maps_root(a.plane)
    T.load_tools(T.find_dev_tools(root, a.dev_tools))
    edata = T.load_enemy_data(T.plane_of(root))
    prov = json.load(open(a.provenance, encoding="utf-8")) if a.provenance else None
    files = [os.path.join(root, *p.split("/")) for p in a.maps] if a.maps else T.all_maps(root)

    out_maps = collections.OrderedDict()
    tot = collections.Counter()
    t0 = time.time()
    for f in files:
        m = T.TMap(f, root)
        rec = A.analyze_map(m, edata, prov, a.threshold)
        tot["maps"] += 1
        tot["chests_audited"] += rec["counts"]["chests"]
        tot["chests_r286_unguarded"] += rec["counts"]["r286_unguarded"]
        tot["chests_runtime_unmatched"] += rec["counts"]["runtime_unmatched_chests"]
        tot["clusters_found"] += len(rec["clusters"])
        tot["clustered_enemies"] += sum(len(k["members"]) for k in rec["clusters"])
        ung = [c for c in rec["chests"] if c.get("r286_guarded") is False]
        if not rec["planned"]:
            tot["clusters_in_unplanned_maps"] += len(rec["clusters"])
            tot["chests_unguarded_in_unplanned_maps"] += len(ung)
            continue
        if not ung and not rec["clusters"]:
            continue
        P = Planner(m, rec, a.threshold, a.cutoff, exclude=excluded.get(m.rel, ()))
        entry = {"sha1": file_sha1(m.path), "nextobjectid": m.nextobjectid,
                 "fighters_on_hard": rec["counts"]["fighters_on_hard"], "patrols": [], "removals": [],
                 "relocations": [], "chests": [], "clusters": [], "notes": []}
        members = {i for k in rec["clusters"] for i in k["members"]}
        for c in ung:
            chest = m.by_id[c["id"]]
            ne = c.get("nearest_enemy")
            row = {"chest": c["id"], "tile": c["tile"], "why_unguarded": c.get("why"), "nearest_enemy": ne,
                   "absent_on_hard": bool(c.get("absent_on_hard"))}
            if os.path.basename(m.rel) in T.Q.ACCEPTED_UNREACHABLE:
                row["result"] = "left alone: the user accepted this map as designed (ACCEPTED_UNREACHABLE)"
                tot["chests_left_accepted_map"] += 1
            elif ne and ne["tiles"] <= BESIDE and not m.grids()["has_entry"] and P.near_floor(ne["id"]):
                row["result"] = ("already guarded: %s #%d stands %.1f tiles away; the round-286 audit only says "
                                 "'unguarded' because it cannot seed a flood fill from this map's entry"
                                 % (P.facts[ne["id"]]["name"], ne["id"], ne["tiles"]))
                tot["chests_already_guarded_entry_blind"] += 1
            elif P.existing_patrol_near((chest.x, chest.y)) and \
                    P.existing_patrol_near((chest.x, chest.y))[0] <= BESIDE + 1e-6:
                d_, pid = P.existing_patrol_near((chest.x, chest.y))
                row["result"] = ("already patrolled: %s #%d's authored route passes %.1f tiles from it (round 286's "
                                 "count reads only where enemies stand, not where they walk)%s"
                                 % (P.facts[pid]["name"], pid, d_, "" if m.grids()["has_entry"] else
                                    "; this map's entry is also one the audit cannot seed from"))
                tot["chests_already_patrolled"] += 1
            else:
                rec_p, tried = P.guard_chest(chest, members)
                if rec_p:
                    row["result"] = "patrol"
                    row["patroller"] = {"id": rec_p["enemy_id"], "name": rec_p["enemy"],
                                        "from_tiles": rec_p["home_to_chest_tiles"]}
                    tot["chests_guarded_by_patrol"] += 1
                else:
                    row["result"] = "no usable enemy"
                    row["tried"] = tried
                    tot["chests_no_usable_enemy"] += 1
            entry["chests"].append(row)
        krows = []
        for k in rec["clusters"]:
            acts, why_not = P.resolve_group(k["members"])
            krow = {"members": k["members"], "pairs": k["pairs"], "actions": acts,
                    "coexist_on": sorted(set.intersection(*[set(P.facts[i]["spawn_on"]) for i in k["members"]]),
                                         key=A.DIFFICULTIES.index)}
            if why_not and all("set-piece" in str(w) for w in why_not.values()):
                krow["set_piece"] = next(iter(why_not.values()))
            elif why_not:
                krow["unresolved"] = {str(i): w for i, w in why_not.items()}
            elif not acts:
                krow["note"] = "already resolved by an earlier action in this map"
            krows.append(krow)
        # third pass (optional class, after the base plan is fixed): relocations. First a chest whose own
        # registered guard stands out of reach, then the clusters the base pass left unresolved.
        for row in entry["chests"]:
            if row["result"] == "no usable enemy":
                mrec, err = P.move_guard_beside(m.by_id[row["chest"]])
                if mrec:
                    row["optional_relocation"] = {"enemy_id": mrec["enemy_id"], "enemy": mrec["enemy"],
                                                  "to_tile": mrec["to_tile"]}
                    tot["chests_guardable_by_optional_relocation"] += 1
                else:
                    row["optional_relocation"] = err
        for krow in krows:
            if krow.get("unresolved"):
                krow["tradeoffs"] = P.tradeoffs(krow["members"])      # before any relocation, as the base plan
        for krow in krows:
            if krow.get("unresolved"):
                racts, rwhy = P.relocate_group(krow["members"])
                krow["relocations"] = racts
                if racts:
                    krow["relocation_resolves"] = not rwhy
                    if rwhy:
                        krow["relocation_unresolved"] = {str(i): w for i, w in rwhy.items()}
                else:
                    krow["relocation_unresolved"] = {str(i): w for i, w in (rwhy or {}).items()}
        for krow in krows:
            kinds = {x["action"] for x in krow["actions"]}
            if krow.get("set_piece"):
                tot["clusters_left_as_authored_set_pieces"] += 1
            elif krow.get("unresolved"):
                tot["clusters_unresolved_base"] += 1
                if kinds:
                    tot["clusters_partly_resolved_base"] += 1
                if krow.get("relocation_resolves"):
                    tot["clusters_resolvable_by_optional_relocation"] += 1
                else:
                    tot["clusters_unresolved_even_with_relocation"] += 1
            elif krow["actions"]:
                tot["clusters_resolved_by_" + "+".join(sorted(kinds))] += 1
            else:
                tot["clusters_resolved_by_earlier_action"] += 1
            entry["clusters"].append(krow)
        entry["patrols"] = list(P.patrols.values())
        entry["relocations"] = list(P.moves.values())
        for rid in sorted(P.removed):
            f2 = P.facts[rid]
            entry["removals"].append({"enemy_id": rid, "enemy": f2["name"], "tier": f2["tier"],
                                      "provenance": f2.get("provenance"),
                                      "home": [round(f2["x"], 4), round(f2["y"], 4)], "tile": f2["tile"]})
        entry["notes"] = P.notes
        mv = P.moved_positions()
        prox_after = T.proximity_guards(m, removed=P.removed, extra=P.patrol_points())
        rows_after, _g = T.match_guards(m, removed=frozenset(P.removed), extra_positions=mv)
        P_nomove = dict(P.moves)
        P.moves = collections.OrderedDict()
        pairs_without_moves = P.pairs(list(P.facts))
        P.moves = P_nomove
        entry["after"] = {
            "r286_unguarded": sorted(c for c, ids in prox_after.items() if not ids),
            "runtime_unmatched": sorted(o.id for o, tier, gid, _d in rows_after if tier in ("booster", "treasure")
                                        and not gid),
            "standing_pairs": [list(p) for p in P.pairs(list(P.facts))],
            "standing_pairs_without_relocations": [list(p) for p in pairs_without_moves],
            "fighters_on_hard": P.fighters_on_hard(),
        }
        tot["patrols"] += len(P.patrols)
        tot["patrols_for_chests"] += sum(1 for r in P.patrols.values() if r["purpose"].startswith("guard chest"))
        tot["patrols_for_clusters"] += sum(1 for r in P.patrols.values() if r["purpose"].startswith("split"))
        tot["removals"] += len(P.removed)
        tot["relocations_optional"] += len(P.moves)
        tot["waypoints_new"] += sum(len(r["waypoints"]) for r in P.patrols.values())
        tot["maps_changed"] += 1 if (P.patrols or P.removed) else 0
        out_maps[m.rel] = entry
    tot["seconds"] = round(time.time() - t0)
    plan = {"generated": time.strftime("%Y-%m-%d %H:%M:%S"), "plane": os.path.abspath(T.plane_of(root)),
            "threshold_tiles": a.threshold, "cutoff_fighters": a.cutoff, "sep_tiles": SEP,
            "post_radius_tiles": POST_RADIUS, "totals": dict(tot), "maps": out_maps}
    os.makedirs(a.out, exist_ok=True)
    json.dump(plan, open(os.path.join(a.out, "plan.json"), "w", encoding="utf-8"), indent=1)
    audit_path = os.path.join(a.out, "audit.json")
    audit_full = json.load(open(audit_path, encoding="utf-8")) if os.path.exists(audit_path) else None
    write_md(os.path.join(a.out, "plan.md"), plan, audit_full["summary"] if audit_full else None, audit_full)
    print(json.dumps(dict(tot), indent=1, sort_keys=True))
    return 0


def _who(r):
    prov = r.get("provenance")
    return "%s #%d (%s%s)" % (r["enemy"], r["enemy_id"], r.get("tier") or "?",
                              ", placed by " + prov if prov and prov not in ("r256", None) else ", hand-authored"
                              if prov == "r256" else "")


def write_md(path, plan, audit, audit_full=None):
    t = plan["totals"]
    g = lambda k: t.get(k, 0)
    maps = plan["maps"]
    L = []
    L.append("# Round 316 plan: chest patrols and crowded enemies\n")
    L.append("Generated %s from `%s`. Nothing here has been applied to the repo.\n" % (plan["generated"], plan["plane"]))
    L.append("## The short version\n")
    L.append("* **The 296 is stale.** It is round 286's count. Rounds 286b and 287 then *added* 363 chest guards "
             "(238 + 125) plus 12 booster guards. Re-measured today with round 286's own audit "
             "(`booster_guards.audit(loot=\"treasure\")`): **%d of %d chests have no guard.** Of those: %d by a "
             "patrol of an existing enemy; %d already guarded (an enemy stands beside it or an authored route "
             "walks past it - the audit cannot see either); %d in the map you accepted as-is (phyrexian_black1); "
             "%d needing a move rather than a patrol (optional relocation)."
             % (g("chests_r286_unguarded"), g("chests_audited"), g("chests_guarded_by_patrol"),
                g("chests_already_guarded_entry_blind") + g("chests_already_patrolled"),
                g("chests_left_accepted_map"), g("chests_no_usable_enemy")))
    L.append("* **Crowding is mostly the new guards.** %d clusters (%d enemies) stand within %g tiles of each other; "
             "most pairs involve a guard placed by rounds 279-287 next to an enemy that already stood by the loot. "
             "The plan resolves %d by a patrol and %d by a removal; %d more are resolvable only by an optional "
             "relocation; %d are hand-authored set-pieces left as designed; %d stay as they are (reasons and "
             "trade-offs below)."
             % (g("clusters_found") - g("clusters_in_unplanned_maps"), g("clustered_enemies"), plan["threshold_tiles"],
                g("clusters_resolved_by_patrol") + g("clusters_resolved_by_earlier_action"),
                g("clusters_resolved_by_remove") + g("clusters_resolved_by_patrol+remove"),
                g("clusters_resolvable_by_optional_relocation"), g("clusters_left_as_authored_set_pieces"),
                g("clusters_unresolved_even_with_relocation")))
    L.append("* **Changes:** %d patrols (%d new waypoint objects, %d for chests, %d to split clusters), %d removals, "
             "in %d maps. Optional, off by default: %d relocations."
             % (g("patrols"), g("waypoints_new"), g("patrols_for_chests"), g("patrols_for_clusters"), g("removals"),
                g("maps_changed"), g("relocations_optional")))
    L.append("")
    L.append("## Decisions\n")
    L.append("* **Cluster threshold: %g tiles, center to center.** Covers the same tile, touching (1.0 orthogonal, "
             "1.41 diagonal) and one empty tile between (2.0). Below it most pairs involve a tool-placed guard; "
             "above it the pairs are mostly hand-authored layouts (see the audit's distribution below). Two "
             "placements with disjoint `spawn.X` flags are one placement per difficulty and never count."
             % plan["threshold_tiles"])
    fp = (audit or {}).get("fighters_per_map") or {}
    if plan["cutoff_fighters"] != 8:
        fp = {}                                   # the audit's "at_least_8" column only describes the default
    L.append("* **\"A lot of enemies\": %d+ fighters on Hard** (non-dialog, active)%s. Only there is anything "
             "removed, and only the weaker/duplicate member, and only if no booster or chest loses its guard and "
             "no booster changes guard (rounds 279/286 tuned those: Adept+, threatRange 40). Otherwise - or when "
             "no removal is safe - one member patrols."
             % (plan["cutoff_fighters"], (": the median map with enemies has %d, the 75th percentile %d, and %d of "
                                          "%d maps reach %d+ - the busier third" % (
                                              fp["median"], fp["p75"], fp["at_least_8"], fp["maps"],
                                              plan["cutoff_fighters"])) if fp else ""))
    L.append("* **Who may be removed.** A group mixing hand-authored and tool-placed enemies loses a tool-placed one "
             "(or an exact duplicate), never an original. A group that is ALL hand-authored (every member placed "
             "before any guard tool ran) is somebody's layout: with a crowned / own-reward / named / scripted member "
             "it is a set-piece and is left alone (the story temple's line of guardians on the carpet to Chandra); "
             "otherwise only an exact duplicate (same enemy) is ever removed, and two different creatures get a "
             "patrol at most.")
    L.append("* **Patrols** are back-and-forth routes of 2 new waypoint objects, written exactly as the maps "
             "already write them (`waypoint.tx` objects, a `waypoints` property of their ids). A split patrol walks "
             "%g+ tiles from its cluster and stays within %d tiles of its post; a chest's guard keeps passing its "
             "chest. Every leg is checked against a replay of the game's own NavigationMap (the enemy pathfinds "
             "between waypoints) plus waypoint_routes_qa's standable test."
             % (plan["sep_tiles"], plan["post_radius_tiles"]))
    L.append("* **Never touched:** bosses, legends, story-tagged or spawnRate-0 placements, dialog NPCs, "
             "anything a map script deletes/activates, hidden ambushers, inactive set-pieces, and any enemy that "
             "already has a `waypoints` route. Booster guards cannot patrol (the runtime pins them) - only the "
             "optional relocation can move one.")
    L.append("")
    if audit:
        L.append("### Nearest other enemy, standing fighters (cumulative, tiles)\n")
        h = audit["nearest_enemy_tiles_standing"]
        L.append("| " + " | ".join(h.keys()) + " |")
        L.append("|" + "---|" * len(h))
        L.append("| " + " | ".join(str(v) for v in h.values()) + " |")
        if audit.get("cluster_pairs_by_provenance"):
            L.append("\nPairs within the threshold, by who placed them: %s.\n" % ", ".join(
                "%s %d" % kv for kv in sorted(audit["cluster_pairs_by_provenance"].items())))
    # chests
    L.append("## 1. The unguarded chests\n")
    L.append("| map | chest (tile) | why unguarded | outcome |")
    L.append("|---|---|---|---|")
    for rel, e in maps.items():
        for c in e["chests"]:
            out = c["result"]
            if out == "patrol":
                p = next(r for r in e["patrols"] if r["enemy_id"] == c["patroller"]["id"])
                out = "**patrol** - %s walks %s, from %.1f tiles away" % (
                    _who(p), " <-> ".join("(%d,%d)" % tuple(w["tile"]) for w in p["waypoints"]),
                    c["patroller"]["from_tiles"])
            elif out == "no usable enemy":
                opt = c.get("optional_relocation")
                out = "no enemy can patrol to it (%s)%s" % ("; ".join(c.get("tried", [])),
                                                            ("; **optional relocation**: %s #%d to tile (%d,%d)" % (
                                                                opt["enemy"], opt["enemy_id"], opt["to_tile"][0],
                                                                opt["to_tile"][1])) if isinstance(opt, dict) else "")
            L.append("| %s | %d (%d,%d) | %s | %s |" % (rel, c["chest"], c["tile"][0], c["tile"][1],
                                                     c.get("why_unguarded") or "", out))
    L.append("\nA patroller keeps its home, so it is not the chest's *registered* guard (MapStage.assignLootGuards() "
             "matches on authored positions): the patrol is presence, not the chase-on-theft hook.\n")
    # removals
    L.append("## 2. Clusters fixed by removing one enemy\n")
    L.append("| map | removed | stood beside | fighters on Hard |")
    L.append("|---|---|---|---|")
    for rel, e in maps.items():
        for k in e["clusters"]:
            for a_ in k["actions"]:
                if a_["action"] == "remove":
                    r = next(x for x in e["removals"] if x["enemy_id"] == a_["enemy_id"])
                    L.append("| %s | %s at (%d,%d) | %s | %d -> %d |" % (
                        rel, _who(r), r["tile"][0], r["tile"][1], ", ".join("#%d" % p for p in a_["partners"]),
                        e["fighters_on_hard"], e["after"]["fighters_on_hard"]))
    # patrols
    L.append("\n## 3. Patrols\n")
    L.append("Legs: walk from its post to the first waypoint, then the loop. `a/b` = straight / pathfinding tiles; "
             "`*` = the straight line is blocked and the game's pathfinding walks round it; `fly` = a flier, which "
             "goes straight (so its line had to be clear).\n")
    L.append("| map | enemy (post tile) | purpose | waypoints (tiles) | returns to post | legs |")
    L.append("|---|---|---|---|---|---|")
    for rel, e in maps.items():
        for p in e["patrols"]:
            legs = " ".join(("%s fly" % l["straight"]) if l.get("nav") is None and p.get("flying") else
                            "%s/%s%s" % (l["straight"], l["nav"], "" if l.get("clear") else "*") for l in p["legs"])
            L.append("| %s | %s (%d,%d) | %s | %s | %s | %s |" % (
                rel, _who(p), p["home_tile"][0], p["home_tile"][1], p["purpose"],
                " <-> ".join("(%d,%d)" % tuple(w["tile"]) for w in p["waypoints"]),
                "yes" if p.get("returns_to_post") else "no - it patrols clear of the group", legs))
    # relocations
    L.append("\n## 4. Optional relocations (not applied unless `apply.py --with-relocations`)\n")
    L.append("The request named removal or patrol; these are moves, offered because a booster's guard can do neither "
             "(pinned, and the only guard its booster has), and for a chest's guard only where no patrol fits. Each "
             "new post is open floor in the same room as its loot (a short walk, not just a short distance), keeps "
             "its own loot and changes no other guard - replayed.\n")
    L.append("| map | enemy | from -> to (tiles) | its loot | why |")
    L.append("|---|---|---|---|---|")
    for rel, e in maps.items():
        for r in e["relocations"]:
            L.append("| %s | %s | (%d,%d) -> (%d,%d) | %s %d at %.1f tiles | %s |" % (
                rel, _who(r), r["from_tile"][0], r["from_tile"][1], r["to_tile"][0], r["to_tile"][1],
                r["loot_kind"], r["loot"], r["loot_tiles"],
                r.get("purpose") or "split from #%s" % ", #".join(str(x) for x in r["partners"])))
    # set-pieces
    L.append("\n## 5. Hand-authored set-pieces, left as designed\n")
    L.append("| map | cluster | why |")
    L.append("|---|---|---|")
    for rel, e in maps.items():
        for k in e["clusters"]:
            if k.get("set_piece"):
                L.append("| %s | %s | %s |" % (rel, ", ".join("#%d" % i for i in k["members"]), k["set_piece"]))
    # unresolved
    L.append("\n## 6. Left as they are\n")
    L.append("| map | cluster | on | why nothing safe fits | what removing the weaker one would cost |")
    L.append("|---|---|---|---|---|")
    for rel, e in maps.items():
        for k in e["clusters"]:
            if not k.get("unresolved") or k.get("relocation_resolves"):
                continue
            why = "; ".join("#%s: %s" % kv for kv in k["unresolved"].items())
            costs = []
            for to in k.get("tradeoffs", []):
                if to.get("impossible"):
                    costs.append("#%d %s: protected (%s)" % (to["remove"], to["enemy"], to["impossible"]))
                    continue
                bits = []
                if to["loot_losing_dedicated_guard"]:
                    bits.append("loot %s loses its registered guard" % to["loot_losing_dedicated_guard"])
                for h in to["boosters_changing_guard"]:
                    bits.append("booster %d passes to %s" % (h["booster"], "#%d %s (%s, threatRange %g)" % (
                        h["to"], h["to_name"], h["to_tier"], h["to_threatRange"]) if h["to"] else "nobody"))
                if to["chests_left_with_no_enemy_within_3"]:
                    bits.append("chest %s left with no enemy within 3 tiles" % to["chests_left_with_no_enemy_within_3"])
                if to["blocked_by_protection"]:
                    bits.append("it is %s" % ", ".join(to["blocked_by_protection"]))
                costs.append("#%d %s: %s" % (to["remove"], to["enemy"], "; ".join(bits) or "nothing lost, but the "
                                             "map has fewer than %d fighters" % plan["cutoff_fighters"]))
            L.append("| %s | %s | %s | %s | %s |" % (rel, ", ".join("#%d" % i for i in k["members"]),
                                                     "/".join(k.get("coexist_on", [])), why, " / ".join(costs)))
    if audit_full:
        L.append("\n## Found on the way (not changed by this plan)\n")
        off, named = collections.Counter(), collections.defaultdict(list)
        examples = []
        for r in audit_full["maps"]:
            if "error" in r:
                continue
            for e in r["enemies"]:
                prov = e.get("provenance")
                if prov == "r286b" and e.get("guards") and e["guards"]["tier"] == "treasure" \
                        and e["tier"] != "Common":
                    off[e["tier"]] += 1
                    if e["tier"] in ("Rare", "Mythic") and len(examples) < 10:
                        examples.append("%s #%d in %s" % (e["name"], e["id"], r["map"]))
                if prov and prov != "r256" and "scripted placement (spawnRate 0)" in e["protected"]:
                    named[r["map"]].append("%s #%d" % (e["name"], e["id"]))
        if off:
            L.append("* **%d chest guards placed by round 286b are above Apprentice** (%s) - against \"make them "
                     "Apprentice level\". Round 287 found the fallback that caused it (`map_roster` settling UP to "
                     "any rank) and fixed it for its own placements, but 286b's were already in the maps. "
                     "Examples: %s." % (sum(off.values()), ", ".join("%d %s" % (v, RANK_WORD.get(k, k))
                                                                    for k, v in sorted(off.items())),
                                        "; ".join(examples)))
        if named:
            L.append("* **Tool-placed guards carrying a unique character's name** (spawnRate 0, so never a random "
                     "spawn): %s. Several stand together as pinned booster guards (section 6) - re-rostering "
                     "them to a generic enemy of the map would fix both the duplication and the crowding." % "; ".join(
                         "%s: %s" % (k, ", ".join(v)) for k, v in sorted(named.items())))
        L.append("* **The repo's reachability tools ignore object-layer collision.** `MapStage.loadObjects()` adds "
                 "every `collision` object (collision.tx) to collisionRect, but `pixel_collision_qa.build_grid()` "
                 "rasterizes tile collision only - 125 maps carry such objects, shard_mines alone 92. This plan's "
                 "checks include them (tfrmaps' strict model).")
        L.append("* **%d maps have an entry the tools cannot seed a flood fill from** (cave_kavu's sits below the "
                 "map, zombietown's on its bottom wall, ...): every chest there audits as unguarded whatever stands "
                 "beside it." % audit_full["summary"]["totals"].get("maps_no_entry", 0))
        L.append("* templeofchandra's two Chandras (#41, #171) are difficulty variants whose `spawn.X` flags overlap "
                 "on Insane only, so an Insane game meets both side by side - probably an authoring slip.")
    L.append("\n## Vetoing a line\n")
    L.append("Every removal and patrol above is individually safe, but the hand-authored removals in particular are "
             "judgment calls. To keep an enemy exactly as it is, re-plan with `plan.py ... --exclude "
             "<map>#<id>` (repeatable): the planner then works around it instead of apply.py skipping a line whose "
             "neighbors were planned assuming it.\n")
    L.append("## For the record: removal checks that failed (these clusters fell through to a patrol)\n")
    for rel, e in maps.items():
        for n in e["notes"]:
            L.append("- %s: %s" % (rel, n))
    open(path, "w", encoding="utf-8").write("\n".join(L) + "\n")


if __name__ == "__main__":
    sys.exit(main())
