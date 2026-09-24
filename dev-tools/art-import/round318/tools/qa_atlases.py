"""qa_atlases.py - round 316: every check the new atlases can get before they reach the game.

1. STRUCTURE   page header (Nearest filtering), every region inside the page, one cell size for every non-Avatar
               region, the animation set the engine loads (walk/fly: Idle and Walk in Down/Left/Right/Up;
               front: un-suffixed Idle and Walk), no region overlapping the Avatar, the creature's lowest pixel on
               the cell's bottom row (feet / shadow bottom-aligned).
2. FRAMES      dev-tools/art-import/frame_qa.py's per-frame tests (a second large figure in a cell, an oversize or a
               near-empty frame against the sprite's Idle/Walk median), applied to direction-suffixed regions too -
               frame_qa.py itself only reads un-suffixed "Idle"/"Walk" (round 179's atlases) and would report every
               4-direction atlas as "no Idle/Walk".
3. SIZE        dev-tools/enemy_scale.py's own body measurement (sprite_body) and the hero body it scales against ->
               the scale enemy_scale.py --write will give each enemy; then TuningData.tierSizeMultiplier()'s round-261
               ceiling: the drawn FRAME may be at most enemySpriteFrameCap (1.4) x the rank's 16-px body, so
               frame height x scale must stay <= 22.4 or the sprite draws below its rank.
4. AVATAR      dev-tools/avatar_crop_qa.py's cut_off() test against the first Idle frame of every atlas (that tool
               only looks at an un-suffixed "Idle" region, so it skips the 4-direction ones). INFORMATIONAL: the
               avatars are close-ups by design (a head, a bust, the behemoth's face) - round 286d recorded that the
               user prefers the close-up to a small fitted body and picks refits one by one - so a "cut" verdict is
               listed, never counted as a failure.
The repo's scripts are imported read-only (no bytecode written); nothing in C:\\TFR\\repo is touched.
usage: python tools/qa_atlases.py [atlas dir]  -> prints a report; exit status 1 on any failure"""
import collections, os, re, statistics, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)
sys.path.insert(0, r"C:\TFR\repo\dev-tools")
sys.path.insert(0, r"C:\TFR\repo\dev-tools\art-import")
from PIL import Image
import frame_qa
import enemy_scale
import avatar_crop_qa
from roster316 import ROSTER, ART, RESKINS, STATIONARY

ATL = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "atlases")
CUE = {"A": 13 / 16, "D": 1.0, "M": 20 / 16, "X": 24 / 16}
CAP = 1.4 * 16                      # enemySpriteFrameCap x STANDARD_BODY_PIXELS, before the rank cue
DIRS = ("Down", "Left", "Right", "Up")
RANK_OF = {r[0]: r[2] for r in ROSTER}
TIER_OF_RESKIN = {"jugan": "D", "yosei": "D", "ryusei": "X", "keiga": "D"}   # the legends' tiers (enemies.json)


def parse(path):
    t = open(path, encoding="utf-8").read()
    head = t.split("\n")[:5]
    regs = [(m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5)))
            for m in re.finditer(r"^(\w+)\n\s+xy: (\d+), (\d+)\n\s+size: (\d+), (\d+)", t, re.M)]
    return head, regs


def structure(stem, kind, head, regs, img):
    errs = []
    if head[0] != stem + ".png" or "filter: Nearest,Nearest" not in head or not head[1].startswith("size: "):
        errs.append("header %s" % head)
    W, H = img.size
    if head[1] != "size: %d,%d" % (W, H):
        errs.append("header size %s vs png %dx%d" % (head[1], W, H))
    names = collections.Counter(r[0] for r in regs)
    if names.get("Avatar") != 1:
        errs.append("Avatar regions: %d" % names.get("Avatar", 0))
    want = {"Idle", "Walk"} if kind == "front" else {a + d for a in ("Idle", "Walk") for d in DIRS}
    got = set(names) - {"Avatar"}
    if got != want:
        errs.append("animations %s (want %s)" % (sorted(got), sorted(want)))
    per = {"walk": {"Idle": 1, "Walk": 4}, "fly": {"Idle": 4, "Walk": 4}, "front": {"Idle": 4, "Walk": 4}}[kind]
    for n, k in names.items():
        if n != "Avatar" and k != per[n[:4]]:
            errs.append("%s has %d frames (want %d)" % (n, k, per[n[:4]]))
    cells = set((w, h) for n, x, y, w, h in regs if n != "Avatar")
    if len(cells) != 1:
        errs.append("cell sizes %s" % cells)
    av = next(r for r in regs if r[0] == "Avatar")
    for n, x, y, w, h in regs:
        if x < 0 or y < 0 or x + w > W or y + h > H:
            errs.append("%s outside the page" % n)
        if n != "Avatar" and x < av[1] + av[3] and av[1] < x + w and y < av[2] + av[4] and av[2] < y + h:
            errs.append("%s overlaps the Avatar" % n)
    # bottom alignment: over all the animation frames, some opaque pixel sits on the cell's last row
    frames = [img.crop((x, y, x + w, y + h)) for n, x, y, w, h in regs if n != "Avatar"]
    lowest = max(f.getchannel("A").getbbox()[3] for f in frames)
    cw, ch = next(iter(cells))
    if lowest != ch:
        errs.append("lowest pixel on row %d of a %d-row cell (not bottom-aligned)" % (lowest, ch))
    return errs, (cw, ch)


