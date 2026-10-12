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
not blow the whole sprite up four times - but at most POSE_CAP (1.2) times the typical frame (round 179: a raised
scythe or a wing flap in one or two frames made a Master draw like an Apprentice).

    scale = hero body size / enemy body size          (EnemyData.scale in world/enemies.json)

The hero body size is the median over the hero sprites, measured the same way. Bosses and "keepSize" entries (the
hand-placed set pieces - an Eldrazi Prison's titan, a lair's legend) keep their hand-set sizes (round 178 migrated them
once so the straight cue left them where they were) - with one floor (user: "Boss enemies should remain larger than
Archmage"): their body never draws under BOSS_MIN = 30 on the 16-px scale, so a boss is always bigger than an
Archmage (24). A smaller one is raised to exactly 30; a bigger one is left alone.

Usage (from the repo root):
    python dev-tools/enemy_scale.py                  dry run: summary + the biggest changes
    python dev-tools/enemy_scale.py --write          write the scales into world/enemies.json
    python dev-tools/enemy_scale.py --check x.atlas  the body box and scale of one atlas (for new art)

ROUND 581 (the user: "Implement one size per tier"): pieces apart from the body under PIECE_MIN of the opaque pixels are
left out of the box, and a creature wider than tall gets the hero's AREA when that is bigger (LONG_CAP on its longest
side) - see LONG_CAP below.
"""
import argparse, collections, json, os, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sprite_sizes as ss  # parse_atlas / idle_frame / resolve / PLANE / COMMON
from PIL import Image

TRIM = 0.10
# round 179: one outlying pose - a raised scythe, a wing flap, a lunge - sets the size only up to 20% over the sprite's
# typical frame (the median of the measured frames); past that the whole sprite drew smaller than its rank. Changes
# 23 of the 1,685 round-178 sizes (the flappers: Bat, Vulture, Aclazotz...) and 22 of the 197 new ones.
POSE_CAP = 1.2
# settings.json enemyTierScale* (the straight rank cue) - for the boss floor below
CUE = {"Common": 13 / 16, "Uncommon": 1.0, "Rare": 20 / 16, "Mythic": 24 / 16}
# bosses and set pieces: the body never draws under 30 on the 16-px scale - always bigger than an Archmage (24)
BOSS_MIN = 30
# round 336 (armadi on Discord: Zhulodok is gigantic on the map, like 25 percent of the screen): and never OVER 48 - three
# tiles, twice an Archmage. A titan set piece drew at 145, a roaming legend at 82; a boss reads as a boss at 48.
BOSS_MAX = 48
# round 581 (the user, after a Master Manananggal drew smaller than the hero: "Implement one size per tier"): the larger
# side gave a long or flat creature - a crocodile, a wyrm, spread wings - its tier's size along that side only, so it
# drew as a sliver. A creature WIDER than it is tall now gets the hero's AREA (the square root of the trimmed box's
# width x height, the hero measured the same way) when that is bigger, with LONG_CAP holding its longest side to 1.6
# times the hero's (a crocodile stays a crocodile, not a three-tile log). Upright creatures keep the larger side (their
# height): measured by area a slim LPC figure would outgrow the chunky hero sprites. Nothing shrinks. Bosses and set
# pieces keep the larger-side measure for their floor and ceiling.
LONG_CAP = 1.6
# round 581: a piece of the frame apart from the body counts only if it holds this share of the opaque pixels
PIECE_MIN = 0.15
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
    """(kind, image) for the first few Idle and Walk frames (any direction suffix), kind "Idle" or "Walk"; else the
    Idle-frame fallback of idle_image()."""
    page, regions = ss.parse_atlas(atlas_path)
    if page is None:
        return []
    png = os.path.join(os.path.dirname(atlas_path), page)
    if png not in _pages:
        _pages[png] = Image.open(png).convert("RGBA")
    out = []
    # round 415: a generated remake has a plain side view (Idle, Walk; the game mirrors it for Left) plus views from
    # behind and from the front (IdleUp, WalkDown, ...). Its size is the side view's: an owl flying at the viewer with
    # spread wings, or a spider's splayed legs seen from the front, would otherwise shrink the side view by up to half.
    # Hand-drawn four-direction sets (IdleRight, IdleLeft, IdleUp, ...) keep the every-direction measure.
    plain_side = any(n in ("Idle", "Walk") for n in regions)
    for name, frames in regions.items():
        if name.startswith("Idle") or name.startswith("Walk"):
            if plain_side and name not in ("Idle", "Walk"):
                continue
            for (x, y, w, h) in frames[:per_anim]:
                out.append((name[:4], _pages[png].crop((x, y, x + w, y + h))))
    if not out:
        img = idle_image(atlas_path)
        out = [("Idle", img)] if img else []
    return out


def sprite_body(atlas_path, measure="side"):
    """The largest pose, capped at POSE_CAP times the typical one (see POSE_CAP). measure "side" = the trimmed box's
    larger side, "area" = the square root of its width x height (round 581); "aspect" = the median width / height."""
    idle, walk = [], []
    for kind, img in pose_images(atlas_path):
        box = body_box(img)
        if box:
            s = max(box) if measure == "side" else box[0] / box[1] if measure == "aspect" else (box[0] * box[1]) ** 0.5
            (idle if kind == "Idle" else walk).append(s)
    sides = idle + walk
    if not sides:
        return None
    if measure == "aspect":
        return statistics.median(sides)
    if idle and walk and statistics.median(idle) < 0.6 * statistics.median(walk):
        typical = statistics.median(walk)        # the crocodile: an Idle that is only eyes above the water
    else:
        typical = statistics.median(sides)
    return min(max(sides), POSE_CAP * typical)


def main_pieces(px, w, h):
    """The opaque pixels of the pieces holding at least PIECE_MIN of them, or None when nothing is dropped."""
    seen, pieces, total = set(), [], 0
    for y0 in range(h):
        for x0 in range(w):
            if not px[x0, y0] or (x0, y0) in seen:
                continue
            stack, piece = [(x0, y0)], []
            seen.add((x0, y0))
            while stack:
                x, y = stack.pop()
                piece.append((x, y))
                for dx in (-1, 0, 1):
                    for dy in (-1, 0, 1):
                        nx, ny = x + dx, y + dy
                        if 0 <= nx < w and 0 <= ny < h and px[nx, ny] and (nx, ny) not in seen:
                            seen.add((nx, ny))
                            stack.append((nx, ny))
            pieces.append(piece)
            total += len(piece)
    big = [p for p in pieces if len(p) >= PIECE_MIN * total]
    if len(big) == len(pieces) or not big:
        return None
    return [xy for p in big for xy in p]


def body_box(img, trim=TRIM):
    """(width, height) of the trimmed body box, in atlas pixels; None for an empty frame. Round 581: pieces of the frame
    that do not touch the body - a creature's dropped legs, a baked shadow, a floating spark - are left out when they
    hold under PIECE_MIN of its opaque pixels (8-connected)."""
    a = img.getchannel("A").point(lambda v: 1 if v >= 32 else 0)
    w, h = a.size
    px = a.load()
    keep = main_pieces(px, w, h)
    if keep is not None:
        a = Image.new("L", (w, h), 0)
        q = a.load()
        for x, y in keep:
            q[x, y] = 1
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

    return span(cols), span(rows)


def body_side(img, trim=TRIM):
    """Larger side of the trimmed body box, in atlas pixels; None for an empty frame."""
    box = body_box(img, trim)
    return max(box) if box else None


def hero_body(measure="side"):
    sizes = []
    for base in (os.path.join(ss.COMMON, "sprites", "heroes"), os.path.join(ss.PLANE, "sprites", "enemy", "heroes")):
        for root, _, files in os.walk(base):
            for f in files:
                if f.endswith(".atlas"):
                    try:
                        s = sprite_body(os.path.join(root, f), measure)
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


def proposed_area(e, heroes, cache):
    """Round 581: the larger-side scale; for a creature wider than tall, the hero's area when bigger (longest side held
    to LONG_CAP x the hero's)."""
    sp = e.get("sprite")
    if not sp:
        return None
    key = ("area", sp)
    if key not in cache:
        p, _ = ss.resolve(sp)
        val = None
        if p:
            try:
                val = (sprite_body(p, "area"), sprite_body(p, "side"), sprite_body(p, "aspect"))
            except Exception:
                val = None
        cache[key] = val
    val = cache[key]
    if not val or not val[0] or not val[1]:
        return None
    (hero_area, hero_side), (area, side, aspect) = heroes, val
    scale = hero_side / side                                    # round 178's rule: the larger side
    if aspect > 1.0:                                            # wider than tall: the hero's area, long side capped
        scale = max(scale, min(hero_area / area, LONG_CAP * hero_side / side))
    return round(min(8.0, max(0.05, scale)), 4)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--check")
    a = ap.parse_args()
    hero, n = hero_body()
    hero_area, _ = hero_body("area")
    if a.check:
        img = idle_image(a.check)
        side, area, aspect = sprite_body(a.check), sprite_body(a.check, "area"), sprite_body(a.check, "aspect")
        scale = hero / side
        if aspect > 1.0:
            scale = max(scale, min(hero_area / area, LONG_CAP * hero / side))
        print("%s: Idle %dx%d, body %s px side / %.1f px area; hero %.1f / %.1f px (%d heroes) -> scale %.4f" % (
            os.path.basename(a.check), img.size[0], img.size[1], side, area, hero, hero_area, n, scale))
        return
    p = os.path.join(ss.PLANE, "world", "enemies.json")
    raw = open(p, "rb").read()
    data = json.loads(raw.decode("utf-8"))
    if json.dumps(data, indent=4, ensure_ascii=False).encode("utf-8") + b"\n" != raw:
        raise SystemExit("enemies.json does not round-trip through json.dumps(indent=4) - refusing to rewrite it")
    cache, changed, unresolved, ratios, raised, lowered = {}, 0, [], [], [], []
    for e in data:
        if e.get("boss") or e.get("keepSize"):
            # user, 2026-09-11: "Boss enemies should remain larger than Archmage". A fixed-size entry keeps its
            # hand-set scale unless its body would draw under BOSS_MIN - then it is raised to exactly that.
            base = proposed(e, hero, cache)            # hero / body side, the scale that draws the body at 16
            if base is None:
                continue
            cue = CUE.get(e.get("tier", "Common"), 1.0)
            old = float(e.get("scale", 1.0))
            drawn = old / base * 16 * cue              # the body's drawn size on the 16-px scale
            if drawn < BOSS_MIN - 0.05:
                new = round(base * BOSS_MIN / 16 / cue, 4)
                raised.append("%s %.1f->%d" % (e["name"], drawn, BOSS_MIN))
                e["scale"] = new
                changed += 1
            elif drawn > BOSS_MAX + 0.05:
                new = round(base * BOSS_MAX / 16 / cue, 4)
                lowered.append("%s %.1f->%d" % (e["name"], drawn, BOSS_MAX))
                e["scale"] = new
                changed += 1
            continue
        new = proposed_area(e, (hero_area, hero), cache)
        if new is None:
            unresolved.append(e["name"])
            continue
        old = float(e.get("scale", 1.0))
        if abs(new - old) >= 0.0005:
            ratios.append((new / old if old else 99, e["name"], old, new))
            e["scale"] = new
            changed += 1
    print("hero body %.1f px side / %.1f px area (median of %d hero sprites, trim %d%%); long side cap %.1f" % (
        hero, hero_area, n, TRIM * 100, LONG_CAP))
    print("scales changed: %d (bosses / set pieces raised to the %d floor: %d); unresolved sprites: %d %s" % (
        changed, BOSS_MIN, len(raised), len(unresolved), unresolved[:8]))
    if raised:
        print("raised:", raised[:12], "..." if len(raised) > 12 else "")
    if lowered:
        print("lowered to the %d ceiling (round 336):" % BOSS_MAX, lowered)
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
