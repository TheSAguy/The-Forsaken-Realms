"""Round 629: the gear sets - fifteen sets of seven (the user, 2026-10-15: "create gear sets and have you get additional
bonuses when you have these sets ... start at 3 out of 7 you get a small bonus, then it gets bigger the more you have").

The one source of the sets. Writes:
  forge-gui/res/adventure/The Forsaken Realms/world/item_sets.json   the sets, their pieces and bonus tiers (util/ItemSets)
  forge-gui/res/adventure/common/custom_cards/<card>.txt            the 30 bonus cards (a 5- and a 7-piece card a set)
  forge-gui/res/adventure/common/custom_card_pics/<Card>.fullborder.png  their faces (488 x 680)

Bonuses sit on top of the items' own: 3 pieces +2 life (a Bane set +3, only against its color), 5 the set's card in the
command zone, 7 its full version in that card's place. Never a creature.

usage: python make_gear_sets.py --repo <TFR repo root> [--preview <png>] [--json-only]   (nothing is written without --repo; --preview
       alone only draws the card sheet)
"""
import argparse
import json
import os
import sys
import textwrap

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tint import Icons, tint  # noqa: E402

SLOTS = ['Head', 'Neck', 'Body', 'Right', 'Left', 'Legs', 'Boots']
COLOR_WORD = {'W': 'White', 'U': 'Blue', 'B': 'Black', 'R': 'Red', 'G': 'Green'}
COLOR_LOWER = {k: v.lower() for k, v in COLOR_WORD.items()}
# TerritoryControl's color wheel: a color's two enemies (a Bane set against X is built from X's enemies).
ENEMIES = {'W': 'BR', 'U': 'RG', 'B': 'GW', 'R': 'WU', 'G': 'UB'}


def gain_life(n):
    return 'SVar:TrigGainLife:DB$ GainLife | LifeAmount$ %d' % n


# ---------------------------------------------------------------------------------------------------- the 15 sets
# Each card: (name, [script lines], oracle). A 7-piece card repeats the 5-piece card's lines and adds its own.

def regalia(id_, name, color, ramp, items, prefix, lines5, text5, lines7, text7):
    card5 = (prefix + ' Presence', lines5, text5)
    card7 = (name, lines5 + lines7, text5 + '\\n' + text7)
    return dict(id=id_, name=name, family='Regalia', color=color, ramp=ramp, items=items, colors=COLOR_LOWER[color],
                card5=card5, card7=card7, tier5_text=text5, tier7_text=text7)


def bane(id_, name, against, ramp, items, prefix):
    word = COLOR_WORD[against]
    ward = ['S:Mode$ RaiseCost | EffectZone$ Command | ValidCard$ Card.%s | Type$ Spell | Activator$ Opponent | Amount$ 1 | '
            'Description$ %s spells your opponents cast cost {1} more to cast.' % (word, word)]
    text5 = '%s spells your opponents cast cost {1} more to cast.' % word
    extra = ['T:Mode$ SpellCast | ValidCard$ Card.%s | ValidActivatingPlayer$ Opponent | TriggerZones$ Command | '
             'Execute$ TrigGainLife | TriggerDescription$ Whenever an opponent casts a %s spell, you gain 1 life.'
             % (word, word.lower()), gain_life(1)]
    text7 = 'Whenever an opponent casts a %s spell, you gain 1 life.' % word.lower()
    colors = ','.join(COLOR_LOWER[c] for c in ENEMIES[against])
    return dict(id=id_, name=name, family='Bane', against=against, ramp=ramp, items=items, colors=colors,
                card5=(prefix + ' Ward', ward, text5), card7=(name, ward + extra, text5 + '\\n' + text7),
                tier5_text=text5, tier7_text=text7)


