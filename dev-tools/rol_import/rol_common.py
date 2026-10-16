r"""rol_common.py - the writers the Realm of Legends imports share (round 669 on; round 668's import_r668.py has its own).

Every writer is idempotent: an entry already in the plane is left alone (items are brought up to date - their
description, effect and csv row). Each takes the round's own data:
  import_maps(c, maps, text, card_swaps)      maps: [(subfolder, file)]; text: {file: [(old, new, times)]} exact
                                               substrings of the raw line text; card_swaps: {file: [(raw old, raw new,
                                               times)]} on the raw .tmx (card names in effects and rewards)
  import_pois(c, pois, new_regions)            pois: [dict(name, display, atlas, sprite, map, type, tags, level, land)]
  import_enemies(c, enemies, round, items)     enemies: [dict(name, source, life, tier, difficulty, games, rebuild, deck,
                                               drop_items, item_swaps, drop_unplayable, extra_rewards)]
  import_items(c, items, sketchbooks=())       items: [(name, slot, icon, cost, effect, rarity, description)]
  import_shops(c, shops)                       shops: {name: (description, [item names])}
Report lines collect in c.report."""
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

ROL_NAME = 'Realm of Legends'
TFR_NAME = 'The Forsaken Realms'


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
        self.rol_shops = {e['name']: e for e in lenient(rw(os.path.join(self.rol, 'world', 'shops.json')))}
        self.report = collections.defaultdict(list)

    def card_ok(self, name):
        name = name.split('|')[0]
        if os.path.exists(os.path.join(self.repo, 'forge-gui', 'res', 'tokenscripts', name + '.txt')):
            return True
        return name in self.cards.builder.cards and self.cards.why_out(name) in (None, 'AI drops it')


# ------------------------------------------------------------------------------------------------------------ maps
def import_maps(c, maps, text, card_swaps, edit_hooks=None):
    for sub, f in maps:
        dst = os.path.join(c.plane, 'maps', 'map', sub, f)
        if os.path.exists(dst):
            c.report['maps'].append('%s/%s (already there)' % (sub, f))
            continue
        raw = rw(os.path.join(c.rol, 'maps', 'map', sub, f))
        raw = raw.replace('../%s/maps/' % ROL_NAME, '../%s/maps/' % TFR_NAME)
        raw = raw.replace('template="../../obj/treasure.tx"', 'template="../../../../common/maps/obj/treasure.tx"')
        assert ROL_NAME not in raw, '%s still points into %s' % (f, ROL_NAME)
        for old, new, times in text.get(f, []):
            found = raw.count(esc(old))
            assert found == times, '%s: %r found %d times, expected %d' % (f, old[:60], found, times)
            raw = raw.replace(esc(old), esc(new))
        for old, new, times in card_swaps.get(f, []):
            found = raw.count(old)
            assert found == times, '%s: %r found %d times, expected %d' % (f, old[:60], found, times)
            raw = raw.replace(old, new)
        if edit_hooks and f in edit_hooks:
            raw = edit_hooks[f](raw)
        for card in re.findall(r'startBattleWithCard\\?&quot;: ?\[([^\]]*)\]', raw):
            for name in re.findall(r'\\?&quot;([^&]+?)\\?&quot;', card):
                assert c.card_ok(name), '%s: card %s is not in Forge, or not playable here' % (f, name)
        for name in re.findall(r'&quot;cardName&quot;: ?&quot;([^&]+?)&quot;', raw):
            assert c.card_ok(name), '%s: reward card %s is not in Forge, or not playable here' % (f, name)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        ww(dst, raw)
        c.report['maps'].append('%s/%s' % (sub, f))


