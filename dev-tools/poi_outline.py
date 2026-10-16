r"""poi_outline.py - the 1px black outline every world-map ENTRANCE sprite carries (rounds 655 + 657).

The user (2026-10-16), of the Elf Town and the Lake of the Hydra (Grove7): "Could we add a 1px black border round these
2 dungeon entrances" (round 655), then "I like the 1px outline more. can we please apply that to all cave entrances
that don't have one. Let's also make that the rule for entrances moving forward." (round 657). The rule is in
CLAUDE.md; `--audit` is its check.

THE OUTLINE (inside the sprite's own atlas region, so its size, position and footprint stay):
  - the silhouette is alpha >= 128 (a painted sprite's feathered fringe below that is cut, so the edge is crisp), less
    specks of 3 pixels or fewer (a stray pixel would become a lone black dot);
  - the outline is every transparent pixel 4-adjacent to the silhouette, plus the silhouette's own pixels on the
    region's border where there is no room outside;
  - silhouette pixels become fully opaque; the outline is pure black.
  A sprite whose edge is already dark (outline score >= 0.9) is left alone, so a re-run never doubles an outline.

OUTLINE SCORE: the share of silhouette edge pixels that are dark (luminance < 45) or much darker than the pixel just
inside them (< 0.55x and < 110) - a light-gray border does not count, a dark one of any color does. Below 0.30 an
entrance plainly has none; 0.30-0.65 needs an eye (a dark-outlined cave on a soft drop shadow scores there), which is
what dev-tools/entrance_outlines.json's "reviewed_ok" list records; 0.65 and up has one.

usage:
  python poi_outline.py <atlas> <region> [<region> ...] [--preview out.png] [--write]
  python poi_outline.py --audit <repo root>             entrances with no outline and not reviewed; exit 1 if any
  python poi_outline.py --apply <repo root> <list.json> [--write]
        outline every [atlas, region] in the list's "outline" entries. A plane atlas is edited in place; a sprite in
        adventure/common (upstream) is copied, outlined, onto the plane's maps/tileset/entrances_outlined page and its
        places in points_of_interest.json point there instead - the upstream file is never edited."""
import io
import json
import os
import re
import sys

from PIL import Image, ImageDraw

BLACK = (0, 0, 0, 255)
ENTRANCE_TYPES = {'cave', 'dungeon', 'sidebosseasy', 'sidebossmoderate', 'sidebosshard'}
HAS_OUTLINE = 0.65
NO_OUTLINE = 0.30
PLANE_PAGE = 'entrances_outlined'


def regions(atlas):
    """name -> {page, xy, size, lines (every property line, for copying)}"""
    out, page, cur = {}, None, None
    for ln in open(atlas, encoding='utf-8').read().splitlines():
        if ln.strip().endswith('.png'):
            page = ln.strip()
            cur = None
            continue
        if ln and not ln.startswith(' ') and ':' not in ln:
            cur = ln.strip()
            out.setdefault(cur, {'page': page, 'lines': []})
        elif cur and ln.startswith(' '):
            s = ln.strip()
            out[cur]['lines'].append(s)
            if s.startswith('xy:') and 'xy' not in out[cur]:
                out[cur]['xy'] = tuple(int(v) for v in s.split(':')[1].split(','))
            elif s.startswith('size:') and 'size' not in out[cur]:
                out[cur]['size'] = tuple(int(v) for v in s.split(':')[1].split(','))
        elif ln.strip() == '':
            cur = None
    return out


def lum(c):
    return 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]


def outline_score(tile):
    w, h = tile.size
    px = tile.load()
    edge = ok = 0
    for y in range(h):
        for x in range(w):
            if px[x, y][3] < 128:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if not (0 <= nx < w and 0 <= ny < h) or px[nx, ny][3] < 128:
                    edge += 1
                    ix, iy = x - dx, y - dy
                    inner = px[ix, iy] if 0 <= ix < w and 0 <= iy < h and px[ix, iy][3] >= 128 else None
                    le = lum(px[x, y])
                    if le < 45 or (inner is not None and le < 0.55 * lum(inner) and le < 110):
                        ok += 1
                    break
    return ok / max(1, edge)


def _drop_specks(mask, w, h, smallest=4):
    seen = [[False] * h for _ in range(w)]
    for x in range(w):
        for y in range(h):
            if not mask[x][y] or seen[x][y]:
                continue
            comp, stack = [], [(x, y)]
            seen[x][y] = True
            while stack:
                cx, cy = stack.pop()
                comp.append((cx, cy))
                for dx in (-1, 0, 1):
                    for dy in (-1, 0, 1):
                        nx, ny = cx + dx, cy + dy
                        if 0 <= nx < w and 0 <= ny < h and mask[nx][ny] and not seen[nx][ny]:
                            seen[nx][ny] = True
                            stack.append((nx, ny))
            if len(comp) < smallest:
                for cx, cy in comp:
                    mask[cx][cy] = False


def outlined(tile):
    w, h = tile.size
    src = tile.load()
    mask = [[src[x, y][3] >= 128 for y in range(h)] for x in range(w)]
    _drop_specks(mask, w, h)
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


def crop(atlas_path, reg):
    r = reg
    page = Image.open(os.path.join(os.path.dirname(atlas_path), r['page'])).convert('RGBA')
    x, y = r['xy']
    w, h = r['size']
    return page.crop((x, y, x + w, y + h))


def lenient(path):
    t = io.open(path, encoding='utf-8-sig').read()
    try:
        return json.loads(t)
    except ValueError:
        return json.loads(re.sub(r'(?m)^\s*//[^\n]*', '', t))


