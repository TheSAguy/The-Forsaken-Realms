r"""upgrade_unique_decks.py - TFR round 644: one card stronger in every unique enemy's deck.

The user (2026-10-15): "go through all Bosses / War Champions / Legends and Arch Mages and swap out 1 card to make the
deck stronger in your opinion."

THE SWAP (one card name - the whole line, so a 4-of becomes four of the new card)
  Strength: Forge's own draft rankings - the best place a card reached in any set's pick order (res/draft/rankings/
  *.rnk), as a percentile of that set (1.0 = a set's first pick). The CubeCobra list (res/draft/rankings_cubecobra.txt)
  only marks a card as proven: it protects it from being swapped out and breaks ties going in. It does NOT rank above
  the sets - a first try that let it did put The One Ring into 55 decks and Animate Dead into 57.
  OUT: the deck's weakest RANKED spell (a card from a set without rankings is not judged). Never a land, a legendary
  card (a legend deck's cast), a cube card, the enemy's own signature (a named card in its rewards, or a card carrying
  its name), or a line over four copies (Relentless Rats, Seven Dwarves - those decks are built on it).
  IN: the best card that fits where the weak one stood - the same card types, mana value within 1, colors inside the
  deck's colors, the copy limit and the legend rule kept, never a card on the plane's restricted list, an A- (Alchemy)
  card or one the AI marks RemoveDeck - and clearly stronger (+0.25 percentile). Ranked first by strength, then fit:
  the weak card's own colors, a creature type it shares, an edition the deck already prints from. A tribal deck (a type
  on a quarter of its creatures) keeps the type. No card goes into more than MAX_USES decks, and each use counts
  against the next - 1,100 decks must not all end up holding the same staples.
  A generated deck (decks/*.json template) has no card to swap: its rares share rises by one card (1 / count).

WHERE IT WRITES
  The deck at its own path in the plane folder (a deck that lives in adventure/common is copied into the plane, which
  the game reads first - the upstream file is never edited), and dev-tools/enemy_tiers/unique_upgrades.json, the ledger:
  a deck already in it is skipped, so a re-run never swaps a second card. --report <file.md> writes the review list.

usage: python upgrade_unique_decks.py <repo root> [--report out.md] [--dry-run]"""
import collections
import glob
import hashlib
import json
import os
import re
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_tier_families as mtf  # noqa: E402

MARGIN = 0.25
MAX_USES = 6
USE_PENALTY = 0.06


def load_power(res):
    """name -> best percentile over the sets' draft rankings; the set of CubeCobra cards."""
    best = {}
    for path in glob.glob(os.path.join(res, 'draft', 'rankings', '*.rnk')):
        rows = []
        for ln in open(path, encoding='utf-8', errors='replace'):
            m = re.match(r'^#(\d+)\|([^|]+)\|', ln.strip())
            if m:
                rows.append((int(m.group(1)), m.group(2).strip()))
        if len(rows) < 20:
            continue
        top = max(r for r, _ in rows)
        for r, name in rows:
            best[name] = max(best.get(name, 0.0), 1.0 - (r - 1) / float(top))
    cube = set()
    path = os.path.join(res, 'draft', 'rankings_cubecobra.txt')
    if os.path.exists(path):
        for ln in open(path, encoding='utf-8', errors='replace'):
            m = re.match(r'^#(\d+)\|([^|]+)\|', ln.strip())
            if m:
                cube.add(m.group(2).strip())
    return best, cube


def kind_of(e):
    if e.get('tier') == 'Mythic':
        return 'Archmage'
    if e.get('boss'):
        return 'Boss'
    if e.get('legend'):
        return 'Legend'
    if mtf.WIZARD_NAME.match(e['name']):
        return 'Wizard family'
    return 'Champion'