# ------------------------------------------------------------------------------------------------------------ places
def import_pois(c, pois, new_regions):
    path = os.path.join(c.plane, 'world', 'points_of_interest.json')
    raw = rw(path)
    nl = nl_of(raw)
    have = {p['name'] for p in lenient(raw)}
    blocks = []
    for p in pois:
        if p['name'] in have:
            continue
        entry = collections.OrderedDict([
            ("name", p['name']), ("displayName", p['display']), ("type", p['type']), ("count", 1),
            ("spriteAtlas", "../%s/sprites/%s" % (TFR_NAME, p['atlas'])), ("sprite", p['sprite']),
            ("map", "../%s/maps/map/%s" % (TFR_NAME, p['map'])), ("radiusFactor", 0.8),
            ("appearsAtLevel", p['level']), ("appearsInBiome", p['land']), ("questTags", p['tags'])])
        body = json.dumps(entry, indent='\t', ensure_ascii=False)
        blocks.append(nl.join('\t' + ln for ln in body.split('\n')))
        c.report['places'].append('%s (level %d, %s)' % (p['name'], p['level'], p['land']))
    if blocks:
        end = raw.rstrip().rindex('}') + 1
        raw = raw[:end] + ',' + nl + (',' + nl).join(blocks) + raw[end:]
        lenient(raw)
        ww(path, raw)
    for atlas, regions in new_regions.items():
        tpath = os.path.join(c.plane, 'sprites', atlas)
        traw = rw(tpath)
        tnl = nl_of(traw)
        src = rw(os.path.join(c.rol, 'sprites', atlas)).replace('\r\n', '\n').split('\n')
        add = []
        for region in regions:
            if re.search(r'(?m)^%s\s*$' % re.escape(region), traw):
                continue
            i = next(k for k, ln in enumerate(src) if ln.strip() == region)
            add.append(src[i])
            for ln in src[i + 1:]:
                if not ln.startswith(' '):
                    break
                add.append(ln)
        if add:
            ww(tpath, traw.rstrip('\r\n') + tnl + tnl.join(add) + tnl)
            c.report['atlas'].append('%s: %s' % (atlas, ', '.join(regions)))


# ------------------------------------------------------------------------------------------------------------ enemies
def enemy_file(name):
    return re.sub(r'[^a-z0-9]+', '_', name.lower()).strip('_')


