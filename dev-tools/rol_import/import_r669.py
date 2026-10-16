r"""import_r669.py - TFR round 669: nine lairs and shops from Realm of Legends 1.077.

The user (2026-10-16), after round 668's legend dungeons: "You can do round 2 also" - the second batch of the 1.077
audit, under the cross-plane import rules in CLAUDE.md (no Commander, no foreign quest lines, no unkept promises):

THE PLACES (rol_common.import_pois; util/LevelPlaces puts each on the map at its level - the user had asked for
level 20 for the end-game three, and these legends are mid-game ones):
  Darksteel Citadel     waste  10  Memnarch's golem factory (Darksteel / Mirrodin boosters)
  Horrible Factory      waste  10  seven patrolling Horrible Spirits (Horrible Pants on Hard)
  Skeleton Ship         blue   10  seven identical skeletons (Time Bomb, Treasure Map)
  Cursed Graveyard      black  10  Vogar the giant zombie, hidden until it hunts you
  Mtenda Mesa           white  10  Rashida Scalebane asks for help against Balefang; she pays Forebear's Blade
  Tresserhorn Keep      black  15  the Lord of Tresserhorn (Alliances boosters)
  Nekoru Nest           red    15  Wasitora's cat-dragon nest
  Jandor's Caravan      green   5  a friendly duel and two armor stalls (JandorItems1 / JandorItems2)
  Thieves' Hideout      red     5  a rogues' stall (ThievesItems), Auntie Blyte's tokens, Gwendlyn hidden at the back
  The lairs are tagged Hostile / NoRotate / Legendary (the door warning, the skull marker); the two friendly places
  only NoRotate.
THE MAPS: copied beside their Realm of Legends folders; the treasure template repointed; typos and the manasight stone
  fixed; digital-only cards swapped for paper ones (Balefang's Dragon Typhoon -> Dragon Tempest; the hideout's
  Triumphant Getaway, Thieving Aven, Impetuous Lootmonger, Polterheist -> Rogue's Gloves / Gonti, Thieving Magpie,
  Robber of the Rich, Thief of Sanity); the "Mtenda Hunter" Forge does not have -> Mtenda Herder (the herders who
  join you). Auntie Blyte: her tokens cost half the max life (2 / 2 / 1 / 3 - this plane's life totals are about
  half of Realm of Legends'), each once (not while you hold it), and the Capitol's merchants, not "Capitols".
THE ENEMIES: nine legends with rebuilt 60-card decks; Rashida Scalebane and Auntie Blyte on the plane's talk-only deck
  (decks/legends/mystery_list.dck, as Adriana and Gwafa Hazid). Gwendlyn's Thief's Hat is left out (its card,
  Triumphant Getaway, is digital-only).
THE ITEMS (31): the drops (pants in Legs, helms and hoods in Head), Jandor's twelve armors and the thieves' eight -
  the two whose lands make mana (Rainbow Armor, High Merchant's Shirt) follow the land-armor rule (one card fewer, no
  life); the ten utility lands make no mana, so they cost no card, and Realm of Legends' +7 to +10 life (and the
  opponents' -5) is cut to +3 - Steel Armor gives +4 for 3,000. Auntie's four tokens.

usage: python import_r669.py <repo root>"""
import html
import json
import os
import re
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rol_common as rc  # noqa: E402

ROUND = 669

MAPS = [('cave', 'Darksteel_Citadel.tmx'), ('cave', 'Horrible_Factory.tmx'), ('merfolkpool', 'Skeleton_Ship.tmx'),
        ('evilgrove', 'Cursed_Graveyard.tmx'), ('fort', 'Mtenda_Mesa.tmx'), ('evilgrove', 'Tresserhorn_Keep.tmx'),
        ('barbariancamp', 'Nekoru_Nest.tmx'), ('grove', 'Jandors_Caravan.tmx'),
        ('barbariancamp', 'Thieves_Hideout.tmx')]

MS_LEGEND = ("Your manasight stone glows, sensing the power of the legend here.",
             "Your spark stirs, sensing the power gathered here.", 1)