def signature_cards(es, deck):
    """The cards an enemy is: the named cards in its rewards, and any card that carries its name (Myr Superion's own
    Myr Superion, "Preston" -> Preston, the Vanisher)."""
    out = set()
    for e in es:
        for r in e.get('rewards') or []:
            if r.get('cardName'):
                out.add(r['cardName'])
        base = re.sub(r'\s*\(.*\)$', '', e['name']).strip().lower()
        first = re.split(r'[ ,]', base)[0]
        for n in deck:
            low = n.lower()
            if low == base or low.startswith(base) or base.startswith(low.split(',')[0]) \
                    or (len(first) >= 4 and low.startswith(first + ' ') or low.startswith(first + ',')):
                out.add(n)
    return out


def main(argv):
    if len(argv) < 2 or argv[1].startswith('-'):
        raise SystemExit(__doc__)
    root = argv[1]
    dry = '--dry-run' in argv
    report_path = argv[argv.index('--report') + 1] if '--report' in argv else None
    res = os.path.join(root, 'forge-gui', 'res')
    plane = os.path.join(res, 'adventure', 'The Forsaken Realms')
    common = os.path.join(res, 'adventure', 'common')
    ledger_path = os.path.join(root, 'dev-tools', 'enemy_tiers', 'unique_upgrades.json')
    ledger = json.load(open(ledger_path, encoding='utf-8')) if os.path.exists(ledger_path) else {}

    enemies = json.load(open(os.path.join(plane, 'world', 'enemies.json'), encoding='utf-8'))
    b = mtf.Builder(res, plane)
    power, cube = load_power(res)
    pool = collections.defaultdict(list)  # card types -> ranked cards, any rarity
    for cards in b.pool.values():
        for card in cards:
            if card['name'] in power:
                pool[card['main']].append(card)
    uses = collections.Counter(v['new'] for v in ledger.values() if v.get('kind') == 'dck')

    users = collections.defaultdict(list)
    for e in enemies:
        if mtf.is_unique(e):
            for d in e.get('deck') or []:
                users[d].append(e)
    rows, skipped = [], collections.Counter()
    for path in sorted(users):
        es = users[path]
        if path in ledger:
            rows.append(dict(deck=path, enemies=es, **ledger[path]))
            continue
        src = mtf.find_deck(plane, common, path)
        if not src:
            skipped['missing'] += 1
            continue
        out = os.path.join(plane, path)
        if path.endswith('.json'):
            tpl = mtf.lenient_json(src)
            t = tpl.setdefault('template', {})
            count = t.get('count') or 60
            before = float(t.get('rares') or 0)
            t['rares'] = round(min(1.0, before + 1.0 / count), 3)
            entry = dict(old='rares %.3g' % before, new='rares %.3g' % t['rares'], count=1, kind='json')
            if not dry:
                os.makedirs(os.path.dirname(out), exist_ok=True)
                with open(out, 'w', encoding='utf-8', newline='\n') as fh:
                    json.dump(tpl, fh, indent=1)
                    fh.write('\n')
            ledger[path] = entry
            rows.append(dict(deck=path, enemies=es, **entry))
            continue
        lines = open(src, encoding='utf-8', errors='replace').read().splitlines()
        deck, suffix, sets_used, title = mtf.read_dck(src)
        signature = signature_cards(es, deck)
        deck_colors = set(''.join(b.cards.get(n, {}).get('colors', '') for n in deck if not b.is_land(n)))
        creatures = [n for n in deck if n in b.cards and 'Creature' in (b.cards[n]['main'] or '')]
        types = collections.Counter(t for n in creatures for t in b.cards[n]['sub'])
        tribe = {t for t, c in types.items() if creatures and c >= max(2, len(creatures) / 4.0)}
        outs = sorted((power[n], -b.cards[n]['mv'], hashlib.md5(n.encode()).hexdigest(), n) for n, cnt in deck.items()
                      if n in b.cards and n in power and n not in cube and n not in signature and cnt <= 4
                      and b.cards[n]['main'] not in (None, 'Land') and not b.cards[n]['basic']
                      and not b.cards[n]['legendary'])
        choice = None
        for p_out, _, _, weak in outs[:6]:  # the weakest few, in order, until one has a real upgrade
            c = b.cards[weak]
            cnt = deck[weak]
            need_tribe = tribe & c['sub'] if 'Creature' in c['main'] else set()
            best = None
            for cand in pool.get(c['main'], []):
                n = cand['name']
                if n in deck or n in b.restricted or n.startswith('A-') or uses[n] >= MAX_USES:
                    continue
                if (cand['legendary'] and cnt > 1) or cnt > b.copy_limit(n):
                    continue
                if abs(cand['mv'] - c['mv']) > 1 or not set(cand['colors']) <= deck_colors:
                    continue
                if need_tribe and not (need_tribe & cand['sub']):
                    continue
                p = power[n]
                if p < p_out + MARGIN:
                    continue
                s = p + (0.08 if cand['colors'] == c['colors'] else 0) \
                    + (0.08 if c['sub'] & cand['sub'] and 'Creature' in c['main'] else 0) \
                    + (0.04 if sets_used & b.sets.get(n, set()) else 0) + (0.03 if n in cube else 0) \
                    - USE_PENALTY * uses[n]
                key = (-s, hashlib.md5((path + n).encode()).hexdigest())
                if best is None or key < best[0]:
                    best = (key, n, p)
            if best:
                choice = (weak, best[1], cnt, p_out, best[2])
                break
        if not choice:
            skipped['no upgrade found'] += 1
            rows.append(dict(deck=path, enemies=es, old=None, new=None, count=0, kind='none'))
            continue
        weak, new, cnt, p_old, p_new = choice
        uses[new] += 1
        # rewrite the [Main] lines of the weak card as one line of the new one, everything else as it was
        out_lines, sec, placed = [], None, False
        for ln in lines:
            s = ln.strip()
            if s.startswith('['):
                sec = s.lower()
            elif sec == '[main]':
                m = re.match(r'^(\d+)\s+([^|]+?)\s*(\|.*)?$', s)
                if m and m.group(2).strip() == weak:
                    if not placed:
                        out_lines.append('%d %s' % (cnt, new))
                        placed = True
                    continue
            out_lines.append(ln)
        entry = dict(old=weak, new=new, count=cnt, kind='dck', power=[round(p_old, 2), round(p_new, 2)])
        if not dry:
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with open(out, 'w', encoding='utf-8', newline='\n') as fh:
                fh.write('\n'.join(out_lines) + '\n')
        ledger[path] = entry
        rows.append(dict(deck=path, enemies=es, **entry))
    if not dry:
        with open(ledger_path, 'w', encoding='utf-8', newline='\n') as fh:
            json.dump(collections.OrderedDict(sorted(ledger.items())), fh, indent=1)
            fh.write('\n')
    swapped = sum(1 for r in rows if r.get('kind') == 'dck')
    print('unique decks: %d - %d cards swapped, %d generated decks +1 rare, skipped %s'
          % (len(rows), swapped, sum(1 for r in rows if r.get('kind') == 'json'), dict(skipped)))
    if report_path:
        L = ['# Unique enemy decks - one card stronger (round 644)', '',
             'Out: the weakest ranked spell (Forge\'s draft rankings; never a land, a legendary card, a cube staple or '
             'the enemy\'s own card). In: the best card of the same card types, mana value within 1, inside the deck\'s '
             'colors, clearly stronger; tribal decks keep their type; no card in more than %d decks. Strength = the '
             'best percentile the card reached in any set\'s pick order (1.0 = a first pick).' % MAX_USES, '',
             '| Enemy | Kind | Out | In | Copies | Strength out -> in |', '|---|---|---|---|---|---|']
        for r in sorted(rows, key=lambda r: (kind_of(r['enemies'][0]), r['enemies'][0]['name'])):
            names = ', '.join(e['name'] for e in r['enemies'])
            p = r.get('power') or ['', '']
            L.append('| %s | %s | %s | %s | %s | %s |' % (names, kind_of(r['enemies'][0]), r.get('old') or '-',
                                                         r.get('new') or '(none found)', r.get('count') or '',
                                                         ('%s -> %s' % tuple(p)) if p[0] != '' else ''))
        open(report_path, 'w', encoding='utf-8').write('\n'.join(L) + '\n')
        print('wrote', report_path)


if __name__ == '__main__':
    main(sys.argv)
