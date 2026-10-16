r"""make_tier_families.py - TFR round 642: every ordinary enemy gets an Apprentice, an Adept and a Master deck.

The user (2026-10-15): "take each enemy ... and create Apprentice, Adept and Master versions for each. (Archmage should
be unique art) ... all 3 having a deck that share a lot, except that the Adept deck has slightly better cards", then
"make the Apprentice min deck size 60 ... [Adept] min deck size 50. This way there are more cards that will overlap,
when the excess is removed from the higher levels", and "Bosses / War Champions / Legends and Arch Mages [stay] unique".

WHO GETS A FAMILY
  Every enemy in world/enemies.json EXCEPT the unique ones: tier Mythic (the Archmages), "boss", "legend", spawnRate 0
  or less (the arena / war / roaming champions), and the names that carry a tier already ("Apprentice Red Wizard",
  "Adept Necromancer" - those families exist as separate enemies with their own art).

HOW A FAMILY IS BUILT (per deck file; an enemy with several decks gets a family for each)
  Yardstick: round 46's mean card rarity, C 1 / U 2 / R 4 / M 8, basic lands left out (Apprentice under 2.0, Adept
  2.0-3.0, Master 3.0-4.5). A card's rarity is the one it was printed at most often in real sets (Expansion, Core,
  Starter, Reprint, Draft, Duel Deck, Boxed Set).
  Apprentice: the enemy's own deck grown to at least 60 cards - more copies of its own commons / uncommons first (never
              past 4, never a legendary), then like-for-like commons, with basics added in the deck's own land ratio -
              then stepped DOWN until it scores 1.85 or less.
  Adept:      the Apprentice minus its weakest cards (lowest rarity, then the most expensive; basics go in the land
              ratio), so 50; then stepped UP to 2.15 or more if still short. A card the Apprentice had downgraded goes
              back to the enemy's original first.
  Master:     the Adept minus its weakest, so 40; then stepped up to 3.15 the same way.
  A deck already over 60 keeps its size as the Apprentice and shrinks in the same proportion (99 -> 83 -> 66).
  A step swaps a WHOLE deck line for a like-for-like card: the same colors, the same set of card types, mana value
  within 1, a shared creature type preferred, an edition the deck already prints from preferred, never a restricted
  card, an A- (Alchemy) card or one the AI marks RemoveDeck; Common <-> Rare in one swap where such a card exists.
  If the pool runs dry: going down, the rare / uncommon non-basic lands give way to the deck's own basics a copy at a
  time (an Apprentice's mana is plainer); then, either way, copies are traded with the deck's own cards (more of its
  commons for an Apprentice, more of its rares for a Master). Printings (|SET|art) of the cards that stay are kept.
  A generated deck (decks/*.json template) gets three templates: count 60 / 50 / 40, rares 0.1 / 0.3 / 0.55.

OUTPUT
  decks/tiers/<deck path, slashes as _>_<apprentice|adept|master>.dck (or .json), and world/enemy_tiers.json:
  {"<enemy>": {"life": [apprentice, adept, master], "decks": [[apprentice, adept, master] for each of its decks, in
  its "deck" order]}, ...}. Life: the enemy's own at its own tier, scaled by the roaming families' median life at the
  others. The game reads the table (util/EnemyTiers.java). Re-running rewrites both.
  --report <file> writes a JSON summary (sizes, scores, overlap) for review.

usage: python make_tier_families.py <repo root> [--report out.json] [--only "Enemy Name" ...]"""
import collections
import hashlib
import json
import os
import re
import sys

sys.dont_write_bytecode = True
W = {'C': 1, 'U': 2, 'R': 4, 'M': 8}
STEP_UP = {'C': 'U', 'U': 'R', 'R': 'M'}
STEP_DOWN = {'M': 'R', 'R': 'U', 'U': 'C'}
GOOD_SETS = {'Expansion', 'Core', 'Starter', 'Reprint', 'Draft', 'Duel_Deck', 'Boxed_Set'}
TYPES = ['Creature', 'Planeswalker', 'Instant', 'Sorcery', 'Enchantment', 'Artifact', 'Battle', 'Land']
TIERS = ('Common', 'Uncommon', 'Rare')
TIER_FILE = {'Common': 'apprentice', 'Uncommon': 'adept', 'Rare': 'master'}
SIZES = {'Common': 60, 'Uncommon': 50, 'Rare': 40}
TARGET_DOWN = 1.85
TARGET_UP = {'Uncommon': 2.15, 'Rare': 3.15}
JSON_RARES = {'Common': 0.1, 'Uncommon': 0.3, 'Rare': 0.55}
WIZARD_NAME = re.compile(r'^(Apprentice|Adept|Master|Archmage) ')


