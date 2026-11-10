"""TFR 1's button icons (sprites/button_icons.atlas/png in the plane), from the cells in button_icons/ beside this script.

Round 1007 (Art, 2026-11-10; the user: "Yes, install"). Each icon at 32 px (region NAME) and 48 px (NAME_48);
util/TfrButtonIcons picks one by the screen's scale (32 at 720p and 1440p, 48 at 1080p and 4K) and maps a button's label
to its NAME. Nearest filtering: pixel art at whole multiples.
  enter      the open stone gate with warm light: Enter, Pass through, Open the gate
  attack     the crossed swords: Attack, Attack (two Archmages), Attack again in N days, (Fight), Prepare yourself!
  leave      the boot walking right with its dust: Leave, (Leave), Walk away, Turn Back
  orders     the sealed scroll: Browse quests, every "(Accept Quest)" answer
  withdraw   the white flag: (Withdraw), (Flee)
  buy        the coin purse: Pay ... (a capital's toll), Buy Back (the Card Lost row)
  lost       the broken sword: the Card Lost row's OK
  coin       Forge's Bronze Challenge Coin item sprite at 2x and 3x: the Card Lost row's Bronze Coin
  build      NEW, the hammer over stone blocks: Restore town, Rebuild, Repair ..., Restore Armory, Upgrade ...
  questdone  NEW, the star medal on a red V ribbon: every "... Complete Quest)", "(Quest Complete)", "(Take your reward)"
The first eight are TFR 2's own art (its sprites/duel_choice.atlas, Art Library: Projects/The Forsaken Realms II/Game Art/
Duel Choice Icons); build and questdone are TFR 1's (Art Library: Projects/The Forsaken Realms/Game Art/Button Icons (round 1007)).
Layout: pitch 50, the 32 px row at y 0 and the 48 px row at y 34. New icons go at the end of both rows, so every earlier
region keeps its place.

    python -I pack_button_icons.py OUT_DIR    writes OUT_DIR/button_icons.png and OUT_DIR/button_icons.atlas, then
                                             reads every region back through the atlas text and checks it against its cell
"""
import os
import sys

import numpy as np
from PIL import Image

CELLS = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'button_icons')
PITCH = 50
NAMES = ['enter', 'attack', 'leave', 'orders', 'withdraw', 'buy', 'lost', 'coin', 'build', 'questdone']
ROWS = [(0, 32, ''), (34, 48, '_48')]   # (row y, size, region suffix)


def cell(name, size):
    im = Image.open(os.path.join(CELLS, '%s_%d.png' % (name, size))).convert('RGBA')
    assert im.size == (size, size), (name, size, im.size)
    return im


def main(out):
    w = PITCH * len(NAMES)
    h = max(y + size for y, size, _ in ROWS)
    png = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    lines = ['button_icons.png', 'size: %d,%d' % (w, h), 'format: RGBA8888', 'filter: Nearest,Nearest', 'repeat: none']
    regions = []
    for y, size, suf in ROWS:
        for i, name in enumerate(NAMES):
            png.paste(cell(name, size), (i * PITCH, y))
            regions.append((name + suf, i * PITCH, y, size, name))
            lines += [name + suf, '  xy: %d, %d' % (i * PITCH, y), '  size: %d, %d' % (size, size)]
    png.save(os.path.join(out, 'button_icons.png'))
    with open(os.path.join(out, 'button_icons.atlas'), 'w', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    # read back: every region through the atlas text, against its cell
    sheet = np.array(Image.open(os.path.join(out, 'button_icons.png')).convert('RGBA'))
    text = open(os.path.join(out, 'button_icons.atlas')).read().split('\n')
    seen = 0
    for k, line in enumerate(text):
        for rname, x, y, size, name in regions:
            if line == rname:
                xy = tuple(int(v) for v in text[k + 1].split(':')[1].split(','))
                sz = tuple(int(v) for v in text[k + 2].split(':')[1].split(','))
                assert xy == (x, y) and sz == (size, size), rname
                got = sheet[y:y + size, x:x + size]
                assert (got == np.array(cell(name, size))).all(), rname
                seen += 1
    assert seen == len(regions), (seen, len(regions))
    print('button_icons.png %dx%d, %d regions read back OK' % (w, h, seen))


if __name__ == '__main__':
    main(sys.argv[1])
