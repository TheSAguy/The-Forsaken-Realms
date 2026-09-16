#!/usr/bin/env python3
"""Find sprite frames with a detached blob stranded below the artwork.

2026-09-16 user report, with two screenshots: "some enemy sprites have this weird little piece at
the bottom. Looks like the image captured something from another image that should not be there."

Confirmed on sprites/enemy/basic/animal/barnyard_hen.png - every 12x28 frame is the hen on rows
0-19, then rows 20-24 COMPLETELY EMPTY, then a red bar on rows 25-27. The frame grid itself is
sound (48x140 = 5 exact rows of 28), so the junk is baked into the pixels, not a slicing error.

DETECTION. Inside each atlas region, take the per-row opaque-pixel count. Walk from the top: the
artwork is the first run of non-empty rows. If, after at least one completely empty row, MORE
non-empty rows appear, that trailing run is a detached island - which is what the report calls out.

WHY IT REPORTS INSTEAD OF DELETING. A detached island below a sprite is not automatically wrong: a
floating creature with a separate drop-shadow looks identical to this test. So every hit is printed
with the island's height, pixel count, mean RGB and mean alpha, which is what tells the two apart -
a shadow is dark and semi-transparent and sits directly under the body, a bled-in fragment is opaque
and often brightly coloured (the hen's is solid red). Use --fix only after reading the report.

Usage
-----
    python dev-tools/sprite_artifact_audit.py [--root DIR] [--plane NAME] [--min-gap N]
                                              [--json OUT] [--fix] [--only SUBSTR]

    --fix       erase the detached island (set alpha 0) in every reported frame, after taking a
                <name>.png.spritebak backup once per file. Never runs without being asked for.
    --only      restrict to paths containing SUBSTR - use it to fix one sheet at a time.

Exit code 1 when anything is reported, so it can gate a build.
"""
import argparse
import collections
import io
import json
import os
import sys

try:
    from PIL import Image
except ImportError:
    print("Pillow is required: pip install pillow")
    sys.exit(2)


# A bled-in fragment is small; anything larger is almost certainly the sprite itself.
ARTIFACT_MAX_PIXELS = 80


def parse_atlas(path):
    """libGDX atlas -> (png filename, [(region_name, x, y, w, h)]). Tolerates the header block."""
    regions = []
    png = None
    name = None
    xy = None
    size = None
    with io.open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.read().splitlines()
    for raw in lines:
        if not raw.strip():
            continue
        if png is None and not raw.startswith(" ") and raw.lower().endswith(".png"):
            png = raw.strip()
            continue
        stripped = raw.strip()
        if raw.startswith(" ") or raw.startswith("\t"):
            if stripped.startswith("xy:"):
                try:
                    xs, ys = stripped.split(":", 1)[1].split(",")
                    xy = (int(xs), int(ys))
                except ValueError:
                    xy = None
            elif stripped.startswith("size:"):
                try:
                    ws, hs = stripped.split(":", 1)[1].split(",")
                    size = (int(ws), int(hs))
                except ValueError:
                    size = None
            if xy and size and name:
                regions.append((name, xy[0], xy[1], size[0], size[1]))
                xy = size = None
        else:
            if ":" in stripped and stripped.split(":", 1)[0] in (
                    "size", "format", "filter", "repeat", "pma", "scale", "index"):
                continue
            name = stripped
            xy = size = None
    return png, regions


def row_profile(px, x, y, w, h, img_w, img_h):
    """opaque pixel count per row of the region, plus colour sums."""
    prof = []
    for r in range(h):
        yy = y + r
        if yy >= img_h:
            prof.append((0, 0, 0, 0, 0))
            continue
        n = rs = gs = bs = a_sum = 0
        for c in range(w):
            xx = x + c
            if xx >= img_w:
                continue
            pr, pg, pb, pa = px[xx, yy]
            if pa > 0:
                n += 1
                rs += pr
                gs += pg
                bs += pb
                a_sum += pa
        prof.append((n, rs, gs, bs, a_sum))
    return prof


def find_island(prof):
    """-> (start_row, end_row_exclusive) of a detached trailing run, or None.

    Skips leading empty rows, takes the first run of content as the artwork, then requires at least
    one empty row before any further content."""
    h = len(prof)
    i = 0
    while i < h and prof[i][0] == 0:
        i += 1
    if i >= h:
        return None                      # frame is entirely empty
    while i < h and prof[i][0] > 0:      # the artwork
        i += 1
    gap = i
    while i < h and prof[i][0] == 0:     # the gap
        i += 1
    if i >= h:
        return None                      # nothing after the artwork
    return (i, h, i - gap)               # island start, frame end, gap height


