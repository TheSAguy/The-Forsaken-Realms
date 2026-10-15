"""Round 629: where the gear sets are found - the Arena prize lists and the color Equipment shops (the user: "We need to
ensure that these sets are available in the appropriate Armories/Arena rewards pool ... We also need to update the Arena
rewards. Before each had 2 sets, but that was when we only had 5 slots.").

- The five AI capitals' Arena (round 3's win item): its own color's Regalia + the Bane set against one of its enemies
  (White -> Flamebane, Blue -> Wildbane, Black -> Sunbane, Red -> Tidebane, Green -> Nightbane), and its map fragment.
  Replaces round 432's two sets of four.
- The Player Capitol: Level 1 (round 3's item) adds Ironclad and Gilded Paragon (Mithril Armor, a Mythic, only at
  Level 2); Level 2 (Challenge) adds Dragonlord's Hoard, Stonewall Bulwark and Fblthp's Lost Things to its pools by
  rarity (Uncommon -> the 25% pool, Rare -> the 40% pool, Mythic -> the 5% pool) and to round 3's sure item.
- Each color's Equipment shop adds its Regalia and shows 4 of its list (3 before).

Only names the catalog holds are written, so a rerun after new items are added (round 631) fills them in. Map edits touch
only the itemNames lines. usage: python arena_prizes.py --repo <TFR repo root> [--dry]
"""
import argparse
import html
import io
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_gear_sets import SETS  # noqa: E402

BY_ID = {s['id']: s for s in SETS}
CAPITALS = {  # map file: (Regalia, Bane)
    'plains_capital.tmx': ('dawnward', 'flamebane'),
    'island_capital.tmx': ('tidewise', 'wildbane'),
    'swamp_capital.tmx': ('gravebound', 'sunbane'),
    'mountain_capital.tmx': ('emberclad', 'tidebane'),
    'forest_capital.tmx': ('wildgrove', 'nightbane'),
}
SHOPS = {'WhiteEquipment': 'dawnward', 'BlueEquipment': 'tidewise', 'BlackEquipment': 'gravebound',
         'RedEquipment': 'emberclad', 'GreenEquipment': 'wildgrove'}
NAME_LINE = re.compile(r'^(\s*)&quot;(.*?)&quot;(,?)\s*$')


def item_blocks(lines):
    """(start, end) line spans of every itemNames list in a map: the names sit one a line between the "[" and "]"."""
    i = 0
    while i < len(lines):
        if '&quot;itemNames&quot;' in lines[i] and lines[i].rstrip().endswith('['):
            j = i + 1
            while j < len(lines) and not lines[j].strip().startswith(']'):
                j += 1
            yield i + 1, j
            i = j
        i += 1


def names_in(lines, start, end):
    return [html.unescape(NAME_LINE.match(lines[k]).group(2)) for k in range(start, end)]


def write_block(lines, start, end, names):
    indent = NAME_LINE.match(lines[start]).group(1)
    nl = '\r\n' if lines[start].endswith('\r\n') else '\n'
    new = [indent + '&quot;' + html.escape(n, quote=False).replace('"', '&quot;') + '&quot;' + (',' if k < len(names) - 1 else '')
           + nl for k, n in enumerate(names)]
    return lines[:start] + new + lines[end:]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--repo', required=True)
    ap.add_argument('--dry', action='store_true')
    a = ap.parse_args()
    plane = os.path.join(a.repo, 'forge-gui', 'res', 'adventure', 'The Forsaken Realms')
    catalog = {i['name']: i for i in json.load(open(os.path.join(plane, 'world', 'items.json'), encoding='utf-8'))}

    def present(names):
        return [n for n in names if n in catalog]

    # the five AI capitals
    for fname, (reg, bane) in CAPITALS.items():
        path = os.path.join(plane, 'maps', 'map', 'main_story', fname)
        lines = io.open(path, encoding='utf-8', newline='').readlines()
        blocks = list(item_blocks(lines))
        assert len(blocks) == 1, (fname, len(blocks))
        start, end = blocks[0]
        old = names_in(lines, start, end)
        fragments = [n for n in old if n.endswith('Map Fragment')]
        new = present(BY_ID[reg]['items']) + present(BY_ID[bane]['items']) + fragments
        print('%s: %d -> %d (%s + %s + %s)' % (fname, len(old), len(new), BY_ID[reg]['name'], BY_ID[bane]['name'],
                                                ', '.join(fragments)))
        if not a.dry:
            io.open(path, 'w', encoding='utf-8', newline='').writelines(write_block(lines, start, end, new))

    # the Player Capitol
    path = os.path.join(plane, 'maps', 'map', 'towns', 'player_capital.tmx')
    lines = io.open(path, encoding='utf-8', newline='').readlines()
    level1 = [n for s in ('ironclad', 'paragon') for n in BY_ID[s]['items'] if n != 'Mithril Armor']
    legend2 = [n for s in ('dragonlord', 'stonewall', 'fblthp') for n in BY_ID[s]['items']] + ['Mithril Armor']
    by_rarity = {r: present([n for n in legend2 if catalog.get(n, {}).get('rarity') == r])
                 for r in ('Common', 'Uncommon', 'Rare', 'Mythic')}
    changes = 0
    for start, end in reversed(list(item_blocks(lines))):  # bottom up: a rewrite shifts the lines below it
        old = names_in(lines, start, end)
        rar = {catalog.get(n, {}).get('rarity') for n in old}
        size = len(old)
        # The Level 2 pools first, by rarity (the 155 Rare pool holds Gold Armor too); then the two round-3 lists.
        if rar == {'Mythic'}:
            add, label = by_rarity['Mythic'], 'Level 2 Mythic pool'
        elif rar == {'Rare'} and size > 100:
            add, label = by_rarity['Rare'], 'Level 2 Rare pool'
        elif 'Uncommon' in rar and size > 100:
            add, label = by_rarity['Uncommon'] + by_rarity['Common'], 'Level 2 Uncommon pool'
        elif 'Gold Armor' in old and size > 40:        # Level 1, round 3's item
            add, label = present(level1), 'Level 1 round 3'
        elif 'Gold Armor' in old:                       # Level 2, round 3's sure item
            add, label = present(legend2), 'Level 2 round 3 (sure item)'
        else:
            continue  # the other Rare pool (86) keeps its list
        new = old + [n for n in add if n not in old]
        if len(new) != len(old):
            changes += 1
            print('player_capital.tmx %s: %d -> %d' % (label, len(old), len(new)))
            lines = write_block(lines, start, end, new)
    if not a.dry and changes:
        io.open(path, 'w', encoding='utf-8', newline='').writelines(lines)

    # the color Equipment shops
    path = os.path.join(plane, 'world', 'shops.json')
    shops = json.load(open(path, encoding='utf-8'))
    for shop in shops:
        sid = SHOPS.get(shop.get('name'))
        if not sid:
            continue
        reward = shop['rewards'][0]
        before = list(reward['itemNames'])
        reward['itemNames'] = before + [n for n in present(BY_ID[sid]['items']) if n not in before]
        reward['count'] = 4
        print('%s: %d -> %d names, shows 4' % (shop['name'], len(before), len(reward['itemNames'])))
    if not a.dry:
        with open(path, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(json.dumps(shops, indent=1) + '\n')


if __name__ == '__main__':
    main()
