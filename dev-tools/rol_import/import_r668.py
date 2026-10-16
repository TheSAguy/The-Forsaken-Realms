r"""import_r668.py - TFR round 668: three end-game legend dungeons from Realm of Legends 1.077.

The user (2026-10-16), after the audit of Realm of Legends 1.077/1.078: "Let's do the First batch: the three legend
dungeons. Make sure to update all quest portions as needed to be independent from RoL and make sense in our world.
Since they are end-game, let's not have them appear till the player reaches level 20+". Under the cross-plane import
rules in CLAUDE.md: no Commander, no foreign quest lines, no unkept promises, every reference resolves.

THE PLACES (points_of_interest.json; appearsAtLevel 20 in the wasteland - util/LevelPlaces puts them on the map)
  Hexhaven Tower       four floors (Hexhaven_Tower_1-4.tmx): six Strixhaven sphinx-masters, the Ur-Sphinx on top
  Echoverse Overlay    a bridge maze of echoed legends (nine Echoes and Titanbones)
  Theorist's Sanctum   six Echo planeswalkers before the Theorist and Tam
  All "dungeon", tagged Hostile / NoRotate / Legendary (the end-game door warning, the red triple-skull marker, never
  rotating) - Realm of Legends tagged them only "Story" and typed them sideboss*.
THE MAPS: copied to maps/map/cave/; the tower's doors and the treasure template repointed from Realm of Legends; the
  text refit to this realm's story (the spark, not the manasight stone; places and legends the realm swallowed).
THE ENEMIES (25): Realm of Legends' entries - sprite (this plane's remake when one of that name exists), rewards - with
  the 100-card Commander decks rebuilt as 60-card decks (rebuild_commander_deck.py), colors from the rebuilt deck, life
  30 for the floor legends and 40-50 for the three at the end, spawnRate 0, Rare (Mythic for the Ur-Sphinx, Tam and the
  Theorist), difficulty 3, best of three for the last three. enemies.csv rows; round 644's ledger marks the decks
  built-r668 so upgrade_unique_decks.py leaves them alone.
THE ITEMS (10): the eight the legends drop (Vexing Boots, Fulcrum Gauntlet, Pants of the Fattest Butt - Legs, not
  Boots, the five "Way" necklaces), descriptions that say what the card does, and the two Landscape Sketchbooks
  Realm of Legends left without a source (Secrets of Strixhaven from the Ur-Sphinx, Reality Fracture from Titanbones).
  Icons: regions this plane's items.png already holds at the same place. items.csv rows.

usage: python import_r668.py <repo root>          (idempotent: anything already there is left as it is)"""
import collections
import io
import json
import os
import re
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import rebuild_commander_deck as rcd  # noqa: E402

ROUND = 668
ROL_NAME = 'Realm of Legends'
TFR_NAME = 'The Forsaken Realms'

MAPS = ['Hexhaven_Tower_1.tmx', 'Hexhaven_Tower_2.tmx', 'Hexhaven_Tower_3.tmx', 'Hexhaven_Tower_4.tmx',
        'Echoverse_Overlay.tmx', 'Theorists_Sanctum.tmx']

MS_A = ("Your manasight stone glows, sensing the power of the legends here.",
        "Your spark stirs, sensing the power gathered here.")
MS_B = ("Your manasight stone glows brightly, sensing a truly mighty legend in this place.",
        "Your spark stirs, sensing something truly mighty in this place.")
