#!/usr/bin/env python3
"""Round 184: re-cut an enemy atlas's regions onto the pitch its ART actually uses.

Found by dev-tools/atlas_align_qa.py, from the user's report that "something wrong with the Axe Orc's
animation": three sheets from the round-117 sprite import declare 32 px cells while their figures are drawn
~40-44 px apart, so some frames land clean and others are cut through a figure - the animation jitters
between whole and half orcs.

What it does, per ROW of same-height regions: finds the drawn blobs (merging gaps under MERGE_GAP, so a
raised axe stays with its owner), then rewrites that row's regions to sit on the blobs. Every region on the
sheet keeps ONE common width and height - libGDX animates a region list, and a varying frame size would make
the sprite pulse - so the width is the widest blob (clamped to the page) and each frame is centred on its
own blob. Region ORDER and names are preserved exactly; only xy/size change.

Height is never touched: round 178 derives an enemy's drawn size from its frame height, so changing it would
silently resize the creature.

Usage:
    python dev-tools/atlas_regrid.py <atlas> [<atlas> ...]         # dry run, prints the new layout
    python dev-tools/atlas_regrid.py --write <atlas> [<atlas> ...] # rewrites, keeping a .bak
"""
import os, re, shutil, sys

try:
    from PIL import Image
except ImportError:
    sys.exit("PIL required: pip install pillow")

ALPHA = 8
MERGE_GAP = 4
PAD = 1  # breathing room each side of a blob, when the page allows it


def parse(path):
    """-> (header_lines, png, [ (name, x, y, w, h, other_lines) ]) preserving file order."""
    header, entries = [], []
    name = xy = size = None
    extra = []
    with open(path, encoding="utf-8", errors="replace") as fh:
        lines = [l.rstrip("\n") for l in fh]
    i = 0
    while i < len(lines) and (not lines[i].strip() or ":" in lines[i] or lines[i].lower().endswith(".png")):
        header.append(lines[i])
        i += 1
    png = next((h.strip() for h in header if h.lower().strip().endswith(".png")), None)
    for line in lines[i:]:
        if not line.strip():
            continue
        if not line.startswith(" "):
            if name is not None:
                entries.append([name, xy[0], xy[1], size[0], size[1], extra])
            name, xy, size, extra = line.strip(), None, None, []
            continue
        m = re.match(r"\s*xy:\s*(\d+)\s*,\s*(\d+)", line)
        if m:
            xy = (int(m.group(1)), int(m.group(2)))
            continue
        m = re.match(r"\s*size:\s*(\d+)\s*,\s*(\d+)", line)
        if m:
            size = (int(m.group(1)), int(m.group(2)))
            continue
        extra.append(line)
    if name is not None:
        entries.append([name, xy[0], xy[1], size[0], size[1], extra])
    return header, png, entries


def blobs(px, W, y0, y1):
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
            merged.append(r)
    return merged