def legend(id_, name, ramp, items, card5name, lines5, text5, lines7, text7, effect5=None, effect7=None):
    return dict(id=id_, name=name, family='Legend', ramp=ramp, items=items, colors=None,
                card5=(card5name, lines5, text5), card7=(name, lines5 + lines7, text5 + '\\n' + text7),
                tier5_text=text5, tier7_text=text7, effect5=effect5 or {}, effect7=effect7 or {})


UPKEEP = 'T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Command | Execute$ %s | TriggerDescription$ %s'

SETS = [
    regalia('dawnward', 'Dawnward Regalia', 'W', [[120, 96, 40], [220, 190, 110], [255, 246, 214]],
            ['Mask of Hypochondria', 'Jewel of Blessings', 'Nomad Armor', 'Thunder Lasso', 'Prayerbook of Vigor',
             'Vindicator Greaves', 'Empyrial Greaves'], 'Dawnward',
            [UPKEEP % ('TrigGainLife', 'At the beginning of your upkeep, you gain 1 life.'), gain_life(1)],
            'At the beginning of your upkeep, you gain 1 life.',
            ['S:Mode$ Continuous | EffectZone$ Command | Affected$ Creature.YouCtrl | AddToughness$ 1 | '
             'Description$ Creatures you control get +0/+1.'],
            'Creatures you control get +0/+1.'),
    regalia('tidewise', 'Tidewise Regalia', 'U', [[18, 50, 110], [60, 130, 220], [190, 225, 255]],
            ['Roiling Stormcrown', 'Tidal Amulet', 'Cephalid Armor', "Giant's Bracer", 'Prayerbook of Fortunes',
             'Barrier Breeches', 'Sovereign Greaves'], 'Tidewise',
            [UPKEEP % ('TrigScry', 'At the beginning of your upkeep, scry 1.'), 'SVar:TrigScry:DB$ Scry | ScryNum$ 1'],
            'At the beginning of your upkeep, scry 1.',
            ['S:Mode$ ReduceCost | EffectZone$ Command | OnlyFirstSpell$ True | Type$ Spell | ValidSpell$ Spell.Instant,'
             'Spell.Sorcery | Activator$ You | Amount$ 1 | Description$ The first instant or sorcery spell you cast each '
             'turn costs {1} less to cast.'],
            'The first instant or sorcery spell you cast each turn costs {1} less to cast.'),
    regalia('gravebound', 'Gravebound Regalia', 'B', [[40, 18, 60], [120, 70, 160], [210, 180, 235]],
            ['Mask of Mortiphobia', 'Amulet of Scorn', 'Cabal Armor', 'Enormous Energy Blade', 'Little Black Book',
             'Leech Breeches', 'Princely Greaves'], 'Gravebound',
            ['T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Creature.YouCtrl | '
             'TriggerZones$ Command | Execute$ TrigDrain | TriggerDescription$ Whenever a creature you control dies, each '
             'opponent loses 1 life.', 'SVar:TrigDrain:DB$ LoseLife | Defined$ Player.Opponent | LifeAmount$ 1'],
            'Whenever a creature you control dies, each opponent loses 1 life.',
            ['T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Creature.YouCtrl | '
             'TriggerZones$ Command | Execute$ TrigGainLife | TriggerDescription$ Whenever a creature you control dies, '
             'you gain 1 life.', gain_life(1)],
            'Whenever a creature you control dies, you gain 1 life.'),
    regalia('emberclad', 'Emberclad Regalia', 'R', [[90, 18, 10], [210, 70, 40], [255, 190, 120]],
            ['Mask of Pyromania', 'Jewel of War', 'Barbarian Armor', 'Ramosian Greatsword', 'Tome of Triumph',
             'Hellkite Greaves', 'Firefrightener Shoes'], 'Emberclad',
            ['T:Mode$ SpellCast | ValidCard$ Instant,Sorcery | ValidActivatingPlayer$ You | ActivatorThisTurnCast$ EQ1 | '
             'TriggerZones$ Command | Execute$ TrigDamage | TriggerDescription$ Whenever you cast your first instant or '
             'sorcery spell each turn, CARDNAME deals 1 damage to each opponent.',
             'SVar:TrigDamage:DB$ DealDamage | Defined$ Player.Opponent | NumDmg$ 1'],
            'Whenever you cast your first instant or sorcery spell each turn, Emberclad Presence deals 1 damage to each '
            'opponent.',
            [], 'It deals 2 damage instead.'),
    regalia('wildgrove', 'Wildgrove Regalia', 'G', [[16, 60, 24], [60, 160, 70], [190, 240, 150]],
            ['Mask of Narcissism', 'Jewel of Rage', 'Centaur Armor', 'Colossal Dreadmace', 'Map to the World Tree',
             'Godsire Greaves', "Greenseeker's Shoes"], 'Wildgrove',
            ['S:Mode$ Continuous | EffectZone$ Command | Affected$ Creature.YouCtrl+powerGE4 | AddKeyword$ Trample | '
             'Description$ Creatures you control with power 4 or greater have trample.'],
            'Creatures you control with power 4 or greater have trample.',
            ['S:Mode$ ReduceCost | EffectZone$ Command | ValidCard$ Creature.powerGE4 | Type$ Spell | Activator$ You | '
             'Amount$ 1 | Description$ Creature spells you cast with power 4 or greater cost {1} less to cast.'],
            'Creature spells you cast with power 4 or greater cost {1} less to cast.'),

    bane('sunbane', 'Sunbane Raiment', 'W', [[50, 8, 16], [150, 28, 44], [235, 130, 120]],
         ['Mask of Valgavoth', 'Amulet of Fury', 'Armor of Urami', 'Unhallowed Sigil', 'Staff of Azar',
          "Alesha's War Skirt", "Witch's Shoes"], 'Sunbane'),
    bane('tidebane', 'Tidebane Raiment', 'U', [[60, 40, 8], [176, 128, 40], [245, 220, 140]],
         ['Pinnacle Circlet', 'Amulet of Favor', "Grovetender's Robes", "Shaman's Staff", "Warren Tender's Baton",
          'Spore Skirt', 'Concordant Boots'], 'Tidebane'),
    bane('nightbane', 'Nightbane Raiment', 'B', [[30, 70, 60], [120, 190, 160], [230, 250, 235]],
         ['Glorious Helm', 'Amulet of Mirth', 'Sanctuary Armor', 'Skyclave Maul', "Guard's Shield",
          "Lookout's Leggings", 'Ley Line Walker Boots'], 'Nightbane'),
    bane('flamebane', 'Flamebane Raiment', 'R', [[40, 60, 90], [140, 175, 215], [235, 245, 255]],
         ["Caretaker's Cap", 'Amulet of Mischief', "Oracle's Robes", "Serra's Prayer Book", 'The Blackstaff of Waterdeep',
          'Pack Leader Pants', "Peddler's Shoes"], 'Flamebane'),
    bane('wildbane', 'Wildbane Raiment', 'G', [[16, 24, 60], [50, 80, 150], [150, 190, 235]],
         ['Crown of the False God', 'Amulet of Agony', "Librarian's Robes", "Poet's Quill", 'Tome of the Trove',
          'Cutthroat Skirt', 'Immortal Treads'], 'Wildbane'),

    legend('dragonlord', "Dragonlord's Hoard", [[80, 36, 10], [200, 120, 40], [255, 220, 120]],
           ['Breaching Stormcrown', 'Phoenix Charm', 'Crucible Armor', 'Sword of the Ur-Dragon', 'Triassic Egg',
            'Draped Dragonhide', 'Faerie Slippers'], "Dragonlord's Presence",
           ['S:Mode$ ReduceCost | EffectZone$ Command | ValidCard$ Card.Dragon | Type$ Spell | Activator$ You | '
            'Amount$ 1 | Description$ Dragon spells you cast cost {1} less to cast.'],
           'Dragon spells you cast cost {1} less to cast.',
           ['S:Mode$ Continuous | EffectZone$ Command | Affected$ Creature.Dragon+YouCtrl | AddPower$ 1 | '
            'Description$ Dragons you control get +1/+0.'],
           'Dragons you control get +1/+0.'),
    legend('stonewall', 'Stonewall Bulwark', [[60, 50, 40], [150, 135, 110], [225, 215, 190]],
           ['High Ground Helm', 'Basilisk Collar', 'Glacial Armor', 'Aegis of the Meek', 'Shield of Air',
            'Greaves of Glare', 'Kry Greaves'], 'Stonewall Presence',
           ['S:Mode$ Continuous | EffectZone$ Command | Affected$ Creature.withDefender+YouCtrl | AddToughness$ 2 | '
            'Description$ Creatures you control with defender get +0/+2.'],
           'Creatures you control with defender get +0/+2.',
           ['T:Mode$ Blocks | ValidCard$ Creature.YouCtrl+withDefender | TriggerZones$ Command | Execute$ TrigGainLife | '
            'TriggerDescription$ Whenever a creature you control with defender blocks, you gain 1 life.', gain_life(1)],
           'Whenever a creature you control with defender blocks, you gain 1 life.'),
    legend('fblthp', "Fblthp's Lost Things", [[20, 70, 80], [70, 170, 180], [200, 245, 240]],
           ["Fblthp's Lost Hat", "Fblthp's Lost Locket", "Fblthp's Lost Shirt", "Fblthp's Lost Fishing Pole",
            "Fblthp's Lost Bauble", "Fblthp's Lost Trousers", "Fblthp's Lost Socks"], "Fblthp's Presence",
           ['T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Command | IsPresent$ Card.YouOwn | '
            'PresentZone$ Hand | PresentCompare$ EQ0 | Execute$ TrigDraw | TriggerDescription$ At the beginning of your '
            'upkeep, if you have no cards in hand, draw a card.', 'SVar:TrigDraw:DB$ Draw | NumCards$ 1'],
           'At the beginning of your upkeep, if you have no cards in hand, draw a card.',
           [], 'You began this game with one more card in hand.',
           effect7={'changeStartCards': 1}),
    legend('ironclad', 'Ironclad', [[40, 46, 56], [120, 130, 145], [215, 222, 232]],
           ['War Helm', 'Amulet of Vigor', 'Steel Armor', 'Steel Shield', 'Steel Sword', 'Steel Greaves', 'Iron Boots'],
           'Ironclad Presence',
           ['S:Mode$ ReduceCost | EffectZone$ Command | ValidCard$ Card | ValidSpell$ Activated.Equip | Activator$ You | '
            'Amount$ 1 | Description$ Equip abilities you activate cost {1} less to activate.'],
           'Equip abilities you activate cost {1} less to activate.',
           ['S:Mode$ Continuous | EffectZone$ Command | Affected$ Creature.equipped+YouCtrl | AddPower$ 1 | '
            'AddToughness$ 1 | Description$ Equipped creatures you control get +1/+1.'],
           'Equipped creatures you control get +1/+1.'),
    legend('paragon', 'Gilded Paragon', [[90, 70, 20], [215, 175, 60], [255, 245, 190]],
           ['Crown of the Vale', 'Golden Egg', 'Mithril Armor', 'Gold Shield', 'Staff of the Ages', 'Mithril Greaves',
            'Mithril Boots'], "Paragon's Presence",
           ['T:Mode$ Sacrificed | ValidCard$ Treasure.YouCtrl | TriggerZones$ Command | Execute$ TrigGainLife | '
            'TriggerDescription$ Whenever you sacrifice a Treasure, you gain 1 life.', gain_life(1)],
           'You start with a Treasure. Whenever you sacrifice a Treasure, you gain 1 life.',
           [], 'You start with a second Treasure, and gain 2 life instead.',
           effect5={'startBattleWithCard': ['c_a_treasure_sac']},
           effect7={'startBattleWithCard': ['c_a_treasure_sac', 'c_a_treasure_sac']}),
]


