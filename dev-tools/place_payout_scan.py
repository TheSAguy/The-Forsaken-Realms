#!/usr/bin/env python3
"""One-off payout audit (round 347): which places that COME BACK hold a prize that should be paid only once?

Usage (from the repo root):
    python dev-tools/place_payout_scan.py ["forge-gui/res/adventure/The Forsaken Realms"]
Exit code 0 when every payout in a returning place is limited or accepted and every key gate has its key inside the
same place, 1 otherwise. Read-only. Run it after map edits, beside norotate_scan.py.

WHY THIS EXISTS. Two kinds of place come back: a rotatable dungeon/cave returns from the rotation reserve (5 copies
of each, DungeonRotation.POOL_MULTIPLIER) and a vanishing boss lair returns after its rest - and both come back
RESTOCKED (DungeonRotation.restock() forgets every deleted object). A dialog that pays and deletes its giver therefore
pays again on every return. The Sphinx's Sanctum paid 8,000 gold per rotation that way (rounds 332/334), the Demon's
Bargain dealt again (round 345), the Vampire Dungeon's captive handed out a Challenge Coin per lair cycle (round 347).
A payout counts as LIMITED when it retires its place (it sets the POI's retireOnQuestFlag) or is once per game (its
path is gated on a quest flag being unset and the payout sets that flag) - the two patterns those rounds use. A path
that also takes something (gold, shards, an item, max life) is a TRADE and may repeat. Anything else is listed as
REPAYS unless ACCEPTED below says why that is fine.

It also checks every gate that consumes an item (a removeItem action) inside a returning place: the key must be
obtainable inside the same place - a placement's reward, an enemy's own reward list, a pickup or a dialog - or a
return visit can find the gate shut for good. And it lists lairs with no boss-flagged enemy (they can never be
cleared, so they never leave - the Strange Desert) and 100% fixed item pickups in returning places, for reference.

Dialog JSON is read the way Forge reads it: libgdx's JsonReader is lenient (optional commas, raw control characters,
unquoted strings), and 17 map dialogs rely on that - a strict parser skips them silently.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PLANE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms")
COMMON = os.path.normpath(os.path.join(PLANE, "..", "common"))

# (map file name, object id) -> why a payout there may repeat on every return.
ACCEPTED = {
    ("vampirecastle_grave_2.tmx", oid): "a coffin holding 25 gold - loot that restocks like a chest"
    for oid in (86, 97, 98, 99, 100, 101, 102, 103, 104)
}

PAY_KEYS = ("addGold", "addShards", "addWood", "addStone", "addLife", "addItem", "giveBlessing", "grantRingGift",
            "addMapReputation", "addColorReputationPlayerColors", "addColorReputationAmount")


# ------------------------------------------------------------------ lenient JSON (libgdx JsonReader rules)
class _Lenient:
    def __init__(self, s):
        self.s, self.i = s, 0

    def skip(self, commas=True):
        s = self.s
        while self.i < len(s):
            c = s[self.i]
            if c in " \t\r\n" or (commas and c == ","):
                self.i += 1
            elif s.startswith("//", self.i):
                j = s.find("\n", self.i)
                self.i = len(s) if j < 0 else j + 1
            elif s.startswith("/*", self.i):
                j = s.find("*/", self.i + 2)
                self.i = len(s) if j < 0 else j + 2
            else:
                break

    def value(self):
        self.skip()
        c = self.s[self.i]
        if c == "{":
            self.i += 1
            out = {}
            while True:
                self.skip()
                if self.s[self.i] == "}":
                    self.i += 1
                    return out
                q = self.s[self.i]
                key = self.string(q) if q in "\"'" else self.bare(key=True)
                self.skip(commas=False)
                if self.s[self.i] == ":":
                    self.i += 1
                out[key] = self.value()
        if c == "[":
            self.i += 1
            out = []
            while True:
                self.skip()
                if self.s[self.i] == "]":
                    self.i += 1
                    return out
                out.append(self.value())
        if c in "\"'":
            return self.string(c)
        return self.bare()

    def string(self, q):
        self.i += 1
        s, buf = self.s, []
        while True:
            c = s[self.i]
            if c == "\\":
                n = s[self.i + 1]
                if n == "u":
                    buf.append(chr(int(s[self.i + 2:self.i + 6], 16)))
                    self.i += 6
                    continue
                buf.append({"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f"}.get(n, n))
                self.i += 2
                continue
            self.i += 1
            if c == q:
                return "".join(buf)
            buf.append(c)

    def bare(self, key=False):
        s, j = self.s, self.i
        stops = ":,{}[]\r\n" if key else ",{}[]\r\n"
        while j < len(s) and s[j] not in stops:
            j += 1
        tok, self.i = s[self.i:j].strip(), j
        if key:
            return tok
        if tok in ("true", "false", "null"):
            return {"true": True, "false": False, "null": None}[tok]
        for cast in (int, float):
            try:
                return cast(tok)
            except ValueError:
                pass
        return tok


def read_json(text):
    try:
        return json.loads(text, strict=False)
    except ValueError:
        return _Lenient(text).value()


# ------------------------------------------------------------------ maps
def resolve(value):
    """POI and teleport map paths are written relative to the plane or the common folder."""
    if not value:
        return None
    v = value.replace("\\", "/")
    for base in (PLANE, COMMON):
        cand = os.path.normpath(os.path.join(base, v))
        if os.path.isfile(cand):
            return cand
    return None


def read_props(elem):
    out = {}
    props = elem.find("properties")
    if props is not None:
        for p in props.findall("property"):
            out[p.get("name")] = p.get("value") if p.get("value") is not None else (p.text or "")
    return out


_templates = {}


def template(path):
    path = os.path.normpath(path)
    if path not in _templates:
        t = {"type": None, "props": {}}
        try:
            obj = ET.parse(path).getroot().find("object")
            if obj is not None:
                t = {"type": obj.get("type") or obj.get("class"), "props": read_props(obj)}
        except (OSError, ET.ParseError):
            pass
        _templates[path] = t
    return _templates[path]


def objects(tmx):
    """[(id, type, props)] with object templates merged in."""
    out = []
    for group in ET.parse(tmx).getroot().iter("objectgroup"):
        for o in group.findall("object"):
            t = template(os.path.join(os.path.dirname(tmx), o.get("template"))) if o.get("template") else \
                {"type": None, "props": {}}
            props = dict(t["props"])
            props.update(read_props(o))
            out.append((int(o.get("id")), o.get("type") or o.get("class") or t["type"], props))
    return out


def level_tree(tmx):
    """The place's first map plus every map an 'entry' or 'portal' teleport reaches from it."""
    seen, order, todo = set(), [], [tmx]
    while todo:
        m = os.path.normpath(todo.pop(0))
        if m in seen:
            continue
        seen.add(m)
        order.append(m)
        for _, _, props in objects(m):
            target = resolve(props.get("teleport"))
            if target:
                todo.append(target)
    return order