def import_enemies(c, enemies, round_no, known_items):
    path = os.path.join(c.plane, 'world', 'enemies.json')
    raw = rw(path)
    nl = nl_of(raw)
    have = {e['name'] for e in lenient(raw)}
    blocks, csv_rows = [], []
    ledger_path = os.path.join(c.repo, 'dev-tools', 'enemy_tiers', 'unique_upgrades.json')
    ledger = json.load(io.open(ledger_path, encoding='utf-8'))
    for spec in enemies:
        name = spec['name']
        src = c.rol_enemies[spec.get('source', name)]  # round 674: an entry copied under a new name (Hellion)
        rel_deck = spec.get('deck') or src['deck'][0]
        summary = None
        if spec.get('rebuild', True):
            dst_deck = os.path.join(c.plane, rel_deck)
            if name not in have:
                if os.path.exists(dst_deck):
                    raise SystemExit('%s: %s already exists in the plane - not overwriting' % (name, rel_deck))
                summary = rcd.rebuild(c.cards, os.path.join(c.rol, src['deck'][0]), dst_deck, name)
                c.report['decks'].append('%s: %d spells / %d lands, %d creatures, %s; out: %s' % (
                    name, summary['spells'], summary['lands'], summary['creatures'], summary['colors'],
                    ', '.join(summary['dropped']) or '-'))
            ledger.setdefault(rel_deck, {'old': None, 'new': None, 'count': 0, 'kind': 'built-r%d' % round_no})
        else:
            assert os.path.exists(os.path.join(c.plane, rel_deck)), rel_deck
        if name in have:
            continue
        sprite = src['sprite']
        remake = 'sprites/enemy/remake/' + os.path.basename(sprite)
        if os.path.exists(os.path.join(c.plane, remake)):
            sprite = remake
        rewards = []
        for r in src.get('rewards', []):
            r = dict(r)
            if r.get('type') == 'item' and (r.get('itemName') in spec.get('drop_items', ())):
                continue
            if r.get('type') == 'item' and r.get('itemName') in spec.get('item_swaps', {}):
                r['itemName'] = spec['item_swaps'][r['itemName']]  # round 674: Lyna's "Soltari Shoes"
            if r.get('type') == 'card' and r.get('cardName'):
                if spec.get('drop_unplayable') and not c.card_ok(r['cardName']):
                    c.report['reward cards dropped'].append('%s: %s' % (name, r['cardName']))
                    continue
                assert c.card_ok(r['cardName']), '%s: reward card %s' % (name, r['cardName'])
            if r.get('type') == 'item':
                for it in ([r['itemName']] if r.get('itemName') else r.get('itemNames', [])):
                    assert it in known_items, '%s: reward item %s is not in the plane' % (name, it)
            rewards.append(r)
        rewards = list(spec.get('extra_rewards', [])) + rewards
        entry = collections.OrderedDict([
            ("name", name), ("sprite", sprite), ("deck", [rel_deck]), ("ai", ""), ("speed", src.get('speed', 45))])
        if src.get('flying'):
            entry['flying'] = True
        entry['life'] = spec.get('life', src.get('life', 30))
        entry['rewards'] = rewards
        entry['colors'] = (summary['colors'] if summary and summary['colors'] else src.get('colors', '')) or 'C'
        if spec.get('games'):
            entry['gamesPerMatch'] = spec['games']
        entry['spawnRate'] = 0
        entry['tier'] = spec.get('tier', 'Rare')
        entry['difficulty'] = spec.get('difficulty', 2)
        body = json.dumps(entry, indent=4, ensure_ascii=False)
        blocks.append(nl.join('    ' + ln for ln in body.split('\n')))
        csv_rows.append('%s,%s,%s,%d,%s,N,%.1f,Y' % ('"%s"' % name if ',' in name else name, entry['colors'],
                                                    enemy_file(name), entry['life'], entry['tier'],
                                                    entry['difficulty']))
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
    rows = [r for r in csv_rows if (cnl + r.split(',')[0] + ',') not in craw]
    if rows:
        ww(cpath, craw.rstrip('\r\n') + cnl + cnl.join(rows) + cnl)


# ------------------------------------------------------------------------------------------------------------ items
def item_row(name, slot, cost, effect, rarity, desc):
    cards = '; '.join('1x ' + x.split('|')[0] for x in effect.get('startBattleWithCard', []))
    note = '%s Slot: %s Battlefield: %s' % (desc, slot, cards)
    if effect.get('changeStartCards'):
        note += ' Starting hand: %d' % effect['changeStartCards']
    if effect.get('lifeModifier'):
        note += ' [+Life] %d' % effect['lifeModifier']
    return '%s,%s,%d,%s,N,"%s",Y,' % ('"%s"' % name if ',' in name else name, rarity, cost, slot,
                                     note.replace('"', '""'))


