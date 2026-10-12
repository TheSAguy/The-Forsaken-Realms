#!/usr/bin/env python
"""sprite_footprints.py - where the creature stands inside its frame (round 578).

An enemy's atlas frame is often far wider than the creature, and taller. A remake's cells are as wide as
its widest pose - the Dwarf Mercenary's Attack lunge reaches column 152 of a 154 px cell around a 37 px
body - and many frames carry empty rows under the feet. The game anchored the FRAME at the enemy's
position, so the body drew up to two tiles right of (and above) where the map put it, and the whole frame
was the duel box: the duel started more than a tile before the player reached the body.

Round 578 gives such an atlas a `footprint` field on its reference Idle frame. libGDX keeps a region field
it does not know (TextureAtlas.AtlasRegion.findValue), so the art files stay ordinary atlases:

    Idle
      xy: 0, 64
      size: 154, 59
      footprint: 59, 1, 37, 58        <- x, y, width, height of the body inside that frame (frame px, y down)

CharacterSprite.readFootprint() reads it; draw() then, every frame:
  - makes the actor's box (the duel box, getCenter, the crown, the color dots) the body's columns, never
    narrower than min(one tile of its rank, body + 6 px), reaching from the feet to the frame's top;
  - draws the frame offset so the body sits centred in that box (a mirrored frame from the other side),
    and a walker's lowest drawn pixel (its shadow, if it has one) on the enemy's position. A flier
    (EnemyData.flying) keeps the hover its art was drawn with. An attack's lunge draws outside the box.
An atlas without the field draws exactly as before.

What is measured, over every frame of the reference animation: the columns and the top are the opaque
box (alpha >= 128, as waypoint_routes_qa's feet are); the bottom is the lowest pixel drawn at all
(alpha > 0), so a soft shadow stays on the ground. The reference animation is the plain side view "Idle",
else "IdleRight"; the field goes on the frame libGDX's findRegion() returns (the first, or the lowest
`index:` when the atlas has indexes).

Only atlases where it changes something are written: an enemy using it drawn >= MIN_SHIFT px off sideways,
its box >= 2 x MIN_SHIFT px wider than it will be, or (a walker) >= MIN_SHIFT px off the ground.

usage (repo root):
  python dev-tools/sprite_footprints.py               dry run: what would change, by folder
  python dev-tools/sprite_footprints.py --write       write / refresh / remove the field
  python dev-tools/sprite_footprints.py --check       exit 1 when any atlas needs --write (new art)
  python dev-tools/sprite_footprints.py --csv out.csv one row per enemy, today and with the field
Run it after ANY enemy art lands (remake installs, pack imports, LPC batches): a regenerated atlas loses the
field, and its enemies fall back to the frame-anchored look until this is re-run.
"""
import argparse, collections, csv, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sprite_sizes as ss

from PIL import Image

FIELD = 'footprint'
MIN_SHIFT = 2.0          # drawn px; see the docstring
TILE = 16.0              # CharacterSprite.FOOTPRINT_TILE: one tile at rank cue 1.0
MARGIN = 3.0             # CharacterSprite.FOOTPRINT_MARGIN: a side, at most, around a body narrower than a tile
REFERENCE = ('Idle', 'IdleRight')
TUNING_DEFAULTS = {'enemyTierScaleCommon': 0.8125, 'enemyTierScaleUncommon': 1.0, 'enemyTierScaleRare': 1.25,
                   'enemyTierScaleMythic': 1.5, 'enemySpriteFrameCap': 1.4}


# ------------------------------------------------------------------ tuning (settings.json has // comments)
_TUNING = None


def tuning():
    """The rank cues and the frame cap from the plane's settings.json, else the Java defaults. The file is
    JSON with // comment lines, which json.load refuses - strip them first."""
    global _TUNING
    if _TUNING is None:
        _TUNING = dict(TUNING_DEFAULTS)
        try:
            txt = open(os.path.join(ss.PLANE, 'config tables', 'settings.json'), encoding='utf-8').read()
            txt = re.sub(r'(?m)^\s*//.*$', '', txt)
            cfg = json.loads(txt)
            for k in _TUNING:
                if k in cfg:
                    _TUNING[k] = float(cfg[k])
        except (OSError, ValueError) as e:
            print('sprite_footprints: settings.json unreadable (%s) - Java defaults used' % e, file=sys.stderr)
    return _TUNING


