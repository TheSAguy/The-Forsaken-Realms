"""Card-art player portraits (rounds 538, 542): every dev-tools/portraits/<race>.json becomes that race's own atlas,
sprites/heroes/portraits/<race>.atlas + .png (race name in lower case, spaces as underscores), which HeroListData loads
the first time that race's portrait is shown - so only the races in use sit in memory. Smooth and mipmapped, where the
pixel avatar atlas (avatar_tfr.atlas, the fallback, left as it is) is Nearest.

Each pick crops a square from a card image in Forge's card cache (%LOCALAPPDATA%/Forge/Cache/pics/cards): its center
(u, v) and side (zoom) are fractions of the "candidate square" - 40% of the card's height, from 10.5% down, centered -
kept inside the art box. "gender": "shared" puts it in both the race's pools, "m" or "f" in one; the pools are the
race's maleAvatar / femaleAvatar region names in world/heroes.json (a dragon's one region serves both, so every pick
goes in it). Cells are 256 px with 8-px extruded gutters (linear filtering and mipmaps stay inside a cell). A portrait
atlas whose race has no JSON any more is deleted.

python -I dev-tools/portraits/make_portraits.py <TFR repo root> <card cache dir>"""
import glob, json, os, re, sys
from PIL import Image

ROOT, CACHE = sys.argv[1], sys.argv[2]
PLANE = os.path.join(ROOT, 'forge-gui', 'res', 'adventure', 'The Forsaken Realms')
OUT = os.path.join(PLANE, 'sprites', 'heroes', 'portraits')
assert os.path.isdir(os.path.join(PLANE, 'sprites', 'heroes')), 'not a TFR repo root: ' + ROOT
os.makedirs(OUT, exist_ok=True)
SIZE, PAD, COLS = 256, 8, 6
STRIDE = SIZE + 2 * PAD


def crop(pick):
    card = Image.open(os.path.join(CACHE, pick['file'])).convert('RGB')
    w, h = card.size
    sq_top, sq_side = 0.105 * h, 0.40 * h
    sq_left = (w - sq_side) / 2
    side = pick['zoom'] * sq_side
    left = sq_left + pick['u'] * sq_side - side / 2
    top = sq_top + pick['v'] * sq_side - side / 2
    top = min(max(top, 0.118 * h), 0.55 * h - side)
    left = min(max(left, 0.085 * w), 0.915 * w - side)
    return card.crop((round(left), round(top), round(left + side), round(top + side))).resize((SIZE, SIZE), Image.LANCZOS)


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
for stale in glob.glob(os.path.join(OUT, '*.atlas')) + glob.glob(os.path.join(OUT, '*.png')):
    if os.path.splitext(os.path.basename(stale))[0] not in made:
        os.remove(stale)
        print('removed stale', os.path.basename(stale))
