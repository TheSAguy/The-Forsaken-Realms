r"""import_r674.py - TFR round 674: the thirteen smaller lairs from Realm of Legends 1.077.

The user (2026-10-16), of round 665's audit: "Let's add these from RoL : 4. The small leftover lairs can come later if
you want more variety." - every one-legend lair the first two batches (668, 669) left, under the cross-plane import
rules in CLAUDE.md. Built on rol_common.py.

THE PLACES (util/LevelPlaces):
  level 8   Brushwagg Underbrush (green)  Isolated Dunes (green)  Baneful Cavern (black)  Hellion Cave (red)
            Hermit's Hideout (blue)
  level 12  Alhammarret's Archive (blue)  Assembly Ground (white)  Koskun Keep (red)  Rathi Shadow Rift (white)
  level 15  Blor's Pond (green)  Domain of Night (waste)  Jester's Tent (blue)  Argivian Military Camp (white)
  The lairs are Hostile / NoRotate / Legendary; the Argivian camp (five shops, a duel with Myrel) only NoRotate.
THE FIXES Realm of Legends shipped broken: Alhammarret's map named him "Alhammaret", so he never appeared, and his
  archive's prize was "Alhammaret's Archvive"; Myrel's Hard effect "Toacsia's Welcome" (Tocasia's); Lyna's reward
  "Soltari Shoes" (the item is Soltari Skirt); "Hellion" in its cave is renamed Great Hellion (this plane's ordinary
  Hellion has the name). Digital-only and commander-only cards out: Archival Whorl -> Lat-Nam's Legacy, Rescue
  Retriever -> Benalish Marshal, the Domain of Night's Volatile Rift and Path of Ancestry dropped, Providence of
  Night's own card (digital-only) left off his rewards. Text: the manasight stone (now the spark), "seemed down to the
  Earth", "Star swirl", "sand stretched", "rath", "the brother's war".
THE ENEMIES (13), decks rebuilt at 60 - the gimmicks kept by rebuild_gimmick (Kurbis's Brushwaggs, Alhammarret's
  Islands, Torsten's Plains), Sandwurm tuned (tune_sandwurm: his deck had one wurm under a legend the AI drops).
THE ITEMS (14): the drops - helms and the Jester's Cap in Head, the Soltari Skirt in Legs, Blor's Shield in the left hand, the two land armors
  (Armor of Sands, Koskun Armor) under the land-armor rule.

usage: python import_r674.py <repo root>"""
import os
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rol_common as rc  # noqa: E402

ROUND = 674

MAPS = [('grove', 'Brushwagg_Underbrush.tmx'), ('grove', 'Isolated_Dunes.tmx'), ('evilgrove', 'Baneful_Cavern.tmx'),
        ('barbariancamp', 'Hellion_Cave.tmx'), ('merfolkpool', 'Hermits_Hideout.tmx'),
        ('merfolkpool', 'Alhammarrets_Archive.tmx'), ('fort', 'Assembly_Ground.tmx'),
        ('barbariancamp', 'Koskun_Keep.tmx'), ('fort', 'Rathi_Shadow_Rift.tmx'), ('grove', 'Blors_Pond.tmx'),
        ('cave', 'Domain_of_Night.tmx'), ('merfolkpool', 'Jesters_Tent.tmx'), ('fort', 'Argivian_Military_Camp.tmx')]

MS = ("Your manasight stone glows, sensing the power of the legend here.",
      "Your spark stirs, sensing the power gathered here.", 1)
TEXT = {
    'Argivian_Military_Camp.tmx': [MS, ("in the brother's war.", "of the Brothers' War.", 1)],
    'Blors_Pond.tmx': [MS],
    'Domain_of_Night.tmx': [MS, ("seemed down to the Earth", "seeped down to the ground", 1)],
    'Isolated_Dunes.tmx': [("sand stretched before you", "sand stretches before you", 1)],
    'Jesters_Tent.tmx': [MS, ("Star swirl", "Stars swirl", 1)],
    'Rathi_Shadow_Rift.tmx': [("This piece of rath lies in ruins", "This piece of Rath lies in ruins", 1)],
}
Q = '&quot;'
CARD_SWAPS = {
    'Alhammarrets_Archive.tmx': [('name="enemy" value="Alhammaret"', 'name="enemy" value="Alhammarret"', 1),
                                 (Q + "Alhammaret's Archvive" + Q, Q + "Alhammarret's Archive" + Q, 1),
                                 (Q + 'Archival Whorl' + Q, Q + "Lat-Nam's Legacy" + Q, 1)],
    'Argivian_Military_Camp.tmx': [(Q + "Toacsia's Welcome" + Q, Q + "Tocasia's Welcome" + Q, 1),
                                   (Q + 'Rescue Retriever' + Q, Q + 'Benalish Marshal' + Q, 1)],
    'Domain_of_Night.tmx': [(Q + 'Volatile Rift' + Q + ', ', '', 1), (', ' + Q + 'Path of Ancestry' + Q, '', 2)],
    'Hellion_Cave.tmx': [('name="enemy" value="Hellion"', 'name="enemy" value="Great Hellion"', 1)],
}

