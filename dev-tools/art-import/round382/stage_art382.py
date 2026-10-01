"""stage_art382.py - copy the reviewed atlases (the art pass's new_units\\atlases, named by the review sheet's stems)
into the import's staging folder under the roster's slugs: every pick keeps its stem except the new Victor, whose art
is the review sheet's "ashcloak_vampire" and becomes victor_vampire (the .atlas page line is renamed with it).
usage: python stage_art382.py <reviewed atlases dir> <staging dir>   -> <staging dir>\\atlases\\<slug>.atlas/.png"""
import os, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True
from roster382 import ROSTER, ART_STEM

if len(sys.argv) != 3:
    raise SystemExit(__doc__)
src, out = sys.argv[1], os.path.join(sys.argv[2], "atlases")
os.makedirs(out, exist_ok=True)
for slug, *_ in ROSTER:
    art = ART_STEM.get(slug, slug)
    text = open(os.path.join(src, art + ".atlas"), encoding="utf-8").read()
    assert text.startswith(art + ".png\n"), art
    with open(os.path.join(out, slug + ".atlas"), "w", encoding="utf-8", newline="\n") as f:
        f.write(slug + ".png\n" + text[len(art + ".png\n"):])
    shutil.copyfile(os.path.join(src, art + ".png"), os.path.join(out, slug + ".png"))
print("%d atlases -> %s" % (len(ROSTER), out))