def paragon_fix(st):
    """Gilded Paragon's 7-piece card gains 2 life a Treasure, not 1 + 1."""
    name, lines, _ = st['card7']
    lines = [ln.replace('you gain 1 life.', 'you gain 2 life.') if ln.startswith('T:') else
             (gain_life(2) if ln.startswith('SVar:TrigGainLife') else ln) for ln in lines]
    st['card7'] = (name, lines, 'You start with two Treasures. Whenever you sacrifice a Treasure, you gain 2 life.')


def emberclad_fix(st):
    """Emberclad Regalia's 7-piece card: the same trigger, 2 damage (the agreed "2 damage instead")."""
    name, lines, _ = st['card7']
    lines = [ln.replace('NumDmg$ 1', 'NumDmg$ 2').replace('deals 1 damage', 'deals 2 damage') for ln in lines]
    st['card7'] = (name, lines, 'Whenever you cast your first instant or sorcery spell each turn, Emberclad Regalia deals 2 '
                                'damage to each opponent.')


for _s in SETS:
    if _s['id'] == 'paragon':
        paragon_fix(_s)
    if _s['id'] == 'emberclad':
        emberclad_fix(_s)


def slug(name):
    return ''.join(ch for ch in name.lower().replace(' ', '_').replace('-', '_') if ch.isalnum() or ch == '_')