TEXT = {  # (old, new, how many times) - exact substrings of the raw .tmx line text
    'Hexhaven_Tower_1.tmx': [
        ("Through the central open area, you see several intimidating sphinxes flying about, each swirling with "
         "different combinations of powerful mana. You have heard rumors of a twisted echo of the mage school "
         "Strixhaven - Hexhaven, it was called. You assume this place must have been pulled from there.",
         "Through its open heart you see intimidating sphinxes wheeling overhead, each swirling with its own blend of "
         "powerful mana. Travelers whisper of Hexhaven - a twisted echo of the great mage school of Strixhaven, which "
         "this realm swallowed whole, masters, students and all, long before you woke. This must be it.", 1),
        MS_A + (1,)],
    'Echoverse_Overlay.tmx': [
        ("criss-crosses over echoed alternate versions of familiar places. Seemingly fittingly, the place is swarming "
         "with echoed versions of legends you've heard tales of, clinging to the bridges to avoid falling into the "
         "nothingness of the void below and above.",
         "criss-crosses over echoes of places you almost recognize - pieces of other worlds this realm has swallowed, "
         "folded over and over until they wore thin. Fittingly, the bridges swarm with echoes of legends you've heard "
         "tales of, clinging on so as not to fall into the nothing below and above. It seems the realm keeps a copy of "
         "everything it takes.", 1),
        ("He croaks out!", "He croaks:", 1)],
    'Theorists_Sanctum.tmx': [
        ("The shimmering shapes of a new multiverse in the process of being born swirl about, and the very air is "
         "thick with twisting and fracturing magic.",
         "Between them swirl the shimmering shapes of a new multiverse being born - one mind trying to write its way "
         "out of this realm by remaking everything else - and the very air is thick with twisting, fracturing magic.",
         1),
        MS_B + (1,),
        ("The shimmering shapes of another multiverse rapidly begin to fade. Fractures in reality begin to heal.",
         "The shimmering shapes of the other multiverse fade fast, and the fractures in the air close over like "
         "water - the realm healing its wound.", 2),
        ("With that, he is finally dead.", "With that, he is gone, and the realm is whole again.", 2)],
}

POIS = [
    ("Hexhaven Tower", "Hexhaven Tower", "buildingsbosses.atlas", "HexhavenTower", "Hexhaven_Tower_1.tmx"),
    ("Echoverse Overlay", "Echoverse Overlay", "buildingsbosses.atlas", "RealityOverlay", "Echoverse_Overlay.tmx"),
    ("Theorist's Sanctum", "Theorist's Sanctum", "buildings.atlas", "Building79", "Theorists_Sanctum.tmx"),
]
NEW_REGIONS = {"buildingsbosses.atlas": ["HexhavenTower", "RealityOverlay"]}

ENEMIES = ['Ingris Stingerquill', 'Denzilore Fatehold', 'Echo Codie', 'Aerid Konstrari', 'Kwia Vigorbloom',
           'Uldaros Theorix', 'The Ur-Sphinx', 'Titanbones', 'Echo Tamiyo', 'Echo Karn', 'Echo Yargle', 'Echo Lyra',
           'Echo Yuriko', 'Echo Edgar', 'Echo Ghalta', 'Echo Kiora', 'Echo Ruric Thar', 'Echo Liliana', 'Echo Nissa',
           'Echo Vraska', 'Echo Gideon', 'Echo Ob Nixilis', 'Echo Avacyn', 'The Theorist', 'Tam the Possibility']
FINAL = {'The Ur-Sphinx': 50, 'Tam the Possibility': 40, 'The Theorist': 50}
EXTRA_REWARDS = {
    'The Ur-Sphinx': {"type": "item", "count": 1, "itemName": "Landscape Sketchbook - Secrets of Strixhaven"},
    'Titanbones': {"type": "item", "count": 1, "itemName": "Landscape Sketchbook - Reality Fracture"},
}

