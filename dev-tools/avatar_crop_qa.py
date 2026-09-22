"""avatar_crop_qa.py - find and repair duel portraits that were generated cut off (round 286b).

User, from the Arena VS screen: *"I've found several portraits in the arena that's a little cut-off
like this one. I think the worm is a little too big."*

WHERE THE CROP HAPPENS

`TransitionScreen` draws the VS portrait with `getAtlas(path).createSprite("Avatar")`, i.e. the region
literally named `Avatar` in the creature's own atlas, into a SQUARE box. A square box stretches a
non-square region - it does not clip it - so nothing about the screen can cut a portrait off. The
truncation is baked into the atlas.

Shellhusk Crawler is the worked example. Its `Avatar` region is 33x33 while its animation frames are
60x35, and the art's bounding box inside that region is (0, 1, 33, 33) - flush against the left, right
AND bottom edges with nothing beyond. A wide worm was pasted into a box too narrow for it and the
overhang was discarded at generation time. `art_convert_generic.py` cut a wide creature's portrait from
the middle until round 273 fixed it; round 273 did not rewrite the atlases already written.

HOW "CUT OFF" IS DETECTED - and the test that did not work

The first attempt flagged "art touches two or more edges of its box" and matched 197 of 197 atlases,
which is a broken test rather than a broken plane: a PAINTED portrait is a framed bust and fills its
frame edge to edge by design. Repairing on that signal would have replaced every hand-painted portrait
with a shrunken sprite frame.

The question that actually separates them is where the avatar's pixels came from:

  painted portrait   separate artwork, so it does NOT resemble the creature's Idle frame.
  sprite crop        the same artwork as the Idle frame, so it resembles it closely. Only these can be
                     truncated at all.

Measured by resizing avatar and Idle frame to 24x24 grayscale on their own alpha boxes and taking the
mean absolute difference. Painted portraits land at 0.40-0.65; sprite crops at 0.02-0.15. The gap is
wide and empty, so SIM_MAX sits in it.

A sprite crop is then CUT OFF when an edge column is DENSE with opaque pixels - a hard vertical cut.
"Touches the edge" was the wrong test twice over. It flagged art that merely reaches the border at its
widest point, and worse, a correctly FITTED wide frame lands exactly the box width, so it touches both
side edges by construction: the first repair pass "fixed" 39 avatars and the audit still reported 32,
because the repair could not satisfy its own detector.

Measured on the backups of that pass, the two cases separate completely:

    truncated   brood_spawn L1.00 R1.00, crimson_burrower L1.00 R1.00, ashen_spinewyrm R0.78,
                carapace_stalker L0.56 (cut on ONE side only - so either edge counts, not both)
    fine        blossomtrap_dryad L0.01 R0.01, frostpelt_wolf L0.19 R0.20
    repaired    the same 39 afterwards, almost all <= 0.25 and mostly ~0.10

So: EDGE_DENSITY on the denser of the two side columns, and no aspect-ratio or both-sides condition -
edge density subsumes them. blossomtrap_dryad and frostpelt_wolf were false positives of the old test
and are correctly left alone now.

HOW IT IS REPAIRED

The whole first Idle frame, scaled to FIT inside the Avatar box with its aspect ratio kept and
transparent padding, centred horizontally and sat on the bottom edge. Deliberately not a re-crop:
a crop has to guess which end is the head, and the head is at whichever end the creature faces - a
question this plane has 57 unreviewed answers for. Fitting the whole frame cannot cut anything off and
cannot guess wrong. A wide creature ends up smaller in its portrait, which is the honest trade for
being all there.

The atlas file is NOT touched - the region keeps its name, position and size, so nothing repacks and
no other tool's assumptions move. Only the pixels inside that rectangle change.

usage: python dev-tools/avatar_crop_qa.py                  # audit
       python dev-tools/avatar_crop_qa.py --apply          # repair
       python dev-tools/avatar_crop_qa.py --apply --backup # repair, keeping <name>.png.avatar-bak
"""
import glob
import io
import os
import re
import sys

from PIL import Image, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
SPRITES = os.path.join(HERE, '..', 'forge-gui', 'res', 'adventure', 'The Forsaken Realms',
                       'sprites', 'enemy', 'tfr')


def regions(atlas_path):
    """[(name, x, y, w, h)] in file order, from the libGDX atlas text format."""
    lines = io.open(atlas_path, encoding='utf-8').read().splitlines()
    out, i = [], 0
    while i < len(lines):
        ln = lines[i]
        if ln and not ln.startswith(' ') and not ln.endswith('.png') and ':' not in ln:
            xy = re.search(r'xy:\s*(\d+),\s*(\d+)', lines[i + 1]) if i + 1 < len(lines) else None
            sz = re.search(r'size:\s*(\d+),\s*(\d+)', lines[i + 2]) if i + 2 < len(lines) else None
            if xy and sz:
                out.append((ln.strip(), int(xy.group(1)), int(xy.group(2)),
                            int(sz.group(1)), int(sz.group(2))))
                i += 3
                continue
        i += 1
    return out


SIM_MAX = 0.25        # below this the avatar is the sprite's own artwork, not a painted portrait
EDGE_DENSITY = 0.30   # opaque fraction of a side column that means a hard vertical cut
FILL_BOTH = 0.90      # art filling BOTH axes this much was clipped, not fitted


def _norm(im, n=24):
    bb = im.getbbox()
    return None if not bb else im.crop(bb).convert('L').resize((n, n), Image.LANCZOS)


