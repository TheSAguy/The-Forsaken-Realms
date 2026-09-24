"""Race data checks the plane validator does not make today: heroes.json <-> raceEditions <-> raceShops agree, hero
atlases / portraits resolve (a missing portrait region is an ArithmeticException in HeroListData.getAvatar()), editions
are real + Draft-booster capable + unrestricted, race shops are offered by the shop chooser.

    python race_checks.py <plane dir> [--common DIR] [--editions DIR] [--maps DIR]

Defaults: common = <plane>/../common, editions = <plane>/../../editions, maps = <plane>/maps. Files resolve the way
Config.getFile() does: plane first, then common. Exit code 1 on any problem.
"""
import json
import os
import re
import sys
from collections import Counter

if len(sys.argv) < 2:
    raise SystemExit(__doc__)
PLANE = sys.argv[1]


def opt(name, default):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


COMMON = opt("--common", os.path.normpath(os.path.join(PLANE, "..", "common")))
EDITIONS = opt("--editions", os.path.normpath(os.path.join(PLANE, "..", "..", "editions")))
MAPS = opt("--maps", os.path.join(PLANE, "maps"))
problems = []


def bad(msg):
    problems.append(msg)


def resolve(p):
    for base in (PLANE, COMMON):
        f = os.path.join(base, p.replace("\\", "/"))
        if os.path.isfile(f):
            return f
    return None


def load(path):
    t = open(path, encoding="utf-8-sig").read()
    return json.loads(re.sub(r"^\s*//.*$", "", t, flags=re.M))


def regions(atlas_rel):
    f = resolve(atlas_rel) if atlas_rel else None
    if not f:
        return None
    names, first = Counter(), True
    for ln in open(f, encoding="utf-8-sig").read().splitlines():
        if not ln.strip():
            first = True
            continue
        if ln.startswith((" ", "\t")) or ":" in ln:
            continue
        if first and ln.strip().lower().endswith(".png"):
            if not os.path.isfile(os.path.join(os.path.dirname(f), ln.strip())):
                bad("%s: page image %s missing" % (atlas_rel, ln.strip()))
            first = False
            continue
        first = False
        names[ln.strip()] += 1
    return names


heroes_file = resolve("world/heroes.json")
cfg = load(os.path.join(PLANE, "config.json"))
hj = load(heroes_file)
print("heroes.json resolved to", heroes_file)
race_names = [h.get("name") for h in hj.get("heroes") or []]
av = regions(hj.get("avatar"))
if av is None:
    bad("avatar atlas %s not found" % hj.get("avatar"))
for i, h in enumerate(hj.get("heroes") or []):
    w = "heroes.json[%d] %s" % (i, h.get("name"))
    for k in ("female", "male"):
        r = regions(h.get(k))
        if r is None:
            bad("%s: %s atlas %s not found" % (w, k, h.get(k)))
        elif not any(n.startswith("Idle") for n in r) or not any(n.startswith("Walk") for n in r):
            bad("%s: %s atlas has no Idle*/Walk* regions" % (w, k))
    for k in ("femaleAvatar", "maleAvatar"):
        if av is not None and not av.get(h.get(k)):
            bad("%s: portrait region %s not in %s (crashes the New Game screen)" % (w, h.get(k), hj.get("avatar")))
for n, c in Counter(race_names).items():
    if c > 1:
        bad("heroes.json: race %s listed %d times" % (n, c))
ed_keys = [r.get("race") for r in cfg.get("raceEditions") or []]
shop_keys = [r.get("race") for r in cfg.get("raceShops") or []]
for n in race_names:
    if n not in ed_keys:
        bad("race %s has no raceEditions entry (it would silently start on starterEditions)" % n)
    if n not in shop_keys:
        bad("race %s has no raceShops entry" % n)
for k in set(ed_keys) | set(shop_keys):
    if k not in race_names:
        bad("config.json race key %r matches no heroes.json race (keys are compared case-insensitively in Java)" % k)

booster, known = {}, set()
for f in os.listdir(EDITIONS):
    meta = open(os.path.join(EDITIONS, f), encoding="utf-8", errors="ignore").read().split("[cards]")[0]
    m = re.search(r"^Code=(\S+)", meta, re.M)
    if m:
        known.add(m.group(1))
        booster[m.group(1)] = bool(re.search(r"^(Booster|DraftBooster)=", meta, re.M))
blocked = set(cfg.get("restrictedEditions") or []) | set(cfg.get("restrictedEvents") or [])
for r in cfg.get("raceEditions") or []:
    eds = r.get("editions") or []
    if len(eds) != 4:
        bad("raceEditions %s: %d editions (the convention and the Easy=4 seed expect 4)" % (r.get("race"), len(eds)))
    for code in eds:
        if code not in known:
            bad("raceEditions %s: %s is not an edition code" % (r.get("race"), code))
        elif not booster[code]:
            bad("raceEditions %s: %s has no Draft booster template" % (r.get("race"), code))
        if code in blocked:
            bad("raceEditions %s: %s is in restrictedEditions/restrictedEvents" % (r.get("race"), code))

visible = set()
for dp, dn, fn in os.walk(MAPS):
    for f in fn:
        if f.endswith(".tmx"):
            t = open(os.path.join(dp, f), encoding="utf-8", errors="ignore").read()
            for m in re.finditer(r'name="(?:common|uncommon|rare)ShopList"\s+value="([^"]*)"', t):
                visible.update(x for x in re.split(r"[,\s]+", m.group(1)) if x)
shop_names = {s.get("name") for s in load(resolve("world/shops.json"))}
for r in cfg.get("raceShops") or []:
    for s in r.get("shops") or []:
        if s not in shop_names:
            bad("raceShops %s: %s is not in shops.json" % (r.get("race"), s))
        elif s not in visible:
            bad("raceShops %s: %s is offered by no common/uncommon/rareShopList (the chooser would never show it)"
                % (r.get("race"), s))

print("%d races checked, %d raceEditions, %d raceShops, %d chooser-visible shop names" % (
    len(race_names), len(ed_keys), len(shop_keys), len(visible)))
for p in problems:
    print("PROBLEM:", p)
print("OK" if not problems else "%d problem(s)" % len(problems))
sys.exit(1 if problems else 0)
