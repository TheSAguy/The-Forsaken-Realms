"""Card-art player portraits (round 538): every dev-tools/portraits/<race>.json becomes that race's avatar pool on the
second page of sprites/heroes/avatar_tfr.atlas - smooth and mipmapped, where the first page is Nearest pixel art.

Each pick crops a square from a card image in Forge's card cache (%LOCALAPPDATA%/Forge/Cache/pics/cards): its center
(u, v) and side (zoom) are fractions of the "candidate square" - 40% of the card's height, from 10.5% down, centered -
kept inside the art box. "gender": "shared" puts it in both the race's pools, "m" or "f" in one; the pools are the
race's maleAvatar / femaleAvatar regions in world/heroes.json (a dragon's one region serves both, so every pick goes in
it). A race's old pixel regions leave the first page. Cells are 256 px with 8-px extruded gutters (linear filtering and mipmaps
stay inside a cell).

python -I dev-tools/portraits/make_portraits.py <TFR repo root> <card cache dir> [--preview <png>]"""
import glob, json, os, re, sys
from PIL import Image

ROOT, CACHE = sys.argv[1], sys.argv[2]
PREVIEW = sys.argv[4] if len(sys.argv) > 4 and sys.argv[3] == '--preview' else None
HEROES = os.path.join(ROOT, 'forge-gui', 'res', 'adventure', 'The Forsaken Realms', 'sprites', 'heroes')
atlas_path = os.path.join(HEROES, 'avatar_tfr.atlas')
assert os.path.isfile(atlas_path), 'not a TFR repo root: ' + ROOT
PAGE, SIZE, PAD, COLS = 'avatar_cards.png', 256, 8, 6
STRIDE = SIZE + 2 * PAD


def crop(pick):
    card = Image.open(os.path.join(CACHE, pick['file'])).convert('RGB')
    w, h = card.size
    sq_top, sq_side = 0.105 * h, 0.40 * h
    sq_left = (w - sq_side) / 2
    side = pick['zoom'] * sq_side
    left = sq_left + pick['u'] * sq_side - side / 2
    top = sq_top + pick['v'] * sq_side - side / 2
    top = min(max(top, 0.112 * h), 0.55 * h - side)
    left = min(max(left, 0.085 * w), 0.915 * w - side)
    return card.crop((round(left), round(top), round(left + side), round(top + side))).resize((SIZE, SIZE), Image.LANCZOS)


heroes_text = open(os.path.join(ROOT, 'forge-gui', 'res', 'adventure', 'The Forsaken Realms', 'world', 'heroes.json'),
                   encoding='utf-8').read()
heroes = {h['name']: h for h in json.loads(re.sub(r'^\s*//.*$', '', heroes_text, flags=re.M))['heroes']}
races = []
for path in sorted(glob.glob(os.path.join(ROOT, 'dev-tools', 'portraits', '*.json'))):
    race = json.load(open(path, encoding='utf-8'))
    hero = heroes[race['race']]
    race['_pools'] = {'m': hero['maleAvatar'], 'f': hero['femaleAvatar']}
    races.append(race)
cells = []  # (race, pick, image)
for race in races:
    for pick in race['picks']:
        cells.append((race['race'], pick, crop(pick)))
rows = (len(cells) + COLS - 1) // COLS
page = Image.new('RGBA', (min(COLS, len(cells)) * STRIDE, rows * STRIDE))
for i, (_, pick, img) in enumerate(cells):
    cell = img.convert('RGBA')
    out = Image.new('RGBA', (STRIDE, STRIDE))
    src, dst = cell.load(), out.load()
    for y in range(STRIDE):  # the cell, its edge pixels extruded into the gutter
        for x in range(STRIDE):
            dst[x, y] = src[min(max(x - PAD, 0), SIZE - 1), min(max(y - PAD, 0), SIZE - 1)]
    pick['_xy'] = ((i % COLS) * STRIDE + PAD, (i // COLS) * STRIDE + PAD)
    page.paste(out, ((i % COLS) * STRIDE, (i // COLS) * STRIDE))
page.save(os.path.join(HEROES, PAGE))

text = open(atlas_path, encoding='utf-8', newline='').read()
nl = '\r\n' if '\r\n' in text else '\n'
lines = text.split(nl)
if PAGE in lines:  # this tool's page from an earlier run
    cut = lines.index(PAGE)
    while cut > 0 and lines[cut - 1] == '':
        cut -= 1
    lines = lines[:cut]
drop = {name for r in races for name in r['_pools'].values()}
kept, i, removed = [], 0, 0
while i < len(lines):
    if lines[i] in drop:
        i += 1
        while i < len(lines) and lines[i].startswith('  '):
            i += 1
        removed += 1
        continue
    kept.append(lines[i])
    i += 1
while kept and kept[-1] == '':
    kept.pop()
kept += ['', PAGE, 'size: %d,%d' % page.size, 'format: RGBA8888', 'filter: MipMapLinearLinear,Linear', 'repeat: none']
for race in races:
    pools = race['_pools']
    one = pools['m'] == pools['f']  # a dragon: one region for both
    for gender in (('m',) if one else ('m', 'f')):
        pool = [p for p in race['picks'] if one or p['gender'] in ('shared', gender)]
        for idx, p in enumerate(pool):
            kept += [pools[gender], '  xy: %d, %d' % p['_xy'], '  size: %d, %d' % (SIZE, SIZE), '  index: %d' % idx]
open(atlas_path, 'w', encoding='utf-8', newline='').write(nl.join(kept) + nl)
print('page', page.size, '-', len(cells), 'portraits for', [r['race'] for r in races], '- old regions removed:', removed)

if PREVIEW:
    sheet = Image.new('RGB', (len(cells) * (SIZE + 8) + 8, SIZE + 16), (228, 208, 166))
    for i, (_, _, img) in enumerate(cells):
        sheet.paste(img, (8 + i * (SIZE + 8), 8))
    sheet.save(PREVIEW)