JACE = "A Jace planeswalker token with 5 loyalty at your first upkeep (-1: surveil 1, -3: draw a card)."
# make_jaces_echo.py's card: each Way empowers Jace as it ENTERS, and an item's card never enters - the agent game
# showed Way of the Mind Sculptor in play on turn 1 with no Jace. Jace's Echo, in the command zone, does it instead.
ECHO = "Jace's Echo"
ITEMS = [  # name, slot, icon, cost, effect, rarity, description
    ("Vexing Boots", "Boots", "VexingBoots", 5000, {"startBattleWithCard": ["Vexing Sphinx"]}, "Rare",
     "A 4/4 flying Sphinx with cumulative upkeep - discard a card. When it dies, draw a card for each age counter."),
    ("Fulcrum Gauntlet", "Left", "FulcrumGauntlet", 5000,
     {"lifeModifier": -5, "startBattleWithCard": ["The Echoverse Fulcrum"]}, "Rare",
     "Draw a card, then discard a card. 5, T, exile it: destroy all creatures (as a sorcery)."),
    ("Pants of the Fattest Butt", "Legs", "PantsoftheFattestButt", 4000,
     {"lifeModifier": -9, "startBattleWithCard": ["Yargle, Goliath of Otaria"]}, "Rare",
     "Yargle, Goliath of Otaria - a legendary 3/9 with many gigantic thighs."),
    ("Mind Sculptor Necklace", "Neck", "MindSculptorNecklace", 6000,
     {"lifeModifier": -10, "startBattleWithCard": ["Way of the Mind Sculptor"],
      "startBattleWithCardInCommandZone": [ECHO]}, "Rare",
     JACE + " Whenever you remove 2 or more loyalty for an ability, draw a card."),
    ("Deathbringer Necklace", "Neck", "DeathbringerNecklace", 6000,
     {"startBattleWithCard": ["Way of the Deathbringer"],
      "startBattleWithCardInCommandZone": [ECHO]}, "Rare",
     JACE + " Your planeswalkers have -2: sacrifice a creature to make a 4/4 trampling Beast."),
    ("Healer Necklace", "Neck", "HealerNecklace", 6000, {"startBattleWithCard": ["Way of the Healer"],
      "startBattleWithCardInCommandZone": [ECHO]}, "Rare",
     JACE + " Your planeswalkers have -2: make a 2/2 Cadet and surveil 1."),
    ("Warlord Necklace", "Neck", "WarlordNecklace", 6000, {"startBattleWithCard": ["Way of the Warlord"],
      "startBattleWithCardInCommandZone": [ECHO]}, "Rare",
     JACE + " Your planeswalkers have -4: 2 damage to up to one creature or planeswalker and 2 to a player."),
    ("Paradox Necklace", "Neck", "ParadoxNecklace", 6000,
     {"lifeModifier": -3, "startBattleWithCard": ["Way of the Paradox"],
      "startBattleWithCardInCommandZone": [ECHO]}, "Rare",
     JACE + " Whenever you use a loyalty ability, gain 1 life and you may play an extra land this turn."),
]
SKETCHBOOKS =["Reality Fracture", "Secrets of Strixhaven"]


def nl_of(raw):
    return '\r\n' if '\r\n' in raw else '\n'


def rw(path):
    return io.open(path, encoding='utf-8', newline='').read()


def ww(path, text):
    io.open(path, 'w', encoding='utf-8', newline='').write(text)


def lenient(text):
    return json.loads(re.sub(r',(\s*[}\]])', r'\1', re.sub(r'(?m)^\s*//[^\n]*', '', text)))


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


class Ctx:
    def __init__(self, repo):
        self.repo = repo
        self.adv = os.path.join(repo, 'forge-gui', 'res', 'adventure')
        self.plane = os.path.join(self.adv, TFR_NAME)
        self.rol = os.path.join(self.adv, ROL_NAME)
        self.cards = rcd.Cards(repo)
        self.rol_enemies = {e['name']: e for e in lenient(rw(os.path.join(self.rol, 'world', 'enemies.json')))}
        self.rol_items = {e['name']: e for e in lenient(rw(os.path.join(self.rol, 'world', 'items.json')))}
        self.report = collections.defaultdict(list)

    def card_ok(self, name):
        return name.split('|')[0] in self.cards.builder.cards


# ------------------------------------------------------------------------------------------------------------ maps
def import_maps(c):
    out_dir = os.path.join(c.plane, 'maps', 'map', 'cave')
    for f in MAPS:
        dst = os.path.join(out_dir, f)
        if os.path.exists(dst):
            c.report['maps'].append(f + ' (already there)')
            continue
        raw = rw(os.path.join(c.rol, 'maps', 'map', 'cave', f))
        raw = raw.replace('../%s/maps/map/cave/' % ROL_NAME, '../%s/maps/map/cave/' % TFR_NAME)
        raw = raw.replace('template="../../obj/treasure.tx"', 'template="../../../../common/maps/obj/treasure.tx"')
        assert ROL_NAME not in raw, '%s still points into %s' % (f, ROL_NAME)
        for old, new, times in TEXT.get(f, []):
            found = raw.count(esc(old))
            assert found == times, '%s: %r found %d times, expected %d' % (f, old[:50], found, times)
            raw = raw.replace(esc(old), esc(new))
        for card in re.findall(r'startBattleWithCard\\?&quot;: ?\[([^\]]*)\]', raw):
            for name in re.findall(r'\\?&quot;([^&]+?)\\?&quot;', card):
                assert c.card_ok(name), '%s: card %s is not in Forge' % (f, name)
        ww(dst, raw)
        c.report['maps'].append(f)


