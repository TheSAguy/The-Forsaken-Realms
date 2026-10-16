r"""rebuild_commander_deck.py - TFR round 668: a Realm of Legends legend's 100-card Commander deck as a 60-card deck.

The cross-plane import rules: no Commander. Every legend deck Realm of Legends added in 1.077 is a 100-card singleton
with a one-card [Commander] section. This builds the 60-card deck a TFR boss plays:
  - The pool: the commander and the 99. Out: commander-only cards (restricted_cards.json commanderOnlyCards), a card
    whose text says "commander" (not "can be your commander"), the plane's restricted cards (Sol Ring sat in 22 of
    the 25 - a Commander habit, not a boss's design), digital-only cards (every printing in an Online edition),
    A- (Alchemy) cards, cards the AI drops (AI:RemoveDeck:All / Random), and names Forge does not know.
  - Lands by the spells' average mana value: 22 up to 2.6, 23 up to 3.2, 24 up to 3.8, else 25. Up to a third of them
    the pool's own nonbasic lands (the strongest first), the rest basics split by the colored pips the spells ask for.
  - Spells ranked by strength (Forge's draft rankings, as upgrade_unique_decks.py reads them: the best percentile in any
    set; an unranked card counts 0.5), plus 0.2 for a creature type the commander has and 0.15 for the deck's tribe
    (a type on a quarter of its creatures). The commander goes in twice. Then copies down the ranking: 3 for the top 5,
    2 for the next 7, then 1 - a legendary card at most 2, the copy limit kept - until the spells are full. At most
    6 copies at mana value 6+, at most 11 at 5+; a creature deck (40% creatures in the pool) keeps 14 creatures.
  - A gimmick keeps its design (Realm of Legends' 1.077 "gimmick" decks): a horde (a nonbasic line of 10+) or a deck
    of basics (60% of the 99). The legend twice,
    the 99 scaled to 58 by their own shares (rebuild_gimmick).
  - Names keep the set code the source line printed (its art).

usage: python rebuild_commander_deck.py <repo root> <source.dck> <out.dck> "<deck name>"
       (or import it: rebuild(Cards(repo), src, out, name) -> a summary dict)"""
import collections
import json
import os
import re
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'enemy_tiers'))
import make_tier_families as mtf  # noqa: E402
import upgrade_unique_decks as uud  # noqa: E402

BASICS = {'W': 'Plains', 'U': 'Island', 'B': 'Swamp', 'R': 'Mountain', 'G': 'Forest'}


