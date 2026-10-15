"""Round 630: the Warden keeps his promise - Liliana's and Chandra's Stones become their Pledges.

The Warden at each walker temple's door says "Defeat her and I can bring her back to herself. A walker who owes you
her mind may stand with you in battles yet to come", and nothing did (stock Forge never built it either; the stone was
a keepsake). Now, while you carry the stone, he takes it and gives you the walker's Pledge: a Neck item whose card,
"<Walker>'s Favor", sits in the command zone and drafts one card from that walker's boss spellbook at your first
upkeep (pick 1 of 3 into your hand) - once a duel.

One source for everything (the user's Discord report, 2026-10-15: "I got Lilianas Stone after defeating her, what am i
supposed to do with it?"):
- world/items.json: the two Pledges (text insert in the file's own layout) + the stones' descriptions
- config tables/items.csv: the same rows (the game regenerates it; the repo copy is a snapshot)
- sprites/items_r630.png + items.atlas: the Pledge icons (the gold-chain prism pendant, gem recolored)
- common/custom_cards/<walker>s_favor.txt + custom_card_pics/<Walker>'s Favor.fullborder.png: the card and its face
  (the boss card's own frame, art from the cached card image named below; needs the Forge card cache)
- the two temple maps: the Warden's dialog gains a "stone" node and a "pledge" node; his old node is kept for
  everyone without either (the three are mutually exclusive - MapDialog.activate() loads EVERY passing root node, and
  the last one loaded is the one shown); the temple Wardens get displayNameOverride "The Warden" as Orazca's have
- Orazca's three maps (ruin, restored town, Capitol): the Warden there takes a stone too (a cleared temple leaves the
  map); his stone nodes go first, so a pending main-quest node still wins

The spellbook is read from the boss card (waker_of_the_dead.txt / fire_of_kaladesh.txt) so the two stay one list, and
every name is checked against cardsfolder (DraftEffect has no null check - a missing name crashes the draft).

usage: python make_pledges.py --repo <TFR repo root> [--preview <dir>]   (no --repo: preview only)
"""
import argparse
import colorsys
import io
import json
import os
import re
import sys
import textwrap

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'gear_sets'))
from tint import parse_atlas  # noqa: E402

CACHE = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Forge', 'Cache', 'pics', 'cards')
ICON_PAGE = 'items_r630.png'
ICON_BASE = 'CelestialPrismPendant'

WALKERS = [
    dict(name='Liliana', colors='black', boss_card='waker_of_the_dead.txt', template='Waker of the Dead.fullborder.jpg',
         art='PUMA/Liliana of the Veil.full.jpg', art_box=(54, 78, 434, 355), artist='Steve Argyle',
         ramp=[(30, 12, 42), (96, 40, 130), (190, 146, 222)], icon='LilianasPledge',
         map='maps/map/main_story/temple_of_liliana/keep.tmx', warden=224,
         stone_desc='A small black gemstone recovered after your battle with Liliana. The Warden at her temple door '
                    'knows what it holds.',
         pledge_desc='Liliana is herself again, and she keeps her word: once in every battle she sends you one of '
                     'her spells.',
         gem='black', feel='cool and dark'),
    dict(name='Chandra', colors='red', boss_card='fire_of_kaladesh.txt', template='Fire of Kaladesh.fullborder.jpg',
         art='FRA/Chandra, Torch of Defiance1.full.jpg', art_box=(74, 72, 428, 330), artist='Kieran Yanner',
         ramp=[(104, 20, 10), (218, 74, 20), (255, 204, 92)], icon='ChandrasPledge',
         map='maps/map/main_story/templeofchandra.tmx', warden=170,
         stone_desc='A small red gemstone recovered after your battle with Chandra. The Warden at her temple door '
                    'knows what it holds.',
         pledge_desc='Chandra is herself again, and she keeps her word: once in every battle she sends you one of '
                     'her spells.',
         gem='red', feel='warm as a banked coal'),
]


def stone(w):
    return "%s's Stone" % w['name']


def pledge(w):
    return "%s's Pledge" % w['name']


def favor(w):
    return "%s's Favor" % w['name']


