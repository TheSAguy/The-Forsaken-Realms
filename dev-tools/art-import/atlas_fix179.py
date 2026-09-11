"""atlas_fix179.py - drop the bad frames frame_qa.py found in the round-179 atlases (checked by eye, 2026-09-11):
Ragnarok sheet bracket corners and label boxes left in as the last frame of a row, a sheet's preview portrait caught
as the first Idle frame, watermark text, a second, larger render of the creature. The region is removed from the
.atlas and its cell cleared in the .png. An animation left empty borrows: Walk <- Idle, Idle <- Walk.
Region numbers count every region in file order, the Avatar being #0 (frame_qa.py prints the same numbers).
usage: python atlas_fix179.py <atlas dir>"""
import os, re, sys
from PIL import Image

REMOVE = {
    # bracket corners / label boxes
    "bonetusk_legionnaire": [5], "cadaver_lord": [14], "chainbound_skeleton": [15], "crimson_temptress": [10, 19],
    "deathshroud_wraith": [10], "frostcloak_marauder": [12], "galewrapped_specter": [19], "grimtusk_shieldbearer": [12],
    "neferu_the_sealed_king": [7], "nightwing_chooser": [11, 20], "odium_of_the_last_spire": [15],
    "rosethorn_prowler": [22], "rotgut_orc": [6, 19], "sandveil_blademaster": [6],
    # portraits, a second figure, watermark text
    "ogre_matriarch": [1], "talonborn_harpy": [1], "aurelian_dragon": [1], "sporeshell_crawler": [2, 7, 8],
    "queen_of_the_gilded_brood": [12], "crimson_reaper": [8, 9],
}


def fix(d, stem, drop):
    ap = os.path.join(d, stem + ".atlas")
    text = open(ap, encoding="utf-8").read()
    head, _, body = text.partition("\nAvatar\n")
    body = "Avatar\n" + body
    regs = re.findall(r"^(\w+)\n(\s+xy: (\d+), (\d+)\n\s+size: (\d+), (\d+))\n?", body, re.M)
    assert regs and regs[0][0] == "Avatar", stem
    png = os.path.join(d, head.split("\n", 1)[0].strip())
    img = Image.open(png).convert("RGBA")
    keep = []
    for i, (name, block, x, y, w, h) in enumerate(regs):
        if i in drop:
            x, y, w, h = int(x), int(y), int(w), int(h)
            img.paste((0, 0, 0, 0), (x, y, x + w, y + h))
            continue
        keep.append((name, block))
    names = [n for n, _ in keep]
    for want, donor in (("Walk", "Idle"), ("Idle", "Walk")):
        if want not in names and donor in names:
            at = max(i for i, n in enumerate(names) if n == donor) + 1
            borrowed = [(want, b) for n, b in keep if n == donor]
            keep[at:at] = borrowed
            names = [n for n, _ in keep]
            print("  %s: %s borrows %d %s frames" % (stem, want, len(borrowed), donor))
    out = head + "\n" + "".join("%s\n%s\n" % (n, b) for n, b in keep)
    open(ap, "w", encoding="utf-8", newline="\n").write(out)
    img.save(png)
    print("%-30s dropped %s" % (stem, ", ".join("%s#%d" % (regs[i][0], i) for i in drop)))


def main():
    d = sys.argv[1]
    for stem, drop in REMOVE.items():
        fix(d, stem, set(drop))


if __name__ == "__main__":
    main()