def plane_dirs(root):
    res = os.path.join(root, 'forge-gui', 'res', 'adventure')
    return os.path.join(res, 'The Forsaken Realms'), os.path.join(res, 'common')


def resolve(plane, common, path):
    for base in (plane, common):
        q = os.path.normpath(os.path.join(base, path))
        if os.path.exists(q):
            return q
    return None


def entrances(root):
    """(atlas as written, sprite) -> [place names], for every entrance type."""
    plane, _ = plane_dirs(root)
    out = {}
    for p in lenient(os.path.join(plane, 'world', 'points_of_interest.json')):
        if p.get('type') in ENTRANCE_TYPES and p.get('spriteAtlas'):
            out.setdefault((p['spriteAtlas'], p['sprite']), []).append(p['name'])
    return out


def audit(root):
    plane, common = plane_dirs(root)
    listing = lenient(os.path.join(root, 'dev-tools', 'entrance_outlines.json'))
    reviewed = {tuple(x) for x in listing.get('reviewed_ok', [])}
    bad = []
    for (atlas, sprite), names in sorted(entrances(root).items()):
        ap = resolve(plane, common, atlas)
        reg = regions(ap).get(sprite) if ap else None
        if not reg or 'xy' not in reg:
            bad.append((0.0, atlas, sprite, names, 'region not found'))
            continue
        s = outline_score(crop(ap, reg))
        if s >= HAS_OUTLINE or (atlas, sprite) in reviewed:
            continue
        bad.append((s, atlas, sprite, names, 'no outline' if s < NO_OUTLINE else 'check by eye'))
    for s, atlas, sprite, names, why in bad:
        print('%.2f  %-14s %s  %s  (%s)' % (s, why, sprite, atlas, ', '.join(names)))
    print('%d entrance sprite(s) without an outline (or unreviewed)' % len(bad))
    return 1 if bad else 0


def apply(root, list_path, write):
    plane, common = plane_dirs(root)
    todo = [tuple(x) for x in lenient(list_path).get('outline', [])]
    pages, done, moved = {}, 0, []
    for atlas, sprite in todo:
        ap = resolve(plane, common, atlas)
        reg = regions(ap)[sprite]
        tile = crop(ap, reg)
        if outline_score(tile) >= 0.9:
            continue  # already outlined (a re-run)
        new = outlined(tile)
        if os.path.normpath(ap).startswith(os.path.normpath(common)):
            moved.append((atlas, sprite, reg, new))
            continue
        path = os.path.join(os.path.dirname(ap), reg['page'])
        page = pages.setdefault(path, Image.open(path).convert('RGBA'))
        page.paste(new, reg['xy'])
        done += 1
    print('outlined in place: %d; upstream sprites to copy onto the plane: %d' % (done, len(moved)))
    if not write:
        print('[DRY RUN] nothing written. Pass --write to apply.')
        return 0
    for path, page in pages.items():
        page.save(path)
        print('wrote', path)
    if moved:
        tile_dir = os.path.join(plane, 'maps', 'tileset')
        width = sum(m[3].width for m in moved) + 2 * (len(moved) - 1)
        height = max(m[3].height for m in moved)
        sheet = Image.new('RGBA', (width, height), (0, 0, 0, 0))
        lines = [PLANE_PAGE + '.png', 'size: %d,%d' % (width, height), 'format: RGBA8888', 'filter: Nearest,Nearest',
                 'repeat: none']
        x = 0
        for atlas, sprite, reg, new in moved:
            sheet.paste(new, (x, 0))
            lines.append(sprite)
            lines.append('  xy: %d, 0' % x)
            for prop in reg['lines']:
                if not prop.startswith('xy:'):
                    lines.append('  ' + prop)
            x += new.width + 2
        sheet.save(os.path.join(tile_dir, PLANE_PAGE + '.png'))
        io.open(os.path.join(tile_dir, PLANE_PAGE + '.atlas'), 'w', encoding='utf-8', newline='\n').write(
            '\n'.join(lines) + '\n')
        # point the places at the plane's copy
        poi_path = os.path.join(plane, 'world', 'points_of_interest.json')
        text = io.open(poi_path, encoding='utf-8', newline='').read()
        target = '../The Forsaken Realms/maps/tileset/%s.atlas' % PLANE_PAGE
        for atlas, sprite, reg, new in moved:
            pat = re.compile(r'("spriteAtlas":\s*)"%s"(,\s*"sprite":\s*"%s")' % (re.escape(atlas), re.escape(sprite)))
            text, n = pat.subn(r'\1"%s"\2' % target, text)
            print('repointed %d place(s) using %s to %s' % (n, sprite, target))
        lenient_check = json.loads(text)
        assert lenient_check
        io.open(poi_path, 'w', encoding='utf-8', newline='').write(text)
    return 0


def preview(pairs, out_path):
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


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__)
    if argv[1] == '--audit':
        return audit(argv[2])
    if argv[1] == '--apply':
        return apply(argv[2], argv[3], '--write' in argv)
    atlas = argv[1]
    out_path = argv[argv.index('--preview') + 1] if '--preview' in argv else None
    names = [a for a in argv[2:] if not a.startswith('--') and a != out_path]
    reg = regions(atlas)
    pages, pairs = {}, []
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
            page.paste(after, (x, y))
    if '--write' in argv:
        for path, page in pages.items():
            page.save(path)
            print('wrote', path)
    if out_path:
        preview(pairs, out_path)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
