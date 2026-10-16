r"""import_r648.py - TFR round 648: six Realm of Legends items return to the Forsaken Realms.

The user (2026-10-16): "import the six items (hats in the Head slot, a new icon for Soothsayer's Hat) with drop
sources you propose", under the cross-plane import rules in CLAUDE.md (no Commander, no foreign quest lines, no unkept
promises). The 2026-08-10 Item Economy round had removed five of them because their Realm of Legends sources were
not imported; Soothsayer's Hat only ever dropped from Whtz in the Crypt of Broken Rules (a Commander dungeon - left out).

THE ITEMS (effects as Realm of Legends wrote them, descriptions checked against the cards)
  Jewel of Winds        Neck   Favorable Winds          Uncommon 30000  BlueEquipment - blue's Jewel, beside white's
                                                                         Blessings, red's War and green's Rage
  Soothsayer's Hat      Head   Soothsaying, +3 life     Uncommon  3000  BlueEquipment
  Gourmand's Hat        Head   Gourmand's Talent, -6    Rare      5000  GreenEquipment
  Heirloom Blade        Right  Heirloom Blade, -3       Uncommon  5000  BlackEquipment
  Reliquary Robes       Body   Reliquary Tower          Uncommon        WhiteEquipment - this plane's land-armor rule
                                                                         (Nomad / Cephalid / Cabal / Centaur Armor):
                                                                         one card fewer in the opening hand, not the
                                                                         -7 life Realm of Legends gave it
  Fortune Teller's Hat  Head   Fortune Teller's Talent  Rare      6000  Atemsis, All-Seeing's reward list (the blue war
                                                                         champion: it roams blue land while you are at
                                                                         war with blue and pays its whole list)
  The hats were Neck items in Realm of Legends; round 607 moved every hat to the Head slot.

ICON: Soothsayer's Hat is new - the 16x16 RPG Item Pack's pointed hat (Item__53), recolored blue with a gold star and
band (the user picked variant B), on its own page sprites/items_r648.png. The other five reuse icons this plane's
items.atlas still holds.

usage: python import_r648.py <repo root> --icon-source "<Art-Library>/Icons/16x16 RPG Item Pack/Item__53.png"
       [--no-enemy]   (skip the Atemsis reward - for a run while another session holds enemies.json)
Idempotent: an item, shop entry, csv row, atlas region or reward already present is left as it is."""
import io
import json
import os
import re
import sys

from PIL import Image

ITEMS = [
    ("Jewel of Winds", "Your flying creatures get +1/+1.", "Neck", "MoxSapphire", 30000,
     {"startBattleWithCard": ["Favorable Winds|SCD"]}, "Uncommon"),
    ("Soothsayer's Hat", "X: look at your top X cards and put them back in any order. 3UU: shuffle your library.",
     "Head", "Soothsayer'sHat", 3000, {"lifeModifier": 3, "startBattleWithCard": ["Soothsaying"]}, "Uncommon"),
    ("Gourmand's Hat", "On your turn your artifacts are Food. 2G: a 3/3 Raccoon the first time you gain life each turn. "
     "3G: also +1/+1 counters on your creatures.", "Head", "Gourmand'sHat", 5000,
     {"lifeModifier": -6, "startBattleWithCard": ["Gourmand's Talent|BLC"]}, "Rare"),
    ("Heirloom Blade", "Equip 1: +3/+1. When the equipped creature dies, the next creature in your library that shares "
     "a type with it goes to your hand.", "Right", "HeirloomBlade", 5000,
     {"lifeModifier": -3, "startBattleWithCard": ["Heirloom Blade|C17"]}, "Uncommon"),
    ("Reliquary Robes", "Taps for C. You have no maximum hand size.", "Body", "ReliquaryRobes", None,
     {"changeStartCards": -1, "startBattleWithCard": ["Reliquary Tower|C14"]}, "Uncommon"),
    ("Fortune Teller's Hat", "Look at your top card any time. 3U: play cards from the top once you have cast a spell "
     "that turn. 2U: spells you cast from anywhere but your hand cost 2 less.", "Head", "FortuneTeller'sHat", 6000,
     {"lifeModifier": -6, "startBattleWithCard": ["Fortune Teller's Talent|BLC"]}, "Rare"),
]
SHOPS = {"BlueEquipment": ["Jewel of Winds", "Soothsayer's Hat"], "GreenEquipment": ["Gourmand's Hat"],
         "BlackEquipment": ["Heirloom Blade"], "WhiteEquipment": ["Reliquary Robes"]}