def slug(name):
    return ''.join(ch for ch in name.lower().replace(' ', '_') if ch.isalnum() or ch == '_')


# ------------------------------------------------------------------------------------------------ the spellbook
def spellbook(common, w, card_names):
    text = open(os.path.join(common, 'custom_cards', w['boss_card']), encoding='utf-8').read()
    m = re.search(r'^SVar:DraftSpell:.*?Spellbook\$\s*([^|\n]+)', text, re.M)
    book = []
    for raw in m.group(1).split(','):
        n = raw.strip()
        if n and n not in book:  # Waker of the Dead lists Liliana of the Dark Realms twice
            book.append(n)
    missing = [n for n in book if n.replace(';', ',') not in card_names]
    if missing:
        sys.exit('%s spellbook names not in cardsfolder: %s' % (w['name'], missing))
    return book


def all_card_names(repo):
    names = set()
    for root, _, files in os.walk(os.path.join(repo, 'forge-gui', 'res', 'cardsfolder')):
        for f in files:
            if f.endswith('.txt'):
                for line in open(os.path.join(root, f), encoding='utf-8', errors='replace'):
                    if line.startswith('Name:'):
                        names.add(line[5:].strip())
    return names


def card_script(w, book):
    oracle = ("At the beginning of your first upkeep, draft a card from %s's spellbook. (Look at three random cards "
              "from it and put one of them into your hand.)" % w['name'])
    return '\n'.join([
        'Name:' + favor(w),
        'ManaCost:no cost',
        'Colors:' + w['colors'],
        'Types:Enchantment',
        'T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Command | CheckSVar$ YourTurns | '
        'SVarCompare$ EQ1 | Execute$ TrigDraft | TriggerDescription$ ' + oracle,
        'SVar:TrigDraft:DB$ Draft | Zone$ Hand | Spellbook$ ' + ','.join(book),
        'SVar:YourTurns:Count$YourTurns',
        'Oracle:' + oracle,
    ]) + '\n', oracle


# ------------------------------------------------------------------------------------------------ the face
def font(name, size):
    try:
        return ImageFont.truetype('C:/Windows/Fonts/' + name, size)
    except OSError:
        return ImageFont.load_default()