def card_script(card, colors):
    name, lines, oracle = card
    out = ['Name:' + name, 'ManaCost:no cost']
    if colors:
        out.append('Colors:' + colors)
    out.append('Types:Enchantment')
    out += lines
    out.append('Oracle:' + oracle)
    return '\n'.join(out) + '\n'


# ---------------------------------------------------------------------------------------------------- where to find them
# Round 631 (the Sets page): a short "where" per piece and per set, from the data that places them - the shops that list
# a piece, the Arena prize lists (round 3's item of each capital, the Capitol's two), the enemies that drop it, and the
# Armories (every piece without excludeFromGeneralSale, by rarity). Rerun after a placement changes.
CAPITAL_MAPS = {'plains_capital.tmx': 'the Plains Capital Arena', 'island_capital.tmx': 'the Island Capital Arena',
                'swamp_capital.tmx': 'the Swamp Capital Arena', 'mountain_capital.tmx': 'the Mountain Capital Arena',
                'forest_capital.tmx': 'the Forest Capital Arena'}


def _quoted_names(path):
    import html
    import re
    # One name a line, as the prize lists hold them: pairing quotes across the file breaks on a dialog's escaped quote.
    text = html.unescape(open(path, encoding='utf-8').read())
    return set(re.findall(r'^[ \t]*"([^"\n]+)",?[ \t]*\r?$', text, re.M))