TEXT = {
    'Tresserhorn_Keep.tmx': [("the smell of decay lays thick", "the smell of decay lies thick", 1), MS_LEGEND],
    'Nekoru_Nest.tmx': [("the overwhelming heat of the seat of lava", "the overwhelming heat of the sea of lava", 1),
                        MS_LEGEND],
    'Mtenda_Mesa.tmx': [("Rashida Scalebane and a small group of allied warriors and determined herders.",
                         "Rashida Scalebane joins you, with a small group of allied warriors and determined herders.",
                         1)],
    'Jandors_Caravan.tmx': [("Great prices, wondrous treasures!\"", "Great prices, wondrous treasures!'\"", 1)],
}
Q = '&quot;'
CARD_SWAPS = {
    'Mtenda_Mesa.tmx': [(Q + 'Dragon Typhoon' + Q, Q + 'Dragon Tempest' + Q, 1),
                        (Q + 'Mtenda Hunter' + Q, Q + 'Mtenda Herder' + Q, 3)],
    'Thieves_Hideout.tmx': [('[ ' + Q + 'Triumphant Getaway' + Q, '[ ' + Q + "Rogue's Gloves" + Q, 1),
                            (Q + 'cardName' + Q + ': ' + Q + 'Triumphant Getaway' + Q,
                             Q + 'cardName' + Q + ': ' + Q + 'Gonti, Lord of Luxury' + Q, 1),
                            (Q + 'Thieving Aven' + Q, Q + 'Thieving Magpie' + Q, 2),
                            (Q + 'Impetuous Lootmonger' + Q, Q + 'Robber of the Rich' + Q, 1),
                            (Q + 'Polterheist' + Q, Q + 'Thief of Sanity' + Q, 1)],
}

TOKEN_PRICE = {"Token of the Devil": 2, "Token of Change": 2, "Token of Munitions": 1, "Token of Ninjutsu": 3}


def auntie_blyte(raw):
    """Her dialog (object 72): half the price in max life, each token once, the Capitol's merchants."""
    m = re.search(r'(<object id="72"[^>]*>.*?<property name="dialog">)(.*?)(</property>)', raw, re.S)
    tree = json.loads(html.unescape(m.group(2)))
    root = tree[0]
    old = root['text']
    root['text'] = old.replace("its your Auntie Blyte", "it's your Auntie Blyte").replace(
        "Capitols charge a fortune for them", "The Capitol's merchants charge a fortune for them")
    assert root['text'] != old

    def walk(node):
        for opt in node.get('options', []):
            for grant in [a.get('grantRewards') for o in opt.get('options', []) for a in o.get('action', [])
                          if a.get('grantRewards')]:
                token = next(r['itemName'] for r in grant if r.get('type') == 'item')
                life = next(r for r in grant if r.get('type') == 'life')
                life['count'] = -TOKEN_PRICE[token]
                opt['name'] = re.sub(r'\(PAY \d+ MAX LIFE\.\)', '(PAY %d MAX LIFE.)' % TOKEN_PRICE[token],
                                     opt['name'])
                opt['condition'] = [{"item": token, "not": True}]
            walk(opt)
    walk(root)
    body = json.dumps(tree, indent=2, ensure_ascii=False)
    body = body.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    return raw[:m.start(2)] + body + raw[m.end(2):]


HOOKS = {'Thieves_Hideout.tmx': auntie_blyte}

LAIR = ["Hostile", "NoRotate", "Legendary"]
POIS = [
    dict(name="Darksteel Citadel", display="Darksteel Citadel", atlas="buildings.atlas", sprite="Castle",
         map="cave/Darksteel_Citadel.tmx", type="cave", tags=LAIR, level=10, land="waste"),
    dict(name="Horrible Factory", display="Horrible Factory", atlas="buildings.atlas", sprite="Building21",
         map="cave/Horrible_Factory.tmx", type="cave", tags=LAIR, level=10, land="waste"),
    dict(name="Skeleton Ship", display="Skeleton Ship", atlas="buildingsbosses.atlas", sprite="Siren",
         map="merfolkpool/Skeleton_Ship.tmx", type="cave", tags=LAIR, level=10, land="blue"),
    dict(name="Cursed Graveyard", display="Cursed Graveyard", atlas="buildings.atlas", sprite="OtherGraveyard",
         map="evilgrove/Cursed_Graveyard.tmx", type="cave", tags=LAIR, level=10, land="black"),
    dict(name="Mtenda Mesa", display="Mtenda Mesa", atlas="buildings.atlas", sprite="Building38",
         map="fort/Mtenda_Mesa.tmx", type="cave", tags=LAIR, level=10, land="white"),
    dict(name="Tresserhorn Keep", display="Tresserhorn Keep", atlas="buildings.atlas", sprite="Slobad",
         map="evilgrove/Tresserhorn_Keep.tmx", type="dungeon", tags=LAIR, level=15, land="black"),
    dict(name="Nekoru Nest", display="Nekoru Nest", atlas="buildings.atlas", sprite="Building138",
         map="barbariancamp/Nekoru_Nest.tmx", type="dungeon", tags=LAIR, level=15, land="red"),
    dict(name="Jandor's Caravan", display="Jandor's Caravan", atlas="buildings.atlas", sprite="Building42",
         map="grove/Jandors_Caravan.tmx", type="cave", tags=["NoRotate"], level=5, land="green"),
    dict(name="Thieves' Hideout", display="Thieves' Hideout", atlas="buildings.atlas", sprite="banditcave",
         map="barbariancamp/Thieves_Hideout.tmx", type="cave", tags=["NoRotate"], level=5, land="red"),
]
NEW_REGIONS = {"buildings.atlas": ["OtherGraveyard"], "buildingsbosses.atlas": ["Siren"]}