def _row_fill(img, box, sample_x, keep=0.5, smooth=0):
    """Paint box row by row with a color from that row's sample_x span: the median (keep=0.5) for a clean bar end,
    a high percentile for a text box full of dark text, then averaged over +-smooth rows so no row reads as a line."""
    px = img.load()
    x0, y0, x1, y1 = box
    rows = []
    for y in range(y0, y1):
        row = sorted(px[x, y] for x in range(*sample_x))
        rows.append(row[int((len(row) - 1) * keep)])
    for k, y in enumerate(range(y0, y1)):
        near = rows[max(0, k - smooth):k + smooth + 1]
        c = tuple(sum(v[i] for v in near) // len(near) for i in range(3))
        for x in range(x0, x1):
            px[x, y] = c


def draw_face(common, w, oracle):
    face = Image.open(os.path.join(common, 'custom_card_pics', w['template'])).convert('RGB')
    if face.size != (486, 680):
        face = face.resize((486, 680), Image.LANCZOS)
    d = ImageDraw.Draw(face)
    # title: clear the bar's text, keep its ends
    _row_fill(face, (38, 36, 400, 67), (404, 444))
    d.text((44, 52), favor(w), font=font('georgiab.ttf', 27), fill=(16, 14, 12), anchor='lm')
    # art
    src = Image.open(os.path.join(CACHE, w['art'])).convert('RGB')
    art = src.crop(w['art_box']).resize((449 - 37, 377 - 76), Image.LANCZOS)
    face.paste(art, (37, 76))
    # rules text
    _row_fill(face, (37, 427, 450, 627), (37, 450), keep=0.9, smooth=6)
    y = 440
    for line in textwrap.wrap(oracle, 38):
        d.text((46, y), line, font=font('georgia.ttf', 20), fill=(16, 14, 12))
        y += 25
    y += 10
    flavor = '"I keep my word, Guardian. Do not make me regret it."' if w['name'] == 'Liliana' else \
        '"You cut the chain. The least I can do is light the way."'
    for line in textwrap.wrap(flavor, 42):
        d.text((46, y), line, font=font('georgiai.ttf', 18), fill=(40, 36, 32))
        y += 22
    # footer: the collector line becomes the artist credit
    d.rectangle((26, 634, 300, 664), fill=(0, 0, 0))
    d.text((30, 635), 'TFR', font=font('georgiab.ttf', 13), fill=(235, 235, 235))
    d.text((30, 650), 'Illus. ' + w['artist'], font=font('georgia.ttf', 13), fill=(235, 235, 235))
    return face


# ------------------------------------------------------------------------------------------------ the icon
def pledge_icon(plane, w):
    regions = parse_atlas(os.path.join(plane, 'sprites', 'items.atlas'))
    r = regions[ICON_BASE]
    base = Image.open(os.path.join(plane, 'sprites', r['page'])).convert('RGBA').crop(
        (r['x'], r['y'], r['x'] + r['w'], r['y'] + r['h']))
    out = base.copy()
    px = out.load()
    dark, mid, light = w['ramp']
    for y in range(out.height):
        for x in range(out.width):
            cr, cg, cb, a = px[x, y]
            if a == 0:
                continue
            h, l, s = colorsys.rgb_to_hls(cr / 255, cg / 255, cb / 255)
            if s > 0.2 and 0.42 < h < 0.72:  # the blue/cyan crystal; the gold chain and the outline stay
                t = max(0.0, min(1.0, (l - 0.15) / 0.7))
                a0, a1, f = (dark, mid, t / 0.5) if t < 0.5 else (mid, light, (t - 0.5) / 0.5)
                px[x, y] = tuple(int(a0[i] + (a1[i] - a0[i]) * f) for i in range(3)) + (a,)
    return out


def write_icons(plane):
    icons = [pledge_icon(plane, w) for w in WALKERS]
    size = icons[0].size
    page = Image.new('RGBA', (size[0] * len(icons), size[1]), (0, 0, 0, 0))
    for k, im in enumerate(icons):
        page.paste(im, (k * size[0], 0))
    page.save(os.path.join(plane, 'sprites', ICON_PAGE))
    atlas = os.path.join(plane, 'sprites', 'items.atlas')
    text = io.open(atlas, encoding='utf-8', newline='').read()
    if ICON_PAGE not in text:
        nl = '\r\n' if '\r\n' in text else '\n'
        block = ['', ICON_PAGE, 'size: %d,%d' % page.size, 'format: RGBA8888', 'filter: Nearest,Nearest', 'repeat: none']
        for k, w in enumerate(WALKERS):
            block += [w['icon'], '  xy: %d, 0' % (k * size[0]), '  size: %d, %d' % size]
        io.open(atlas, 'w', encoding='utf-8', newline='').write(text.rstrip('\r\n') + nl + nl.join(block) + nl)
    return icons


# ------------------------------------------------------------------------------------------------ items
def json_str(s):
    return json.dumps(s).replace("'", '\\u0027')


def item_block(w):
    pad = ' ' * 23
    return '\n'.join([
        '    {',
        '        "name":  %s,' % json_str(pledge(w)),
        '        "description":  %s,' % json_str(w['pledge_desc']),
        '        "equipmentSlot":  "Neck",',
        '        "iconName":  "%s",' % w['icon'],
        '        "cost":  10000,',
        '        "excludeFromGeneralSale":  true,',
        '        "effect":  {',
        pad + '"startBattleWithCardInCommandZone":  [',
        pad + ' ' * 41 + json_str(favor(w)),
        pad + ' ' * 37 + ']',
        ' ' * 19 + '},',
        '        "rarity":  "Mythic"',
        '    },',
    ]) + '\n'


def write_items(plane):
    path = os.path.join(plane, 'world', 'items.json')
    raw = io.open(path, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in raw else '\n'
    text = raw.replace('\r\n', '\n')
    for w in WALKERS:
        name = json_str(stone(w))
        m = re.search(r'    \{\n        "name":  %s,\n        "description":  ("(?:[^"\\]|\\.)*"),' % re.escape(name), text)
        assert m, stone(w)
        text = text.replace(m.group(0), m.group(0).replace(m.group(1), json_str(w['stone_desc'])), 1)
        if '"name":  %s' % json_str(pledge(w)) not in text:
            end = text.index('    },\n', m.end()) + len('    },\n')
            text = text[:end] + item_block(w) + text[end:]
    json.loads(text)  # still valid
    io.open(path, 'w', encoding='utf-8', newline='').write(text.replace('\n', nl))


def write_csv(plane):
    path = os.path.join(plane, 'config tables', 'items.csv')
    raw = io.open(path, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in raw else '\n'
    lines = raw.split(nl)
    out = []
    for line in lines:
        for w in WALKERS:
            if line.startswith(stone(w) + ','):
                line = '%s,Common,1000,,N,"%s",Y,' % (stone(w), w['stone_desc'])
        if not any(line.startswith(pledge(w) + ',') for w in WALKERS):
            out.append(line)
        for w in WALKERS:
            if line.startswith(stone(w) + ','):
                out.append('%s,Mythic,10000,Neck,N,Slot: Neck Command: 1x %s,Y,' % (pledge(w), favor(w)))
    io.open(path, 'w', encoding='utf-8', newline='').write(nl.join(out))


# ------------------------------------------------------------------------------------------------ the Warden
def xml_text(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')


def warden_nodes(w):
    s, p = stone(w), pledge(w)
    take = {
        'name': '(Hand him %s)' % s,
        'text': 'He closes his fist around the gem. Something like a scream leaves it, and then only quiet. When he '
                'opens his hand the stone hangs on a fine gold chain, %s. "Her mind is her own again, and she '
                'remembers who cut the chain. Wear this, and %s will answer you once in every battle."'
                % (w['feel'], w['name']),
        'options': [{'name': '(Take %s)' % p,
                     'action': [{'removeItem': s},
                                {'grantRewards': [{'type': 'item', 'itemName': p, 'count': 1}]}]}],
    }
    return [
        {'condition': [{'item': s}],
         'text': 'The Warden\'s eyes go to the %s stone you carry. "You beat her down to where the binding shows. '
                 'The chain lives in that stone now, Guardian. Give it to me, and I will finish what you started."'
                 % w['gem'],
         'options': [take, {'name': '"Not yet."'}]},
        {'condition': [{'item': p}, {'item': s, 'not': True}],
         'text': 'The Warden nods at the pledge on its chain. "%s remembers you, Guardian. Keep her close - the Five '
                 'will not forgive either of you."' % w['name'],
         'options': [{'name': '(Leave)'}]},
    ]


def name_warden(plane, w):
    """The temple Wardens were labeled by their stand-in enemy ("-Adept Black Wizard-" over his dialog); Orazca's
    already carry displayNameOverride "The Warden"."""
    path = os.path.join(plane, w['map'])
    text = io.open(path, encoding='utf-8', newline='').read()
    start = text.index('<object id="%d"' % w['warden'])
    end = text.index('</object>', start)
    if 'displayNameOverride' in text[start:end]:
        return False
    at = text.index('<properties>', start) + len('<properties>')
    nl = '\r\n' if '\r\n' in text else '\n'
    text = text[:at] + nl + '    <property name="displayNameOverride" value="The Warden"/>' + text[at:]
    io.open(path, 'w', encoding='utf-8', newline='').write(text)
    return True


def write_warden(plane, w):
    path = os.path.join(plane, w['map'])
    text = io.open(path, encoding='utf-8', newline='').read()
    if xml_text(json.dumps(stone(w))) in text.split('<object id="%d"' % w['warden'])[1].split('</object>')[0]:
        return False  # already patched
    start = text.index('<object id="%d"' % w['warden'])
    head = '<property name="dialog">[ {'
    at = text.index(head, start)
    assert at < text.index('</object>', start), 'the Warden has no dialog'
    old_cond = json.dumps([{'item': stone(w), 'not': True}, {'item': pledge(w), 'not': True}])
    new = ''.join(xml_text(json.dumps(n)) + ', ' for n in warden_nodes(w))
    text = text[:at] + '<property name="dialog">[ ' + new + '{ ' + xml_text('"condition":' + old_cond + ', ') + \
        text[at + len(head):]
    io.open(path, 'w', encoding='utf-8', newline='').write(text)
    return True


# The Warden at Orazca (each of its three looks) takes a stone too: a temple leaves the map after a full clear, and a
# stone carried out of it would otherwise wait for the temple to come back. His stone nodes go FIRST and his default
# line ("(Leave)", the first node, no condition) gets "carries no stone": the last passing root node is the one shown,
# so any main-quest node later in the list still wins and the trade never blocks the story.
ORAZCA = [('maps/map/towns/orazca.tmx', 100), ('maps/map/towns/orazca_restored.tmx', 100),
          ('maps/map/towns/player_capital.tmx', 110)]


def write_orazca(plane, rel, oid):
    path = os.path.join(plane, rel)
    text = io.open(path, encoding='utf-8', newline='').read()
    start = text.index('<object id="%d"' % oid)
    end = text.index('</object>', start)
    if xml_text(json.dumps(stone(WALKERS[0]))) in text[start:end]:
        return False
    head = '<property name="dialog">['
    at = text.index(head, start) + len(head)
    first = text.index('{', at)
    assert first < end and 'The Warden' in text[first:first + 400], rel
    nodes = ''.join(xml_text(json.dumps(warden_nodes(w)[0])) + ', ' for w in WALKERS)
    cond = xml_text('"condition": ' + json.dumps([{'item': stone(w), 'not': True} for w in WALKERS]) + ', ')
    text = text[:at] + ' ' + nodes + text[at:first + 1] + ' ' + cond + text[first + 1:]
    io.open(path, 'w', encoding='utf-8', newline='').write(text)
    return True


# ------------------------------------------------------------------------------------------------ main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--repo')
    ap.add_argument('--preview')
    a = ap.parse_args()
    root = a.repo or 'C:/TFR/repo'
    plane = os.path.join(root, 'forge-gui', 'res', 'adventure', 'The Forsaken Realms')
    common = os.path.join(root, 'forge-gui', 'res', 'adventure', 'common')
    names = all_card_names(root)
    for n in [favor(w) for w in WALKERS] + [pledge(w) for w in WALKERS]:
        if n in names:
            sys.exit('%s is a real card name' % n)
    built = []
    for w in WALKERS:
        book = spellbook(common, w, names)
        script, oracle = card_script(w, book)
        built.append((w, book, script, draw_face(common, w, oracle)))
        print('%s: %d cards in the spellbook' % (favor(w), len(book)))
    if a.preview:
        os.makedirs(a.preview, exist_ok=True)
        icons = [pledge_icon(plane, w) for w in WALKERS]
        sheet = Image.new('RGB', (2 * 494 + 2 * 276, 690), (30, 30, 34))
        for k, (w, _, _, f) in enumerate(built):
            sheet.paste(f, (4 + k * 494, 4))
            big = icons[k].resize((256, 256), Image.NEAREST)
            sheet.paste(big, (2 * 494 + k * 276 + 10, 40), big)
            sheet.paste(icons[k], (2 * 494 + k * 276 + 10, 320), icons[k])
            sheet.paste(icons[k].resize((64, 64), Image.NEAREST), (2 * 494 + k * 276 + 60, 320),
                        icons[k].resize((64, 64), Image.NEAREST))
        sheet.save(os.path.join(a.preview, 'pledges_preview.png'))
        print('preview', os.path.join(a.preview, 'pledges_preview.png'))
    if not a.repo:
        return
    for w, book, script, f in built:
        with open(os.path.join(common, 'custom_cards', slug(favor(w)) + '.txt'), 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(script)
        f.save(os.path.join(common, 'custom_card_pics', favor(w) + '.fullborder.png'))
        print('Warden (%s): %s' % (w['map'], 'patched' if write_warden(plane, w) else 'already patched'))
        print('Warden (%s): %s' % (w['map'], 'named' if name_warden(plane, w) else 'already named'))
    for rel, oid in ORAZCA:
        print('Warden (%s): %s' % (rel, 'patched' if write_orazca(plane, rel, oid) else 'already patched'))
    write_icons(plane)
    write_items(plane)
    write_csv(plane)
    print('wrote 2 cards + faces, the icon page, items.json, items.csv')


if __name__ == '__main__':
    main()
