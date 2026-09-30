"""audit.py - boosters/chests behind gates, and the loot guards we added to them (READ-ONLY on the repo).

Model (verified against forge-gui-mobile/.../stage/MapStage.java at HEAD):
  * blocked pixels = tile-layer collision + `collision` objects + blocking actors;
  * blocking actors = `dialog` tile objects whose dialog shows something on a fresh visit (DialogActor
    resets the player's position; box x+4, bottom 0.4h, w-6) and `dummy` objects with blocking != false
    (DummySprite, whole rect);
  * each blocker is classed by what removes it (deleteMapObject, anywhere in the map's dialogs/defeatDialogs):
        FREE      its own dialog removes it along a path with no unmet condition (a door you just open,
                  a one-time story trigger)  -> open in every state
        GATE      removed only by a task: a key item, a quest/map flag (enemiesDefeated...), an actor gone,
                  a switch elsewhere, a boss's defeatDialog  -> closed in CLOSED, open in OPEN
        WALL      nothing ever removes it -> closed in every state
        INACTIVE  its dialog shows nothing on a fresh visit -> open
  * portals: usable only when active; a portal not authored `active` is LOCKED (opened by activateMapObject or
    a quest flag) -> locked in CLOSED, active in OPEN;
  * reachability is GLOBAL: from every POI map's world arrival (MapStage.spawn with no target: spawn=true entry,
    else the first entry/portal whose teleport is empty, else the first other one) across entries/portals into
    their target maps (teleportObjectId, else the source-map match), over 10x6 player boxes.
A reward is GATED when no reachable player box can touch it in CLOSED but one can in OPEN.

Guards: MapStage.assignLootGuards() replayed per difficulty (canSpawn), boosters first, then chests, then the
rest, nearest non-dialog enemy within 48 px (3 x the world tile) between bottom-left corners, one reward per
enemy, ties to the earlier actor. Origin of each enemy from enemy_history.json (git).
"""
import collections
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import dialogs as dg
import geom
from tmxobj import MAP_ROOT, REPO, all_maps, map_objects, rel

GUARD_ADD_ROUNDS = {
    "6c106dd0c68": "R279",   # add_booster_guards.py: 69 booster guards
    "aac4e42ad80": "R284",   # the Basilica's guard (phyrexian_w1)
    "dda3df5ac9e": "R286b",  # add_booster_guards.py --loot treasure --rank 0: chest guards
    "e0db036c457": "R287",   # add_loot_guards.py: a dedicated guard per booster/chest
}
MOVE_ROUNDS = {"386cc9b2670": "R257-258", "e785f00872a": "R269", "20ae4b3afc9": "R274-275",
               "22675316d7b": "R278", "ae591d54aa2": "R319"}
REACH = 48.0
PREFIX = "../The Forsaken Realms/maps/map/"


def norm_map(v):
    if not v:
        return ""
    v = v.replace("\\", "/")
    i = v.find("/maps/map/")
    return v[i + len("/maps/map/"):] if i >= 0 else v


def truthy(v, default=True):
    if v is None:
        return default
    return str(v).strip().lower() == "true"