def tier_cue(tier):
    t = tuning()
    return {'Common': t['enemyTierScaleCommon'], 'Uncommon': t['enemyTierScaleUncommon'],
            'Rare': t['enemyTierScaleRare'], 'Mythic': t['enemyTierScaleMythic']}.get(tier or 'Common', 1.0)


def draw_scale(enemy, frame_h):
    """EnemyData.scale x TuningData.tierSizeMultiplier(tier, frame height x scale, boss || keepSize) - the
    factor CharacterSprite.draw() multiplies the frame by."""
    t = tuning()
    scale = float(enemy.get('scale', 1.0))
    if scale < 0:
        scale = 1.0
    cue = tier_cue(enemy.get('tier'))
    mult, base_h = cue, frame_h * scale
    if not (enemy.get('boss') or enemy.get('keepSize')) and base_h > 0 and t['enemySpriteFrameCap'] > 0:
        ceiling = cue * TILE * t['enemySpriteFrameCap']
        if base_h * cue > ceiling:
            mult = cue * ceiling / (base_h * cue)
    return scale * mult


# ------------------------------------------------------------------ atlas text
class Block:
    __slots__ = ('name', 'page', 'start', 'end', 'xy', 'size', 'index', 'field_line', 'indent')


def parse_blocks(lines):
    """Region blocks of a libGDX text atlas: name, page, [start, end) line span, xy, size, index, the line
    holding our field (or None) and the property indent."""
    blocks, page, cur = [], None, None
    for i, raw in enumerate(lines):
        line = raw.rstrip('\r\n')
        s = line.strip()
        if not s:
            cur = None
            continue
        indented = line[0] in ' \t'
        if not indented:
            if ':' in s:
                cur = None          # a page header line (size:, format:, ...)
                continue
            if s.lower().endswith(('.png', '.jpg')):
                page, cur = s, None
                continue
            cur = Block()
            cur.name, cur.page, cur.start, cur.end = s, page, i, i + 1
            cur.xy = cur.size = None
            cur.index, cur.field_line, cur.indent = -1, None, '  '
            blocks.append(cur)
            continue
        if cur is None:
            continue
        cur.end = i + 1
        cur.indent = line[:len(line) - len(line.lstrip())]
        key, _, val = s.partition(':')
        key = key.strip()
        try:
            nums = [int(v) for v in val.split(',')]
        except ValueError:
            nums = []
        if key == 'xy':
            cur.xy = tuple(nums[:2])
        elif key == 'size':
            cur.size = tuple(nums[:2])
        elif key == 'bounds':
            cur.xy, cur.size = tuple(nums[:2]), tuple(nums[2:4])
        elif key == 'index' and nums:
            cur.index = nums[0]
        elif key == FIELD:
            cur.field_line = i
    return blocks


def reference_block(blocks):
    """The block libGDX's findRegion() returns for the first REFERENCE name present: regions are sorted by
    index only when some region has one (index -1 last), stably; otherwise file order."""
    for name in REFERENCE:
        cands = [b for b in blocks if b.name == name and b.xy and b.size]
        if cands:
            return min(cands, key=lambda b: (b.index if b.index != -1 else 1 << 30, b.start))
    return None


# ------------------------------------------------------------------ measuring
_PAGES = {}


def _page(atlas_path, page):
    png = os.path.join(os.path.dirname(atlas_path), page)
    if png not in _PAGES:
        _PAGES[png] = Image.open(png).convert('RGBA') if os.path.exists(png) else None
    return _PAGES[png]


_INFO = {}