LAIR = ["Hostile", "NoRotate", "Legendary"]
B, BB = "buildings.atlas", "buildingsbosses.atlas"
POIS = [
    dict(name="Brushwagg Underbrush", display="Brushwagg Underbrush", atlas=B, sprite="Grove",
         map="grove/Brushwagg_Underbrush.tmx", type="cave", tags=LAIR, level=8, land="green"),
    dict(name="Isolated Dunes", display="Isolated Dunes", atlas=B, sprite="ruinedcity",
         map="grove/Isolated_Dunes.tmx", type="cave", tags=LAIR, level=8, land="green"),
    dict(name="Baneful Cavern", display="Baneful Cavern", atlas=B, sprite="Building148",
         map="evilgrove/Baneful_Cavern.tmx", type="cave", tags=LAIR, level=8, land="black"),
    dict(name="Hellion Cave", display="Hellion Cave", atlas=B, sprite="Cave",
         map="barbariancamp/Hellion_Cave.tmx", type="cave", tags=LAIR, level=8, land="red"),
    dict(name="Hermit's Hideout", display="Hermit's Hideout", atlas=B, sprite="Cave",
         map="merfolkpool/Hermits_Hideout.tmx", type="cave", tags=LAIR, level=8, land="blue"),
    dict(name="Alhammarret's Archive", display="Alhammarret's Archive", atlas=B, sprite="Building97",
         map="merfolkpool/Alhammarrets_Archive.tmx", type="cave", tags=LAIR, level=12, land="blue"),
    dict(name="Assembly Ground", display="Assembly Ground", atlas=B, sprite="BarbarianCamp",
         map="fort/Assembly_Ground.tmx", type="cave", tags=LAIR, level=12, land="white"),
    dict(name="Koskun Keep", display="Koskun Keep", atlas=B, sprite="Building50",
         map="barbariancamp/Koskun_Keep.tmx", type="cave", tags=LAIR, level=12, land="red"),
    dict(name="Rathi Shadow Rift", display="Rathi Shadow Rift", atlas=BB, sprite="Snarl",
         map="fort/Rathi_Shadow_Rift.tmx", type="cave", tags=LAIR, level=12, land="white"),
    dict(name="Blor's Pond", display="Blor's Pond", atlas=B, sprite="FlamingoPond",
         map="grove/Blors_Pond.tmx", type="dungeon", tags=LAIR, level=15, land="green"),
    dict(name="Domain of Night", display="Domain of Night", atlas=BB, sprite="Great Aurora",
         map="cave/Domain_of_Night.tmx", type="dungeon", tags=LAIR, level=15, land="waste"),
    dict(name="Jester's Tent", display="Jester's Tent", atlas=BB, sprite="Black Circus",
         map="merfolkpool/Jesters_Tent.tmx", type="dungeon", tags=LAIR, level=15, land="blue"),
    dict(name="Argivian Military Camp", display="Argivian Military Camp", atlas=B, sprite="Building13",
         map="fort/Argivian_Military_Camp.tmx", type="dungeon", tags=["NoRotate"], level=15, land="white"),
]
NEW_REGIONS = {"buildings.atlas": ["FlamingoPond"]}

ENEMIES = [
    dict(name="Kurbis"), dict(name="Sandwurm"), dict(name="Axelrod Gunnarson"),
    dict(name="Great Hellion", source="Hellion"), dict(name="Homer"), dict(name="Alhammarret"),
    dict(name="Torsten Von Ursus"), dict(name="Eron"), dict(name="Lyna", item_swaps={"Soltari Shoes": "Soltari Skirt"}),
    dict(name="Blor", life=40, difficulty=3), dict(name="Providence of Night", life=40, difficulty=3,
                                                  drop_unplayable=True),
    dict(name="The Madcap Jester", life=40, difficulty=3), dict(name="Myrel", life=40, difficulty=3),
]