def audit(root, plane=None, min_gap=1, only=None, fix=False):
    base = os.path.join(root, "forge-gui", "res", "adventure")
    planes = [plane] if plane else sorted(
        d for d in os.listdir(base) if os.path.isdir(os.path.join(base, d)))
    hits = []
    scanned_atlas = scanned_frames = 0
    unparsed = []
    for p in planes:
        sprite_root = os.path.join(base, p, "sprites")
        if not os.path.isdir(sprite_root):
            continue
        for dirpath, _dirs, files in os.walk(sprite_root):
            for fn in sorted(files):
                if not fn.endswith(".atlas"):
                    continue
                apath = os.path.join(dirpath, fn)
                if only and only.lower() not in apath.replace("\\", "/").lower():
                    continue
                png, regions = parse_atlas(apath)
                if not png or not regions:
                    unparsed.append(apath)
                    continue
                ipath = os.path.join(dirpath, png)
                if not os.path.exists(ipath):
                    unparsed.append(apath + " (missing " + png + ")")
                    continue
                try:
                    im = Image.open(ipath).convert("RGBA")
                except OSError:
                    unparsed.append(apath + " (unreadable png)")
                    continue
                px = im.load()
                iw, ih = im.size
                scanned_atlas += 1
                per_file = []
                for (rname, x, y, w, h) in regions:
                    scanned_frames += 1
                    prof = row_profile(px, x, y, w, h, iw, ih)
                    found = find_island(prof)
                    if not found:
                        continue
                    start, end, gap = found
                    if gap < min_gap:
                        continue
                    n = sum(prof[r][0] for r in range(start, end))
                    if n == 0:
                        continue
                    rs = sum(prof[r][1] for r in range(start, end))
                    gs = sum(prof[r][2] for r in range(start, end))
                    bs = sum(prof[r][3] for r in range(start, end))
                    a_s = sum(prof[r][4] for r in range(start, end))
                    # Classify. The reported bug is a SMALL, bottom-anchored blob left behind by a
                    # neighbouring image: the hen's is 24 px on the frame's last three rows after a
                    # 5-row gap. A big island is almost always the creature itself - a sprite drawn
                    # with a horizontal break (a floating head, a raised weapon) splits the row
                    # profile and the lower half reads as "detached". Those are left for review
                    # rather than erased, because deleting one would gut the sprite.
                    bottom_anchored = (end - 1) >= (h - 2)
                    artifact = n <= ARTIFACT_MAX_PIXELS and gap >= 2 and bottom_anchored
                    per_file.append({
                        "atlas": os.path.relpath(apath, root).replace("\\", "/"),
                        "png": os.path.relpath(ipath, root).replace("\\", "/"),
                        "region": rname, "x": x, "y": y, "w": w, "h": h,
                        "island_rows": "%d-%d" % (start, end - 1), "gap": gap,
                        "pixels": n, "bottom_anchored": bottom_anchored,
                        "verdict": "artifact" if artifact else "review",
                        "rgb": (rs // n, gs // n, bs // n), "alpha": a_s // n,
                    })
                hits.extend(per_file)
                # Only touch a sheet that actually has something to erase. Re-saving a sheet whose
                # only hits are "review" rewrites the PNG byte-for-byte differently for no pixel
                # change, which is pure churn in a binary file.
                to_erase = [r for r in per_file if r["verdict"] == "artifact"]
                if fix and to_erase:
                    backup = ipath + ".spritebak"
                    if not os.path.exists(backup):
                        # format must be explicit - PIL cannot infer one from ".spritebak"
                        Image.open(ipath).save(backup, format="PNG")
                    for hrec in to_erase:
                        s, e = (int(v) for v in hrec["island_rows"].split("-"))
                        for r in range(s, e + 1):
                            for c in range(hrec["w"]):
                                xx, yy = hrec["x"] + c, hrec["y"] + r
                                if xx < iw and yy < ih:
                                    px[xx, yy] = (0, 0, 0, 0)
                    im.save(ipath)
    return hits, scanned_atlas, scanned_frames, unparsed


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--plane")
    ap.add_argument("--min-gap", type=int, default=1)
    ap.add_argument("--only")
    ap.add_argument("--json", dest="json_out")
    ap.add_argument("--fix", action="store_true")
    args = ap.parse_args(argv)

    hits, na, nf, unparsed = audit(args.root, args.plane, args.min_gap, args.only, args.fix)
    print("scanned %d atlas file(s), %d frame(s); %d unparsed" % (na, nf, len(unparsed)))
    if not hits:
        print("\nNo frame has a detached blob below its artwork.")
        return 0

    for verdict, title in (
            ("artifact", "LIKELY ARTIFACT - small, bottom-anchored blob after a gap (safe to erase)"),
            ("review", "NEEDS EYES - island too large to erase blind (probably the sprite itself)")):
        group = [h for h in hits if h["verdict"] == verdict]
        by_png = collections.defaultdict(list)
        for h in group:
            by_png[h["png"]].append(h)
        print("\n== %s ==\n%d frame(s) across %d sheet(s)\n" % (title, len(group), len(by_png)))
        for png in sorted(by_png):
            rows = by_png[png]
            sample = rows[0]
            print("  %s   (%d frame(s))" % (png, len(rows)))
            print("      rows %-7s of h=%-3d gap %-2d %4d px  rgb %s alpha %d"
                  % (sample["island_rows"], sample["h"], sample["gap"], sample["pixels"],
                     sample["rgb"], sample["alpha"]))
    if args.json_out:
        io.open(args.json_out, "w", encoding="utf-8").write(
            json.dumps(hits, indent=2, ensure_ascii=False))
        print("\nwrote %s" % args.json_out)
    if args.fix:
        print("\n--fix applied: islands erased, backups at <name>.png.spritebak")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