DROP = ("Atemsis", "Fortune Teller's Hat")
ICON_PAGE, ICON_NAME = 'items_r648.png', "Soothsayer'sHat"


def nl_of(raw):
    return '\r\n' if '\r\n' in raw else '\n'


def write_items(plane):
    path = os.path.join(plane, 'world', 'items.json')
    raw = io.open(path, encoding='utf-8', newline='').read()
    nl = nl_of(raw)
    have = {i['name'] for i in json.loads(raw)}
    blocks = []
    for name, desc, slot, icon, cost, effect, rarity in ITEMS:
        if name in have:
            continue
        lines = ['    {', '        "name":  %s,' % json.dumps(name), '        "description":  %s,' % json.dumps(desc),
                 '        "equipmentSlot":  %s,' % json.dumps(slot), '        "iconName":  %s,' % json.dumps(icon)]
        if cost:
            lines.append('        "cost":  %d,' % cost)
        lines.append('        "effect":  %s,' % json.dumps(effect))
        lines.append('        "rarity":  %s' % json.dumps(rarity))
        lines.append('    }')
        blocks.append(nl.join(lines))
    if not blocks:
        return 0
    end = raw.rstrip().rindex('}') + 1  # the last item's closing brace
    raw = raw[:end] + ',' + nl + (',' + nl).join(blocks) + raw[end:]
    json.loads(raw)
    io.open(path, 'w', encoding='utf-8', newline='').write(raw)
    return len(blocks)


def write_shops(plane):
    path = os.path.join(plane, 'world', 'shops.json')
    raw = io.open(path, encoding='utf-8', newline='').read()
    nl = nl_of(raw)
    added = 0
    for shop, names in SHOPS.items():
        m = re.search(r'"name": %s,.*?"itemNames": \[(.*?)\n(\s*)\]' % re.escape(json.dumps(shop)), raw, re.S)
        if not m:
            raise SystemExit('shop %s has no itemNames list' % shop)
        body = m.group(1)
        indent = re.search(r'\n(\s*)"', body).group(1)
        new = [n for n in names if json.dumps(n) not in body]
        if not new:
            continue
        ins = body.rstrip() + ',' + ''.join(nl + indent + json.dumps(n) + ',' for n in new).rstrip(',')
        raw = raw[:m.start(1)] + ins + raw[m.end(1):]
        added += len(new)
    json.loads(raw)
    io.open(path, 'w', encoding='utf-8', newline='').write(raw)
    return added


def write_csv(plane):
    path = os.path.join(plane, 'config tables', 'items.csv')
    raw = io.open(path, encoding='utf-8', newline='').read()
    nl = nl_of(raw)
    rows = []
    for name, desc, slot, icon, cost, effect, rarity in ITEMS:
        if raw.startswith(name + ',') or (nl + name + ',') in raw or (nl + '"' + name + '",') in raw:
            continue
        cards = '; '.join('1x ' + c.split('|')[0] for c in effect.get('startBattleWithCard', []))
        note = '%s Slot: %s Battlefield: %s' % (desc, slot, cards)
        if effect.get('changeStartCards'):
            note += ' Starting hand: %d' % effect['changeStartCards']
        rows.append('%s,%s,%d,%s,N,"%s",Y,' % (name if ',' not in name else '"%s"' % name, rarity, cost or 1000, slot,
                                              note.replace('"', '""')))
    if rows:
        raw = raw.rstrip('\r\n') + nl + nl.join(rows) + nl
        io.open(path, 'w', encoding='utf-8', newline='').write(raw)
    return len(rows)