def lenient_json(path):
    text = open(path, encoding='utf-8').read()
    return json.loads(re.sub(r'(?m)^\s*//[^\n]*', '', text))


def is_unique(e):
    return (e.get('tier') == 'Mythic' or e.get('boss') or e.get('legend')
            or (e.get('spawnRate') is not None and e['spawnRate'] <= 0) or WIZARD_NAME.match(e['name']))


def mana_value(cost):
    if not cost or cost == 'no cost':
        return 0
    mv = 0
    for sym in cost.split():
        if sym.isdigit():
            mv += int(sym)
        elif sym in ('X', 'Y', 'Z'):
            continue
        else:
            mv += 1
    return mv


def colors_of(cost, colors_line):
    if colors_line:
        found = {c for c, word in zip('WUBRG', ('white', 'blue', 'black', 'red', 'green')) if word in colors_line.lower()}
        return ''.join(c for c in 'WUBRG' if c in found)
    return ''.join(c for c in 'WUBRG' if cost and c in cost)


class Builder:
    def __init__(self, res, plane):
        self.res, self.plane = res, plane
        self.cards = self._load_cards()
        self.rarity, self.sets = self._load_rarity()
        self.restricted = self._load_restricted()
        self.pool = collections.defaultdict(list)  # (colors, card types, rarity) -> [card]
        for name, c in self.cards.items():
            r = self.rarity.get(name)
            if (r is None or c['main'] in (None, 'Land') or c['basic'] or c['ai_bad'] or name in self.restricted
                    or name.startswith('A-')):
                continue
            self.pool[(c['colors'], c['main'], r)].append(c)
        self.origin = {}

    # ------------------------------------------------------------------ data
    def _load_cards(self):
        cards = {}
        for root, _, files in os.walk(os.path.join(self.res, 'cardsfolder')):
            for f in files:
                if not f.endswith('.txt'):
                    continue
                text = open(os.path.join(root, f), encoding='utf-8', errors='replace').read()
                front = text.split('\nALTERNATE')[0]
                d = {}
                for ln in front.splitlines():
                    if ':' in ln:
                        k, v = ln.split(':', 1)
                        if k in ('Name', 'ManaCost', 'Types', 'Colors') and k not in d:
                            d[k] = v.strip()
                if 'Name' not in d:
                    continue
                types = d.get('Types', '').split()
                # A land is a land first (Darksteel Citadel is an artifact land - never swapped); the pool key is the
                # FULL set of card types, so an artifact creature is replaced by an artifact creature.
                if 'Land' in types:
                    main = 'Land'
                else:
                    kinds = [t for t in TYPES if t in types]
                    main = '+'.join(kinds) if kinds else None
                last = max((types.index(t) for t in TYPES if t in types), default=-1)
                sub = set(types[last + 1:]) if last >= 0 else set()
                cards[d['Name']] = dict(
                    name=d['Name'], mv=mana_value(d.get('ManaCost')),
                    colors=colors_of(d.get('ManaCost'), d.get('Colors')), main=main,
                    legendary='Legendary' in types, basic='Basic' in types, sub=sub,
                    any_number='A deck can have any number' in text,
                    deck_limit=int(re.search(r'K:DeckLimit:(\d+)', text).group(1))
                    if re.search(r'K:DeckLimit:(\d+)', text) else None,
                    ai_bad='AI:RemoveDeck:All' in text or 'AI:RemoveDeck:Random' in text)
        return cards

    def _load_rarity(self):
        """name -> rarity letter: the most common rarity across real printings (promo / online / joke sets out)."""
        seen = collections.defaultdict(collections.Counter)
        sets = collections.defaultdict(set)
        ed = os.path.join(self.res, 'editions')
        for f in os.listdir(ed):
            lines = open(os.path.join(ed, f), encoding='utf-8', errors='replace').read().splitlines()
            meta = {ln.split('=', 1)[0]: ln.split('=', 1)[1] for ln in lines if '=' in ln and not ln.startswith(' ')}
            if meta.get('Type') not in GOOD_SETS:
                continue
            code = meta.get('Code', f)
            sec = None
            for ln in lines:
                s = ln.strip()
                if s.startswith('['):
                    sec = s.lower()
                    continue
                if sec != '[cards]':
                    continue
                m = re.match(r'^\S+\s+([CURMS])\s+(.+?)(?:\s+@.*)?$', s)
                if m and m.group(1) in W:
                    seen[m.group(2).strip()][m.group(1)] += 1
                    sets[m.group(2).strip()].add(code)
        return {n: c.most_common(1)[0][0] for n, c in seen.items()}, sets

    def _load_restricted(self):
        d = lenient_json(os.path.join(self.plane, 'config tables', 'restricted_cards.json'))
        out = set()
        for v in d.values():
            if isinstance(v, list):
                out |= {x for x in v if isinstance(x, str)}
        return out

    # ------------------------------------------------------------------ helpers
    def weight(self, name):
        r = self.rarity.get(name)
        return W.get(r) if r else None

    def score(self, deck):
        n = tot = 0
        for name, cnt in deck.items():
            c = self.cards.get(name)
            if c and c['basic']:
                continue
            w = self.weight(name)
            if w is None:
                continue
            n += cnt
            tot += cnt * w
        return tot / n if n else 0.0

    def is_land(self, name):
        return self.cards.get(name, {}).get('main') == 'Land'

    def is_basic(self, name):
        return self.cards.get(name, {}).get('basic', False)

    def copy_limit(self, name):
        c = self.cards.get(name, {})
        return 999 if c.get('basic') or c.get('any_number') else c.get('deck_limit') or 4

    def land_ratio(self, deck):
        size = sum(deck.values())
        return sum(c for n, c in deck.items() if self.is_land(n)) / size if size else 0.4

    # ------------------------------------------------------------------ swaps
    def substitute(self, name, cnt, direction, deck, used, deck_sets, seed, relax=0):
        if direction == 'up':  # a card the Apprentice downgraded returns to the enemy's own original first
            orig = self.origin.get(name)
            if orig and orig not in deck and orig not in used and (self.weight(orig) or 0) > (self.weight(name) or 0):
                return (-99, ''), orig, 99
        c = self.cards.get(name)
        r = self.rarity.get(name)
        if not c or not r or c['main'] in (None, 'Land') or c['basic']:
            return None
        table = STEP_UP if direction == 'up' else STEP_DOWN
        nr = table.get(r)
        if not nr:
            return None
        rarities = [nr]
        if table.get(nr) and table.get(nr) != 'M':  # Common <-> Rare in one swap, never to or from Mythic
            rarities = [table.get(nr), nr]
        for want in rarities:
            best = self._best(c, name, cnt, want, deck, used, deck_sets, seed, relax)
            if best:
                return best
        return None

    @staticmethod
    def _keys(c, relax):
        """The pools a card may be replaced from: its own colors and card types; relax 1 also takes a card of one of
        its colors and its first card type alone (an Artifact Creature -> a Creature, a Gruul card -> a green one)."""
        if not relax:
            return [(c['colors'], c['main'])]
        mains = [c['main']] + ([c['main'].split('+')[0]] if '+' in c['main'] else [])
        cols = [c['colors']] + (list(c['colors']) if len(c['colors']) > 1 else [])
        return [(col, m) for col in cols for m in mains]

    def _best(self, c, name, cnt, nr, deck, used, deck_sets, seed, relax=0):
        best = None
        for colors, main in self._keys(c, relax):
            for cand in self.pool.get((colors, main, nr), []):
                if cand['name'] in deck or cand['name'] in used:
                    continue
                if (cand['legendary'] and cnt > 1) or cnt > self.copy_limit(cand['name']):
                    continue  # 7 Seven Dwarves never become 7 of a four-copy card
                d = abs(cand['mv'] - c['mv'])
                if d > (2 if relax else 1):
                    continue
                s = (3 if d == 0 else 1) + (4 if c['sub'] & cand['sub'] and 'Creature' in c['main'] else 0) \
                    + (2 if deck_sets & self.sets.get(cand['name'], set()) else 0) \
                    + (2 if (colors, main) == (c['colors'], c['main']) else 0)
                h = hashlib.md5((seed + '|' + name + '|' + cand['name']).encode()).hexdigest()
                key = (-s, h)
                if best is None or key < best[0]:
                    best = (key, cand['name'], s)
        return best

    def reached(self, deck, direction, target):
        sc = self.score(deck)
        return sc >= target if direction == 'up' else sc <= target

    def step(self, deck, direction, target, deck_sets, seed):
        """Swap whole lines until the score passes target - like-for-like first, then the looser match (relax 1);
        if the card pool runs dry, trade copies with the deck's own cards (rebalance). Returns (new deck,
        [(old, new, count)]). A swapped-in card may be swapped again the same way (Rare -> Uncommon -> Common):
        rarity only ever moves one way, so it cannot loop."""
        deck = collections.OrderedDict(deck)
        swaps, used = [], set()
        for relax in (0, 1):
            for _ in range(400):
                if self.reached(deck, direction, target):
                    return deck, swaps
                options = []
                for name, cnt in deck.items():
                    w = self.weight(name)
                    if w is None:
                        continue
                    sub = self.substitute(name, cnt, direction, deck, used, deck_sets, seed, relax)
                    if sub:
                        options.append((w if direction == 'up' else -w, sub[0], name, sub[1], cnt))
                if not options:
                    break
                options.sort()
                _, _, old, new, cnt = options[0]
                deck = collections.OrderedDict((new if k == old else k, v) for k, v in deck.items())
                used.add(new)
                swaps.append((old, new, cnt))
        if direction == 'down':
            deck = self.plainer_lands(deck, target)
        return self.rebalance(deck, direction, target), swaps

    def plainer_lands(self, deck, target):
        """Going down only: a copy at a time, the rarest non-basic land (a Triome, a fetchland) gives way to the deck's
        scarcest basic - an Apprentice's mana is plainer. Common lands (gates) stay; a deck without basics keeps all."""
        deck = collections.OrderedDict(deck)
        basics = [n for n in deck if self.is_basic(n)]
        while basics and not self.reached(deck, 'down', target):
            fancy = [(self.weight(n), n) for n in deck if self.is_land(n) and not self.is_basic(n)
                     and (self.weight(n) or 0) >= 2]
            if not fancy:
                break
            n = max(fancy)[1]
            deck[n] -= 1
            if not deck[n]:
                del deck[n]
            b = min(basics, key=lambda x: deck.get(x, 0))
            deck[b] = deck.get(b, 0) + 1
        return deck

    def rebalance(self, deck, direction, target):
        """The last resort: one copy at a time, take out a copy of the deck's highest-rarity spell and put in a copy of
        its own lowest-rarity one (the reverse going up), within the copy limit - the deck's own cards, so the levels
        still share them."""
        deck = collections.OrderedDict(deck)
        sign = 1 if direction == 'down' else -1
        for _ in range(200):
            if self.reached(deck, direction, target):
                break
            spells = [(self.weight(n), n) for n in deck if not self.is_land(n) and self.weight(n)]
            outs = sorted(spells, key=lambda x: (-sign * x[0], x[1]))
            ins = sorted((x for x in spells if deck[x[1]] < self.copy_limit(x[1])
                          and not self.cards.get(x[1], {}).get('legendary')), key=lambda x: (sign * x[0], x[1]))
            if not outs or not ins or (outs[0][0] - ins[0][0]) * sign <= 0:
                break
            o, i = outs[0][1], ins[0][1]
            deck[o] -= 1
            if not deck[o]:
                del deck[o]
            deck[i] += 1
        return deck

    # ------------------------------------------------------------------ resizing
    def shrink(self, deck, target_size, ratio):
        """Drop the weakest cards: basics (or the most-copied land) in the land ratio, then spells lowest rarity /
        most expensive."""
        deck = collections.OrderedDict(deck)
        while sum(deck.values()) > target_size:
            size = sum(deck.values())
            lands = sum(c for n, c in deck.items() if self.is_land(n))
            if lands > round((size - 1) * ratio):
                pool = [(c, n) for n, c in deck.items() if self.is_basic(n)] or \
                       [(c, n) for n, c in deck.items() if self.is_land(n)]
                if pool:
                    n = max(pool)[1]
                    deck[n] -= 1
                    if not deck[n]:
                        del deck[n]
                    continue
            spells = [(self.weight(n) or 1, -self.cards.get(n, {}).get('mv', 0), -c, n) for n, c in deck.items()
                      if not self.is_land(n)]
            if not spells:
                break
            n = min(spells)[3]
            deck[n] -= 1
            if not deck[n]:
                del deck[n]
        return deck

    def grow(self, deck, target_size, ratio, seed):
        deck = collections.OrderedDict(deck)
        basics = [n for n in deck if self.is_basic(n)]
        for _ in range(300):
            size = sum(deck.values())
            if size >= target_size:
                break
            lands = sum(c for n, c in deck.items() if self.is_land(n))
            if basics and lands < round((size + 1) * ratio):
                n = min(basics, key=lambda b: deck.get(b, 0))  # the scarcest basic, to keep the color mix
                deck[n] = deck.get(n, 0) + 1
                continue
            spells = sorted((self.weight(n) or 9, self.cards.get(n, {}).get('mv', 0), n) for n, c in deck.items()
                            if not self.is_land(n) and c < self.copy_limit(n)
                            and not self.cards.get(n, {}).get('legendary') and (self.weight(n) or 9) <= 2)
            if spells:
                deck[spells[0][2]] += 1
                continue
            commons = [n for n in deck if not self.is_land(n) and (self.weight(n) or 9) <= 2]
            sib = None
            for n in sorted(commons, key=lambda x: hashlib.md5((seed + x).encode()).hexdigest()):
                c, r = self.cards.get(n), self.rarity.get(n)
                sib = self._best(c, n, 1, r, deck, set(), set(), seed) if c and r and c['main'] else None
                if sib:
                    break
            if sib:
                deck[sib[1]] = 1
                continue
            if basics:  # nothing left to copy: fill with lands
                n = min(basics, key=lambda b: deck.get(b, 0))
                deck[n] = deck.get(n, 0) + 1
                continue
            break
        return deck

    # ------------------------------------------------------------------ one family
    def family(self, deck, deck_sets, seed):
        self.origin = {}
        ratio = self.land_ratio(deck)
        # a few source decks break the four-copy rule (9 Evolving Wilds); their families keep to it - growing refills
        deck = collections.OrderedDict((n, min(c, self.copy_limit(n))) for n, c in deck.items())
        native = sum(deck.values())
        appr = self.grow(deck, SIZES['Common'], ratio, seed) if native < SIZES['Common'] else deck
        appr, down = self.step(appr, 'down', TARGET_DOWN, deck_sets, seed)
        for old, new, _ in down:  # a chain (Rare -> Uncommon -> Common) points back to the enemy's own card
            self.origin[new] = self.origin.pop(old, old)
        out = {'Common': appr}
        top = sum(appr.values())
        prev = appr
        for tier in ('Uncommon', 'Rare'):
            want = SIZES[tier] if top <= SIZES['Common'] else max(SIZES[tier], round(top * SIZES[tier] / 60.0))
            cut = self.shrink(prev, want, ratio) if sum(prev.values()) > want else prev
            up, _ = self.step(cut, 'up', TARGET_UP[tier], deck_sets, seed)
            out[tier] = up
            prev = up
        return out