class Map:
    def __init__(self, path):
        self.path = path
        self.rel = rel(path)
        self.objs = map_objects(path)
        self.byid = {o["id"]: o for o in self.objs}
        self._base = None
        self.blockers = []   # dicts
        self.portals = []
        self.teleporters = []  # entries (not noExit) + portals: things the player can touch to travel
        self.arrivals = []     # entries + portals in load order (spawn candidates)
        self.rewards = []
        self.enemies = []
        self.openers = collections.defaultdict(list)    # target id -> [(src, via, conds_ok, conds_desc)]
        self.activators = collections.defaultdict(list)
        self.summary_cache = {}
        self._analyse()

    # ------------------------------------------------------------------ objects
    def _analyse(self):
        for o in self.objs:
            for via in ("dialog", "defeatDialog"):
                s = o["props"].get(via)
                if not s or not s.strip():
                    continue
                paths, ok = dg.action_paths(s)
                for kind, tgt, conds in paths:
                    t = o["id"] if tgt == -1 else tgt
                    fresh, desc = dg.conds_fresh(conds)
                    rec = {"src": o["id"], "src_type": o["type"], "src_name": o["props"].get("enemy") or o["name"],
                           "via": via, "fresh": fresh, "conds": desc}
                    (self.openers if kind == "delete" else self.activators)[t].append(rec)
        for o in self.objs:
            t = o["type"]
            if t == "dialog" and o["is_tile"]:
                d = o["props"].get("dialog") or ""
                roots = dg.activating_roots(d)
                ops = list(self.openers.get(o["id"], []))
                self_free = any(r["src"] == o["id"] and r["via"] == "dialog" and r["fresh"] for r in ops)
                dis = [dg.root_disablers(r) for r in roots]
                own_flags = dg.free_flag_sets(d)
                note = ""
                if not roots:
                    cls = "INACTIVE"
                elif self_free:
                    cls = "FREE"
                elif all(dr and any(k == "flag" and v in own_flags for k, v in dr) for dr in dis):
                    cls = "FREE"
                    note = "one-time: its own dialog sets the flag that switches it off"
                elif ops:
                    cls = "GATE"
                elif all(dr for dr in dis):
                    # it stops showing once an actor is gone / a flag is set elsewhere: a conditional barrier
                    cls = "GATE"
                    for dr in dis:
                        for k, v in dr:
                            if k == "actor":
                                a = self.byid.get(v)
                                ops.append({"src": v, "src_type": a["type"] if a else "?",
                                            "src_name": (a["props"].get("enemy") or a["name"]) if a else "?",
                                            "via": "actorGone", "fresh": False, "conds": []})
                            else:
                                ops.append({"src": o["id"], "src_type": "dialog", "src_name": o["name"],
                                            "via": "flagSet", "fresh": False, "conds": ["%s %s" % (k, v)]})
                else:
                    cls = "WALL"
                self.blockers.append({"id": o["id"], "kind": "dialog", "obj": o, "cls": cls, "openers": ops,
                                      "box": geom.actor_box(o, character=True), "note": note})
            elif t == "dummy" and o["is_tile"]:
                blocking = truthy(o["props"].get("blocking"), True)
                ops = self.openers.get(o["id"], [])
                cls = "INACTIVE" if not blocking else ("GATE" if ops else "WALL")
                self.blockers.append({"id": o["id"], "kind": "dummy", "obj": o, "cls": cls, "openers": ops,
                                      "box": geom.actor_box(o, character=False)})
            if t in ("entry", "portal"):
                self.arrivals.append(o)
                if t == "portal":
                    st = (o["props"].get("portalState") or "").lower()
                    o["_portal_active"] = st == "active"
                    self.portals.append(o)
                    self.teleporters.append(o)
                elif not truthy(o["props"].get("noExit"), False):
                    self.teleporters.append(o)
            if t == "reward":
                r = o["props"].get("reward")
                if r and r.strip():
                    sp = o["props"].get("sprite") or "sprites/treasure.atlas"
                    kind = "booster" if "booster" in sp else ("chest" if "treasure" in sp else "other")
                    o["_kind"] = kind
                    o["_sprite"] = sp
                    self.rewards.append(o)
            if t == "enemy":
                e = o["props"].get("enemy")
                if e and e.strip():
                    self.enemies.append(o)

    def gates(self):
        return [b for b in self.blockers if b["cls"] == "GATE"]

    def base(self):
        if self._base is None:
            coll = [o for o in self.objs if o["type"] == "collision"]
            blocked, W, H, tw, th = geom.base_blocked(self.path, coll)
            for b in self.blockers:
                if b["cls"] == "WALL":
                    geom.add_rect(blocked, *b["box"])
            self._base = (blocked, W, H, tw, th)
        return self._base

    # ------------------------------------------------------------------ one geometry state
    def summary(self, closed_ids):
        """label lookups for a given set of CLOSED gate ids in this map."""
        key = frozenset(closed_ids)
        if key in self.summary_cache:
            return self.summary_cache[key]
        blocked, W, H, tw, th = self.base()
        g = blocked.copy()
        for b in self.blockers:
            if b["cls"] == "GATE" and b["id"] in key:
                geom.add_rect(g, *b["box"])
        free = geom.free_positions(g)
        lab = geom.label(free)
        s = {"touch": {}, "arrive": {}, "W": W, "H": H}
        for o in self.rewards:
            x, yb = o["x"], (o["y"] if o["is_tile"] else o["y"] + o["h"])
            s["touch"][o["id"]] = geom.labels_touching_rect(lab, x, yb - o["h"], o["w"], o["h"])
        s["near"] = {}
        for o in self.enemies:
            bx, by, bw, bh = geom.actor_box(o, character=True)
            t = geom.labels_touching_rect(lab, bx, by, bw, bh)
            s["touch"][o["id"]] = t
            if not t:  # standing on collision: what a box can reach within 20 px (pixel_collision_qa's slack)
                s["near"][o["id"]] = geom.labels_near(lab, bx, by, 20)
        for o in self.teleporters:
            x, yb = o["x"], (o["y"] if o["is_tile"] else o["y"] + o["h"])
            s["touch"][o["id"]] = geom.labels_touching_rect(lab, x, yb - o["h"], o["w"], o["h"])
        for o in self.arrivals:
            s["arrive"][o["id"]] = self._arrival_label(lab, o)
        counts = np.bincount(lab.ravel())
        need = set()
        for v in list(s["touch"].values()) + list(s["near"].values()):
            need |= set(v)
        s["area"] = {int(k): int(counts[k]) for k in need if k < len(counts)}
        self.summary_cache[key] = s
        if not self.gates():
            self._base = None  # never needed again; keep memory flat
        return s

    def _arrival_label(self, lab, o):
        x, w, h = o["x"], o["w"], o["h"]
        yb = o["y"] if o["is_tile"] else o["y"] + o["h"]
        d = (o["props"].get("direction") or "").lower()
        if d == "up":
            px, py = x + w / 2 - 4, yb - h - 6
        elif d == "down":
            px, py = x + w / 2 - 4, yb + 16 - 6
        elif d == "right":
            px, py = x - 12, yb - h / 2 + 2
        elif d == "left":
            px, py = x + w + 4, yb - h / 2 + 2
        else:
            px, py = x + w / 2 - 5, yb - h / 2 - 3
        lb = geom.nearest_label(lab, px, py, 16)
        if lb is None:
            lb = geom.nearest_label(lab, x + w / 2 - 5, yb - h / 2 - 3, 24)
        return lb

    def choose_arrival(self, source_rel, target_id):
        """the object MapStage.spawn() puts the player at, arriving from source_rel ('' = the world)."""
        if target_id and target_id > 0:
            o = self.byid.get(target_id)
            if o is not None and (o["type"] == "portal" or (o["type"] == "entry" and not truthy(o["props"].get("noExit"), False))):
                return o
        spawn_cls, src_match, other = [], [], []
        for o in self.arrivals:
            tp = norm_map(o["props"].get("teleport") or "")
            if truthy(o["props"].get("spawn"), False):
                spawn_cls.append(o)
            elif (tp == "" and source_rel == "") or (source_rel != "" and tp == source_rel):
                src_match.append(o)
            else:
                other.append(o)
        for lst in (spawn_cls, src_match, other):
            if lst:
                return lst[0]
        return None