def sources(plane):
    """item name -> {'shops': [...], 'arenas': [...], 'drops': [...], 'armory': rarity or None}"""
    import re
    catalog = {i['name']: i for i in json.load(open(os.path.join(plane, 'world', 'items.json'), encoding='utf-8'))}
    names = {n for st in SETS for n in st['items']}
    out = {n: {'shops': [], 'arenas': [], 'drops': [], 'armory': None, 'exists': n in catalog} for n in names}
    for n in names:
        it = catalog.get(n)
        if it and not it.get('excludeFromGeneralSale'):
            out[n]['armory'] = it.get('rarity', 'Common')
    for sh in json.load(open(os.path.join(plane, 'world', 'shops.json'), encoding='utf-8')):
        label = re.sub(r'(?<!^)([A-Z])', r' \1', sh['name']) + ' shops'
        for r in sh.get('rewards') or []:
            for n in r.get('itemNames') or []:
                if n in out and label not in out[n]['shops']:
                    out[n]['shops'].append(label)
    for fn, label in CAPITAL_MAPS.items():
        found = _quoted_names(os.path.join(plane, 'maps', 'map', 'main_story', fn))
        for n in names & found:
            out[n]['arenas'].append(label)
    found = _quoted_names(os.path.join(plane, 'maps', 'map', 'towns', 'player_capital.tmx'))
    for n in names & found:
        out[n]['arenas'].append("your Capitol's Arena")
    for e in json.load(open(os.path.join(plane, 'world', 'enemies.json'), encoding='utf-8')):
        who = e.get('nameOverride') or e['name']
        for r in e.get('rewards') or []:
            n = r.get('itemName')
            if n in out and who not in out[n]['drops']:
                out[n]['drops'].append(who)
    return out


