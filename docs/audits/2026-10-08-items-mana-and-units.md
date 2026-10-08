# Item audit - mana/land and units at duel start (2026-10-08)

*Round 499 (same day): the 13 Common mana/land items listed below were made Uncommon.*

Asked by the user: "List me all items that add mana/land. I.E. Dungeon Map - Tap for 1 colorless. I need to know the slot
and rarity. Same for all items that add units on the battlefield. Slot and rarity. I think we need to possibly re-allocate
some of them to prevent someone from starting a duel with 3 or 4 creatures."

**Source:**
- `world/items.json`: `effect.startBattleWithCard`, `...Tapped` and `...InCommandZone`.
- Each card's type read from its script (cardsfolder, custom_cards, tokenscripts).
- All 708 items checked; every card name resolved. The full rows are in the two CSVs next to this file.

**How start cards enter** (`Player.java` ~2941): they are added straight to the battlefield, summoning-sick, with no zone
change. **An "enters the battlefield" trigger never fires.** So "when it enters, create tokens" items make nothing at the
start; only real creature cards (and creature tokens listed as the card) are there on turn 1.

## 1. Mana and land - 74 items

| Slot | Common | Uncommon | Rare | Mythic | Total |
|---|---|---|---|---|---|
| Body | 8 | 26 | 13 | 0 | **47** |
| Neck | 0 | 2 | 10 | 0 | **12** |
| Left | 3 | 3 | 2 | 0 | **8** |
| Right | 0 | 0 | 3 | 0 | **3** |
| Boots | 2 | 1 | 1 | 0 | **4** |

- **Body is the land slot** (47 of 74, one land each).
- **Neck** holds the Moxen and other mana rocks (12, mostly Rare).
- **Left** holds the fast mana (8): Sol Ring {C}{C}, Black Lotus, Kaleidostone, Lotus Petal, Chromatic Sphere, Golden
  Egg, Vessel of Volatility {R}{R}{R}{R}, and Presence of the Hydra (command zone).
- **Right** (3): Dungeon Map, Treasure, Change.
- **Boots** (4) are mana creatures: Joraga Treespeaker, Utopia Tree, Petalmane Baku, Scarecrow Guide.

**Worst case today:** a Body land + a Neck Mox (or Crown of the False God {C}{C}) + Sol Ring + Dungeon Map or Treasure +
a Boots mana creature. That is 5-6 extra mana on turn 1. One-shots add more (Black Lotus +3, Kaleidostone WUBRG), and
gauntlets add a second Left/Right.

## 2. Units - creatures on the battlefield at the start: 127 items

| Slot | Common | Uncommon | Rare | Mythic | Total |
|---|---|---|---|---|---|
| Body | 1 | 0 | 0 | 0 | **1** |
| Neck | 0 | 2 | 1 | 1 | **4** |
| Left | 2 | 0 | 6 | 0 | **8** |
| Right | 2 | 2 | 14 | 0 | **18** |
| Boots | 30 | 41 | 21 | 0 | **92** |
| Token | 0 | 0 | 0 | 4 | **4** |

- **Boots is already the companion slot**: 92 of the 127 items.
- **Outside Boots**: Right 18, Left 8, Neck 4, Body 1, and 4 Mythic tokens in the
  utility **Token** slot.
- **Items that bring more than one creature**: Cheat (3), Cursed Ring (3), Kobold Boots (2), Kobold King's Blade (3).

**Worst case today:** a creature from Boots, Left, Right, Neck, Body, the Token slot and, with gauntlets, Left2 and Right2.
That is 8 items. With the multi-creature ones that is well over 10 creatures on turn 1.

**With Ascendance (round 493+), new games are already capped by the main-item limit:**
- 1 main item at levels 0-4, 2 at 5, 3 at 10, 4 at 15, 5 at 20.
- **Not capped:** the Token slot and the gauntlets' Left2/Right2 (by your call: a bonus slot).
- So 3-4 creatures arrive around level 10-15, and up to 8 at level 20+.
- Older saves have no limit at all.

### Creature-token makers (no creature at the start; tokens over the duel): 30 items