def load_pois():
    p = os.environ.get("TFR_POI_JSON") or os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms", "world", "points_of_interest.json")
    d = json.load(open(p, encoding="utf-8"))
    out = collections.OrderedDict()
    for poi in d:
        m = norm_map(poi.get("map") or "")
        if m:
            out.setdefault(m, []).append(poi.get("name"))
    return out


class World:
    def __init__(self):
        self.maps = collections.OrderedDict()
        for p in all_maps():
            m = Map(p)
            self.maps[m.rel] = m
        self.pois = load_pois()
        self.gate_list = []  # (rel, id, kind)  kind: blocker | portal
        for m in self.maps.values():
            for b in m.gates():
                self.gate_list.append((m.rel, b["id"], "blocker"))
            for p in m.portals:
                if not p["_portal_active"]:
                    self.gate_list.append((m.rel, p["id"], "portal"))

    def reach(self, closed, active_portals):
        """closed: set of (rel, id) gate blockers closed; active_portals: set of (rel, id).
        returns {rel: set(labels)} of reachable regions, plus summaries used."""
        sums = {}

        def S(r):
            if r not in sums:
                m = self.maps[r]
                ids = {i for (rr, i) in closed if rr == r}
                sums[r] = m.summary(ids)
            return sums[r]

        visited = collections.defaultdict(set)
        queue = collections.deque()

        def push(r, lb):
            if lb is None or lb in visited[r]:
                return
            visited[r].add(lb)
            queue.append((r, lb))

        for r in self.pois:
            if r not in self.maps:
                continue
            a = self.maps[r].choose_arrival("", 0)
            if a is not None:
                push(r, S(r)["arrive"].get(a["id"]))
        # map -> teleporter objects index by label, computed lazily per map
        while queue:
            r, lb = queue.popleft()
            m = self.maps[r]
            s = S(r)
            for o in m.teleporters:
                if lb not in s["touch"].get(o["id"], ()):
                    continue
                if o["type"] == "portal" and (r, o["id"]) not in active_portals:
                    continue
                tgt = norm_map(o["props"].get("teleport") or "")
                if not tgt:
                    continue  # leaves to the world
                tid = o["props"].get("teleportObjectId")
                try:
                    tid = int(tid) if tid not in (None, "") else 0
                except ValueError:
                    tid = 0
                if tgt not in self.maps:
                    continue
                tm = self.maps[tgt]
                a = tm.choose_arrival(r if tgt != r else r, tid)
                if a is None:
                    continue
                push(tgt, S(tgt)["arrive"].get(a["id"]))
        return visited, sums