# ------------------------------------------------------------------------------------------------------------ places
def import_pois(c):
    path = os.path.join(c.plane, 'world', 'points_of_interest.json')
    raw = rw(path)
    nl = nl_of(raw)
    have = {p['name'] for p in lenient(raw)}
    blocks = []
    for name, display, atlas, sprite, tmx in POIS:
        if name in have:
            continue
        entry = collections.OrderedDict([
            ("name", name), ("displayName", display), ("type", "dungeon"), ("count", 1),
            ("spriteAtlas", "../%s/sprites/%s" % (TFR_NAME, atlas)), ("sprite", sprite),
            ("map", "../%s/maps/map/cave/%s" % (TFR_NAME, tmx)), ("radiusFactor", 0.8),
            ("appearsAtLevel", 20), ("appearsInBiome", "waste"),
            ("questTags", ["Hostile", "NoRotate", "Legendary"])])
        body = json.dumps(entry, indent='\t', ensure_ascii=False)
        blocks.append(nl.join('\t' + ln for ln in body.split('\n')))
    if blocks:
        end = raw.rstrip().rindex('}') + 1
        raw = raw[:end] + ',' + nl + (',' + nl).join(blocks) + raw[end:]
        lenient(raw)
        ww(path, raw)
    c.report['places'] = [p[0] for p in POIS if p[0] not in have]
    # the two sprite regions Realm of Legends added on the page this plane shares
    for atlas, regions in NEW_REGIONS.items():
        tpath = os.path.join(c.plane, 'sprites', atlas)
        traw = rw(tpath)
        tnl = nl_of(traw)
        src = rw(os.path.join(c.rol, 'sprites', atlas)).replace('\r\n', '\n').split('\n')
        add = []
        for region in regions:
            if re.search(r'(?m)^%s\s*$' % re.escape(region), traw):
                continue
            i = next(k for k, ln in enumerate(src) if ln.strip() == region)
            block = [src[i]]
            for ln in src[i + 1:]:
                if not ln.startswith(' '):
                    break
                block.append(ln)
            add.extend(block)
        if add:
            traw = traw.rstrip('\r\n') + tnl + tnl.join(add) + tnl
            ww(tpath, traw)
            c.report['atlas'].append('%s: %s' % (atlas, ', '.join(regions)))


# ------------------------------------------------------------------------------------------------------------ enemies
def enemy_file(name):
    return re.sub(r'[^a-z0-9]+', '_', name.lower()).strip('_')