def atlas_info(atlas_path):
    """{'ref': (w, h), 'body': (x, y, w, h) measured, 'field': (x, y, w, h) written or None,
    'opaque_bottom': y1 of the opaque box} for an atlas, or None when it has no usable reference frame."""
    if atlas_path in _INFO:
        return _INFO[atlas_path]
    info = None
    try:
        lines = open(atlas_path, encoding='utf-8').read().split('\n')
    except OSError:
        lines = []
    blocks = parse_blocks(lines)
    ref = reference_block(blocks)
    if ref is not None and ref.page:
        im = _page(atlas_path, ref.page)
        frames = [b for b in blocks if b.name == ref.name and b.page == ref.page and b.size == ref.size and b.xy]
        opaque = drawn = None
        if im is not None:
            for b in frames:
                (x, y), (w, h) = b.xy, b.size
                a = im.crop((x, y, x + w, y + h)).getchannel('A')
                o = a.point(lambda v: 255 if v >= 128 else 0).getbbox()
                d = a.getbbox()
                if o:
                    opaque = o if opaque is None else (min(opaque[0], o[0]), min(opaque[1], o[1]),
                                                       max(opaque[2], o[2]), max(opaque[3], o[3]))
                if d:
                    drawn = d if drawn is None else (min(drawn[0], d[0]), min(drawn[1], d[1]),
                                                     max(drawn[2], d[2]), max(drawn[3], d[3]))
        field = None
        if ref.field_line is not None:
            try:
                field = tuple(int(v) for v in lines[ref.field_line].split(':', 1)[1].split(','))[:4]
            except ValueError:
                field = None
        if opaque:
            bottom = max(opaque[3], drawn[3])
            info = {'ref': ref.size, 'body': (opaque[0], opaque[1], opaque[2] - opaque[0], bottom - opaque[1]),
                    'opaque_bottom': opaque[3], 'field': field}
    _INFO[atlas_path] = info
    return info


def geometry(enemy, info, with_field):
    """How the game draws `enemy` - with the footprint field (with_field=True, as the Java does when the atlas
    carries one) or without it (the frame anchored at the position, as before round 578). Drawn px relative to
    the enemy's position (its box's bottom-left), y UP:
      box_w, box_h   the actor's box - the duel box
      body_l, body_r the opaque body's columns
      feet           the opaque body's bottom row (waypoint_routes_qa's feet stand on it)
      shadow         the lowest drawn pixel"""
    fw, fh = info['ref']
    k = draw_scale(enemy, fh)
    bx, by, bw, bh = info['body']
    opaque_lift = (fh - info['opaque_bottom']) * k
    drawn_lift = (fh - (by + bh)) * k
    if not with_field:
        return dict(k=k, box_w=fw * k, box_h=fh * k, body_l=bx * k, body_r=(bx + bw) * k,
                    feet=opaque_lift, shadow=drawn_lift)
    if info['field'] is not None:
        bx, by, bw, bh = info['field']
        drawn_lift = (fh - (by + bh)) * k
    body = bw * k
    box = max(body, min(TILE * tier_cue(enemy.get('tier')), body + 2 * MARGIN))
    ground = not enemy.get('flying')
    drop = drawn_lift if ground else 0.0
    pad = (box - body) / 2.0
    return dict(k=k, box_w=box, box_h=fh * k - drop, body_l=pad, body_r=pad + body,
                feet=opaque_lift - drop, shadow=drawn_lift - drop)


def worth_a_field(enemy, info):
    """Whether the field changes this enemy by MIN_SHIFT px or more (see the docstring)."""
    a, b = geometry(enemy, info, False), geometry(enemy, info, True)
    shift = abs((a['body_l'] + a['body_r']) - (b['body_l'] + b['body_r'])) / 2.0
    narrower = a['box_w'] - b['box_w']
    lower = a['shadow'] - b['shadow']
    return shift >= MIN_SHIFT or narrower >= 2 * MIN_SHIFT or lower >= MIN_SHIFT


def enemy_geometry():
    """{enemy name: geometry(...) as the game draws it NOW} - with the field where its atlas carries one.
    For waypoint_routes_qa.enemy_feet() and any other tool that needs the drawn body."""
    out = {}
    for e in json.load(open(os.path.join(ss.PLANE, 'world', 'enemies.json'), encoding='utf-8')):
        path, _ = ss.resolve(e.get('sprite') or '')
        info = atlas_info(path) if path else None
        if info:
            out[e['name']] = geometry(e, info, info['field'] is not None)
    return out