def origin_of(hist, r, oid):
    rec = hist.get(r, {}).get(str(oid))
    if not rec:
        return "UNTRACKED", "", []
    sha = rec.get("added", "")
    lab = GUARD_ADD_ROUNDS.get(sha[:11])
    moved = []
    for c in rec.get("changes", []):
        if c[0][:11] in MOVE_ROUNDS and "moved" in c[2]:
            moved.append(MOVE_ROUNDS[c[0][:11]])
    if lab:
        return "OURS", lab, moved
    if sha == "BASE":
        return "AUTHORED", "base maps", moved
    return "AUTHORED", "added by %s %s" % (sha[:11], rec.get("added_msg", "")[:40]), moved


def can_spawn(o, rank):
    key = ["spawn.Easy", "spawn.Normal", "spawn.Hard", "spawn.Insane"][rank]
    return truthy(o["props"].get(key), True)


def replay_guards(m, rank, honor_noguard=False):
    """{reward id: (enemy id, distance)} exactly as assignLootGuards() would pair them at difficulty `rank`.
    honor_noguard=True models the RECOMMENDED MapStage change (a reward with noGuard=true is never registered)."""
    loot = [o for o in m.rewards if can_spawn(o, rank)
            and not (honor_noguard and truthy(o["props"].get("noGuard"), False))]
    pri = {"booster": 2, "chest": 1, "other": 0}
    loot_sorted = sorted(loot, key=lambda o: -pri[o["_kind"]])  # Python sort is stable, like libGDX's TimSort
    mobs = [o for o in m.enemies if can_spawn(o, rank) and not (o["props"].get("dialog") or "").strip()]
    taken = set()
    out = {}
    for L in loot_sorted:
        lx, ly = L["x"], (L["y"] if L["is_tile"] else L["y"] + L["h"])
        best, bd = None, float("inf")
        for e in mobs:
            if e["id"] in taken:
                continue
            ex, ey = e["x"], (e["y"] if e["is_tile"] else e["y"] + e["h"])
            d = math.hypot(ex - lx, ey - ly)
            if d <= REACH and d < bd:
                best, bd = e, d
        if best is not None:
            taken.add(best["id"])
            out[L["id"]] = (best["id"], bd)
    return out


def describe_opener(m, b):
    outs = []
    for r in b["openers"]:
        if r["src"] == b["id"] and r["via"] == "dialog":
            outs.append("its own dialog, needs " + (" & ".join(r["conds"]) if r["conds"] else "nothing (?)"))
        else:
            src = m.byid.get(r["src"])
            nm = r["src_name"]
            if r["via"] == "defeatDialog":
                outs.append("defeating #%d %s" % (r["src"], nm))
            elif r["via"] == "actorGone":
                outs.append("#%d %s gone (stops showing)" % (r["src"], nm))
            elif r["via"] == "flagSet":
                outs.append("stops showing once %s is set" % " & ".join(r["conds"]))
            elif src is not None and src["type"] == "enemy":
                outs.append("talking to #%d %s" % (r["src"], nm))
            else:
                outs.append("%s #%d '%s'%s" % (src["type"] if src else "?", r["src"], nm,
                                              (" needing " + " & ".join(r["conds"])) if r["conds"] else ""))
    # dedupe keep order
    seen, res = set(), []
    for x in outs:
        if x not in seen:
            seen.add(x)
            res.append(x)
    return "; ".join(res)