def import_enemies(c):
    path = os.path.join(c.plane, 'world', 'enemies.json')
    raw = rw(path)
    nl = nl_of(raw)
    have = {e['name'] for e in lenient(raw)}
    blocks, csv_rows = [], []
    ledger_path = os.path.join(c.repo, 'dev-tools', 'enemy_tiers', 'unique_upgrades.json')
    ledger = json.load(io.open(ledger_path, encoding='utf-8'))
    for name in ENEMIES:
        src = c.rol_enemies[name]
        rel_deck = src['deck'][0]  # decks/legends/<file>.dck
        dst_deck = os.path.join(c.plane, rel_deck)
        if os.path.exists(dst_deck) and name not in have:
            raise SystemExit('%s: %s already exists in the plane - not overwriting' % (name, rel_deck))
        summary = None
        if name not in have:
            summary = rcd.rebuild(c.cards, os.path.join(c.rol, rel_deck), dst_deck, name)
            c.report['decks'].append('%s: %d spells / %d lands, %d creatures, %s; out: %s' % (
                name, summary['spells'], summary['lands'], summary['creatures'], summary['colors'],
                ', '.join(summary['dropped']) or '-'))
        ledger.setdefault(rel_deck, {'old': None, 'new': None, 'count': 0, 'kind': 'built-r%d' % ROUND})
        if name in have:
            continue
        sprite = src['sprite']
        remake = 'sprites/enemy/remake/' + os.path.basename(sprite)
        if os.path.exists(os.path.join(c.plane, remake)):
            sprite = remake
        rewards = [dict(r) for r in src.get('rewards', [])]
        for r in rewards:
            if r.get('type') == 'card' and r.get('cardName'):
                assert c.card_ok(r['cardName']), '%s: reward card %s is not in Forge' % (name, r['cardName'])
        if name in EXTRA_REWARDS:
            rewards.insert(0, EXTRA_REWARDS[name])
        final = name in FINAL
        entry = collections.OrderedDict([
            ("name", name), ("sprite", sprite), ("deck", [rel_deck]), ("ai", ""), ("speed", src.get('speed', 45))])
        if src.get('flying'):
            entry['flying'] = True
        entry['life'] = FINAL.get(name, 30)
        entry['rewards'] = rewards
        entry['colors'] = summary['colors'] if summary and summary['colors'] else src.get('colors', '')
        if final:
            entry['gamesPerMatch'] = 3
        entry['spawnRate'] = 0
        entry['tier'] = 'Mythic' if final else 'Rare'
        entry['difficulty'] = 3
        body = json.dumps(entry, indent=4, ensure_ascii=False)
        blocks.append(nl.join('    ' + ln for ln in body.split('\n')))
        csv_rows.append('%s,%s,%s,%d,%s,N,3.0,Y' % ('"%s"' % name if ',' in name else name, entry['colors'] or 'C',
                                                   enemy_file(name), entry['life'], entry['tier']))
        c.report['enemies'].append('%s (life %d, %s, %s)' % (name, entry['life'], entry['tier'], sprite))
    if blocks:
        end = raw.rstrip().rindex('}') + 1
        raw = raw[:end] + ',' + nl + (',' + nl).join(blocks) + raw[end:]
        lenient(raw)
        ww(path, raw)
    with io.open(ledger_path, 'w', encoding='utf-8', newline='\n') as fh:
        json.dump(dict(sorted(ledger.items())), fh, indent=1)
        fh.write('\n')
    cpath = os.path.join(c.plane, 'config tables', 'enemies.csv')
    craw = rw(cpath)
    cnl = nl_of(craw)
    rows = [r for r in csv_rows if r.split(',')[0] not in craw]
    if rows:
        ww(cpath, craw.rstrip('\r\n') + cnl + cnl.join(rows) + cnl)