ENEMIES = [
    dict(name="Memnarch"), dict(name="Horrible Spirit"), dict(name="Skeleton Shipmate"), dict(name="Vogar"),
    dict(name="Balefang"), dict(name="Lord of Tresserhorn", life=40, difficulty=3),
    dict(name="Wasitora", life=40, difficulty=3), dict(name="Jandor"),
    dict(name="Gwendlyn Di Corci", drop_items=["Thief's Hat"]),
    dict(name="Rashida Scalebane", rebuild=False, deck="decks/legends/mystery_list.dck", life=1, tier="Common",
         difficulty=0.1),
    dict(name="Auntie Blyte", rebuild=False, deck="decks/legends/mystery_list.dck", life=1, tier="Common",
         difficulty=0.1),
]

NO_MANA = " Makes no mana."
ITEMS = [  # name, slot, icon, cost, effect, rarity, description
    ("Brute Shield", "Left", "BruteShield", 5000, {"startBattleWithCard": ["Darksteel Brute"]}, "Rare",
     "Darksteel Brute: an indestructible artifact. 3: it becomes a 2/2 creature until end of turn."),
    ("Time Bomb", "Left", "TimeBomb", 4000, {"startBattleWithCard": ["Time Bomb"]}, "Uncommon",
     "A time counter each upkeep. 1, T, sacrifice it: damage equal to its counters to each creature and player."),
    ("Decayed Pants", "Legs", "DecayedPants", 3000,
     {"lifeModifier": -4, "startBattleWithCard": ["b_2_2_zombie_decayed", "b_2_2_zombie_decayed"]}, "Uncommon",
     "Two 2/2 decayed Zombie tokens - they can't block, and each is sacrificed after it attacks."),
    ("Horrible Pants", "Legs", "HorriblePants", 3000,
     {"changeStartCards": -1, "startBattleWithCard": ["Horrible Hordes", "Horrible Hordes"]}, "Uncommon",
     "Two Horrible Hordes - 2/2 artifact Spirits with rampage 1."),
    ("Spiteful Helm", "Head", "SpitefulHelm", 4000,
     {"changeStartCards": -1, "startBattleWithCard": ["Spiteful Visions"]}, "Rare",
     "Spiteful Visions: every player draws an extra card each draw step and takes 1 damage for each card drawn."),
    ("Mostly Kitten-Proof Pants", "Legs", "MostlyKitten-ProofPants", 5000,
     {"lifeModifier": -5, "startBattleWithCard": ["brg_3_3_cat_dragon_flying"]}, "Rare",
     "An extremely cute 3/3 flying Cat Dragon token."),
    ("Forebear's Blade", "Right", "Forebear'sBlade", 5000,
     {"lifeModifier": -6, "startBattleWithCard": ["Forebear's Blade"]}, "Rare",
     "Equip 3: +3/+0, vigilance and trample. When the creature dies, attach it to another creature you control."),
    ("Jandor's Saddlebags", "Right", "Jandor'sSaddlebags", 3000, {"startBattleWithCard": ["Jandor's Saddlebags"]},
     "Uncommon", "An artifact - 3, T: untap target creature."),
    # Jandor's stalls: the utility lands make no mana - no card for them, +3 life (not Realm of Legends' +7 to +10)
    ("Armor of Wak-Wak", "Body", "ArmorofWak-Wak", 2500,
     {"lifeModifier": 3, "startBattleWithCard": ["Island of Wak-Wak|ARN"]}, "Uncommon",
     "Island of Wak-Wak - T: a creature with flying has base power 0 until end of turn." + NO_MANA),
    ("Gladiator Armor", "Body", "GladiatorArmor", 2500, {"lifeModifier": 3, "startBattleWithCard": ["Arena|PHPR"]},
     "Uncommon", "Arena - 3, T: tap your creature and one of the opponent's (their choice); they fight." + NO_MANA),
    ("Armor of Safety", "Body", "ArmorofSafety", 2500,
     {"lifeModifier": 3, "startBattleWithCard": ["Safe Haven|TSB"]}, "Uncommon",
     "Safe Haven - 2, T: exile one of your creatures; in your upkeep, sacrifice it to bring them all back." + NO_MANA),
    ("Armor of Sorrow", "Body", "ArmorofSorrow", 2500,
     {"lifeModifier": 3, "startBattleWithCard": ["Sorrow's Path|DRK"]}, "Uncommon",
     "Sorrow's Path - T: swap two of the opponent's blockers; 2 damage to you and each of your creatures." + NO_MANA),
    ("Icy Armor", "Body", "IcyArmor", 2500, {"lifeModifier": 3, "startBattleWithCard": ["Ice Floe|ICE"]}, "Uncommon",
     "Ice Floe - T: tap an attacker without flying; it stays tapped while Ice Floe does." + NO_MANA),
    ("Oasis Outfit", "Body", "OasisOutfit", 2500, {"lifeModifier": 3, "startBattleWithCard": ["Oasis|ARN"]},
     "Uncommon", "Oasis - T: prevent the next 1 damage to a creature this turn." + NO_MANA),
    ("Rainbow Armor", "Body", "RainbowArmor", 6000,
     {"changeStartCards": -1, "startBattleWithCard": ["Rainbow Vale|FEM"]}, "Rare",
     "Rainbow Vale - T: one mana of any color; the opponent gains control of it at end of turn."),
    ("Adventurer's Outfit", "Body", "Adventurer'sOutfit", 3000,
     {"lifeModifier": 3, "startBattleWithCard": ["Adventurers' Guildhouse"]}, "Uncommon",
     "Adventurers' Guildhouse - your green legendary creatures can band with other legends." + NO_MANA),
    ("Armor of Serra", "Body", "ArmorofSerra", 3000,
     {"lifeModifier": 3, "startBattleWithCard": ["Cathedral of Serra"]}, "Uncommon",
     "Cathedral of Serra - your white legendary creatures can band with other legends." + NO_MANA),
    ("Unholy Armor", "Body", "UnholyArmor", 3000, {"lifeModifier": 3, "startBattleWithCard": ["Unholy Citadel"]},
     "Uncommon", "Unholy Citadel - your black legendary creatures can band with other legends." + NO_MANA),
    ("Seafarer's Outfit", "Body", "Seafarer'sOutfit", 3000,
     {"lifeModifier": 3, "startBattleWithCard": ["Seafarer's Quay"]}, "Uncommon",
     "Seafarer's Quay - your blue legendary creatures can band with other legends." + NO_MANA),
    ("Stronghold Armor", "Body", "StrongholdArmor", 3000,
     {"lifeModifier": 3, "startBattleWithCard": ["Mountain Stronghold"]}, "Uncommon",
     "Mountain Stronghold - your red legendary creatures can band with other legends." + NO_MANA),
    # the thieves' stall
    ("Vectis Boots", "Boots", "VectisBoots", 10000, {"lifeModifier": -5, "startBattleWithCard": ["Vectis Agents"]},
     "Rare", "Vectis Agents - a 4/3 artifact Rogue. UB: -2/-0 and it can't be blocked this turn."),
    ("Bane Alley Boots", "Boots", "BaneAlleyBoots", 25000,
     {"lifeModifier": -6, "startBattleWithCard": ["Bane Alley Broker"]}, "Mythic",
     "Bane Alley Broker - a 0/3 Rogue. T: draw, then exile a card face down. UB, T: return one of them to hand."),
    ("Swindler Shoes", "Boots", "SwindlerShoes", 5000, {"startBattleWithCard": ["Tavern Swindler"]}, "Uncommon",
     "Tavern Swindler - a 2/2 Rogue. T, pay 3 life: flip a coin; win and you gain 6."),
    ("High Merchant's Shirt", "Body", "HighMerchant'sShirt", 6000,
     {"changeStartCards": -1, "startBattleWithCard": ["High Market|MMQ"]}, "Rare",
     "High Market - T: add C. T, sacrifice a creature: gain 1 life."),
    ("Throwing Knife", "Right", "ThrowingKnife", 3000, {"startBattleWithCard": ["Throwing Knife"]}, "Uncommon",
     "Equip 2: +2/+0. When it attacks, you may sacrifice the knife: 2 damage to any target."),
    ("Whispersteel Dagger", "Right", "WhispersteelDagger", 6000,
     {"lifeModifier": -6, "startBattleWithCard": ["Whispersteel Dagger"]}, "Rare",
     "Equip 3: +2/+0. When it deals combat damage, you may cast a creature from that player's graveyard this turn."),
    ("Thought Dissector", "Left", "ThoughtDissector", 4000, {"startBattleWithCard": ["Thought Dissector"]},
     "Uncommon", "X, T: the opponent reveals cards until an artifact or X cards; you take the artifact and sacrifice this."),
    ("Skulker's Hood", "Head", "Skulker'sHood", 30000, {"startBattleWithCard": ["Behind the Scenes"]}, "Mythic",
     "Behind the Scenes: your creatures have skulk. 4W: your creatures get +1/+1 until end of turn."),
    # Auntie Blyte's tokens (paid in max life)
    ("Token of the Devil", "Token", "TokenoftheDevil", 1000, {"startBattleWithCard": ["r_1_1_devil_burn"]}, "Rare",
     "A 1/1 Devil token - when it dies, 1 damage to any target."),
    ("Token of Change", "Token", "TokenofChange", 1000, {"startBattleWithCard": ["c_1_1_shapeshifter_changeling"]},
     "Rare", "A colorless 1/1 Shapeshifter token with changeling - every creature type."),
    ("Token of Munitions", "Token", "TokenofMunitions", 1000, {"startBattleWithCard": ["munitions"]}, "Rare",
     "An artifact token - when it leaves the battlefield, 2 damage to any target."),
    ("Token of Ninjutsu", "Token", "TokenofNinjutsu", 1000, {"startBattleWithCard": ["u_1_1_ninja_unblockable"]},
     "Rare", "A blue 1/1 Ninja token that can't be blocked."),
]
JANDOR1 = ["Armor of Wak-Wak", "Gladiator Armor", "Armor of Safety", "Armor of Sorrow", "Icy Armor", "Oasis Outfit"]
JANDOR2 = ["Rainbow Armor", "Adventurer's Outfit", "Armor of Serra", "Unholy Armor", "Seafarer's Outfit",
           "Stronghold Armor"]