Mostly Neck and Left: Monuments, Ominous Seas, Awakening Zone, Bearscape, Goblin Warrens, Security Detail and the like.
They make tokens through upkeep triggers or activations, not on turn 1. They are listed below so you can judge them too.

## 3. Options

1. **A companion limit by rule, not by slot (recommended).** At most **1** item that starts a creature can be worn at
   once, whatever slot it is in.
   - It works exactly like the main-item limit: equipping a second one says why. Optionally Ascendance raises it (for
     example, 2 at level 15).
   - Every item keeps its slot, name and theme (a shield that is a wall, an egg that hatches).
   - Gauntlets and the Token slot can't get around it.
   - It can cover old saves too, if you want.
   - Work: one rule in the equip check (the card types come from the item's own cards), about one round.
2. **Re-allocate**: move the non-Boots creature items into Boots (or Boots + Token).
   - It only lowers stacking: Token and Left2/Right2 still add their own.
   - It crowds Boots to about 120 items.
   - It breaks themes (Kiora's Bident, Steel Shield, Prismatic Egg, Marble Mace as boots).
   - It changes items players already own.
3. **Both**: a light re-allocation for the odd ones (Dark Armor's snake on Body, the Neck creatures) plus the rule.

**My recommendation is 1:** a limit of 1 creature item, raised to 2 at Ascendance 15 if you want growth.
- **Mana:** a similar "1 fast-mana item" rule could cover the Left one-shots, the Moxen and Sol Ring.
- **Body lands** are probably fine as they are: one land a turn is what a deck does anyway.

## 4. Full lists

### Mana and land

| Slot | Rarity | Item | Card | What |
|---|---|---|---|---|
| Body | Common | Cloak of the Wastes | Wastes | Land: Add {C} |
| Body | Common | Isle Shirt | Remote Isle | Land: Add {U} |
| Body | Common | Karst Shawl | Slippery Karst | Land: Add {G} |
| Body | Common | Meadow Outfit | Drifting Meadow | Land: Add {W} |
| Body | Common | Mire Leather | Polluted Mire | Land: Add {B} |
| Body | Common | Pilgrim's Cloak | Evolving Wilds | Land: land |
| Body | Common | Seraphim Wings | Seraph Sanctuary | Land: Add {C} |
| Body | Common | Smoldering Cloak | Smoldering Crater | Land: Add {R} |
| Body | Uncommon | Armor of Ifnir | Ifnir Deadlands | Land: Add {C} |
| Body | Uncommon | Armor of Ipnu | Ipnu Rivulet | Land: Add {C} |
| Body | Uncommon | Armor of Ramunap | Ramunap Ruins | Land: Add {C} |
| Body | Uncommon | Barbarian Armor | Barbarian Ring | Land: Add {R} |
| Body | Uncommon | Cabal Armor | Cabal Pit | Land: Add {B} |
| Body | Uncommon | Centaur Armor | Centaur Garden | Land: Add {G} |
| Body | Uncommon | Cephalid Armor | Cephalid Coliseum | Land: Add {U} |
| Body | Uncommon | Cloak of Orzhova | Orzhova, the Church of Deals | Land: Add {C} |
| Body | Uncommon | Cloak of Prahv | Prahv, Spires of Order | Land: Add {C} |
| Body | Uncommon | Cloak of Svogthos | Svogthos, the Restless Tomb | Land: Add {C} |
| Body | Uncommon | Cloudcrest Cloak | Cloudcrest Lake | Land: Add {C} |
| Body | Uncommon | Explorer's Cloak | Access Tunnel | Land: Add {C} |
| Body | Uncommon | Glacial Armor | Mouth of Ronom | Land: Add {C} |
| Body | Uncommon | Lantern-Lit Cloak | Lantern-Lit Graveyard | Land: Add {C} |
| Body | Uncommon | Mantle of Dusk | Duskmantle, House of Shadow | Land: Add {C} |
| Body | Uncommon | Nivix Vest | Nivix, Aerie of the Firemind | Land: Add {C} |
| Body | Uncommon | Nomad Armor | Nomad Stadium | Land: Add {W} |
| Body | Uncommon | Novijen Cloak | Novijen, Heart of Progress | Land: Add {C} |
| Body | Uncommon | Pinecrest Cloak | Pinecrest Ridge | Land: Add {C} |
| Body | Uncommon | Rancher's Garb | Bucolic Ranch | Land: Add {C} |
| Body | Uncommon | Rix Maadi Cloak | Rix Maadi, Dungeon Palace | Land: Add {C} |
| Body | Uncommon | Skargg Cloak | Skarrg, the Rage Pits | Land: Add {C} |
| Body | Uncommon | Sunhome Cloak | Sunhome, Fortress of the Legion | Land: Add {C} |
| Body | Uncommon | Tranquil Cloak | Tranquil Garden | Land: Add {C} |
| Body | Uncommon | Vitu-Ghazi Cloak | Vitu-Ghazi, the City-Tree | Land: Add {C} |
| Body | Uncommon | Waterveil Cloak | Waterveil Cavern | Land: Add {C} |
| Body | Rare | Armor of Urami | Tomb of Urami | Land: Add {B} |
| Body | Rare | Armor of the First | Sliver Hive | Land: Add {C} |
| Body | Rare | Cloud Keeper Armor | Untaidake, the Cloud Keeper | Land: Add {C}{C} |
| Body | Rare | Crucible Armor | Crucible of the Spirit Dragon | Land: Add {C} |
| Body | Rare | Crystalline Armor | Crystal Quarry | Land: Add {C} |
| Body | Rare | Diamond Belt | Diamond Valley | Land: land |
| Body | Rare | Fblthp's Lost Shirt | Mystifying Maze | Land: Add {C} |
| Body | Rare | Interplanar Armor | Interplanar Beacon | Land: Add {C} |
| Body | Rare | Librarian's Robes | The Biblioplex | Land: Add {C} |
| Body | Rare | Oracle's Robes | Hall of Oracles | Land: Add {C} |
| Body | Rare | Parun's Armor | Pillar of the Paruns | Land: land |
| Body | Rare | Sanctuary Armor | Animal Sanctuary | Land: Add {C} |
| Body | Rare | Urza's Robe | Urza's Workshop | Land: Add {C} |
| Neck | Uncommon | Amulet of Annihilation | Path of Annihilation | Mana: Add {C} |
| Neck | Uncommon | Jeweled Amulet | Jeweled Amulet | Mana: mana ability |
| Neck | Rare | Celestial Prism | Celestial Prism | Mana: mana ability |
| Neck | Rare | Crown of the False God | Temple of the False God | Land: Add {C}{C} |
| Neck | Rare | Crown of the Vale | Rainbow Vale | Land: land |
| Neck | Rare | Krampus's Horns | Charcoal Diamond | Mana: Add {B} |
| Neck | Rare | Mox Emerald | Mox Emerald | Mana: Add {G} |
| Neck | Rare | Mox Jet | Mox Jet | Mana: Add {B} |
| Neck | Rare | Mox Pearl | Mox Pearl | Mana: Add {W} |
| Neck | Rare | Mox Ruby | Mox Ruby | Mana: Add {R} |
| Neck | Rare | Mox Sapphire | Mox Sapphire | Mana: Add {U} |
| Neck | Rare | Santa's Hat | Jack-in-the-Mox | Mana: Add {W} |
| Left | Common | Chromatic Sphere | Chromatic Sphere | Mana: mana ability |
| Left | Common | Lotus Petal | Lotus Petal | Mana: mana ability |
| Left | Common | Volatile Prayerbook | Vessel of Volatility | Mana: Add {R}{R}{R}{R} |
| Left | Uncommon | Golden Egg | Golden Egg | Mana: mana ability |
| Left | Uncommon | Presence of the Hydra | Presence of the Hydra | Mana: mana ability (command zone) |
| Left | Uncommon | Sol Ring | Sol Ring | Mana: Add {C}{C} |
| Left | Rare | Black Lotus | Black Lotus | Mana: mana ability |
| Left | Rare | Kaleidostone | Kaleidostone | Mana: Add {W}{U}{B}{R}{G} |
| Right | Rare | Change | c_a_gold_sac | Mana: mana ability |
| Right | Rare | Dungeon Map | Dungeon Map | Mana: Add {C} |
| Right | Rare | Treasure | c_a_treasure_sac | Mana: mana ability |
| Boots | Common | Petalmane Pants | Petalmane Baku | Mana: mana ability |
| Boots | Common | Scarecrow Socks | Scarecrow Guide | Mana: mana ability |
| Boots | Uncommon | Joraga Boots | Joraga Treespeaker | Mana: Add {G}{G} |
| Boots | Rare | Utopia Anklet | Utopia Tree | Mana: mana ability |

### Creatures at the start

| Slot | Rarity | Item | Creatures |
|---|---|---|---|
| Body | Common | Dark Armor | Skeletal Snake 2/1 |
| Neck | Uncommon | Acorn Amulet | Nut Collector 1/1 |
| Neck | Uncommon | Outlaw's Hat | r_1_1_mercenary_tappump 1/1 |
| Neck | Rare | Xira's Fancy Hat | Xira's Hive 0/2 |
| Neck | Mythic | Cheat | Blightsteel Colossus 11/11, Urabrask the Hidden 4/4, Avatar of Slaughter 8/8 |
| Left | Common | Battle Standard | r_1_1_goblin 1/1 |
| Left | Common | Kiora's Bident | Kraken Hatchling 0/4 |
| Left | Rare | Attendant's Prayerbook | Silent Attendant 0/2 |
| Left | Rare | Ferret Food | Joven's Ferrets 1/1 |
| Left | Rare | Goblin Trumpet | Goblin Polka Band 1/1 |
| Left | Rare | Shield of Air | Wall of Air 1/5 |
| Left | Rare | Shield of the Hivelord | Plated Sliver 1/1 |
| Left | Rare | Tasty Tome | Orcish Librarian 1/1 |
| Right | Common | Chicken Egg | Chicken Egg 0/1 |
| Right | Common | Dark Shield | Barrier of Bones 0/3 |
| Right | Uncommon | Cursed Ring | c_0_1_a_goblin_construct_noblock_ping 0/1, c_0_1_a_goblin_construct_noblock_ping 0/1, c_0_1_a_goblin_construct_noblock_ping 0/1 |
| Right | Uncommon | Jungle Shield | g_0_1_plant 0/1 |
| Right | Rare | Bog Glider Glove | Bog Glider 1/1 |
| Right | Rare | Breathstealer's Blade | Breathstealer 2/2 |
| Right | Rare | Faerie Dragon Egg | Faerie Dragon 1/3 |
| Right | Rare | Holy Symbol | Miracle Worker 1/1 |
| Right | Rare | Ichor Knife | Ichorid 3/1 |
| Right | Rare | Istvan's Axe | Uncle Istvan 1/3 |
| Right | Rare | Kobold King's Blade | Crimson Kobolds 0/1, Crookshank Kobolds 0/1, Kobolds of Kher Keep 0/1 |
| Right | Rare | Marble Mace | Marble Priest 3/3 |
| Right | Rare | Mithril Shield | c_0_4_a_wall_defender 0/4 |
| Right | Rare | Prismatic Egg | Prismatic Dragon 2/3 |
| Right | Rare | Rainbow Spear | Rainbow Knights 2/1 |
| Right | Rare | Shaman's Staff | Gorilla Shaman 1/1 |
| Right | Rare | Spyglass | Orcish Spy 1/1 |
| Right | Rare | Steel Shield | w_0_3_wall_defender 0/3 |
| Boots | Common | Bearhide Breeches | Mother Bear 2/2 |
| Boots | Common | Blistering Breeches | Blistering Barrier 5/2 |
| Boots | Common | Bloodfire Boots | Bloodfire Dwarf 1/1 |
| Boots | Common | Brokers Boots | Brokers Initiate 0/4 |
| Boots | Common | Cabaretti Kicks | Cabaretti Initiate 1/2 |
| Boots | Common | Coiled Anklet | komas_coil 3/3 |
| Boots | Common | Courier's Boots | Torch Courier 1/1 |
| Boots | Common | Deathspitter Skirt | Frilled Deathspitter 3/2 |
| Boots | Common | Enduring Sliverband | Enduring Sliver 2/2 |
| Boots | Common | Fblthp's Lost Socks | Humongulus 2/5 |
| Boots | Common | Glimmerbell Bondband | Glimmerbell 1/3 |
| Boots | Common | Greaves of Glare | Wall of Glare 0/5 |
| Boots | Common | Imposter's Sliverband | Venser's Sliver 3/3 |
| Boots | Common | Kobold Boots | Kobolds of Kher Keep 0/1, Crookshank Kobolds 0/1 |
| Boots | Common | Maestro Loafers | Maestros Initiate 3/1 |
| Boots | Common | Obscura Shoes | Obscura Initiate 2/2 |
| Boots | Common | Petalmane Pants | Petalmane Baku 1/2 |
| Boots | Common | Plated Sliverband | Plated Sliver 1/1 |
| Boots | Common | Raptor Bondband | Frenzied Raptor 4/2 |
| Boots | Common | Referee's Shoes | Mage Tower Referee 2/1 |
| Boots | Common | Riveteer Greaves | Riveteers Initiate 2/2 |
| Boots | Common | Sabertooth Bondband | Savai Sabertooth 3/1 |
| Boots | Common | Scarecrow Socks | Scarecrow Guide 2/1 |
| Boots | Common | Scrapling Shoes | Myr Scrapling 1/1 |
| Boots | Common | Silvergill Tailband | Silvergill Douser 1/1 |
| Boots | Common | Starfish On Your Foot | Spiny Starfish 0/1 |
| Boots | Common | Steel Boots | Steel Wall 0/4 |
| Boots | Common | Symbiote Bondband | Essence Symbiote 2/2 |
| Boots | Common | Timebug Boots | Jhoira's Timebug 1/2 |
| Boots | Common | Winter Boots | Priest of the Haunted Edge 0/4 |
| Boots | Uncommon | Acidic Sliverband | Acidic Sliver 2/2 |
| Boots | Uncommon | Angelic Greaves | Angelic Wall 0/4 |
| Boots | Uncommon | Anklet of the End | It That Heralds the End 2/2 |
| Boots | Uncommon | Artificial Sliverband | Sliversmith 1/1 |
| Boots | Uncommon | Bandar Boots | Wily Bandar 1/1 |
| Boots | Uncommon | Barrier Breeches | Hover Barrier 0/6 |
| Boots | Uncommon | Battle Cry Boots | Battle Cry Goblin 2/2 |
| Boots | Uncommon | Beastbreaker Boots | Beastbreaker of Bala Ged 2/2 |
| Boots | Uncommon | Bloodsworn Bite | Olivia's Bloodsworn 2/1 |
| Boots | Uncommon | Brimstone Boots | Brimstone Mage 2/2 |
| Boots | Uncommon | Caravaneer's Greaves | Caravan Escort 1/1 |
| Boots | Uncommon | Cloudseeder Shoes | Cloudseeder 1/1 |
| Boots | Uncommon | Cryptologist's Fins | Enclave Cryptologist 0/1 |
| Boots | Uncommon | Dark Boots | Clattering Augur 1/1 |
| Boots | Uncommon | Deathspore Shoes | Deathspore Thallid 1/1 |
| Boots | Uncommon | Despoiler's Boots | Ulamog's Despoiler 5/5 |
| Boots | Uncommon | Draped Dragonhide | Dragon Whelp 2/3 |
| Boots | Uncommon | Empyrial Greaves | Angel's Herald 1/1 |
| Boots | Uncommon | Faerie Anklet | Faerie Dragon 1/3 |
| Boots | Uncommon | Firefrightener Shoes | Firefright Mage 1/1 |
| Boots | Uncommon | Flamecaster Pants | Enraged Flamecaster 3/2 |
| Boots | Uncommon | Fleshwright Chaps | Stitchwing Skaab 3/1 |
| Boots | Uncommon | Gingerboots | Gingerbrute 1/1 |
| Boots | Uncommon | Godsire Greaves | Behemoth's Herald 1/1 |
| Boots | Uncommon | Greenseeker's Shoes | Greenseeker 1/1 |
| Boots | Uncommon | Hellkite Greaves | Dragon's Herald 1/1 |
| Boots | Uncommon | Hengestrider Boots | Henge Guardian 3/4 |
| Boots | Uncommon | Jolly Boots | Jolly Gerbils 2/3 |
| Boots | Uncommon | Joraga Boots | Joraga Treespeaker 1/1 |
| Boots | Uncommon | Leech Breeches | Leech Collector 2/2 |
| Boots | Uncommon | Mamba Bondband | Zagoth Mamba 1/1 |
| Boots | Uncommon | Packsong Pants | Packsong Pup 1/1 |
| Boots | Uncommon | Princely Greaves | Demon's Herald 1/1 |
| Boots | Uncommon | Shoes of the Swarm | Swarm of Rats */1 |
| Boots | Uncommon | Soul Shoes | Wall of Souls 0/4 |
| Boots | Uncommon | Sovereign Greaves | Sphinx's Herald 1/1 |
| Boots | Uncommon | Spectral Sliverband | Spectral Sliver 2/2 |
| Boots | Uncommon | Trickster's Shoes | Trickster Mage 1/1 |
| Boots | Uncommon | Valorous Greaves | Inspiring Veteran 2/2 |
| Boots | Uncommon | Victual Sliverband | Victual Sliver 2/2 |
| Boots | Uncommon | Vindicator Greaves | Kabira Vindicator 2/4 |
| Boots | Rare | Acolyte's Anklet | Klement, Novice Acolyte 2/2 |
| Boots | Rare | Alesha's War Skirt | Alesha, Who Smiles at Death 3/2 |
| Boots | Rare | Chandler's Shoes | Chandler 3/3 |
| Boots | Rare | Cutthroat Skirt | Nirkana Cutthroat 3/2 |
| Boots | Rare | Dune-Brood Anklet | Dune-Brood Nephilim 3/3 |
| Boots | Rare | Evershrike Anklet | Evershrike 2/2 |
| Boots | Rare | Ghoulcaller Greaves | Graf Reaver 3/3 |
| Boots | Rare | Girlfriend's Skirt | Joined Researchers 2/2 |
| Boots | Rare | Gixian Graft | Gixian Recycler 3/1 |
| Boots | Rare | Glint-Eye Anklet | Glint-Eye Nephilim 2/2 |
| Boots | Rare | Ink-Treader Anklet | Ink-Treader Nephilim 3/3 |
| Boots | Rare | Joven's Shoes | Joven 3/3 |
| Boots | Rare | Lookout's Harness | Cliffside Lookout 1/1 |
| Boots | Rare | Pack Leader Pants | Pack Leader 2/2 |
| Boots | Rare | Peddler's Shoes | Tonic Peddler 1/1 |
| Boots | Rare | Sheldon's Shoes | Keen Duelist 2/2 |
| Boots | Rare | Spore Skirt | Mindbender Spores 0/1 |
| Boots | Rare | Utopia Anklet | Utopia Tree 0/2 |
| Boots | Rare | Witch's Shoes | Plague Witch 1/1 |
| Boots | Rare | Witch-Maw Anklet | Witch-Maw Nephilim 1/1 |
| Boots | Rare | Yore-Tiller Anklet | Yore-Tiller Nephilim 2/2 |
| Token | Mythic | Token of Decay | b_2_2_zombie_decayed 2/2 |
| Token | Mythic | Token of Shielding | w_0_3_wall_defender 0/3 |
| Token | Mythic | Token of Soaring Spirit | wb_1_1_spirit_flying 1/1 |
| Token | Mythic | Token of Spirit | c_1_1_spirit 1/1 |

### Creature-token makers (tokens over the duel)

| Slot | Rarity | Item | Card | What |
|---|---|---|---|---|
| Body | Uncommon | Vitu-Ghazi Cloak | Vitu-Ghazi, the City-Tree | creature tokens (g_1_1_saproling) - activated |
| Body | Rare | Armor of the First | Sliver Hive | creature tokens (c_1_1_sliver) - activated |
| Neck | Uncommon | Amulet of Annihilation | Path of Annihilation | creature tokens (c_0_1_eldrazi_spawn_sac) - on entering |
| Neck | Uncommon | Liliana's Veil | Oath of Liliana | creature tokens (b_2_2_zombie) - on entering |
| Neck | Uncommon | Ominous Amulet | Ominous Seas | creature tokens (u_8_8_kraken) - activated |
| Neck | Uncommon | Outlaw's Hat | Rakish Crew | creature tokens (r_1_1_mercenary_tappump) - on entering |
| Neck | Uncommon | Pegasus Helm | Pegasus Refuge | creature tokens (w_1_1_pegasus_flying) - activated |
| Neck | Uncommon | Skywise Talisman | Skywise Teachings | creature tokens (u_2_2_djinn_monk_flying) |
| Neck | Rare | Amulet of Awakening | Awakening Zone | creature tokens (c_0_1_eldrazi_spawn_sac) - each upkeep |
| Neck | Rare | Bear Helm | Bearscape | creature tokens (g_2_2_bear) - activated |
| Neck | Rare | Ghoulish Jewel | Ghoulish Procession | creature tokens (b_2_2_zombie_decayed) |
| Neck | Rare | Morcant's Crown | Morcant's Eyes | creature tokens (bg_2_2_elf) - each upkeep/activated |
| Neck | Rare | Ooze Amulet | Ooze Flux | creature tokens (g_x_x_ooze) - activated |
| Neck | Rare | Teeming Stormcrown | Teeming Dragonstorm | creature tokens (w_2_2_soldier) - on entering |
| Left | Common | Ephemeral Prayerbook | Vessel of Ephemera | creature tokens (w_1_1_spirit_flying) - activated |
| Left | Uncommon | Abzan Gauntlet | Abzan Monument | creature tokens (w_x_x_spirit) - on entering/activated |
| Left | Uncommon | Jeskai Gauntlet | Jeskai Monument | creature tokens (w_1_1_bird_flying) - on entering/activated |
| Left | Uncommon | Map to the World Tree | Path to the World Tree | creature tokens (g_2_2_bear) - on entering |
| Left | Uncommon | Mardu Gauntlet | Mardu Monument | creature tokens (r_1_1_warrior) - on entering/activated |
| Left | Uncommon | Sultai Gauntlet | Sultai Monument | creature tokens (b_2_2_zombie_druid) - on entering/activated |
| Left | Uncommon | Temur Gauntlet | Temur Monument | creature tokens (g_5_5_elephant) - on entering/activated |
| Left | Uncommon | Tome of the Builder | The Birth of Meletis | creature tokens (c_0_4_a_wall_defender) |
| Left | Rare | Garruk's Mighty Axe | Garruk's Mighty Axe | creature tokens (bg_2_2_wolf_garruk) (command zone) |
| Left | Rare | Guard's Shield | Security Detail | creature tokens (w_1_1_soldier) - activated |
| Left | Rare | Warren Tender's Baton | Goblin Warrens | creature tokens (r_1_1_goblin) - activated |
| Right | Uncommon | Fblthp's Lost Fishing Pole | Fishing Pole | creature tokens (u_1_1_fish) |
| Right | Uncommon | Giant's Bracer | Giant's Amulet | creature tokens (u_4_4_giant_wizard) - on entering |
| Right | Uncommon | Starforged Sword | Starforged Sword | creature tokens (u_1_1_fish) - on entering |
| Boots | Rare | Slime-Covered Boots | Ooze Flux | creature tokens (g_x_x_ooze) - activated |
| Boots | Rare | Slobad's Iron Boots | Slobad's Iron Boots | creature tokens (c_0_0_a_construct_total_artifacts) - activated (command zone) |
