"""Round 382 roster: 56 new enemies from the user's "New Art\\Units" folder (Heroes of Might and Magic III, Diablo /
Hellfire and Arcanum sprites), cut and sized in the round-382 art pass (atlases staged outside the repo).

ROSTER rows keep round 179/316's shape plus two columns:
    (slug, name, rank, colors, deck_theme, tag_theme, extra quest tags)
  slug        the file stem (the enemy's name in snake_case; Victor's art and deck are "victor_vampire")
  rank        A Apprentice (Common) / D Adept (Uncommon) / M Master (Rare) / X Archmage (Mythic)
  colors      WUBRG letters, "C" = colorless (the Wasteland)
  deck_theme  deckgen382.THEMES key the generated deck is built around ("artifact" = the user's "color + Artifact"
              decks: the color's cards plus an artifact package); None = a hand-made deck (Victor)
  tag_theme   import382.THEME_TAGS key for the descriptive quest tags (what the creature IS)
  extra       extra quest tags; "Flying" also sets enemies.json "flying": true (import179's rule), "Large"/"Small"
              move life and speed like round 179's
LEGENDS     the 12 "war champions / wandering legends" (the user: "Let's make the top two rows ...") - the first two
            of each color's column on the review sheet. spawnRate 0, best of three, boss, a legend-grade reward list,
            named in config tables/roaming_champions.json - the legend table is their only route; no roster, no arena.
ARTIFACT    the ordinary creatures with a "<color> + artifacts" deck (the user: "give a few of them their color +
            Artifact decks") - two per color: one "artifact" deck (artifact creatures - constructs, golems, thopters -
            and their payoffs) and, in four colors, one "artifact_equip" deck (equipment and the artifact creatures
            that carry it); blue has two of the first kind.
"""