def piece_where(src):
    """One short line: shops, Arenas, a boss or two, the Armories - at most three places."""
    parts = src['shops'] + src['arenas']
    if src['drops']:
        parts.append('drops from ' + ' / '.join(sorted(src['drops'])[:2]))
    if src['armory']:
        parts.append('Armories (%s)' % src['armory'])
    if not src['exists']:
        return 'not in the game yet'
    return ', '.join(parts[:3]) if parts else 'no shop or drop yet'


def set_where(st, src):
    places = []
    for n in st['items']:
        for p in src[n]['shops'] + src[n]['arenas']:
            if p not in places:
                places.append(p)
    if any(src[n]['armory'] for n in st['items']):
        places.append('the Armories')
    if any(src[n]['drops'] for n in st['items']):
        places.append('some pieces drop from bosses')
    return '; '.join(places)


def sets_json(plane):
    src = sources(plane)
    out = []
    for st in SETS:
        three = {'pieces': 3, 'effect': {'lifeModifier': 3 if st['family'] == 'Bane' else 2},
                 'text': ('+3 life against %s' % COLOR_LOWER[st['against']]) if st['family'] == 'Bane' else '+2 life'}
        three['oracle'] = ('+3 life in a duel against a %s enemy' % COLOR_LOWER[st['against']]
                           if st['family'] == 'Bane' else '+2 life in every duel')
        if st['family'] == 'Bane':
            three['onlyAgainst'] = True
        five_effect = {'startBattleWithCardInCommandZone': [st['card5'][0]]}
        five_effect.update(st.get('effect5') or {})
        seven_effect = {'startBattleWithCardInCommandZone': [st['card7'][0]]}
        seven_effect.update(st.get('effect7') or {})
        if st['id'] == 'paragon':
            seven_effect['startBattleWithCard'] = ['c_a_treasure_sac', 'c_a_treasure_sac']
        out.append({
            'id': st['id'], 'name': st['name'], 'family': st['family'], 'against': st.get('against'),
            'color': COLOR_LOWER[st['color']] if st['family'] == 'Regalia' else None,
            'items': st['items'],
            'itemWhere': [piece_where(src[n]) for n in st['items']],
            'where': set_where(st, src),
            'tiers': [three,
                      {'pieces': 5, 'effect': five_effect, 'text': st['card5'][0],
                       'oracle': st['card5'][2].replace('\\n', ' ')},
                      {'pieces': 7, 'effect': seven_effect, 'replaces': 5, 'text': st['card7'][0],
                       'oracle': st['card7'][2].replace('\\n', ' ')}],
        })
    return out


# ---------------------------------------------------------------------------------------------------- the faces
W, H = 488, 680


def font(name, size):
    try:
        return ImageFont.truetype('C:/Windows/Fonts/' + name, size)
    except OSError:
        return ImageFont.load_default()