class Cards:
    """The card data the rebuild needs on top of make_tier_families.Builder: text, pips and online-only."""

    def __init__(self, repo):
        self.res = os.path.join(repo, 'forge-gui', 'res')
        self.plane = os.path.join(self.res, 'adventure', 'The Forsaken Realms')
        self.builder = mtf.Builder(self.res, self.plane)
        self.power, self.cube = uud.load_power(self.res)
        rc = mtf.lenient_json(os.path.join(self.plane, 'config tables', 'restricted_cards.json'))
        self.commander_only = set(rc.get('commanderOnlyCards', []))
        self.text = {}
        self.cost = {}
        for root, _, files in os.walk(os.path.join(self.res, 'cardsfolder')):
            for f in files:
                if not f.endswith('.txt'):
                    continue
                body = open(os.path.join(root, f), encoding='utf-8', errors='replace').read()
                front = body.split('\nALTERNATE')[0]
                m = re.search(r'^Name:(.+)$', front, re.M)
                if not m:
                    continue
                name = m.group(1).strip()
                o = re.search(r'^Oracle:(.*)$', front, re.M)
                c = re.search(r'^ManaCost:(.*)$', front, re.M)
                self.text[name] = (o.group(1) if o else '') + '\n' + '\n'.join(
                    ln for ln in front.splitlines() if ln.startswith(('A:', 'K:', 'T:', 'S:')))
                self.cost[name] = c.group(1).strip() if c else ''
        self.online = self._online_only()

    def _online_only(self):
        prints = collections.defaultdict(set)
        ed = os.path.join(self.res, 'editions')
        for f in os.listdir(ed):
            lines = open(os.path.join(ed, f), encoding='utf-8', errors='replace').read().splitlines()
            kind = next((ln.split('=', 1)[1] for ln in lines if ln.startswith('Type=')), '')
            sec = None
            for ln in lines:
                s = ln.strip()
                if s.startswith('['):
                    sec = s.lower()
                    continue
                if sec != '[cards]':
                    continue
                m = re.match(r'^\S+\s+[CURMSLT]\s+(.+?)(?:\s+@.*)?$', s)
                if m:
                    prints[m.group(1).strip()].add(kind)
        return {n for n, kinds in prints.items() if kinds == {'Online'}}

    def why_out(self, name):
        c = self.builder.cards.get(name)
        if c is None:
            return 'not in Forge'
        if name in self.commander_only:
            return 'commander-only'
        if name in self.builder.restricted:  # Sol Ring sat in 22 of 25 - a Commander habit, not a boss's design
            return 'restricted'
        if name.startswith('A-') or name in self.online:
            return 'digital-only'
        if c['ai_bad']:
            return 'AI drops it'
        text = re.sub(r'[^.\n]*can be your commander[^.\n]*\.?', '', self.text.get(name, ''), flags=re.I)
        if re.search(r'\bcommander\b', text, re.I):
            return 'commander text'
        return None

    def strength(self, name):
        return self.power.get(name, 0.5)

    def pips(self, name):
        cost = self.cost.get(name, '')
        out = collections.Counter()
        for sym in cost.split():
            for ch in 'WUBRG':
                if ch in sym:
                    out[ch] += 1
        return out


def read_sections(path):
    sec, out = None, collections.OrderedDict()
    for ln in open(path, encoding='utf-8-sig'):
        s = ln.strip()
        if not s:
            continue
        if s.startswith('['):
            sec = s.strip('[]').lower()
            out.setdefault(sec, [])
            continue
        m = re.match(r'^(\d+)\s+(.+)$', s)
        if m and sec:
            name, _, code = m.group(2).partition('|')
            out[sec].append((int(m.group(1)), name.strip(), code.strip()))
    return out


