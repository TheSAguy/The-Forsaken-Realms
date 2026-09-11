"""One-shot rebalance of roster179.py: color identity and rank changes by name (see round-179 notes), then rewrite the
ROSTER list in place. usage: python roster_rebalance.py"""
import os, re

HERE = os.path.dirname(os.path.abspath(__file__))
P = os.path.join(HERE, "roster179.py")

COLORS = {
    # two -> three (Esper / Grixis / Mardu / Abzan / Sultai / Naya / Temur / Bant / Jeskai)
    "Shade of the Rootwyrm": "UBR", "Bonetusk Legionnaire": "RWB", "Cultist of the Tusk": "RWB",
    "Devil Empress": "UBR", "Dragonwing Demon": "UBR", "Bloodwing Dragon": "RWB", "Hoarder Beetle": "WBG",
    "Mother of Webs": "UBG", "Brood Spawn": "UBG", "Four-Maw Horror": "UBG", "Earthcore Colossus": "RGW",
    "Snowpeak Sasquatch": "URG", "Rockthrower Oak": "RGW", "Rosethorn Prowler": "RGW", "Cruststrider": "RGW",
    "Sabertusk Dragon": "URG", "Tyrant Rex": "RGW", "Odium of the Last Spire": "WUB", "Revenant Knight-Captain": "WUB",
    "Umbral Dragon": "WUB", "Skullrobe Necromancer": "WUB", "Galewrapped Specter": "GWU", "Verdant Wyrm": "URG",
    "Vinewing Lizardlord": "URG", "Shellplate Wyrm": "GWU", "Skyhall Harrier": "GWU", "Azure Headsman": "URW",
    "Shrine Scythe-Dancer": "URW", "Tombwrapped Ancient": "WUB", "Marshal of the Barrow Legion": "RWB",
    "Nightwing Chooser": "WUB", "Sunplume Phoenix": "URW", "Sandveil Blademaster": "URW", "Crimson Skywyrm": "URW",
    "Clockwork Siegewalker": "URW", "Blossomtrap Dryad": "GWU", "Shellback Ankylosaur": "RGW", "Mossback Dragon": "GWU",
    "Jadeshell Beetle": "UBG", "Duchess of Thorns": "WUB", "Cadaver Lord": "UBR", "Grimtusk Matron": "RGW",
    "Ogre Matriarch": "RGW", "Golden Glaive Warlord": "RGW", "Galewhisper Sylph": "GWU", "Bloodveil Countess": "RWB",
    "Crossroads Tikbalang": "BRG", "Geode Slug": "URW", "Tidetusk Brute": "URG", "Frostcloak Marauder": "URW",
    "Randgrith, Storm Shieldmaiden": "RWB", "Voidmaw Stalker": "UBR", "Ironstride Construct": "WUR",
    # black out where the theme does not need it
    "Hollow Oathkeeper": "WU", "Gloomshroud Wraith": "WU", "Keening Banshee": "WU", "Bloodwatch Seeker": "UR",
    "Magma Dragon": "UR", "Pomhair Spider": "G", "Carrionbloom": "GW", "Carapace Stalker": "RG",
    "Sporeshell Crawler": "UG", "Fangmaw Ravager": "RG", "Serratongue": "RG", "Magma Ogre": "RW",
    "Fleshlump Horror": "U", "Serpleg Stalker": "U", "Megamouth Wyrm": "UR", "Plague Ghoul": "B",
    "Spinecoil Isopod": "G",
    # mono -> two, and white / blue into the thin Apprentice ranks
    "Pilfer Beetle": "UB", "Hollowlight Shade": "UB", "Cracked Shieldbone": "WB", "Grave-Tattered Bride": "WB",
    "Talonborn Harpy": "UB", "Deathshroud Wraith": "WB", "Duskfeather Owlet": "WU", "Floewalker Seal": "WU",
    "Dewdrop Ooze": "GW", "Nutcache Scrapper": "GW", "Triplate Crawler": "GW", "Blushback Frog": "UG",
    "Frostpelt Wolf": "GW",
}
RANKS = {"Tyrant Rex": "X", "Devil Empress": "X", "Cadaver Lord": "X", "Tidecaller Witch": "X", "Sabertusk Dragon": "X",
         "Magma Dragon": "X"}
RENAME = {"Bristleback Boar": "Bristlehide Boar"}

text = open(P, encoding="utf-8").read()
out, hits = [], set()
for line in text.splitlines(keepends=True):
    m = re.match(r'(\s*\("[^"]+", ")([^"]+)(", ")([ADMX])(", ")([WUBRG]+)(",.*)$', line.rstrip("\n"))
    if m:
        name = m.group(2)
        new_name = RENAME.get(name, name)
        rank = RANKS.get(name, m.group(4))
        cols = COLORS.get(name, m.group(6))
        if name in COLORS or name in RANKS or name in RENAME:
            hits.add(name)
        line = m.group(1) + new_name + m.group(3) + rank + m.group(5) + cols + m.group(7) + "\n"
    out.append(line)
missing = (set(COLORS) | set(RANKS) | set(RENAME)) - hits
open(P, "w", encoding="utf-8", newline="\n").write("".join(out))
print("changed", len(hits), "entries; names not found:", sorted(missing))