def figures(found):
    """Blobs -> one entry per FIGURE. Two passes driven by the row's own pitch: pieces of one figure that
    sit closer together than 0.6 x pitch (a thrown axe, a trailing cloak) are merged, and a gap of about
    2 x pitch means two neighbours touched and became one blob, so a figure is put back in the middle."""
    if len(found) < 3:
        return found
    starts = [a for a, _b in found]
    deltas = sorted(b - a for a, b in zip(starts, starts[1:]))
    pitch = deltas[len(deltas) // 2]
    if pitch <= 0:
        return found
    merged = [found[0]]
    for a, b in found[1:]:
        if a - merged[-1][0] < 0.6 * pitch:
            merged[-1] = (merged[-1][0], b)
        else:
            merged.append((a, b))
    out = []
    for i, (a, b) in enumerate(merged):
        out.append((a, b))
        if i + 1 < len(merged):
            gap = merged[i + 1][0] - a
            if gap > 1.6 * pitch:            # a swallowed neighbour - restore it at one pitch along
                out.append((a + pitch, min(b + pitch, merged[i + 1][0] - 1)))
    return out


def regrid(atlas_path, write=False):
    header, png, entries = parse(atlas_path)
    img_path = os.path.join(os.path.dirname(atlas_path), png)
    im = Image.open(img_path).convert("RGBA")
    px, (W, H) = im.load(), im.size

    rows = {}
    for e in entries:
        if e[0] == "Avatar":
            continue
        rows.setdefault((e[2], e[4]), []).append(e)

    plan, width_needed = {}, 0
    for (y, h), row_entries in rows.items():
        found = figures(blobs(px, W, y, min(y + h - 1, H - 1)))
        if not found:
            print("   row y=%d: nothing drawn - left alone" % y)
            continue
        if len(found) != len(row_entries):
            # The declared frame count is what is wrong on these sheets: axe_orc's walk row is sliced into
            # 8 cells of 32 across a 256 px page while the art holds 6 figures at 44 px pitch. The art wins.
            print("   row y=%d: %d declared frame(s) -> %d actual figure(s)" % (y, len(row_entries), len(found)))
        plan[(y, h)] = found
        width_needed = max(width_needed, max(b - a + 1 for a, b in found))
    if not plan:
        return False
    # One cell size for the whole sheet, and it must not be wider than the TIGHTEST row's spacing, or a
    # cell reaches into its neighbour and the frame shows two figures (the attack and death rows did
    # exactly that on the first pass). The median blob width sets the target; the narrowest pitch caps it.
    widths, pitches = [], []
    for found in plan.values():
        widths.extend(b - a + 1 for a, b in found)
        pitches.extend(b[0] - a[0] for a, b in zip(found, found[1:]))
    widths.sort()
    target = widths[len(widths) // 2] + 2 * PAD
    cap = (min(pitches) - 1) if pitches else W
    cell = max(1, min(W, target, cap, width_needed + 2 * PAD))

    # Rebuild each row from its FIGURES, not from its declared entries: a row sliced into 8 cells that holds
    # 6 figures has to lose two regions, or the surplus keeps pointing at the old boxes and the animation
    # still flicks through half-drawn frames. The row's own name and trailing lines are carried over.
    changed = 0
    rebuilt = []
    consumed = set()
    for entry in entries:
        key = (entry[2], entry[4])
        if entry[0] == "Avatar" or key not in plan:
            rebuilt.append(entry)
            continue
        if key in consumed:
            continue                       # this row was already emitted at its first entry
        consumed.add(key)
        row_entries = sorted(rows[key], key=lambda e: e[1])
        template = row_entries[0]
        for (a, b) in plan[key]:
            centre = (a + b) // 2
            x = max(0, min(W - cell, centre - cell // 2))
            if (x, cell) != (template[1], template[3]):
                changed += 1
            rebuilt.append([template[0], x, template[2], cell, template[4], list(template[5])])
        if len(plan[key]) != len(row_entries):
            print("   row y=%d: %d region(s) -> %d" % (key[0], len(row_entries), len(plan[key])))
    entries = rebuilt
    rows = {}
    for e in entries:
        if e[0] != "Avatar":
            rows.setdefault((e[2], e[4]), []).append(e)
    # The Avatar still, if any, follows the first frame of the first row.
    first = min(plan) if plan else None
    for e in entries:
        if e[0] == "Avatar" and first:
            row_entries = sorted(rows[first], key=lambda x: x[1])
            e[1], e[2], e[3], e[4] = row_entries[0][1], row_entries[0][2], row_entries[0][3], row_entries[0][4]

    print("   -> %d frame(s) moved, uniform cell %dx%d" % (changed, cell, entries[0][4]))
    if not write:
        return changed > 0

    out = list(header)
    for name, x, y, w, h, extra in entries:
        out.append(name)
        out.append("  xy: %d, %d" % (x, y))
        out.append("  size: %d, %d" % (w, h))
        out.extend(extra)
    shutil.copyfile(atlas_path, atlas_path + ".bak")
    with open(atlas_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(out) + "\n")
    return changed > 0


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    write = "--write" in sys.argv
    if not args:
        sys.exit(__doc__)
    for path in args:
        print(os.path.basename(path) + ":")
        try:
            regrid(path, write)
        except Exception as exc:
            print("   FAILED: %s" % exc)
    print("(dry run - pass --write to apply)" if not write else "(written; .bak kept beside each atlas)")


if __name__ == "__main__":
    main()
