"""Round 671: the chain pendants get the bottom of their chain back.

The user, of Piper's Charm in a shop: "It looks like this necklace got clipped off." Round 609 built five Neck pendant
icons from round 506's NeckTreasure: its chain with the coin cut away and the item's own art hanging from the right
strand (Piper's Charm, Phoenix in silver, Phoenix Charm, Celestial Prism, Goose); round 630's two Pledges recolored the
Celestial Prism's gem. Cutting the coin away also cut the bottom of the chain, which ran behind the coin, so the left
strand ended in the air.

Each icon is rebuilt as NeckTreasure's whole chain (its rows 0-18, and row 19's two end beads beside the coin -
recolored to the icon's own chain, so the silver Phoenix stays silver) with the icon drawn over it: the chain is whole
again and passes behind the item.

usage: python close_pendant_chains.py <repo root> [--preview out.png] [--write]"""
import os
import sys

from PIL import Image

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import poi_outline as po  # noqa: E402

SOURCE = 'NeckTreasure'
TARGETS = ['PipersCharmPendant', 'PhoenixPendant', 'PhoenixCharmPendant', 'CelestialPrismPendant', 'GoosePendant',
           'LilianasPledge', 'ChandrasPledge']


def chain_mask(x, y):
    return y <= 18 or (y == 19 and (10 <= x <= 13 or 18 <= x <= 21))


def rebuild(src, cur):
    sp, cp = src.load(), cur.load()
    cmap = {}
    for y in range(3, 15):  # the strands: the icon's own chain colors
        for x in range(32):
            if sp[x, y][3] >= 128 and cp[x, y][3] >= 128:
                cmap.setdefault(sp[x, y][:3], cp[x, y][:3])
    chain = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    kp = chain.load()
    for y in range(32):
        for x in range(32):
            c = sp[x, y]
            if c[3] >= 128 and chain_mask(x, y):
                kp[x, y] = cmap.get(c[:3], c[:3]) + (255,)
    chain.alpha_composite(cur)
    return chain


def main(argv):
    repo = argv[1]
    atlas = os.path.join(repo, 'forge-gui', 'res', 'adventure', 'The Forsaken Realms', 'sprites', 'items.atlas')
    reg = po.regions(atlas)
    src = po.crop(atlas, reg[SOURCE]).convert('RGBA')
    pages, pairs = {}, []
    for name in TARGETS:
        r = reg[name]
        path = os.path.join(os.path.dirname(atlas), r['page'])
        page = pages.setdefault(path, Image.open(path).convert('RGBA'))
        x, y = r['xy']
        cur = page.crop((x, y, x + 32, y + 32))
        new = rebuild(src, cur)
        pairs.append((name, cur, new))
        page.paste(new, (x, y))
    if '--preview' in argv:
        s = 8
        sheet = Image.new('RGBA', (len(pairs) * (32 * s + 16) + 16, 2 * (32 * s + 16) + 16), (40, 40, 48, 255))
        for i, (name, cur, new) in enumerate(pairs):
            for row, im in enumerate((cur, new)):
                b = im.resize((32 * s, 32 * s), Image.NEAREST)
                sheet.paste(b, (16 + i * (32 * s + 16), 16 + row * (32 * s + 16)), b)
        sheet.save(argv[argv.index('--preview') + 1])
    if '--write' in argv:
        for path, page in pages.items():
            page.save(path)
            print('wrote', path)


if __name__ == '__main__':
    main(sys.argv)
