r"""poi_outline.py - TFR round 655: a 1px black outline round a world-map place sprite, inside its own atlas region.

The user (2026-10-16), of the Elf Town and the Lake of the Hydra (Grove7) entrances - painted art among pixel sprites
that all carry a dark outline: "Could we add a 1px black border round these 2 dungeon entrances. I just want to see if
it makes them look better."

The region keeps its size, position and footprint - nothing else about the place changes:
  - the silhouette is alpha >= 128 (a painted sprite's feathered fringe below that is cut, so the edge is crisp);
  - the outline is every transparent pixel 4-adjacent to the silhouette, plus the silhouette's own pixels on the
    region's border where the outline has no room outside (Grove7 fills its region on three sides);
  - silhouette pixels become fully opaque; the outline is black.
The page is edited in place; git holds the original (revert = `git checkout -- <png>`).

usage: python poi_outline.py <atlas> <region> [<region> ...] [--preview out.png] [--write]"""
import os
import sys

from PIL import Image, ImageDraw

BLACK = (0, 0, 0, 255)


def regions(atlas):
    out, page, cur = {}, None, None
    for ln in open(atlas, encoding='utf-8').read().splitlines():
        if ln.endswith('.png'):
            page = ln.strip()
            continue
        if ln and not ln.startswith(' ') and ':' not in ln:
            cur = ln.strip()
            out.setdefault(cur, {'page': page})
        elif cur and ln.strip().startswith('xy:') and 'xy' not in out[cur]:
            out[cur]['xy'] = tuple(int(v) for v in ln.split(':')[1].split(','))
        elif cur and ln.strip().startswith('size:') and 'size' not in out[cur]:
            out[cur]['size'] = tuple(int(v) for v in ln.split(':')[1].split(','))
    return out


def outlined(tile):
    w, h = tile.size
    src = tile.load()
    mask = [[src[x, y][3] >= 128 for y in range(h)] for x in range(w)]
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    px = out.load()
    for x in range(w):
        for y in range(h):
            if mask[x][y]:
                r, g, b, _ = src[x, y]
                px[x, y] = (r, g, b, 255)
    for x in range(w):
        for y in range(h):
            if mask[x][y]:
                if x in (0, w - 1) or y in (0, h - 1):
                    px[x, y] = BLACK  # no room outside: the edge pixel itself
                continue
            if any(0 <= x + dx < w and 0 <= y + dy < h and mask[x + dx][y + dy]
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = BLACK
    return out


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__)
    atlas = argv[1]
    names = [a for a in argv[2:] if not a.startswith('--')]
    if '--preview' in argv:
        names = [n for n in names if n != argv[argv.index('--preview') + 1]]
    reg = regions(atlas)
    pages = {}
    pairs = []
    for name in names:
        r = reg[name]
        path = os.path.join(os.path.dirname(atlas), r['page'])
        page = pages.setdefault(path, Image.open(path).convert('RGBA'))
        x, y = r['xy']
        w, h = r['size']
        before = page.crop((x, y, x + w, y + h))
        after = outlined(before)
        pairs.append((name, before, after))
        if '--write' in argv:
            page.paste(after, (x, y))  # replaces the region's pixels (alpha included)
    if '--write' in argv:
        for path, page in pages.items():
            page.save(path)
            print('wrote', path)
    if '--preview' in argv:
        out_path = argv[argv.index('--preview') + 1]
        scale, game = 8, 3
        cell_w = max(p[1].width for p in pairs) * scale + 20
        cell_h = max(p[1].height for p in pairs) * scale + 20
        sheet = Image.new('RGBA', (cell_w * 2 + 40 + 2 * (max(p[1].width for p in pairs) * game + 20),
                                   len(pairs) * (cell_h + 24) + 10), (52, 104, 44, 255))
        d = ImageDraw.Draw(sheet)
        for i, (name, before, after) in enumerate(pairs):
            y0 = 10 + i * (cell_h + 24)
            for j, im in enumerate((before, after)):
                big = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
                sheet.paste(big, (10 + j * cell_w, y0), big)
                small = im.resize((im.width * game, im.height * game), Image.NEAREST)
                sheet.paste(small, (2 * cell_w + 20 + j * (im.width * game + 20), y0), small)
            d.text((10, y0 + cell_h), '%s - before / after (8x), and at x3 game size' % name, fill=(255, 255, 255, 255))
        sheet.save(out_path)
        print('preview', out_path)


if __name__ == '__main__':
    main(sys.argv)
