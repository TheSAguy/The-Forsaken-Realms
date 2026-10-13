"""Card-art player portraits (rounds 538, 542): every dev-tools/portraits/<race>.json becomes that race's own atlas,
sprites/heroes/portraits/<race>.atlas + .png (race name in lower case, spaces as underscores), which HeroListData loads
the first time that race's portrait is shown - so only the races in use sit in memory. Smooth and mipmapped, where the
pixel avatar atlas (avatar_tfr.atlas, the fallback, left as it is) is Nearest.

Each pick crops a square from a card image in Forge's card cache (%LOCALAPPDATA%/Forge/Cache/pics/cards): its center
(u, v) is a fraction of the "candidate square" - 40% of the card's height, from 10.5% down, centered - kept inside the
art box. Round 584 (the user, with a Sheoldred example: "Can we make the image selection boxes bigger, on probably all
portraits ... I want to see more of the body"): the square's side is "body" times the candidate square (default 1.0,
about the art's full height), where rounds 538-575 used the face-sized "zoom" (still in the JSON, no longer read). A
pick's "art" [left, top, right, bottom] (fractions of the card) replaces the usual art box for a frame that differs,
such as a planeswalker's, whose art ends higher. "gender": "shared" puts it in both the race's pools, "m" or "f" in one;
the pools are the race's maleAvatar / femaleAvatar region names in world/heroes.json (a dragon's one region serves
both, so every pick goes in it). Cells are 256 px with 8-px extruded gutters (linear filtering and mipmaps stay inside a
cell). A portrait atlas whose race has no JSON any more is deleted.

python -I dev-tools/portraits/make_portraits.py <TFR repo root> <card cache dir> [--preview <dir>]
--preview writes one numbered sheet a race to <dir> instead of the atlases."""
import glob, json, os, re, sys
from PIL import Image, ImageDraw

ROOT, CACHE = sys.argv[1], sys.argv[2]
PREVIEW = sys.argv[sys.argv.index('--preview') + 1] if '--preview' in sys.argv else None
ART = (0.085, 0.118, 0.915, 0.55)  # the usual art box, as fractions of the card
BODY = 1.0
PLANE = os.path.join(ROOT, 'forge-gui', 'res', 'adventure', 'The Forsaken Realms')
OUT = os.path.join(PLANE, 'sprites', 'heroes', 'portraits')
assert os.path.isdir(os.path.join(PLANE, 'sprites', 'heroes')), 'not a TFR repo root: ' + ROOT
os.makedirs(OUT, exist_ok=True)
SIZE, PAD, COLS = 256, 8, 6
STRIDE = SIZE + 2 * PAD


def crop(pick):
    card = Image.open(os.path.join(CACHE, pick['file'])).convert('RGB')
    w, h = card.size
    ax0, ay0, ax1, ay1 = pick.get('art', ART)
    sq_top, sq_side = 0.105 * h, 0.40 * h
    sq_left = (w - sq_side) / 2
    side = min(pick.get('body', BODY) * sq_side, (ay1 - ay0) * h, (ax1 - ax0) * w)
    left = sq_left + pick['u'] * sq_side - side / 2
    top = sq_top + pick['v'] * sq_side - side / 2
    top = min(max(top, ay0 * h), ay1 * h - side)
    left = min(max(left, ax0 * w), ax1 * w - side)
    return card.crop((round(left), round(top), round(left + side), round(top + side))).resize((SIZE, SIZE), Image.LANCZOS)


def preview(race, picks):
    """One sheet a race: every portrait at 160 px, numbered in pick order (the JSON's order)."""
    tile, cols = 160, 8
    rows = (len(picks) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * (tile + 6) + 6, rows * (tile + 24) + 30), (24, 24, 30))
    draw = ImageDraw.Draw(sheet)
    draw.text((6, 6), race['race'], fill=(240, 240, 240))
    for i, pick in enumerate(picks):
        x, y = 6 + (i % cols) * (tile + 6), 30 + (i // cols) * (tile + 24)
        sheet.paste(crop(pick).resize((tile, tile), Image.LANCZOS), (x, y))
        draw.text((x, y + tile + 4), '%d %s %s' % (i + 1, pick['gender'], pick['card'][:20]), fill=(200, 200, 210))
    sheet.save(os.path.join(PREVIEW, slug(race['race']) + '.png'))


def slug(race):
    return race.lower().replace(' ', '_')


heroes_text = open(os.path.join(PLANE, 'world', 'heroes.json'), encoding='utf-8').read()
heroes = {h['name']: h for h in json.loads(re.sub(r'^\s*//.*$', '', heroes_text, flags=re.M))['heroes']}
made = set()
for path in sorted(glob.glob(os.path.join(ROOT, 'dev-tools', 'portraits', '*.json'))):
    race = json.load(open(path, encoding='utf-8'))
    hero = heroes[race['race']]
    pools = {'m': hero['maleAvatar'], 'f': hero['femaleAvatar']}
    picks = race['picks']
    if PREVIEW:
        preview(race, picks)
        print('%-12s %2d portraits -> %s.png' % (race['race'], len(picks), slug(race['race'])))
        continue
    rows = (len(picks) + COLS - 1) // COLS
    page = Image.new('RGBA', (min(COLS, len(picks)) * STRIDE, rows * STRIDE))
    for i, pick in enumerate(picks):
        cell = crop(pick).convert('RGBA')
        out = Image.new('RGBA', (STRIDE, STRIDE))
        src, dst = cell.load(), out.load()
        for y in range(STRIDE):  # the cell, its edge pixels extruded into the gutter
            for x in range(STRIDE):
                dst[x, y] = src[min(max(x - PAD, 0), SIZE - 1), min(max(y - PAD, 0), SIZE - 1)]
        pick['_xy'] = ((i % COLS) * STRIDE + PAD, (i // COLS) * STRIDE + PAD)
        page.paste(out, ((i % COLS) * STRIDE, (i // COLS) * STRIDE))
    name = slug(race['race'])
    page.save(os.path.join(OUT, name + '.png'))
    lines = [name + '.png', 'size: %d,%d' % page.size, 'format: RGBA8888', 'filter: MipMapLinearLinear,Linear',
             'repeat: none']
    one = pools['m'] == pools['f']  # a dragon: one region for both
    for gender in (('m',) if one else ('m', 'f')):
        pool = [p for p in picks if one or p['gender'] in ('shared', gender)]
        for idx, p in enumerate(pool):
            lines += [pools[gender], '  xy: %d, %d' % p['_xy'], '  size: %d, %d' % (SIZE, SIZE), '  index: %d' % idx]
    open(os.path.join(OUT, name + '.atlas'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    made.add(name)
    print('%-12s %2d portraits -> portraits/%s.atlas (%dx%d)' % (race['race'], len(picks), name, page.size[0], page.size[1]))
for stale in [] if PREVIEW else glob.glob(os.path.join(OUT, '*.atlas')) + glob.glob(os.path.join(OUT, '*.png')):
    if os.path.splitext(os.path.basename(stale))[0] not in made:
        os.remove(stale)
        print('removed stale', os.path.basename(stale))