def frame_flags(regs, img):
    """frame_qa.check()'s tests over Idle*/Walk* regions (direction suffixes allowed)."""
    info = []
    for i, (n, x, y, w, h) in enumerate(regs):
        if n == "Avatar":
            continue
        fr = img.crop((x, y, x + w, y + h))
        info.append((i, n[:4], fr.getbbox(), frame_qa.blobs(fr)))
    base = [(bb, bl) for i, n, bb, bl in info if bb]
    mh = statistics.median(bb[3] - bb[1] for bb, _ in base)
    mw = statistics.median(bb[2] - bb[0] for bb, _ in base)
    ma = statistics.median(sum(b[0] for b in bl) for _, bl in base)
    flags = []
    for i, n, bb, bl in info:
        if not bb:
            flags.append("%s#%d empty" % (n, i))
            continue
        tot = sum(b[0] for b in bl)
        big = [b for b in bl if b[0] >= 0.2 * bl[0][0] and b[0] >= 0.08 * tot]
        if len(big) >= 2:
            a, b = big[0], big[1]
            gap = max(b[1] - a[3], a[1] - b[3], b[2] - a[4], a[2] - b[4])
            if gap > 6:
                flags.append("%s#%d two figures (%d/%d px, gap %d)" % (n, i, a[0], b[0], gap))
        hh, ww = bb[3] - bb[1], bb[2] - bb[0]
        if hh > 1.35 * mh or ww > 1.6 * mw:
            flags.append("%s#%d oversize %dx%d vs median %dx%d" % (n, i, ww, hh, mw, mh))
        if tot < 0.3 * ma:
            flags.append("%s#%d near-empty (%d vs %d px)" % (n, i, tot, ma))
    return sorted(set(flags))


def main():
    hero, nheroes = enemy_scale.hero_body()
    print("hero body %.1f px (%d hero sprites) - enemy_scale.py's reference" % (hero, nheroes))
    fails = 0
    rows = []
    for stem, (sheet, pick, kind, avm, shadow) in ART.items():
        ap = os.path.join(ATL, stem + ".atlas")
        head, regs = parse(ap)
        img = Image.open(os.path.join(ATL, stem + ".png")).convert("RGBA")
        errs, (cw, ch) = structure(stem, kind, head, regs, img)
        flags = frame_flags(regs, img)
        body = enemy_scale.sprite_body(ap)
        scale = round(min(8.0, max(0.05, hero / body)), 4)
        rank = RANK_OF.get(stem) or TIER_OF_RESKIN[stem]
        pre = ch * scale                                   # frame height drawn before the rank cue
        capped = pre > CAP + 1e-6
        drawn_body = hero * CUE[rank] * (min(1.0, CAP / pre) if capped else 1.0)
        # avatar_crop_qa's test against the first Idle frame
        av = next(r for r in regs if r[0] == "Avatar")
        idle = next(r for r in regs if r[0].startswith("Idle"))
        cur = img.crop((av[1], av[2], av[1] + av[3], av[2] + av[4]))
        fr0 = img.crop((idle[1], idle[2], idle[1] + idle[3], idle[2] + idle[4]))
        cut, sim, why = avatar_crop_qa.cut_off(cur, fr0, av[3], av[4], idle[3], idle[4])
        note = ("avatar = close-up (%s)" % why) if cut else ""   # informational: a deliberate bust, see the docstring
        if capped:
            errs.append("frame cap: drawn frame %.1f px > %.1f - the sprite would draw below its rank" % (pre, CAP))
        fails += bool(errs) or bool(flags)
        rows.append((stem, rank, kind, cw, ch, body, scale, pre, drawn_body, errs, flags, why, note))
    print("\n%-26s %-2s %-5s %-7s %-6s %-7s %-8s %-9s %s" % ("atlas", "rk", "kind", "cell", "body", "scale",
                                                            "frame@1x", "body drawn", "result"))
    for stem, rank, kind, cw, ch, body, scale, pre, drawn, errs, flags, why, note in rows:
        print("%-26s %-2s %-5s %3dx%-3d %5.1f  %.4f  %5.1f/%.1f  %5.1f px   %s%s%s" % (
            stem, rank, kind, cw, ch, body, scale, pre, CAP, drawn, "; ".join(errs) if errs else "OK",
            ("  | frame_qa: " + "; ".join(flags)) if flags else "", ("   [" + note + "]") if note else ""))
    print("\navatar test (avatar_crop_qa.cut_off): %s" % collections.Counter(
        re.sub(r" \(.*", "", r[11]) for r in rows))
    print("atlases %d, failing %d, frame_qa flags %d" % (len(rows), fails, sum(1 for r in rows if r[10])))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