def import_items(c, items):
    path = os.path.join(c.plane, 'world', 'items.json')
    raw = rw(path)
    nl = nl_of(raw)
    have = {i['name'] for i in lenient(raw)}
    blocks, csv_rows, icons, updated = [], [], [], []
    for name, slot, icon, cost, effect, rarity, desc in items:
        for card in effect.get('startBattleWithCard', []):
            assert c.card_ok(card) or card.split('|')[0] in c.cards.builder.cards, '%s: %s' % (name, card)
        icons.append(icon)
        row = item_row(name, slot, cost, effect, rarity, desc)
        if name in have:
            m = re.search(r'"name":  %s,\r?\n\s*"description":  (.*?),\r?\n(?:.*\r?\n){3}\s*"effect":  (.*?),\r?\n'
                          % re.escape(json.dumps(name)), raw)
            if m and (m.group(1) != json.dumps(desc) or m.group(2) != json.dumps(effect)):
                raw = raw[:m.start(2)] + json.dumps(effect) + raw[m.end(2):]
                raw = raw[:m.start(1)] + json.dumps(desc) + raw[m.end(1):]
                updated.append((name, row))
            continue
        lines = ['    {', '        "name":  %s,' % json.dumps(name), '        "description":  %s,' % json.dumps(desc),
                 '        "equipmentSlot":  %s,' % json.dumps(slot), '        "iconName":  %s,' % json.dumps(icon),
                 '        "cost":  %d,' % cost, '        "effect":  %s,' % json.dumps(effect),
                 '        "rarity":  %s' % json.dumps(rarity), '    }']
        blocks.append(nl.join(lines))
        csv_rows.append(row)
        c.report['items'].append('%s (%s, %s, %d)' % (name, slot, rarity, cost))
    if blocks:
        end = raw.rstrip().rindex('}') + 1
        raw = raw[:end] + ',' + nl + (',' + nl).join(blocks) + raw[end:]
    if blocks or updated:
        lenient(raw)
        ww(path, raw)
    apath = os.path.join(c.plane, 'sprites', 'items.atlas')
    araw = rw(apath)
    anl = nl_of(araw)
    src = rw(os.path.join(c.rol, 'sprites', 'items.atlas')).replace('\r\n', '\n').split('\n')
    add = []
    for icon in icons:
        if re.search(r'(?m)^%s\s*$' % re.escape(icon), araw) or icon in [a.strip() for a in add]:
            continue
        i = next(k for k, ln in enumerate(src) if ln.strip() == icon)
        add.append(src[i])
        for ln in src[i + 1:]:
            if not ln.startswith(' '):
                break
            add.append(ln)
    if add:
        m = re.search(r'(?m)^items\.png\s*$', araw)
        nxt = re.search(r'(?m)^\S+\.png\s*$', araw[m.end():])
        at = m.end() + nxt.start() if nxt else len(araw.rstrip('\r\n'))
        araw = araw[:at].rstrip('\r\n') + anl + anl.join(add) + anl + (anl if nxt else '') + araw[at:].lstrip('\r\n')
        ww(apath, araw)
        c.report['icons'].append(', '.join(ln for ln in add if not ln.startswith(' ')))
    cpath = os.path.join(c.plane, 'config tables', 'items.csv')
    craw = rw(cpath)
    cnl = nl_of(craw)
    for name, row in updated:
        craw = re.sub(r'(?m)^%s,.*$' % re.escape('"%s"' % name if ',' in name else name), lambda _m: row, craw, count=1)
        c.report['items updated'].append(name)
    rows = [r for r in csv_rows if (cnl + r.split(',')[0] + ',') not in craw]
    if rows or updated:
        ww(cpath, craw.rstrip('\r\n') + cnl + (cnl.join(rows) + cnl if rows else ''))


# ------------------------------------------------------------------------------------------------------------ shops
def import_shops(c, shops):
    path = os.path.join(c.plane, 'world', 'shops.json')
    raw = rw(path)
    nl = nl_of(raw)
    have = {s['name'] for s in lenient(raw)}
    blocks = []
    for name, (desc, items) in shops.items():
        if name in have:
            continue
        src = c.rol_shops[name]
        entry = collections.OrderedDict([
            ("name", name), ("description", desc), ("spriteAtlas", src['spriteAtlas']), ("sprite", src['sprite']),
            ("rewards", [collections.OrderedDict([("type", "item"), ("count", 1), ("itemName", i)]) for i in items])])
        body = json.dumps(entry, indent=1, ensure_ascii=False)  # shops.json's own one-space layout
        blocks.append(nl.join(' ' + ln for ln in body.split('\n')))
        c.report['shops'].append('%s: %s' % (name, ', '.join(items)))
    if blocks:
        end = raw.rstrip().rindex('}') + 1
        raw = raw[:end] + ',' + nl + (',' + nl).join(blocks) + raw[end:]
        lenient(raw)
        ww(path, raw)


def print_report(c):
    for k, v in c.report.items():
        print('== %s (%d)' % (k, len(v)))
        for x in v:
            print('  ', x)