# ------------------------------------------------------------------------------------------------------------ items
def import_items(c):
    path = os.path.join(c.plane, 'world', 'items.json')
    raw = rw(path)
    nl = nl_of(raw)
    have = {i['name'] for i in lenient(raw)}
    blocks, csv_rows, icons = [], [], []
    for name, slot, icon, cost, effect, rarity, desc in ITEMS:
        for card in effect.get('startBattleWithCard', []):
            assert c.card_ok(card) or os.path.exists(os.path.join(c.repo, 'forge-gui', 'res', 'tokenscripts',
                                                                   card + '.txt')), card
        icons.append(icon)
        cards = '; '.join('1x ' + x.split('|')[0] for x in effect.get('startBattleWithCard', []))
        note = '%s Slot: %s Battlefield: %s' % (desc, slot, cards)
        if effect.get('startBattleWithCardInCommandZone'):
            note += ' Command zone: ' + '; '.join('1x ' + x for x in effect['startBattleWithCardInCommandZone'])
        if effect.get('lifeModifier'):
            note += ' [+Life] %d' % effect['lifeModifier']
        row = '%s,%s,%d,%s,N,"%s",Y,' % ('"%s"' % name if ',' in name else name, rarity, cost, slot,
                                         note.replace('"', '""'))
        if name in have:  # written by an earlier run: bring its description, effect and csv row up to date
            m = re.search(r'"name":  %s,\r?\n\s*"description":  (.*?),\r?\n(?:.*\r?\n){3}\s*"effect":  (.*?),\r?\n'
                          % re.escape(json.dumps(name)), raw)
            if m and (m.group(1) != json.dumps(desc) or m.group(2) != json.dumps(effect)):
                raw = raw[:m.start(2)] + json.dumps(effect) + raw[m.end(2):]
                raw = raw[:m.start(1)] + json.dumps(desc) + raw[m.end(1):]
                c.report['items updated'].append(name)
                csv_rows.append(('replace', name, row))
            continue
        lines = ['    {', '        "name":  %s,' % json.dumps(name), '        "description":  %s,' % json.dumps(desc),
                 '        "equipmentSlot":  %s,' % json.dumps(slot), '        "iconName":  %s,' % json.dumps(icon),
                 '        "cost":  %d,' % cost, '        "effect":  %s,' % json.dumps(effect),
                 '        "rarity":  %s' % json.dumps(rarity), '    }']
        blocks.append(nl.join(lines))
        csv_rows.append(row)
    for ed in SKETCHBOOKS:
        name = 'Landscape Sketchbook - ' + ed
        if name in have:
            continue
        desc = ("Gives access to %s land arts in the add basic lands menu of the deckbuilder's upper right corner." % ed)
        lines = ['    {', '        "name":  %s,' % json.dumps(name), '        "description":  %s,' % json.dumps(desc),
                 '        "iconName":  "LandscapeSketchbook",', '        "questItem":  true,',
                 '        "rarity":  "Common"', '    }']
        blocks.append(nl.join(lines))
        csv_rows.append('%s,Common,1000,,Y,"%s",Y,' % (name, desc))
    if blocks:
        end = raw.rstrip().rindex('}') + 1
        raw = raw[:end] + ',' + nl + (',' + nl).join(blocks) + raw[end:]
        c.report['items'] = [b.split('"name":  ')[1].split(',')[0] for b in blocks]
    if blocks or c.report.get('items updated'):
        lenient(raw)
        ww(path, raw)
    # icons: regions this plane's items.png holds at the same place as Realm of Legends'
    apath = os.path.join(c.plane, 'sprites', 'items.atlas')
    araw = rw(apath)
    anl = nl_of(araw)
    src = rw(os.path.join(c.rol, 'sprites', 'items.atlas')).replace('\r\n', '\n').split('\n')
    add = []
    for icon in icons:
        if re.search(r'(?m)^%s\s*$' % re.escape(icon), araw):
            continue
        i = next(k for k, ln in enumerate(src) if ln.strip() == icon)
        add.append(src[i])
        for ln in src[i + 1:]:
            if not ln.startswith(' '):
                break
            add.append(ln)
    if add:
        # the plane's items.atlas holds several pages: the regions go under items.png's page, before the next page
        m = re.search(r'(?m)^items\.png\s*$', araw)
        nxt = re.search(r'(?m)^\S+\.png\s*$', araw[m.end():])
        at = m.end() + nxt.start() if nxt else len(araw.rstrip('\r\n'))
        head = araw[:at].rstrip('\r\n')
        araw = head + anl + anl.join(add) + anl + (anl if nxt else '') + araw[at:].lstrip('\r\n')
        ww(apath, araw)
        c.report['icons'] = [ln for ln in add if not ln.startswith(' ')]
    cpath = os.path.join(c.plane, 'config tables', 'items.csv')
    craw = rw(cpath)
    cnl = nl_of(craw)
    for r in [r for r in csv_rows if isinstance(r, tuple)]:
        _, name, row = r
        craw = re.sub(r'(?m)^%s,.*$' % re.escape(name), lambda _m: row, craw, count=1)
        ww(cpath, craw)
    csv_rows = [r for r in csv_rows if not isinstance(r, tuple)]
    rows = [r for r in csv_rows if (cnl + r.split(',')[0] + ',') not in craw]
    if rows:
        ww(cpath, craw.rstrip('\r\n') + cnl + cnl.join(rows) + cnl)


def main(argv):
    c = Ctx(argv[1])
    import_maps(c)
    import_pois(c)
    import_enemies(c)
    import_items(c)
    for k, v in c.report.items():
        print('== %s (%d)' % (k, len(v)))
        for x in v:
            print('  ', x)


if __name__ == '__main__':
    main(sys.argv)