def make_icon(src_path):
    """The pointed hat recolored: outline kept, the black inside it a deep blue so it reads lit, the three purples a
    blue ramp, a gold band across the brim and a gold star on the cone."""
    src = Image.open(src_path).convert('RGBA')
    sp = src.load()
    K, D2, D, M, H = (0, 0, 0, 255), (22, 30, 78, 255), (34, 56, 128, 255), (54, 100, 190, 255), (118, 176, 242, 255)
    G1, G2 = (214, 160, 48, 255), (255, 232, 128, 255)
    im = Image.new('RGBA', (16, 16))
    p = im.load()
    for y in range(16):
        for x in range(16):
            c = sp[x, y]
            if c[3] == 0:
                continue
            rgb = c[:3]
            if rgb == (0, 0, 0):
                edge = any(not (0 <= x + dx < 16 and 0 <= y + dy < 16) or sp[x + dx, y + dy][3] == 0
                           for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                p[x, y] = K if edge else D2
            elif rgb == (34, 32, 52):
                p[x, y] = D
            elif rgb == (69, 40, 60):
                p[x, y] = M
            else:
                p[x, y] = H
    for x in range(1, 15):
        if p[x, 11][3] and p[x, 11][:3] != (0, 0, 0):
            p[x, 11] = G1 if x < 9 else G2
    p[8, 6] = G2
    if p[7, 6][:3] != (0, 0, 0):
        p[7, 6] = G1
    p[8, 5] = G1
    p[8, 7] = G1
    return im


def write_icon(plane, src_path):
    sprites = os.path.join(plane, 'sprites')
    atlas = os.path.join(sprites, 'items.atlas')
    text = io.open(atlas, encoding='utf-8', newline='').read()
    make_icon(src_path).save(os.path.join(sprites, ICON_PAGE))
    if '\n' + ICON_PAGE in text:
        return 0
    nl = nl_of(text)
    block = ['', ICON_PAGE, 'size: 16,16', 'format: RGBA8888', 'filter: Nearest,Nearest', 'repeat: none', ICON_NAME,
             '  xy: 0, 0', '  size: 16, 16']
    io.open(atlas, 'w', encoding='utf-8', newline='').write(text.rstrip('\r\n') + nl + nl.join(block) + nl)
    return 1


def write_drop(plane):
    path = os.path.join(plane, 'world', 'enemies.json')
    raw = io.open(path, encoding='utf-8', newline='').read()
    enemy, item = DROP
    m = re.search(r'"name": %s,' % re.escape(json.dumps(enemy)), raw)
    if not m:
        raise SystemExit('no enemy %s' % enemy)
    nxt = raw.find('"name":', m.end())
    nxt = len(raw) if nxt < 0 else nxt
    if '"itemName": %s' % json.dumps(item) in raw[m.start():nxt]:
        return 0
    # appended at the END of the list: the first named card stays the champion's signature (EnemySprite.getRewards)
    r = re.compile(r'(?m)^( *)"rewards": \[\r?\n( *)\{\r?\n( *)"').search(raw, m.start(), nxt)
    if not r:
        raise SystemExit('%s has no rewards list' % enemy)
    list_ind, ind, inner = r.group(1), r.group(2), r.group(3)
    close = re.compile(r'(?m)^%s\]' % list_ind).search(raw, r.end(), nxt)
    nl = nl_of(raw)
    last = raw.rindex('}', r.end(), close.start()) + 1  # the last reward's closing brace
    entry = (',' + nl + ind + '{' + nl + inner + '"type": "item",' + nl + inner + '"count": 1,' + nl + inner
             + '"itemName": ' + json.dumps(item) + nl + ind + '}')
    raw = raw[:last] + entry + raw[last:]
    json.loads(raw)
    io.open(path, 'w', encoding='utf-8', newline='').write(raw)
    return 1


def main(argv):
    if len(argv) < 2 or argv[1].startswith('-') or '--icon-source' not in argv:
        raise SystemExit(__doc__)
    plane = os.path.join(argv[1], 'forge-gui', 'res', 'adventure', 'The Forsaken Realms')
    src = argv[argv.index('--icon-source') + 1]
    print('items.json: %d added' % write_items(plane))
    print('shops.json: %d entries added' % write_shops(plane))
    print('items.csv: %d rows added' % write_csv(plane))
    print('icon page: %s' % ('added' if write_icon(plane, src) else 'already there (png rewritten)'))
    if '--no-enemy' not in argv:
        print('enemies.json: %s' % ('Atemsis drops the Fortune Teller\'s Hat' if write_drop(plane) else 'already there'))


if __name__ == '__main__':
    main(sys.argv)