def similarity(avatar, frame):
    """0 = identical artwork, 1 = nothing in common. See the module docstring for the calibration."""
    na, nf = _norm(avatar), _norm(frame)
    if na is None or nf is None:
        return None
    d = ImageChops.difference(na, nf)
    px = list(d.get_flattened_data()) if hasattr(d, 'get_flattened_data') else list(d.getdata())
    return sum(px) / (len(px) * 255.0)


def side_density(im):
    """(left, right) opaque fraction of the two edge columns."""
    w, h = im.size
    px = im.load()
    col = lambda x: sum(1 for y in range(h) if px[x, y][3] > 0) / float(h)
    return col(0), col(w - 1)


def cut_off(avatar, frame, aw, ah, fw, fh):
    """(is_cut_off, sim, reason) - a sprite crop with a hard vertical cut on either side."""
    if avatar.getbbox() is None:
        return False, None, 'empty'
    sim = similarity(avatar, frame)
    if sim is None:
        return False, None, 'unreadable'
    if sim >= SIM_MAX:
        return False, sim, 'painted portrait'
    left, right = side_density(avatar)
    dense = max(left, right)
    if dense < EDGE_DENSITY:
        return False, sim, 'edges clear (L%.2f R%.2f)' % (left, right)
    # Edge density alone still mis-reads a broad silhouette. The geometry settles it: fit_into()
    # scales the WHOLE frame, so a fitted avatar fills exactly one axis and keeps real margin on the
    # other (a 1.29 frame leaves ~23% vertical margin, a 1.71 frame ~42%). Truncated art fills BOTH
    # axes, because the overhang was discarded at the border - shellhusk_crawler measured
    # (0, 1, 33, 33) in 33x33, i.e. 1.00 wide and 0.97 tall.
    bb = avatar.getbbox()
    fill_w = (bb[2] - bb[0]) / float(aw)
    fill_h = (bb[3] - bb[1]) / float(ah)
    if min(fill_w, fill_h) < FILL_BOTH:
        return False, sim, 'fitted (fills %.2fx%.2f of the box)' % (fill_w, fill_h)
    return True, sim, 'cut: L%.2f R%.2f, fills %.2fx%.2f' % (left, right, fill_w, fill_h)


def fit_into(frame, w, h):
    """The whole frame, aspect kept, centred horizontally and sitting on the bottom edge."""
    bb = frame.getbbox()
    art = frame.crop(bb) if bb else frame
    scale = min(w / float(art.size[0]), h / float(art.size[1]))
    nw, nh = max(1, int(round(art.size[0] * scale))), max(1, int(round(art.size[1] * scale)))
    small = art.resize((nw, nh), Image.LANCZOS)
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    out.alpha_composite(small, ((w - nw) // 2, h - nh))
    return out


def main():
    apply_it = '--apply' in sys.argv
    backup = '--backup' in sys.argv
    # Round 286d: which portraits to refit is the USER's pick from the ones this tool flags. That
    # choice is aesthetic and cannot be automated - the first pass refitted all 54 and made most of
    # them worse, trading a dramatic close-up for a small distant body. So the detector decides what
    # is CAPABLE of being refitted; a person decides what SHOULD be.
    only = None
    for i, arg in enumerate(sys.argv):
        if arg == '--only' and i + 1 < len(sys.argv):
            only = {n.strip().lower() for n in sys.argv[i + 1].split(',') if n.strip()}
    bad = fixed = skipped = 0
    asked = set(only) if only else set()
    for atlas in sorted(glob.glob(os.path.join(SPRITES, '*.atlas'))):
        png = atlas[:-6] + '.png'
        if not os.path.isfile(png):
            continue
        try:
            regs = regions(atlas)
        except Exception as e:
            print('  !! %s: %s' % (os.path.basename(atlas), e))
            continue
        av = next((r for r in regs if r[0] == 'Avatar'), None)
        idle = next((r for r in regs if r[0] == 'Idle'), None)
        if not av:
            continue
        name = os.path.basename(atlas)[:-6]
        if not idle:
            skipped += 1
            continue
        sheet = Image.open(png).convert('RGBA')
        _n, ax, ay, aw, ah = av
        _n2, ix, iy, iw, ih = idle
        cur = sheet.crop((ax, ay, ax + aw, ay + ah))
        frame0 = sheet.crop((ix, iy, ix + iw, iy + ih))
        is_cut, sim, why = cut_off(cur, frame0, aw, ah, iw, ih)
        if not is_cut:
            continue
        bad += 1
        if only is not None:
            asked.discard(name.lower())
            if name.lower() not in only:
                continue
        print('  %-34s sim %.3f  frame %dx%d  avatar %dx%d  %s'
              % (name, sim, iw, ih, aw, ah, why))
        if not apply_it:
            continue
        new = fit_into(frame0, aw, ah)
        if backup and not os.path.isfile(png + '.avatar-bak'):
            # explicit format: PIL infers it from the extension, and '.avatar-bak' is not one
            sheet.save(png + '.avatar-bak', format='PNG')
        # clear the rectangle, then composite - paste() would keep the old pixels under transparency
        sheet.paste((0, 0, 0, 0), (ax, ay, ax + aw, ay + ah))
        sheet.alpha_composite(new, (ax, ay))
        sheet.save(png)
        fixed += 1
    print('\n%s: %d avatar(s) cut off, %d repaired, %d with no Idle frame (of %d atlases)'
          % ('APPLIED' if apply_it else 'AUDIT', bad, fixed, skipped,
             len(glob.glob(os.path.join(SPRITES, '*.atlas')))))
    if asked:
        # Named but never reached: either not flagged as cut off, or no such atlas. Reported rather
        # than silently dropped - a typo in a 21-name list is otherwise invisible.
        print('NOT TOUCHED (not flagged as cut off, or no such atlas): %s'
              % ', '.join(sorted(asked)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
