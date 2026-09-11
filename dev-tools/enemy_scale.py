#!/usr/bin/env python
"""
enemy_scale.py - one size per tier (round 178, user decision 2026-09-11). Supersedes round 160's size classes.

THE RULE: every non-boss enemy's BODY is drawn the same size as the hero's body, and the tier cue alone makes it
smaller or bigger - Apprentice 13 / Adept 16 / Master 20 / Archmage 24 (settings.json enemyTierScale* = 13/16, 1,
20/16, 24/16, applied straight by TuningData.tierSizeMultiplier). What a creature IS no longer sets its size: a rat
and a dragon of the same tier are the same size, so the size tells the player the tier.

THE BODY BOX: a frame's opaque pixels, with the outer TRIM (10%) of them cut from each side of each axis before
boxing - a thin sword, tail, staff or a stray shadow pixel does not set the size (the user's Aegis Paladin case), and
a tall narrow sprite is measured by its height (the Wandering Giant case). Its larger side is the body size, taken as
the LARGEST over the first four Idle and Walk frames - an Idle that is only a crocodile's eyes above the water must
not blow the whole sprite up four times.

    scale = hero body size / enemy body size          (EnemyData.scale in world/enemies.json)

The hero body size is the median over the hero sprites, measured the same way. Bosses and "keepSize" entries (the
hand-placed set pieces - an Eldrazi Prison's titan, a lair's legend) are never touched: they keep their hand-set sizes
(round 178 migrated them once so the straight cue left them where they were).

Usage (from the repo root):
    python dev-tools/enemy_scale.py                  dry run: summary + the biggest changes
    python dev-tools/enemy_scale.py --write          write the scales into world/enemies.json
    python dev-tools/enemy_scale.py --check x.atlas  the body box and scale of one atlas (for new art)
"""
import argparse, collections, json, os, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sprite_sizes as ss  # parse_atlas / idle_frame / resolve / PLANE / COMMON
from PIL import Image

TRIM = 0.10
_pages = {}


def idle_image(atlas_path):
    page, regions = ss.parse_atlas(atlas_path)
    frame = ss.idle_frame(regions)
    if frame is None or page is None:
        return None
    png = os.path.join(os.path.dirname(atlas_path), page)
    if png not in _pages:
        _pages[png] = Image.open(png).convert("RGBA")
    x, y, w, h = frame
    return _pages[png].crop((x, y, x + w, y + h))


def pose_images(atlas_path, per_anim=4):
    """The first few Idle and Walk frames (any direction suffix), else the Idle-frame fallback of idle_image()."""
    page, regions = ss.parse_atlas(atlas_path)
    if page is None:
        return []
    png = os.path.join(os.path.dirname(atlas_path), page)
    if png not in _pages:
        _pages[png] = Image.open(png).convert("RGBA")
    out = []
    for name, frames in regions.items():
        if name.startswith("Idle") or name.startswith("Walk"):
            for (x, y, w, h) in frames[:per_anim]:
                out.append(_pages[png].crop((x, y, x + w, y + h)))
    if not out:
        img = idle_image(atlas_path)
        out = [img] if img else []
    return out


def sprite_body(atlas_path):
    sides = [s for s in (body_side(img) for img in pose_images(atlas_path)) if s]
    return max(sides) if sides else None


def body_side(img, trim=TRIM):
    """Larger side of the trimmed body box, in atlas pixels; None for an empty frame."""
    a = img.getchannel("A").point(lambda v: 1 if v >= 32 else 0)
    w, h = a.size
    px = a.load()
    cols = [sum(px[x, y] for y in range(h)) for x in range(w)]
    rows = [sum(px[x, y] for x in range(w)) for y in range(h)]
    total = sum(cols)
    if total == 0:
        return None

    def span(v):
        lo_t, hi_t = total * trim, total * (1 - trim)
        c, lo, hi = 0, 0, len(v) - 1
        for i, s in enumerate(v):
            c += s
            if c > lo_t:
                lo = i
                break
        c = 0
        for i, s in enumerate(v):
            c += s
            if c >= hi_t:
                hi = i
                break
        return hi + 1 - lo

    return max(span(cols), span(rows))


def hero_body():
    sizes = []
    for base in (os.path.join(ss.COMMON, "sprites", "heroes"), os.path.join(ss.PLANE, "sprites", "enemy", "heroes")):
        for root, _, files in os.walk(base):
            for f in files:
                if f.endswith(".atlas"):
                    try:
                        s = sprite_body(os.path.join(root, f))
                        if s:
                            sizes.append(s)
                    except Exception:
                        pass
    return statistics.median(sizes), len(sizes)


def proposed(e, hero, cache):
    sp = e.get("sprite")
    if not sp:
        return None
    if sp not in cache:
        p, _ = ss.resolve(sp)
        side = None
        if p:
            try:
                side = sprite_body(p)
            except Exception:
                side = None
        cache[sp] = side
    side = cache[sp]
    if not side:
        return None
    return round(min(8.0, max(0.05, hero / side)), 4)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--check")
    a = ap.parse_args()
    hero, n = hero_body()
    if a.check:
        img = idle_image(a.check)
        side = sprite_body(a.check)
        print("%s: Idle %dx%d, body %s px; hero body %.1f px (%d heroes) -> scale %.4f" % (
            os.path.basename(a.check), img.size[0], img.size[1], side, hero, n, hero / side))
        return
    p = os.path.join(ss.PLANE, "world", "enemies.json")
    raw = open(p, "rb").read()
    data = json.loads(raw.decode("utf-8"))
    if json.dumps(data, indent=4, ensure_ascii=False).encode("utf-8") + b"\n" != raw:
        raise SystemExit("enemies.json does not round-trip through json.dumps(indent=4) - refusing to rewrite it")
    cache, changed, unresolved, ratios = {}, 0, [], []
    for e in data:
        if e.get("boss") or e.get("keepSize"):
            continue
        new = proposed(e, hero, cache)
        if new is None:
            unresolved.append(e["name"])
            continue
        old = float(e.get("scale", 1.0))
        if abs(new - old) >= 0.0005:
            ratios.append((new / old if old else 99, e["name"], old, new))
            e["scale"] = new
            changed += 1
    print("hero body %.1f px (median of %d hero sprites, trim %d%%)" % (hero, n, TRIM * 100))
    print("non-boss enemies rescaled: %d; unresolved sprites: %d %s" % (changed, len(unresolved), unresolved[:8]))
    ratios.sort()
    print("biggest shrinks:", ["%s %.2f->%.2f" % (nm, o, nw) for _, nm, o, nw in ratios[:8]])
    print("biggest growths:", ["%s %.2f->%.2f" % (nm, o, nw) for _, nm, o, nw in ratios[-8:]])
    if not a.write:
        print("dry run - pass --write to change enemies.json")
        return
    out = json.dumps(data, indent=4, ensure_ascii=False).encode("utf-8") + b"\n"
    open(p, "wb").write(out)
    print("wrote", p, len(out), "bytes")


if __name__ == "__main__":
    main()