def rebuild(cards, src, out, title):
    secs = read_sections(src)
    commander = [e for e in secs.get('commander', [])]
    pool = commander + secs.get('main', [])
    code = {}
    dropped = []
    seen = set()
    entries = []
    for n, name, cd in pool:
        if name in seen:
            continue
        seen.add(name)
        code[name] = cd
        why = cards.why_out(name)
        if why:
            dropped.append('%s (%s)' % (name, why))
            continue
        entries.append(name)
    b = cards.builder
    cmd = commander[0][1] if commander else None
    # a gimmick: a horde (a nonbasic line of 10+) or a deck of basics (60% of the 99 - Alhammarret's 93 Islands,
    # Torsten's 98 Plains); round 674 - basics split over printings (Myrel's 14 + 14 Plains) are no horde
    main = secs.get('main', [])
    total = sum(n for n, _, _ in main)
    basics = sum(n for n, name, _ in main if b.cards.get(name, {}).get('basic'))
    if (max((n for n, name, _ in main if not b.cards.get(name, {}).get('basic')), default=0) >= 10
            or (total and basics >= 0.6 * total)):
        return rebuild_gimmick(cards, secs, cmd, code, dropped, out, title)
    cmd_types = b.cards.get(cmd, {}).get('sub', set()) if cmd else set()
    lands = [x for x in entries if b.cards[x]['main'] == 'Land' and not b.cards[x]['basic']]
    spells = [x for x in entries if b.cards[x]['main'] != 'Land']
    creatures = [x for x in spells if 'Creature' in (b.cards[x]['main'] or '')]
    tribe = collections.Counter(t for x in creatures for t in b.cards[x]['sub'])
    tribal = {t for t, k in tribe.items() if creatures and k >= 0.25 * len(creatures)}
    creature_deck = len(creatures) >= 0.4 * max(1, len(spells))

    def score(x):
        s = cards.strength(x)
        sub = b.cards[x]['sub']
        if sub & cmd_types:
            s += 0.2
        elif sub & tribal:
            s += 0.15
        return s

    mvs = [b.cards[x]['mv'] for x in spells]
    avg = sum(mvs) / len(mvs) if mvs else 3
    n_lands = 22 if avg <= 2.6 else 23 if avg <= 3.2 else 24 if avg <= 3.8 else 25
    n_spells = 60 - n_lands
    deck = collections.OrderedDict()
    if cmd and cmd in spells:
        deck[cmd] = 2
    ranked = sorted((x for x in spells if x != cmd), key=lambda x: -score(x))

    def mv_total(lo):
        return sum(k for x, k in deck.items() if b.cards[x]['mv'] >= lo)

    def creature_total():
        return sum(k for x, k in deck.items() if 'Creature' in (b.cards[x]['main'] or ''))

    def room():
        return n_spells - sum(deck.values())

    for i, x in enumerate(ranked):
        if room() <= 0:
            break
        want = 3 if i < 5 else 2 if i < 12 else 1
        c = b.cards[x]
        if c['legendary']:
            want = min(want, 2)
        if c['deck_limit']:
            want = min(want, c['deck_limit'])
        mv = c['mv']
        if mv >= 6:
            want = min(want, max(0, 6 - mv_total(6)))
        if mv >= 5:
            want = min(want, max(0, 11 - mv_total(5)))
        if creature_deck and 'Creature' not in (c['main'] or ''):
            # keep room for 14 creatures
            left_creatures = sum(1 for y in ranked[i + 1:] if 'Creature' in (b.cards[y]['main'] or ''))
            need = max(0, 14 - creature_total())
            if need and room() - want < need and left_creatures:
                want = max(0, min(want, room() - need))
        want = min(want, room())
        if want > 0:
            deck[x] = want
    # still short (a small pool): one more copy of the best, round and round
    guard = 0
    while room() > 0 and guard < 400:
        guard += 1
        for x in list(deck):
            if room() <= 0:
                break
            c = b.cards[x]
            cap = 2 if c['legendary'] else (c['deck_limit'] or 4)
            if deck[x] < cap and not (c['mv'] >= 6 and mv_total(6) >= 6):
                deck[x] += 1
    # lands
    nonbasic = sorted(lands, key=lambda x: -cards.strength(x))[:n_lands // 3]
    land_lines = collections.OrderedDict((x, 1) for x in nonbasic)
    pips = collections.Counter()
    for x, k in deck.items():
        for ch, p in cards.pips(x).items():
            pips[ch] += p * k
    basics_needed = n_lands - len(nonbasic)
    if not pips:
        orig = collections.Counter()
        for n, name, _ in pool:
            for ch, nm in BASICS.items():
                if name == nm:
                    orig[ch] += n
        pips = orig or collections.Counter({'C': 1})
    total = sum(pips.values())
    shares = {ch: basics_needed * p / total for ch, p in pips.items()}
    counts = {ch: int(v) for ch, v in shares.items()}
    for ch in sorted(shares, key=lambda c: -(shares[c] - counts[c]))[:basics_needed - sum(counts.values())]:
        counts[ch] += 1
    for ch in 'WUBRGC':
        if counts.get(ch):
            land_lines[BASICS.get(ch, 'Wastes')] = counts[ch]
    lines = ['[metadata]', 'Name=%s' % title, '[Main]']
    for x, k in list(deck.items()) + list(land_lines.items()):
        cd = code.get(x)
        lines.append('%d %s%s' % (k, x, '|' + cd.upper() if cd and x not in BASICS.values() and x != 'Wastes' else ''))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(lines) + '\n')
    curve = collections.Counter(min(b.cards[x]['mv'], 7) for x, k in deck.items() for _ in range(k))
    colors = ''.join(ch for ch in 'WUBRG' if pips.get(ch))
    return dict(name=title, out=out, spells=sum(deck.values()), lands=sum(land_lines.values()),
                creatures=creature_total(), avg_mv=round(avg, 2), curve=dict(sorted(curve.items())), colors=colors,
                dropped=dropped, distinct=len(deck), commander=cmd, tribal=sorted(tribal))