def draw_face(st, card, pieces, icons):
    dark, mid, light = [tuple(c) for c in st['ramp']]
    face = Image.new('RGB', (W, H), (12, 12, 14))
    d = ImageDraw.Draw(face)
    d.rounded_rectangle((10, 10, W - 10, H - 10), radius=18, fill=tuple(int(c * 0.55) for c in mid))
    # title
    d.rounded_rectangle((28, 28, W - 28, 76), radius=10, fill=light, outline=dark, width=3)
    d.text((44, 52), card[0], font=font('georgiab.ttf', 25), fill=(20, 18, 16), anchor='lm')
    # art: the set's seven pieces, tinted
    d.rectangle((34, 88, W - 34, 352), fill=(22, 22, 28), outline=dark, width=3)
    tiles = []
    for item in st['items']:
        ic = icons.icon(item)
        tiles.append(tint(ic, st['ramp']).resize((64, 64), Image.NEAREST) if ic is not None else None)
    rows = [tiles[:4], tiles[4:]]
    for r, row in enumerate(rows):
        x0 = (W - (len(row) * 80 - 16)) // 2
        for k, im in enumerate(row):
            x, y = x0 + k * 80, 118 + r * 112
            d.rounded_rectangle((x - 6, y - 6, x + 70, y + 70), radius=8, fill=tuple(int(c * 0.35) for c in mid))
            if im is not None:
                face.paste(im, (x, y), im)
            else:
                d.text((x + 32, y + 32), '?', font=font('georgiab.ttf', 30), fill=light, anchor='mm')
    # type line
    d.rounded_rectangle((28, 362, W - 28, 402), radius=8, fill=light, outline=dark, width=3)
    d.text((44, 382), 'Enchantment - Gear Set Bonus', font=font('georgiab.ttf', 19), fill=(20, 18, 16), anchor='lm')
    # rules text
    d.rectangle((34, 412, W - 34, 610), fill=(236, 228, 210), outline=dark, width=3)
    y = 426
    for para in card[2].split('\\n'):
        for line in textwrap.wrap(para, 38):
            d.text((48, y), line, font=font('georgia.ttf', 20), fill=(20, 18, 16))
            y += 25
        y += 8
    # footer
    d.text((W // 2, 636), '%s  -  %d of 7 pieces worn' % (st['name'], pieces), font=font('georgia.ttf', 17),
           fill=light, anchor='mm')
    return face


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--repo')
    ap.add_argument('--preview')
    ap.add_argument('--json-only', action='store_true', help='rewrite item_sets.json only (round 631: the where lines)')
    a = ap.parse_args()
    root = a.repo or 'C:/TFR/repo'
    plane = os.path.join(root, 'forge-gui', 'res', 'adventure', 'The Forsaken Realms')
    common = os.path.join(root, 'forge-gui', 'res', 'adventure', 'common')
    icons = Icons(plane)
    faces = []
    for st in SETS:
        for card, pieces in ((st['card5'], 5), (st['card7'], 7)):
            faces.append((st, card, draw_face(st, card, pieces, icons)))
    if a.preview:
        cols = 6
        thumb = (244, 340)
        sheet = Image.new('RGB', (cols * (thumb[0] + 8) + 8, ((len(faces) + cols - 1) // cols) * (thumb[1] + 8) + 8),
                          (30, 30, 34))
        for i, (_, _, f) in enumerate(faces):
            sheet.paste(f.resize(thumb, Image.LANCZOS), (8 + (i % cols) * (thumb[0] + 8), 8 + (i // cols) * (thumb[1] + 8)))
        sheet.save(a.preview)
        print('preview', a.preview)
    if not a.repo:
        return
    data = sets_json(plane)
    with open(os.path.join(plane, 'world', 'item_sets.json'), 'w', encoding='utf-8', newline='\n') as fh:
        json.dump(data, fh, indent=2, ensure_ascii=False)
        fh.write('\n')
    if a.json_only:
        print('wrote item_sets.json (%d sets)' % len(SETS))
        return
    for st, card, f in faces:
        with open(os.path.join(common, 'custom_cards', slug(card[0]) + '.txt'), 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(card_script(card, st['colors']))
        f.save(os.path.join(common, 'custom_card_pics', card[0] + '.fullborder.png'))
    print('wrote item_sets.json (%d sets), %d cards and faces' % (len(SETS), len(faces)))


if __name__ == '__main__':
    main()