# ------------------------------------------------------------------ the rotation rules (DungeonRotation)
def returns(poi):
    """'rotates', 'lair' or None - DungeonRotation.notRotatableReason() / lairStaysReason() in data form."""
    t = (poi.get("type") or "").lower()
    name = poi.get("name") or ""
    tags = [x for x in (poi.get("questTags") or []) if x]
    if name.startswith("Quest_") or name in ("DEBUGZONE", "Test"):
        return None
    if any(x == "Story" or x.startswith("Quest_") or x == "NoRotate" for x in tags) or "Hostile" not in tags:
        return None
    if t in ("dungeon", "cave"):
        return "rotates"
    return "lair" if t.startswith("sideboss") else None


# ------------------------------------------------------------------ dialogs
def dialog_paths(nodes, conds, out):
    """Every dialog node with actions: (path conditions, merged actions of the node)."""
    for n in nodes or []:
        if not isinstance(n, dict):
            continue
        here = list(conds) + [c for c in (n.get("condition") or []) if isinstance(c, dict)]
        merged = {}
        for a in n.get("action") or []:
            if isinstance(a, dict):
                for k, v in a.items():
                    merged.setdefault(k, []).append(v)
        if merged:
            out.append((here, merged, (n.get("name") or n.get("text") or "")[:60]))
        dialog_paths(n.get("options"), here, out)


def payouts(merged):
    pays, costs = [], []
    for k in PAY_KEYS:
        for v in merged.get(k, []):
            if v in (None, 0, "", False):
                continue
            (costs if isinstance(v, (int, float)) and v < 0 else pays).append("%s %s" % (k, v))
    for k in ("grantRewards", "grantRewardsChoice"):
        for lst in merged.get(k, []):
            for r in lst or []:
                if isinstance(r, dict):
                    neg = r.get("type") == "life" and isinstance(r.get("count"), int) and r["count"] < 0
                    (costs if neg else pays).append("%s %s" % (r.get("type"), r.get("itemName") or r.get("cardName")
                                                               or r.get("count", "")))
    costs += ["removeItem %s" % v for v in merged.get("removeItem", []) if v]
    return pays, costs


def flags_set(merged):
    out = set()
    for v in merged.get("setQuestFlag", []):
        if isinstance(v, dict) and v.get("key") and v.get("val", 1):
            out.add(v["key"])
    out.update(v for v in merged.get("advanceQuestFlag", []) if isinstance(v, str))
    return out