ITEMS = [  # name, slot, icon, cost, effect, rarity, description
    ("Sphinx Helm", "Head", "SphinxHelm", 5000, {"startBattleWithCard": ["Sphinx's Tutelage"]}, "Rare",
     "Sphinx's Tutelage - whenever you draw, the opponent mills two, again while two nonlands share a color. "
     "5U: draw, then discard."),
    ("Veteran's Blade", "Right", "Veteran'sBlade", 4000, {"startBattleWithCard": ["Veteran's Armaments"]}, "Rare",
     "Veteran's Armaments - attacking or blocking, +1/+1 for each attacking creature. It attaches itself to each "
     "Soldier that enters. Equip 2."),
    ("Soldier's Helm", "Head", "Soldier'sHelm", 3000, {"startBattleWithCard": ["Mobilization"]}, "Uncommon",
     "Mobilization - Soldiers have vigilance. 2W: a 1/1 Soldier token."),
    ("Armor of Horrors", "Body", "ArmorofHorrors", 5000,
     {"changeStartCards": 3, "startBattleWithCard": ["Cosmic Horror"]}, "Rare",
     "Three extra cards and Cosmic Horror, a 7/7 first striker - each upkeep pay 3BBB, or it is destroyed and deals "
     "7 damage to you."),
    ("Blor's Bow", "Right", "Blor'sBow", 3000, {"startBattleWithCard": ["Short Bow"]}, "Uncommon",
     "Short Bow - Equip 1: +1/+1, vigilance and reach."),
    ("Blor's Shield", "Left", "Blor'sShield", 4000, {"lifeModifier": -4, "startBattleWithCard": ["Assault Suit|C14"]},
     "Rare", "Assault Suit - Equip 3: +2/+2 and haste, and the creature can't be sacrificed."),
    ("Brushwagg Boots", "Boots", "BrushwaggBoots", 2000, {"startBattleWithCard": ["Brushwagg"]}, "Common",
     "A 3/2 Brushwagg - -2/+2 whenever it blocks or becomes blocked."),
    ("Sneakling Shoes", "Boots", "SneaklingShoes", 3000,
     {"lifeModifier": 2, "startBattleWithCard": ["Mischievous Sneakling"]}, "Uncommon",
     "Mischievous Sneakling - a 2/2 with changeling, every creature type."),
    ("Scoria Anklet", "Boots", "ScoriaAnklet", 4000, {"startBattleWithCard": ["Scoria Wurm"]}, "Rare",
     "Scoria Wurm, a 7/7 - each upkeep flip a coin; lose, and it returns to your hand."),
    ("Crab Hat", "Head", "CrabHat", 3000, {"startBattleWithCard": ["Scuttletide"]}, "Uncommon",
     "Scuttletide - 1, discard a card: a 0/3 Crab token. Delirium: your Crabs get +1/+1."),
    ("Armor of Sands", "Body", "ArmorofSands", 3000,
     {"changeStartCards": -1, "startBattleWithCard": ["Endless Sands|HOU"]}, "Uncommon",
     "Endless Sands - T: add C. 2, T: exile a creature you control. 4, T, sacrifice it: return them all."),
    ("Jester's Cap", "Head", "Jester'sCap", 4000,
     {"changeStartCards": 1, "lifeModifier": -4, "startBattleWithCard": ["Jester's Cap"]}, "Rare",
     "An extra card, and Jester's Cap - 2, T, sacrifice it: exile three cards from the opponent's library."),
    ("Koskun Armor", "Body", "KoskunArmor", 3000,
     {"changeStartCards": -1, "startBattleWithCard": ["Koskun Keep"]}, "Uncommon",
     "Koskun Keep - T: add C. 1, T: add R. 2, T: add B or G."),
    ("Soltari Skirt", "Legs", "SoltariSkirt", 3000, {"startBattleWithCard": ["Soltari Foot Soldier"]}, "Uncommon",
     "Soltari Foot Soldier - a 1/1 Soldier with shadow."),
]


def tune_sandwurm(c):
    """Realm of Legends' Sandwurm is a deck of deserts and basics with one Greater Sandwurm under Johan - and Johan is a
    card the AI drops, so the 60-card deck was a pushover. Four Greater Sandwurms and two Sandwurm Convergences (his
    own reward card) make the lurking wurm real; its deserts stay, the basics lean green for the wurms' GG."""
    path = os.path.join(c.plane, 'decks', 'legends', 'johan.dck')
    lines = [ln.strip() for ln in open(path, encoding='utf-8') if ln.strip()]
    deserts = [ln for ln in lines if ln[:1].isdigit() and ln.split(' ', 1)[1].split('|')[0] not in
               ('Greater Sandwurm', 'Forest', 'Plains', 'Mountain', 'Sandwurm Convergence')]
    body = ['4 Greater Sandwurm|AKH', '2 Sandwurm Convergence|HOU'] + deserts
    n = sum(int(ln.split()[0]) for ln in body)
    basics = 60 - n
    forest = basics - 2 * (basics * 9 // 39)
    body += ['%d Forest' % forest, '%d Plains' % (basics * 9 // 39), '%d Mountain' % (basics * 9 // 39)]
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(['[metadata]', 'Name=Sandwurm', '[Main]'] + body) + '\n')
    total = sum(int(ln.split()[0]) for ln in body)
    assert total == 60, total
    c.report['decks tuned'].append('Sandwurm: 4 Greater Sandwurm, 2 Sandwurm Convergence, %d deserts, %d basics'
                                   % (len(deserts), basics))


def main(argv):
    c = rc.Ctx(argv[1])
    for sub, f in MAPS:
        assert not os.path.exists(os.path.join(c.plane, 'maps', 'map', sub, f)), 'already a %s/%s here' % (sub, f)
    rc.import_items(c, ITEMS)
    known = {i['name'] for i in rc.lenient(rc.rw(os.path.join(c.plane, 'world', 'items.json')))}
    rc.import_maps(c, MAPS, TEXT, CARD_SWAPS)
    rc.import_pois(c, POIS, NEW_REGIONS)
    rc.import_enemies(c, ENEMIES, ROUND, known)
    tune_sandwurm(c)
    rc.print_report(c)


if __name__ == '__main__':
    main(sys.argv)