def rebuild_gimmick(cards, secs, cmd, code, dropped, out, title):
    """A one-card horde (Realm of Legends' 1.077 "gimmick" decks: 99 Darksteel Citadels, 99 Horrible Hordes, 40
    Walking Corpses...) is its design, not a pool to pick from: the legend twice, the 99 scaled to 58 by their own
    shares (largest remainder), a line the rebuild may not use dropped and its share given to the rest."""
    b = cards.builder
    merged = collections.OrderedDict()  # one line per name - a basic split over printings counts once
    for n, name, _ in secs.get('main', []):
        if cards.why_out(name) is None or b.cards.get(name, {}).get('basic'):
            merged[name] = merged.get(name, 0) + n
    lines = [(n, name) for name, n in merged.items()]
    total = sum(n for n, _ in lines)
    target = 58 if cmd and cards.why_out(cmd) is None else 60  # a legend the AI drops (Johan) leaves no two slots
    shares = [(name, n * target / total) for n, name in lines]
    counts = {name: int(s) for name, s in shares}
    for name, s in sorted(shares, key=lambda x: -(x[1] - int(x[1])))[:target - sum(counts.values())]:
        counts[name] += 1
    # the horde itself (a line of 20+ in the 99) keeps its count; a side line keeps the copy limit, its excess going to
    # the deck's basic land (Balefang's 10 Crimson Hellkites came out 6)
    horde = {name for n, name in lines if n >= 20}
    basic = next((name for _, name in lines if b.cards.get(name, {}).get('basic')), None)
    for name, _ in shares:
        c = b.cards.get(name, {})
        cap = None if name in horde or c.get('basic') or c.get('any_number') else (c.get('deck_limit') or 4)
        if cap is not None and counts[name] > cap:
            extra = counts[name] - cap
            counts[name] = cap
            if basic:
                counts[basic] += extra
    deck = collections.OrderedDict()
    if cmd and cards.why_out(cmd) is None:
        deck[cmd] = 2
    for name, _ in shares:
        if counts[name]:
            deck[name] = deck.get(name, 0) + counts[name]
    out_lines = ['[metadata]', 'Name=%s' % title, '[Main]']
    for x, k in deck.items():
        cd = code.get(x)
        out_lines.append('%d %s%s' % (k, x, '|' + cd.upper() if cd and not b.cards.get(x, {}).get('basic') else ''))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(out_lines) + '\n')
    spells = sum(k for x, k in deck.items() if b.cards[x]['main'] != 'Land')
    pips = collections.Counter()
    for x, k in deck.items():
        for ch, p in cards.pips(x).items():
            pips[ch] += p * k
    for x, k in deck.items():  # a deck of basics shows its colors through them
        for ch, nm in BASICS.items():
            if x == nm:
                pips[ch] += k
    return dict(name=title, out=out, spells=spells, lands=sum(deck.values()) - spells,
                creatures=sum(k for x, k in deck.items() if 'Creature' in (b.cards[x]['main'] or '')),
                avg_mv=0, curve={}, colors=''.join(ch for ch in 'WUBRG' if pips.get(ch)), dropped=dropped,
                distinct=len(deck), commander=cmd, tribal=[], gimmick=True)


def main(argv):
    repo, src, out, title = argv[1:5]
    print(json.dumps(rebuild(Cards(repo), src, out, title), indent=1))


if __name__ == '__main__':
    main(sys.argv)