def flags_required_unset(conds):
    out = set()
    for c in conds:
        if c.get("checkQuestFlag") and c.get("not"):
            out.add(c["checkQuestFlag"])
        q = c.get("getQuestFlag")
        if isinstance(q, dict) and q.get("key") and ((q.get("op") in ("=", "==") and q.get("val") == 0) or
                                                     (q.get("op") == "<" and q.get("val") == 1)):
            out.add(q["key"])
    return out


def main():
    world = os.path.join(PLANE, "world")
    pois = json.load(open(os.path.join(world, "points_of_interest.json"), encoding="utf-8"))
    enemies = {e["name"]: e for e in json.load(open(os.path.join(world, "enemies.json"), encoding="utf-8"))}
    items = {i["name"]: i for i in json.load(open(os.path.join(world, "items.json"), encoding="utf-8"))}
    problems = 0
    repays, trades, limited, gates, bossless, fixed_pickups = [], [], [], [], [], []
    for poi in pois:
        kind = returns(poi)
        first = resolve(poi.get("map"))
        if kind is None or first is None:
            continue
        label = '%s "%s" (%s)' % (poi["name"], poi.get("displayName"), kind)
        tree = level_tree(first)
        key_sources, key_gates, has_boss = set(), [], False
        for tmx in tree:
            base = os.path.basename(tmx)
            for oid, otype, props in objects(tmx):
                if otype == "enemy":
                    data = enemies.get(props.get("enemy"), {})
                    has_boss |= bool(data.get("boss"))
                    for r in data.get("rewards") or []:
                        if r.get("type") == "item" and r.get("itemName"):
                            key_sources.add(r["itemName"])
                if props.get("reward"):
                    try:
                        rewards = read_json(props["reward"])
                    except (ValueError, IndexError):
                        rewards = []
                    for r in rewards if isinstance(rewards, list) else []:
                        if isinstance(r, dict) and r.get("type") == "item" and r.get("itemName"):
                            key_sources.add(r["itemName"])
                            it = items.get(r["itemName"], {})
                            if otype == "reward" and r.get("probability", 1) >= 1 and not it.get("questItem"):
                                fixed_pickups.append("%s  %s obj %d: %s (cost %s)" % (label, base, oid, r["itemName"],
                                                                                     it.get("cost")))
                for key in ("dialog", "defeatDialog"):
                    if not props.get(key):
                        continue
                    try:
                        data = read_json(props[key])
                    except (ValueError, IndexError):
                        print("UNREADABLE dialog: %s obj %d" % (base, oid))
                        problems += 1
                        continue
                    paths = []
                    dialog_paths(data if isinstance(data, list) else [data], [], paths)
                    for conds, merged, where in paths:
                        for v in merged.get("addItem", []):
                            if v:
                                key_sources.add(v)
                        for r in [r for lst in merged.get("grantRewards", []) for r in (lst or [])]:
                            if isinstance(r, dict) and r.get("itemName"):
                                key_sources.add(r["itemName"])
                        for v in merged.get("removeItem", []):
                            if v:
                                key_gates.append((base, oid, v))
                        pays, costs = payouts(merged)
                        if not pays:
                            continue
                        line = "%s  %s obj %d [%s]: %s" % (label, base, oid, where, "; ".join(pays)[:120])
                        sets = flags_set(merged)
                        if poi.get("retireOnQuestFlag") and poi["retireOnQuestFlag"] in sets:
                            limited.append(line + "  -> retires the place (%s)" % poi["retireOnQuestFlag"])
                        elif sets & flags_required_unset(conds):
                            limited.append(line + "  -> once per game (%s)" % ", ".join(sorted(sets & flags_required_unset(conds))))
                        elif costs:
                            trades.append(line + "  for " + "; ".join(costs)[:80])
                        elif (base, oid) in ACCEPTED:
                            limited.append(line + "  -> accepted: " + ACCEPTED[(base, oid)])
                        else:
                            repays.append(line)
        for base, oid, item in key_gates:
            if item not in key_sources:
                gates.append("%s  %s obj %d needs %s - NOT obtainable inside this place" % (label, base, oid, item))
        if kind == "lair" and not has_boss:
            bossless.append("%s - no boss-flagged enemy in its %d level(s): it can never be cleared, so it never "
                            "leaves the map" % (label, len(tree)))

    def section(title, rows):
        print("\n== %s (%d)" % (title, len(rows)))
        for r in rows:
            print("  " + r)

    section("REPAYS on every return - make it once per game, retire the place, or accept it", repays)
    section("limited or accepted", limited)
    section("trades (the player pays for it - may repeat)", trades)
    section("key gates whose key is not inside the place", gates)
    section("lairs that never leave (no boss)", bossless)
    section("100% fixed item pickups in returning places (restock with the place - for reference)", fixed_pickups)
    problems += len(repays) + len(gates)
    print("\n%s" % ("OK" if problems == 0 else "%d problem(s)" % problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
