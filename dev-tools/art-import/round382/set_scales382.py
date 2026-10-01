"""set_scales382.py - the sizes of the round-382 enemies ONLY (enemy_scale.py --write would also re-measure everyone
else; the coordinator asked for the new entries alone).

  ordinary entries   enemy_scale.py's own rule, its own code (read-only import): scale = hero body / the sprite's
                     body box (the trimmed opaque box of the first Idle/Walk frames, POSE_CAP), so the rank cue alone
                     makes the size: Apprentice 13 / Adept 16 / Master 20 / Archmage 24 on the 16-px scale
  the 12 legends     they are bosses (import382.py), and a boss keeps a hand-set size between enemy_scale.py's
                     BOSS_MIN (30) and BOSS_MAX (48). Set here to 30 for an Adept, 32 for a Master, 36 for an
                     Archmage - the 25 roaming champions draw at 30 to 36 - so a later enemy_scale.py --write keeps them.
usage: python set_scales382.py <repo root> [--write]   (a dry run without --write)"""
import json, os, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from roster382 import ROSTER, LEGENDS

args = [a for a in sys.argv[1:] if not a.startswith("--")]
if len(args) != 1:
    raise SystemExit(__doc__)
ROOT = os.path.abspath(args[0])
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))     # this repo's dev-tools: enemy_scale, sprite_sizes
import enemy_scale as es
import sprite_sizes as ss

# enemy_scale resolves sprites under ITS repo's plane; point both modules at this root's plane
ss.PLANE = os.path.join(ROOT, "forge-gui", "res", "adventure", "The Forsaken Realms")
ss.COMMON = os.path.join(ROOT, "forge-gui", "res", "adventure", "common")
if not os.path.isdir(ss.COMMON):        # a test copy of the plane: the stock heroes from this script's own repo
    ss.COMMON = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(HERE))), "forge-gui", "res", "adventure",
                             "common")
BOSS_BODY = {"D": 30, "M": 32, "X": 36}
RANK = {"Common": "A", "Uncommon": "D", "Rare": "M", "Mythic": "X"}

p = os.path.join(ss.PLANE, "world", "enemies.json")
raw = open(p, "rb").read()
data = json.loads(raw.decode("utf-8"))
if json.dumps(data, indent=4, ensure_ascii=False).encode("utf-8") + b"\n" != raw:
    raise SystemExit("enemies.json does not round-trip through json.dumps(indent=4) - refusing to rewrite it")
names = {r[1]: r[0] for r in ROSTER}
hero, n = es.hero_body()
cache = {}
changed = 0
for e in data:
    slug = names.get(e.get("name"))
    if slug is None or not e.get("sprite", "").startswith("sprites/enemy/tfr2/"):
        continue
    base = es.proposed(e, hero, cache)                   # hero / body: the scale that draws the body at 16
    if base is None:
        raise SystemExit("unresolved sprite for %s: %s" % (e["name"], e["sprite"]))
    if slug in LEGENDS:
        cue = es.CUE[e["tier"]]
        new = round(base * BOSS_BODY[RANK[e["tier"]]] / 16.0 / cue, 4)
        drawn = new / base * 16 * cue
        assert es.BOSS_MIN - 0.05 <= drawn <= es.BOSS_MAX + 0.05, (e["name"], drawn)
    else:
        new = base
        drawn = 16 * es.CUE[e["tier"]]
    old = e.get("scale")
    if old != new:
        e["scale"] = new
        changed += 1
    print("%-30s %-8s %-3s scale %.4f  body drawn %.1f px%s" % (e["name"], e["tier"], e.get("colors"), new, drawn,
                                                                "  (boss)" if slug in LEGENDS else ""))
print("hero body %.1f px (%d hero sprites); %d of %d new entries changed" % (hero, n, changed, len(names)))
if "--write" not in sys.argv:
    print("dry run - pass --write to change enemies.json")
    sys.exit(0)
out = json.dumps(data, indent=4, ensure_ascii=False).encode("utf-8") + b"\n"
open(p, "wb").write(out)
print("wrote", p)
