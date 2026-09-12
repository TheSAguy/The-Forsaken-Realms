#!/usr/bin/env python3
"""Round 184: find enemy atlases whose declared frame grid does not match the pitch the art is drawn at.

The user reported "something wrong with the Axe Orc's animation". axe_orc.atlas declares 32x21 cells, but the
walk row's six figures sit 44 px apart, so every declared frame cuts through a figure and the animation
jitters between halves. That sheet came from our own round-117 sprite import, so the importer may have
produced more of them.

The test: for each ROW of declared cells, find the drawn blobs (runs of columns holding any pixel, merged
across gaps narrower than MERGE_GAP), take the median distance between consecutive blob starts, and compare
it with the declared cell width. Art packed tightly enough that neighbours touch is NOT reported - only a
row whose measured pitch disagrees with the grid it is sliced on, which is the thing that actually breaks an
animation. Rows with fewer than three blobs are skipped (too little evidence to call it).

Usage:  python dev-tools/atlas_align_qa.py ["plane dir"]
"""
import os, re, statistics, sys

try:
    from PIL import Image
except ImportError:
    sys.exit("PIL required: pip install pillow")

PLANE = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else (
    r"F:\FORGE\C--Users-vicwaver-MTG-Forge\forge-gui\res\adventure\The Forsaken Realms")
ALPHA = 8        # a pixel counts as drawn above this alpha
MERGE_GAP = 4    # blobs closer than this are one figure (a raised axe, a trailing tail)
TOLERANCE = 0.12 # a measured pitch this far from the declared width is a real mismatch


def parse_atlas(path):
    regions, png, name, xy, size = [], None, None, None, None
    with open(path, encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line.strip():
                continue
            if not line.startswith(" ") and line.lower().endswith(".png"):
                png = line.strip()
                continue
            if not line.startswith(" "):
                if name and xy and size:
                    regions.append((name, xy[0], xy[1], size[0], size[1]))
                if ":" in line:
                    continue
                name, xy, size = line.strip(), None, None
                continue
            m = re.match(r"\s*xy:\s*(\d+)\s*,\s*(\d+)", line)
            if m:
                xy = (int(m.group(1)), int(m.group(2)))
                continue
            m = re.match(r"\s*size:\s*(\d+)\s*,\s*(\d+)", line)
            if m:
                size = (int(m.group(1)), int(m.group(2)))
    if name and xy and size:
        regions.append((name, xy[0], xy[1], size[0], size[1]))
    return png, regions


def blob_starts(px, W, y0, y1):
    drawn = [any(px[x, y][3] > ALPHA for y in range(y0, y1 + 1)) for x in range(W)]
    runs, start = [], None
    for x, d in enumerate(drawn):
        if d and start is None:
            start = x
        elif not d and start is not None:
            runs.append((start, x - 1))
            start = None
    if start is not None:
        runs.append((start, W - 1))
    merged = []
    for r in runs:
        if merged and r[0] - merged[-1][1] <= MERGE_GAP:
            merged[-1] = (merged[-1][0], r[1])
        else:
            merged.append(list(r) if False else (r[0], r[1]))
            merged[-1] = (r[0], r[1])
    return [m[0] for m in merged]


def scan(atlas_path):
    png, regions = parse_atlas(atlas_path)
    if not png or not regions:
        return None
    img_path = os.path.join(os.path.dirname(atlas_path), png)
    if not os.path.exists(img_path):
        return ("(missing png)", 0, 0, 0)
    im = Image.open(img_path).convert("RGBA")
    px, (W, H) = im.load(), im.size
    rows = {}
    for name, x, y, w, h in regions:
        if name == "Avatar":
            continue
        rows.setdefault((y, h, w), set()).add(x)
    worst = None
    for (y, h, w), xs in sorted(rows.items()):
        if len(xs) < 3 or y + h > H:
            continue
        starts = blob_starts(px, W, y, y + h - 1)
        # Only trust a row where every declared frame produced exactly one blob. Two sprites drawn close
        # enough to touch merge into one, which reads as a 1.5x pitch and would flag half the catalogue;
        # requiring a 1:1 match keeps this to rows the grid genuinely misses (axe_orc: 6 blobs, 6 frames,
        # 44 px apart, declared 32).
        if len(starts) != len(xs) or len(starts) < 3:
            continue
        pitch = statistics.median([b - a for a, b in zip(starts, starts[1:])])
        if pitch <= 0:
            continue
        drift = abs(pitch - w) / float(w)
        if drift > TOLERANCE and (worst is None or drift > worst[3]):
            worst = (y, w, pitch, drift)
    return worst


def main():
    root = os.path.join(PLANE, "sprites", "enemy")
    total = flagged = 0
    hits = []
    for dirpath, _dirs, files in os.walk(root):
        for f in sorted(files):
            if not f.endswith(".atlas"):
                continue
            total += 1
            path = os.path.join(dirpath, f)
            worst = scan(path)
            if not worst:
                continue
            flagged += 1
            rel = os.path.relpath(path, PLANE).replace("\\", "/")
            hits.append((worst[3], rel, worst))
    for drift, rel, (y, w, pitch, _d) in sorted(hits, reverse=True):
        print("%-62s declared %d px cells, art sits %g px apart (row y=%d, %.0f%% off)"
              % (rel, w, pitch, y, drift * 100))
    print("\nscanned %d atlas file(s); %d sliced on a grid the art does not use" % (total, flagged))


if __name__ == "__main__":
    main()