# ---------------------------------------------------------------------- deck files
def find_deck(plane, common, path):
    for base in (plane, common):
        p = os.path.join(base, path)
        if os.path.exists(p):
            return p
    return None


def read_dck(path):
    """[Main] as name -> count, the printing suffix each name first carried, the editions it prints from, and the
    deck's Name=."""
    main, suffix, sets_used, sec, title = collections.OrderedDict(), {}, set(), None, None
    for ln in open(path, encoding='utf-8', errors='replace'):
        s = ln.strip()
        if s.startswith('['):
            sec = s.lower()
            continue
        if sec == '[metadata]' and s.lower().startswith('name='):
            title = s.split('=', 1)[1]
        if sec != '[main]':
            continue
        m = re.match(r'^(\d+)\s+([^|]+?)\s*(\|.*)?$', s)
        if m:
            name = m.group(2).strip()
            main[name] = main.get(name, 0) + int(m.group(1))
            if m.group(3):
                suffix.setdefault(name, m.group(3))
                sets_used.add(m.group(3)[1:].split('|')[0])
    return main, suffix, sets_used, title


def write_dck(path, title, deck, suffix):
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('[metadata]\nName=%s\n[Main]\n' % title)
        for n, c in deck.items():
            fh.write('%d %s%s\n' % (c, n, suffix.get(n, '')))