ROSTER = [
    # ------------------------------------------------------------------ White (W)
    ("seraph_of_the_burning_brand", "Seraph of the Burning Brand", "X", "W", "angel", "angel", ["Flying", "Holy"]),
    ("sunscale_dragon", "Sunscale Dragon", "X", "W", "dragon", "dragon", ["Flying"]),
    ("moonhorn_unicorn", "Moonhorn Unicorn", "M", "W", "unicorn", "unicorn", ["Holy"]),
    ("silverlance_cavalier", "Silverlance Cavalier", "M", "W", "artifact_equip", "knight", ["Mounted", "Human"]),
    ("sandstone_colossus", "Sandstone Colossus", "M", "W", "artifact", "elemental", ["Stone", "Large"]),
    ("thunderhelm_titan", "Thunderhelm Titan", "M", "WU", "giant", "giant", ["Large"]),
    ("crownfeather_griffin", "Crownfeather Griffin", "D", "W", "griffin", "griffin", ["Flying"]),
    ("halo_warden", "Halo Warden", "D", "W", "angel", "angel", ["Flying", "Holy"]),
    ("silvervale_skyrider", "Silvervale Skyrider", "D", "WG", "pegasus", "pegasus", ["Flying", "Mounted"]),
    ("glaivehoof_centaur", "Glaivehoof Centaur", "D", "WG", "centaur", "centaur", ["Warrior"]),
    ("twinblade_crusader", "Twinblade Crusader", "A", "W", "soldier", "soldier", ["Warrior", "Holy"]),
    ("lampbearer_zealot", "Lampbearer Zealot", "A", "W", "cleric", "cleric", ["Holy"]),
    # ------------------------------------------------------------------ Blue (U)
    ("skyvault_dragon", "Skyvault Dragon", "X", "U", "dragon", "dragon", ["Flying"]),
    ("sixblade_naga", "Sixblade Naga", "M", "U", "naga", "naga", ["Warrior"]),
    ("tempest_djinn", "Tempest Djinn", "M", "U", "djinn", "djinn", ["Flying", "Wind"]),
    ("tidewrought_colossus", "Tidewrought Colossus", "M", "U", "artifact", "elemental", ["Water", "Large"]),
    ("brinecoil_nereid", "Brinecoil Nereid", "D", "U", "merfolk", "merfolk", ["Water", "Swimming"]),
    ("starweave_enchanter", "Starweave Enchanter", "D", "U", "wizard", "wizard", ["Human", "Enchanter"]),
    ("frostrobe_lich", "Frostrobe Lich", "D", "UB", "lich", "lich", ["Wizard", "Snow"]),
    ("stormhorn_fiend", "Stormhorn Fiend", "D", "UR", "devil", "demon", ["Wind"]),
    ("chainball_gremlin", "Chainball Gremlin", "A", "U", "artifact", "gremlin", ["Small"]),
    ("galewalker_elemental", "Galewalker Elemental", "A", "U", "elemental", "elemental", ["Wind"]),
    ("driftbell_medusoid", "Driftbell Medusoid", "A", "U", "sea", "sea", ["Floating"]),
    # ------------------------------------------------------------------ Black (B)
    ("nightscale_dragon", "Nightscale Dragon", "X", "B", "dragon", "dragon", ["Flying"]),
    ("victor_vampire", "Victor", "X", "B", None, "vampire", ["Leader"]),
    ("dreadmount_rider", "Dreadmount Rider", "M", "B", "knight_undead", "knight_undead", ["Mounted"]),
    ("gilded_lich", "Gilded Lich", "M", "B", "artifact", "lich", ["Wizard"]),
    ("scythewing_reaver", "Scythewing Reaver", "M", "BR", "demon", "demon", ["Large"]),
    ("broodweaver_drider", "Broodweaver Drider", "M", "BG", "spider", "spider", ["Large"]),
    ("fleshrender_hulk", "Fleshrender Hulk", "D", "B", "horror", "horror", ["Large"]),
    ("hollowplate_phantom", "Hollowplate Phantom", "D", "B", "artifact_equip", "knight_undead", ["Ghost"]),
    ("stonegaze_medusa", "Stonegaze Medusa", "D", "BG", "gorgon", "gorgon", ["Snake"]),
    ("grave_wight", "Grave Wight", "A", "B", "wraith", "wraith", ["Ghost", "Flying"]),
    ("blind_troglodyte", "Blind Troglodyte", "A", "B", "horror", "troglodyte", ["Small"]),
    # ------------------------------------------------------------------ Red (R)
    ("pyreclaw_dragon", "Pyreclaw Dragon", "X", "R", "dragon", "dragon", ["Flying", "Fire"]),
    ("cinderstone_brute", "Cinderstone Brute", "M", "R", "elemental", "elemental", ["Fire", "Stone", "Large"]),
    ("emberlord_efreet", "Emberlord Efreet", "M", "R", "djinn", "djinn", ["Fire", "Flying"]),
    ("thunderwing_roc", "Thunderwing Roc", "M", "R", "bird", "bird", ["Flying", "Large"]),
    ("rendclaw_mauler", "Rendclaw Mauler", "M", "RG", "beast", "beast", ["Large", "Predator"]),
    ("three_maw_hellhound", "Three-Maw Hellhound", "D", "R", "hellhound", "hellhound", ["Fire"]),
    ("hellmaw_boar", "Hellmaw Boar", "D", "R", "beast", "beast", ["Aggressive", "Demon"]),
    ("twinskull_ogre", "Twinskull Ogre", "D", "R", "artifact_equip", "ogre", ["Aggressive"]),
    ("tuskmaw_fiend", "Tuskmaw Fiend", "D", "R", "ogre", "demon", ["Aggressive"]),
    ("hellspark_fiend", "Hellspark Fiend", "A", "R", "artifact", "devil", ["Fire", "Small"]),
    # ------------------------------------------------------------------ Green (G)
    ("thornscale_dragon", "Thornscale Dragon", "X", "G", "dragon", "dragon", ["Flying"]),
    ("bogspawn_horror", "Bogspawn Horror", "M", "G", "bog", "horror", ["Water", "Large"]),
    ("ironhide_gorgon", "Ironhide Gorgon", "M", "G", "artifact", "gorgon_bull", ["Large"]),
    ("barkhide_dendroid", "Barkhide Dendroid", "D", "G", "treefolk", "treefolk", []),
    ("frillback_basilisk", "Frillback Basilisk", "D", "G", "basilisk", "basilisk", []),
    ("marshscale_lizardman", "Marshscale Lizardman", "D", "G", "lizard", "lizardfolk", ["Warrior"]),
    ("flailfang_gnoll", "Flailfang Gnoll", "A", "G", "artifact_equip", "gnoll", ["Aggressive"]),
    # ------------------------------------------------------------------ the Wasteland (C)
    ("ossuary_dragon", "Ossuary Dragon", "M", "C", "dragon", "bone_dragon", ["Flying"]),
    ("blightborn_mutant", "Blightborn Mutant", "D", "C", "horror", "horror", []),
    ("bonestinger", "Bonestinger", "D", "C", "scourge", "bone_scorpion", ["Poison"]),
    ("clockwork_legionary", "Clockwork Legionary", "D", "C", "construct", "construct", ["Robot"]),
    ("cryptstalker_fiend", "Cryptstalker Fiend", "A", "C", "horror", "horror", ["Subterranean"]),
]

LEGENDS = ["seraph_of_the_burning_brand", "sunscale_dragon", "skyvault_dragon", "sixblade_naga", "nightscale_dragon",
           "victor_vampire", "pyreclaw_dragon", "cinderstone_brute", "thornscale_dragon", "bogspawn_horror",
           "ossuary_dragon", "blightborn_mutant"]

ARTIFACT = ["silverlance_cavalier", "sandstone_colossus", "chainball_gremlin", "tidewrought_colossus", "gilded_lich",
            "hollowplate_phantom", "twinskull_ogre", "hellspark_fiend", "ironhide_gorgon", "flailfang_gnoll"]

# the user's own deck for the new Victor (the coordinator wrote it), and the old Victor's new catalog name
VICTOR_DECK = "decks/legends/victor_vampire.dck"
OLD_VICTOR = "Victor"
OLD_VICTOR_NEW_NAME = "Victor, Valgavoth's Seneschal"

# the art stem of every pick on the review sheets (new_units\atlases) when it differs from the slug
ART_STEM = {"victor_vampire": "ashcloak_vampire"}

assert len(ROSTER) == 56 and len(set(r[0] for r in ROSTER)) == 56
assert all(s in {r[0] for r in ROSTER} for s in LEGENDS + ARTIFACT)
assert all(r[4].startswith("artifact") for r in ROSTER if r[0] in ARTIFACT)
assert all(r[0] in ARTIFACT for r in ROSTER if r[4] and r[4].startswith("artifact"))