# ------------------------------------------------------------------ writing
def rewrite(atlas_path, body):
    """Put `footprint: x, y, w, h` on the reference frame (body None: remove it). -> True when the file changed."""
    raw = open(atlas_path, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in raw else '\n'
    lines = raw.split(nl)
    blocks = parse_blocks(lines)
    drop = {b.field_line for b in blocks if b.field_line is not None}
    ref = reference_block(blocks)
    out = []
    for i, line in enumerate(lines):
        if i in drop:
            continue
        out.append(line)
        if body is not None and ref is not None and i == ref.end - 1:
            out.append('%s%s: %d, %d, %d, %d' % (ref.indent, FIELD, body[0], body[1], body[2], body[3]))
    new = nl.join(out)
    if new == raw:
        return False
    with open(atlas_path, 'w', encoding='utf-8', newline='') as f:
        f.write(new)
    return True


def survey():
    """-> (plan {atlas path: (field it should carry or None, field it carries or None, sprite)}, one row per enemy,
    Counter of the enemies with a field by sprite folder)."""
    enemies = json.load(open(os.path.join(ss.PLANE, 'world', 'enemies.json'), encoding='utf-8'))
    users = collections.defaultdict(list)
    for e in enemies:
        if e.get('sprite'):
            users[e['sprite']].append(e)

    plan, rows = {}, []
    by_folder = collections.Counter()
    for sp, es in sorted(users.items()):
        path, where = ss.resolve(sp)
        info = atlas_info(path) if path else None
        if not info:
            continue
        want = any(worth_a_field(e, info) for e in es)
        target = tuple(info['body']) if want else None
        plan[path] = (target, info['field'], sp)
        if want:
            by_folder[sp.split('/')[2] if sp.count('/') >= 3 else sp] += len(es)
        for e in es:
            a, b = geometry(e, info, False), geometry(e, info, True)
            rows.append({'enemy': e['name'], 'sprite': sp, 'field': int(want), 'flying': int(bool(e.get('flying'))),
                         'boss': int(bool(e.get('boss') or e.get('keepSize'))),
                         'box_today': '%.1fx%.1f' % (a['box_w'], a['box_h']),
                         'box_after': '%.1fx%.1f' % ((b if want else a)['box_w'], (b if want else a)['box_h']),
                         'body_centre_today': round((a['body_l'] + a['body_r']) / 2, 1),
                         'body_centre_after': round(((b if want else a)['body_l'] + (b if want else a)['body_r']) / 2, 1),
                         'shadow_today': round(a['shadow'], 1),
                         'shadow_after': round((b if want else a)['shadow'], 1)})
    return plan, rows, by_folder


def pending(plan=None):
    """[(atlas path, 'add' | 'remove' | 'stale', sprite)] - the atlases --write would change. validate_plane_data.py
    reports these, so new or regenerated enemy art without its field is caught before packaging."""
    plan = plan if plan is not None else survey()[0]
    out = []
    for p, (want, has, sp) in sorted(plan.items()):
        if want != has:
            out.append((p, 'add' if has is None else 'remove' if want is None else 'stale', sp))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--write', action='store_true')
    ap.add_argument('--check', action='store_true')
    ap.add_argument('--csv')
    args = ap.parse_args()

    plan, rows, by_folder = survey()
    todo = {p: plan[p] for p, _what, _sp in pending(plan)}
    n_field = sum(1 for t in plan.values() if t[0])
    print('%d enemy atlases measured; %d want a footprint (%d enemies), %d need writing'
          % (len(plan), n_field, sum(by_folder.values()), len(todo)))
    print('  enemies by folder: ' + ', '.join('%s %d' % kv for kv in by_folder.most_common()))
    common = [t[2] for p, t in plan.items() if t[0] and os.sep + 'common' + os.sep in os.path.normpath(p)]
    if common:
        print('  %d of them are stock atlases under adventure/common (an upstream file each)' % len(common))

    if args.csv:
        with open(args.csv, 'w', newline='', encoding='utf-8') as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0]))
            w.writeheader()
            w.writerows(rows)
        print('wrote', args.csv)
    if args.write:
        changed = sum(rewrite(p, t[0]) for p, t in todo.items())
        print('wrote %d atlas file(s)' % changed)
        return 0
    if args.check and todo:
        for p, what, sp in pending(plan):
            print('  NEEDS --write  %s (%s)' % (sp, what))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