THIEVES = ["Vectis Boots", "Bane Alley Boots", "Swindler Shoes", "High Merchant's Shirt", "Throwing Knife",
           "Whispersteel Dagger", "Thought Dissector", "Skulker's Hood"]
SHOPS = {"JandorItems1": ("Exotic Silks for Sale", JANDOR1), "JandorItems2": ("Banding is the New Black", JANDOR2),
         "ThievesItems": ("Thieves' Guild Gear", THIEVES)}


def main(argv):
    c = rc.Ctx(argv[1])
    # the Siren region must be the same pixels on this plane's page as on Realm of Legends'
    from PIL import Image
    a = Image.open(os.path.join(c.rol, 'sprites', 'buildingsbosses.png')).convert('RGBA').crop((61, 278, 102, 319))
    b = Image.open(os.path.join(c.plane, 'sprites', 'buildingsbosses.png')).convert('RGBA').crop((61, 278, 102, 319))
    assert a.tobytes() == b.tobytes(), 'Siren differs on this plane\'s buildingsbosses.png'
    rc.import_items(c, ITEMS)
    known = {i['name'] for i in rc.lenient(rc.rw(os.path.join(c.plane, 'world', 'items.json')))}
    rc.import_shops(c, SHOPS)
    rc.import_maps(c, MAPS, TEXT, CARD_SWAPS, HOOKS)
    rc.import_pois(c, POIS, NEW_REGIONS)
    rc.import_enemies(c, ENEMIES, ROUND, known)
    rc.print_report(c)


if __name__ == '__main__':
    main(sys.argv)