def overlap(a, b):
    return sum(min(c, b.get(n, 0)) for n, c in a.items())


def tier_life(e, medians):
    """The enemy's own life at its own tier; at another tier, scaled by the roaming families' median life there
    (a tough Apprentice stays tougher than most Apprentices as an Adept, too)."""
    life, label = e.get('life') or medians.get(e.get('tier'), 12), e.get('tier')
    base = medians.get(label)
    return [life if t == label or not base else max(1, int(round(life * medians[t] / float(base)))) for t in TIERS]


def main(argv):
    if len(argv) < 2 or argv[1].startswith('-'):
        raise SystemExit(__doc__)
    root = argv[1]
    report_path = argv[argv.index('--report') + 1] if '--report' in argv else None
    only = set(argv[argv.index('--only') + 1:]) if '--only' in argv else None
    res = os.path.join(root, 'forge-gui', 'res')
    plane = os.path.join(res, 'adventure', 'The Forsaken Realms')
    common = os.path.join(res, 'adventure', 'common')
    out_dir = os.path.join(plane, 'decks', 'tiers')
    os.makedirs(out_dir, exist_ok=True)

    enemies = json.load(open(os.path.join(plane, 'world', 'enemies.json'), encoding='utf-8'))
    life_medians = {}
    for t in TIERS:
        lives = sorted(e.get('life', 0) for e in enemies
                       if not is_unique(e) and e.get('tier') == t and (e.get('spawnRate') or 0) > 0 and e.get('life'))
        life_medians[t] = lives[len(lives) // 2]
    print('life medians of the roaming families:', life_medians)
    b = Builder(res, plane)
    table, report, built, missing = collections.OrderedDict(), [], {}, []
    for e in enemies:
        if is_unique(e) or (only and e['name'] not in only):
            continue
        triples = []
        for path in e.get('deck') or []:
            if path in built:
                triples.append(built[path])
                continue
            src = find_deck(plane, common, path)
            if not src:
                missing.append((e['name'], path))
                triples = None
                break
            stem = re.sub(r'[^A-Za-z0-9]+', '_', re.sub(r'^decks/', '', path.rsplit('.', 1)[0])).strip('_').lower()
            ext = path.rsplit('.', 1)[1]
            files = ['decks/tiers/%s_%s.%s' % (stem, TIER_FILE[t], ext) for t in TIERS]
            if ext == 'json':
                tpl = lenient_json(src)
                for t, rel in zip(TIERS, files):
                    d = json.loads(json.dumps(tpl))
                    d.setdefault('template', {})
                    d['template']['count'] = SIZES[t]
                    d['template']['rares'] = JSON_RARES[t]
                    with open(os.path.join(plane, rel), 'w', encoding='utf-8', newline='\n') as fh:
                        json.dump(d, fh, indent=1)
                        fh.write('\n')
                report.append(dict(enemy=e['name'], deck=path, kind='json'))
            else:
                deck, suffix, sets_used, title = read_dck(src)
                fam = b.family(deck, sets_used, e['name'] + '|' + path)
                for t, rel in zip(TIERS, files):
                    write_dck(os.path.join(plane, rel), title or e['name'], fam[t], suffix)
                A, D, M = fam['Common'], fam['Uncommon'], fam['Rare']
                report.append(dict(
                    enemy=e['name'], deck=path, kind='dck', label=e.get('tier'), native=sum(deck.values()),
                    native_score=round(b.score(deck), 2),
                    sizes=[sum(fam[t].values()) for t in TIERS], scores=[round(b.score(fam[t]), 2) for t in TIERS],
                    lands=[sum(c for n, c in fam[t].items() if b.is_land(n)) for t in TIERS],
                    a_d=overlap(A, D), d_m=overlap(D, M),
                    all3=sum(min(A.get(n, 0), D.get(n, 0), M.get(n, 0)) for n in set(A) | set(D) | set(M))))
            built[path] = files
            triples.append(files)
        if triples:
            table[e['name']] = dict(life=tier_life(e, life_medians), decks=triples)
    with open(os.path.join(plane, 'world', 'enemy_tiers.json'), 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('{\n')
        rows = ['  %s: %s' % (json.dumps(k), json.dumps(v)) for k, v in table.items()]
        fh.write(',\n'.join(rows))
        fh.write('\n}\n')
    if report_path:
        json.dump(report, open(report_path, 'w', encoding='utf-8'), indent=1)
    print('families: %d enemies, %d deck files; missing decks: %d' % (len(table), len(built), len(missing)))
    for m in missing:
        print('  missing', m)


if __name__ == '__main__':
    main(sys.argv)
