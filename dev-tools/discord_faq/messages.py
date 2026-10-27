"""The Discord FAQ: (topic, short label, message). Each message must stay under Discord's 2,000 characters."""

# The released version the FAQ describes; build_page.py shows it in the page header. One line to change per release.
FAQ_VERSION = "v1.24"

# What this version changed, by message number: EDITED are edited in place in Discord, ADDED are posted as new
# messages at the end of the channel. build_page.py tags them. Reset both at the next release.
EDITED = {1, 2, 5, 6, 7, 9, 11, 12, 15, 21}
ADDED = {23, 24}

M = []

M.append(("About the game: is this a mod? Android", "About / Android", "**THE FORSAKEN REALMS - FAQ** (as of " + FAQ_VERSION + r""")
**About the game**

**Q: Is this a mod for Forge?**
**A:** No. The Forsaken Realms began as an Adventure-mode world for Forge, but since v1.00 it's a standalone game built on the Forge rules engine.
- Unzip it into its own folder (not into a Forge install) and run The Forsaken Realms.exe. It needs Java 17 or newer (64-bit). Windows is the tested platform; macOS and Linux launchers are included but untested.
- It keeps its own saves and settings, so your Forge install is untouched, and the other way around.
- On PC it shares Forge's card-art folder, so card images you already downloaded in Forge aren't downloaded again.

**Q: Can I play this on Android?**
**A:** Yes. Every release includes an Android version (Android 8.0 or newer), installed by sideloading:
1. Download the forsaken-realms-...-signed-aligned.apk file from the release on GitHub.
2. Open it and allow installs from your browser or file manager when asked (it's signed by us, not the Play Store).
3. Grant the storage permission it asks for.
4. On first launch, tap Download for the game data (about 430 MB, use Wi-Fi). The app restarts, and after that it plays offline.
It installs alongside the official Forge app, and installing a newer APK over the old one keeps your saves. Android gets less testing than PC, so please report problems here with your device model and Android version. (There is no iOS version.)"""))

M.append(("Ascendance: Power and levels", "Ascendance", r"""**Ascendance (your level)**

**Q: What is Power? How do I level up?**
**A:** Ascendance is your character's level (New Game and New Game+ characters; Settings can switch it off). You earn **Power** (the sun icon) from almost everything:
- **Duels**, by the enemy's rank: 9 / 15 / 24 / 34 for a repeat win over an Apprentice / Adept / Master / Archmage. A first win, a champion or a war mage pays x1.5, a storm or a treasure guardian x2, a legend x2.5, a boss x3. Arena duels pay half and are never a first win. The Power comes as a card in the loot: collect it to get it.
- **Everything else:** first visits, clearing caves and dungeons, restoring and taking towns, raising the Capitol, quests, stopping invasions and raids, Arena brackets, Inn tournaments, treasures, and now and then a chest or a diamond on the map.
- Enemies you've outgrown pay less (never under 20%); Archmages never do. A lost duel pays nothing.

**Levels:** level 1 costs 50 Power, then each level costs more, times your difficulty (Easy x0.8 up to Insane x1.61). Level 40 takes about 20,000 Power on Normal. Past 40 there's no cap, but each level only gives +1 max life.
**Every 5th level** is a milestone: +1 max life, a title and room for more gear:
```
Lvl  Title          Main items  Companions
5    Unbound        2           1
10   Reclaimer      3           1
15   Warden of Ash  4           2
20   Seal-breaker   5           2
25   Sovereign      5           3
30   Ascendant      5           3
35   Weavebinder    6           3
40   Eternal        7           3
```
**Every other level** offers 3 rewards to pick from (tap the Asc panel): gold, Shards, Wood or Stone, rare cards, an item, a booster, a map fragment, a blueprint, a Bronze Coin, goodwill - or a **lasting perk**. Choices stack up, so "Not now" costs nothing."""))

M.append(("Ascendance: gear limits, perks, your hero's size", "Perks & limits", r"""**Q: Why can't I put this item on?**
**A:** Your level limits how many **main items** you wear (head, neck, body, both hands, legs, boots): 1 to start, up to 7 at level 40 (see the table above). It also limits **companions**, items that start a creature in play: 1, then 2 at level 15 and 3 at 25. Swapping what's already in a slot always works. A **cracked** item must be repaired first - losing to a boss, or running out of life, cracks one item you wear.

**Q: What are the lasting perks?**
**A:** About 35 perks that last the whole run. Most have ranks: take one again to rank it up. A few examples:
- **Haggler** (shop prices up to -15%), **Swift Feet** (up to +15% speed), **Far Sight**, **Prospector** (up to +45% resource pickups and mine income)
- **Aura of Fear** (enemies start with up to 30% less life), **Opening Gambit** (win the coin toss 75% of the time), **Morning Vigor**, **Shardwell**
- **Zealot** (up to +25% Power), **Insight** (4 reward choices a level), **Gambler** (two cheaper re-rolls)
- **Marshal**, **Medic** and **Spoils of War** for your roaming guards
- **Envoy** and **Warmonger** bend the Net-0 rule in your favor
Don't like an offer? **Re-roll** it once per level for 35-50 Shards. The **Level sheet** (on the Asc panel or your character sheet) lists every level you've reached and every perk you hold.

**Q: Why is my hero getting bigger?**
**A:** Your hero grows with your level, from about an Apprentice's size at level 0 to an Archmage's at 40. It's only the look: walls, doors and the camera treat you exactly the same."""))

M.append(("Gear sets and the Walkers' Pledges", "Gear sets", r"""**Gear sets**

**Q: What are gear sets?**
**A:** 15 sets of seven pieces, one for each main slot. Wear several pieces of one set and you get a bonus on top of what the items do:
- **3 pieces:** +2 life in every duel (a **Bane** set gives +3, but only against its color)
- **5 pieces:** the set's card starts in your command zone
- **7 pieces:** its full version takes that card's place
Only different pieces count, and a cracked piece drops the set a tier until it's back on. You can wear two partial sets at once (say 4 + 3 at level 35), and sets never put a creature into play.
- **Regalia** sets (one per color, Uncommon) play to their color. **Bane** sets (Rare) hunt one color: wear the Sunbane Raiment when you're at war with White. **Legend** sets (Dragonlord's Hoard, Stonewall Bulwark, Fblthp's Lost Things, Ironclad, Gilded Paragon) reward a theme.
- **Your roaming guards** get set bonuses too: an Adept guard reaches 3 pieces, a Master 5 and an Archmage all 7.

**Q: Where do I find the pieces?**
**A:** Armories sell them by rarity and most also drop from a named boss. Each color's Equipment shop sells its Regalia, and each AI capital's Arena prize is a piece of its Regalia or of a Bane set. Your Capitol's Arena adds Ironclad and Gilded Paragon, and its Level 2 Arena the other three Legend sets. An Arena never hands you a piece you already own while there's one you're missing.
Once you own a piece, the **Sets** button (the armor icon, bottom-left of the inventory's character doll) shows every set you own pieces of, how many you wear and own, and where to find the rest. Each piece names its set in its description.

**Q: What are Liliana's and Chandra's Pledges?**
**A:** Beat Chandra or Liliana in her temple and take her stone to the Warden: it becomes her **Pledge**, a Mythic Neck item. Wear it, and at your first upkeep you draft one of three cards from her spellbook."""))

M.append(("Treasure maps and locked castles", "Treasure & castles", r"""**Treasure maps and locked castles**

**Q: What are the obelisks? How do I find a treasure?**
**A:** Six treasures lie buried in every world: one in the Wastes and one in each color's land. Each has a map torn into nine pieces and an Archmage guarding it.
- **Obelisks:** each region has one standing at a time. Walk onto it for the next piece of that region's map; it then sinks, and a week later rises somewhere else.
- **Map Fragments** (items) add a piece too. They come from the AI capitals' Arenas, the Archaeologist, or a level-up reward.
- **The Treasure Maps** button (beside your portrait, and in the inventory) shows your maps. The first piece is the center one with a red X; each new piece shows more of the land around it.
- **The Spade** comes with your first piece. Wear it in a utility slot and dig from the HUD, 5 Shards a dig. Dig within 2 tiles of the treasure and its guardian rises. With all nine pieces the X shows on your world map, and walking onto it digs.
- **The guardian** is an Archmage with half again its usual life. Lose and the treasure stays buried (dig again to call it back). Win and you get 1,000 gold, 40 Shards, four Rare or Mythic cards, 64 Power and the region's prize.

**Q: Why can't I get into a color's castle?**
**A:** The five colors' castles are sealed. There are two ways in: carry that color's **Secret Entrance Key** (it's in that color's buried treasure), or be at **War** with the color and fight through the front gate. Reaching the sealed gate still counts for the main quest's "Find the ... Castle" stage.
The Wastes treasure holds the **Cartographer's Compass**: while you carry it, your world map shows every obelisk standing. The keys, the Spade and the Compass can't be sold or thrown away.

**Q: Where do I get the Witch-Maw Slippers?**
**A:** Only from Akroma and Lathliss, the Mythic bosses of the White and Red Castles: a 1-in-4 drop, one pair per player."""))

M.append(("Enemies: ranks, where they come from, the early weeks", "Enemy ranks", r"""**Enemies**

**Q: What are Apprentice, Adept and Master?**
**A:** Most creatures come in three ranks: the same creature, drawn bigger at each rank, with more life and a better deck. An Apprentice plays 60+ cards of mostly commons, an Adept 50 with better ones, a Master 40 with rares. The week, the land and your record pick the rank. Bosses, champions, legends and the Archmages are one of a kind, with their own decks. No Masters roam in week 1, and Archmages only from week 4.
New in v1.24, in all three ranks: the Torchhead Stallion, Flamecrest Serpent, Ashbark Gaunt, Thornspine Ravager, Bogmaw Golem and Crocodile Hexmage.

**Q: Why are there so many monsters around this cave?**
**A:** Most roaming creatures come out of the ordinary dungeons and caves near you, found or not - more the longer each one stands (+25% a week, up to double). Clear one (beat every enemy inside) and it goes quiet, and the nearest town gives you +1 reputation. Story places, boss lairs and castles don't send creatures, and quest creatures come wherever you are.

**Q: Why do monsters have less life early on?**
**A:** The realm eases you in. In days 1-7 a monster starts its duel with half its life (never under 20), and in days 8-14 with three quarters (never under 25). Bosses, legends, Arena and Inn fights, war mages and New Game+ are never eased.

**Q: Does Speed-Up change how many monsters I meet?**
**A:** No. A game day brings the same number at any speed, and new ones stop coming while about 14 are already roaming near you.

**Q: What decides an enemy's life on the map?**
**A:** In roaming fights, the time of day and the terrain you fight on: by day, plains enemies get +10% life and swamp enemies -10% (forest +5%, mountain -5%), and at night it flips. Raid the swamps at noon and the plains after dark."""))

M.append(("Enemies that adapt to you", "Adapting enemies", r"""**Q: Why are the enemies getting tougher (or easier)?**
**A:** On top of the weeks, the land's color and the fog, your own record shapes what you meet:
- **Notoriety:** a score, +1 for every duel you win and -5 for every loss (never below 0). From 5, enemies start with a **Wall** in play (a 0/1 that grows to a 3/6 by 20, then a second one from 25). Which enemies bring one depends on the difficulty: Archmages on Easy, everyone on Insane. At 45 and 50 every enemy gets another flying Wall and +25% / +50% life. Notoriety also tilts roaming ranks toward Masters and Archmages, and steps up new dungeons' creatures. A loss takes it all down one step, not back to nothing. **Settings > Disable Notoriety** switches it off (the score still counts, so switching it back on brings your level back).
- **Win ratio:** your last 50 duels. Once 25 are on record, a strong ratio (80%+ won) shifts the roaming ranks tougher and a weak one (20% or less) easier - up to 5 points, or 10 once 50 are on record. One loss doesn't wipe it.
- **Help tokens:** once 50 duels are on record, the enemy starts with a Clue if you've won 75-89%, a Treasure at 90-94%, and both at 95%+. Win 50% or fewer and **you** get a Treasure (plus a Clue at 25% or less). Each card says why it's there.
- **Enemies learn your tricks:** beat the same enemy 5 times in a row (4 on Hard, 3 on Insane) and it starts with a Wastes in play - up to three - until it beats you once. Easy is spared.
Arena duels, Inn tournaments, the Deck Tester and your guards' fights don't count toward your notoriety or your ratio."""))

M.append(("Legends and places that come with your level", "Legends & places", r"""**Legends, and places that come with your level**

**Q: Where are the legends? Why do I keep meeting the same one?**
**A:** Legends (dragon lords, gods, Slivers, the Arena's wandering champions) only roam the land of a color that is **Unhappy or at War** with you - rarely, one at a time, and the ones you haven't met first. You're told which one it is and where, a gold dot marks it on the map, and it waits **3 days** for you to come to it. A legend starts its duels with a Gemstone Mine in play.

**Q: Where did these new places on my map come from?**
**A:** 25 places from the Realm of Legends open up as your Ascendance level climbs, each in its own color's land, with a notice saying which way:
```
Lvl 5   Jandor's Caravan, Thieves' Hideout (stalls)
Lvl 8   Baneful Cavern, Brushwagg Underbrush, Isolated
        Dunes, Hellion Cave, Hermit's Hideout
Lvl 10  Cursed Graveyard, Skeleton Ship, Mtenda Mesa,
        Darksteel Citadel, Horrible Factory
Lvl 12  Alhammarret's Archive, Assembly Ground,
        Koskun Keep, Rathi Shadow Rift
Lvl 15  Tresserhorn Keep, Nekoru Nest, Blor's Pond,
        Jester's Tent, Domain of Night,
        Argivian Military Camp (5 shops + Myrel)
Lvl 20  Hexhaven Tower, Echoverse Overlay,
        Theorist's Sanctum
```
Most are a legend's lair: one duel with its own deck and rewards. They stay once they arrive. Without Ascendance they come by day instead (the level-20 ones on day 60, a level-10 one on day 30).
The level-20 dungeons, like the **Legendary Dungeons** with the red triple-skull marker, are true end-game: their bosses are built far stronger than the world around them."""))

M.append(("Losing (and winning)", "Losing & winning", r"""**Losing (and winning)**

**Q: How do I lose the game? How do I win?**
**A:** Losing duels never ends a run. Only three things do:
1. **Your Capitol falls.** A war mage that gets past your Capitol's guards forces you into a best-of-3 defense duel, wherever you are. Lose it and the run is over.
2. **One AI color holds 3 of the 5 Ring Cities.** You're warned the moment any color holds 2.
3. **No free town is left:** you hold no town and no Capitol, and an AI capture leaves no wasteland town anywhere (working, ruined, or an unclaimed Ring City).
**To win:** hold all five Ring Cities AND bring down all five colors' castles. You can keep playing afterward. Both end screens let you watch the **History** replay first.

**Q: What does losing a duel cost?**
**A:**
- **Gold:** a share of what you carry (2% / 5% / 10% / 15% from Easy to Insane). From week 2 that's at least 50 / 100 / 150 / 200, or all of it if you carry less; week 1 has no minimum. Gold in the Bank is safe.
- **Life:** 10% / 20% / 30% / 30% of your max life.
- **Your ante** (a Bronze Coin gets it back - see Challenge Coins).
- **An item:** losing to a boss, or running out of life, cracks one item you wear. Repair it to wear it again (the Mender perk can save it, Mechanic makes repairs cheap).
If your life hits 0 you're carried home: to your Capitol, or to Orazca before it stands. Losing inside a rotating dungeon usually makes it vanish. The Stubborn perk cuts the gold and life a loss costs.

**Q: Can I just reload after a loss?**
**A:** You can, but the realm notices: the Notices log teases you, and on Hard and Insane each reload after a lost duel gives that world's enemies +1 life (up to +10). Quitting the game mid-duel counts as a loss and a reload. Lose to the same foe 5 times and the game suggests an easier difficulty; win 50 in a row and it suggests a harder one (for your next game or New Game+)."""))

M.append(("Your towns under attack: raids and war mages", "Raids & mages", r"""**Your towns under attack**

**Q: One of my towns is being pillaged - what do I do?**
**A:** As each week begins, raiders from nearby dungeons can fall on one of your towns (or a neutral town you've been in). A town needs at least 4 hostile dungeons or caves near it, and there are no raids in week 1 (none before day 15 on Easy).
- While you're near, 3 or 4 raiders stand just outside its walls, and each one that falls is replaced.
- **Beat 5 raiders within 7 days:** +1 town reputation, 50 Wood, 50 Stone and 21 Power.
- **Let the days run out:** -2 town reputation.
- Your quest log lists every raided town with its count and days left, and the world map's Details view marks it "Pillaged! 2/5".
- A **roaming guard** with "Help with pillaged towns" ticked (your Capitol's Armory, Manage Guards) rides out and fights the raiders for you, and its wins count toward the five.

**Q: What are the war mages?**
**A:** The AI colors send attack mages at towns - more often at yours the more a color dislikes you (75% less likely at Partner, 50% more at War). A mage at one of your towns fights its guard; beat it yourself for +1 town reputation there. Hire guards to defend your towns (paid weekly), and keep town reputation up, since it also helps fend mages off. Once your Capitol stands, every color still in the game sends an Archmage at your realm - and a mage that gets past your Capitol's guards forces the defense duel that can end your run."""))

M.append(("The Capitol, the Temple and your roads", "Capitol & Temple", r"""**The Capitol**

**Q: How do I get a Capitol?**
**A:** Only at **Orazca**, the ruined town at the center of the map. Restore it at its Job Board, hold five towns in all (Orazca counts), and the same Job Board offers the upgrade.
- 24 building slots: 16 open, plus five colored Land Shops, a Utility Land Shop, a Booster Shop and an Armory.
- Two guards, and buildings no other town has: the **Bank**, the **Exchange**, the **Archaeologist**, the **Temple**, the **Research Lab**, Rare and Mythic card shops, and an **Arena** you can upgrade.
- It draws fire: every color still in the game sends an Archmage at your realm, and each may keep one more attack mage in the field.

**Q: What does the Temple do?**
**A:** Every duel you (or your guards) win honors the beaten enemy's colors. When the week ends, the most honored color gives **1 Rare**, the second **2 Uncommons** and the third **4 Commons** - non-land cards from the sets you've unlocked. The cards wait at the Temple until you **Collect Offering**, piling up week after week. The Temple Priest perk doubles it. (Capitol only: 300 gold + 100 Stone on Normal.)

**Q: What are the cobblestone roads?**
**A:** Your own roads. Raising the Capitol joins it to every town you hold, and towns you restore or capture later join the network. You travel about 1.7x on them, against 1.5x on the old sand roads.

**Q: What's the Archaeologist?**
**A:** Send them on a week-long expedition for a chance at boosters, cards you don't own, rare items, blueprints and treasure-map fragments.

**Q: Why are there more Archmages in my Arena brackets?**
**A:** Each Arena tournament you win puts an Archmage in every other bracket you enter that week (it resets on day 8, 15, 22...), and each one you won last week makes this week's lowest tier 5% rarer. Arena duels pay half Power."""))

M.append(("Card sets and Research", "Research", r"""**Card sets and Research**

**Q: How does Research work? How do I unlock more card sets?**
**A:** You only start with a few sets open. Research opens the rest.
- **Starting sets:** each race has 4 sets (see Races). You start with all 4 on Easy, 3 on Normal, 2 on Hard and 1 on Insane, picked at random from your race's 4.
- **Where:** the **Research Lab** in your Capitol. It lists every set with its days and today's price.
- **Cost:** with nothing given, 40 Shards on Normal (30 Easy, 50 Hard, 60 Insane) and **40 days**. Several sets can run at once.
- **Feed it:** every card you **sell** (anywhere) takes days and some Shards off its own set's research; **sacrifice** spares at the Lab and they count double. A card's first copy counts in full, later copies a quarter. About **3 boosters** of a set sacrificed (or 6 sold) make it **Ready**: half the Shards, no wait. A running research keeps shrinking as you feed it and finishes at 0 days.
- **Found Cards** in the Lab sells or sacrifices a set's cards on the spot (or **Sell all over 4**). Deck copies and favorites are kept.
- Research towns and the Researcher perk cut the days, never the Shards.
- **Games started before v1.24** keep the classic research: own 10% of a set (at least 5 cards), then 40 Shards and a week.

**What unlocking a set does:**
- Your own shops (card shops, plus the Capitol's Booster Shop and Spellsmith) can stock it. Your shops sell ONLY your unlocked sets, but 25% cheaper than neutral towns and 40% cheaper than AI towns (before reputation).
- Your towns' Inn tournaments can use it.
- It does NOT change what AI or neutral towns sell, what enemies drop or what chests hold. Those follow the world's set split (see "Why can't I find cards from set XYZ?").
- It never limits your deck: every card you own is always playable.

**Q: Which cards shorten a set's research?**
**A:** Only that set's own cards, in that set's printing: a card you own as an Amonkhet printing counts for Amonkhet only."""))

M.append(("Card set distribution", "Sets", r"""**Card set distribution**

**Q: Why can't I find cards from set XYZ?**
**A:** Every new world deals the card sets out at random:
- 12 sets go to a **neutral** pool, and the rest are split evenly among the five **AI colors**.
- **Your race's 4 sets** never go to an AI color. They're added to the neutral pool.
- It's re-dealt for every new world: XYZ can be Red's in one game and Blue's in the next.

**Where cards come from:**
- **An AI color's towns and capital:** that color's sets.
- **Neutral towns:** the neutral pool.
- **Your towns and Capitol:** only the sets YOU have unlocked (see Research).
- **Ring Cities:** every set, at double price.
- **Enemy drops:** the sets of the enemy's color (its first color if it has several). Colorless enemies use the neutral pool. Bosses and special fighters can drop anything.
- **Dungeon chests:** the sets of whoever owns the land the dungeon stands on (the neutral pool on the Wasteland and your own land). Some have themed chests of their own.
- **The Archaeologist** (Capitol): cards you don't own, from any set.
- **The Temple** (Capitol): cards from your unlocked sets.

**Also worth knowing:**
- A card belongs to every set it was printed in. Any source holding one of those sets can give it to you (as that set's printing).
- At **War** with a color you're barred from its towns (its capital charges 500 gold to enter).
- A shop's type (say, Goblins) plus its owner's sets can make a short shelf.
- Never dealt: sets with no booster packs, Un-sets and other novelty or promo sets, and Alpha, Beta and Unlimited (their cards still turn up as later printings). A short ban list (Power 9, Sol Ring, Signets, Commander-only cards...) is kept out of rewards and shops.
- Advanced: the game folder's content filter tables let you switch sets, items or enemies off yourself (all on by default)."""))

M.append(("Races", "Races", r"""**Races**

**Q: What does my race change?**
**A:** Your race decides your look, your 4 card sets and 2 of your 5 starting shop blueprints. It doesn't change any stats.
- **Look:** your portrait is a Magic card's art, and you can step through the pool on the Create a Character screen.
- **Sets:** you start with some of your race's 4 sets unlocked (4 on Easy, 3 Normal, 2 Hard, 1 Insane, picked at random). Standard, Constructed and Pile starting decks are built from your race's sets, no AI color ever holds them (they sit in the neutral pool), and your own towns' Inn tournaments use them.
- **Blueprints:** your race's 2 tribal shops, plus your color's 3 Common shops.
- The **?** button on the race screen lists the sets before you commit.

```
Race          Sets              Tribal shops
Angel         AVR SNC FDN KHM   Angel, Spirit
Black Dragon  DTK TDM AFR VOW   Dragon, Vampire
Blue Dragon   DTK TDM M21 MH1   Dragon, Sphinx
Devil         RNA TOR SOI VOW   Devil, Demon
Dwarf         KLD AER KHM BRO   Dwarf, Giant
Elf           LRW MOR KHM ELD   Elf, Druid
Goblin        ONS LGN SCG 10E   Goblin, Shaman
Green Dragon  DTK TDM IKO KHM   Dragon, Hydra
Human         DOM DMU M20 M21   Human, Soldier
Kor           ZEN BFZ ZNR ROE   Angel, Eldrazi
Leonin        MRD DST AKH IKO   Cat, Knight
Merfolk       LRW SHM EVE ECL   Merfolk, Wizard
Metathran     INV PLS APC 8ED   Wizard, Sphinx
Phyrexian     SOM MBS NPH ONE   Horror, Mutant
Red Dragon    DTK TDM M19 IKO   Dragon, Minotaur
Undead        AKH HOU ISD DKA   Zombie, Skeleton
Vampire       VOW XLN RIX LCI   Vampire, Noble
Viashino      GRN ALA ARB DGM   Goblin, Dinosaur
Werewolf      ISD MID EMN DKA   Wolf, Spirit
White Dragon  DTK TDM M20 AFR   Dragon, Angel
```
In the build menu shops go by their sign names (the Zombie shop is "Braaaaaains???", the Cat shop "The Feline Kingdom")."""))

M.append(("Shops", "Shops", r"""**Shops and Blueprints**

**Q: How do shops work in my towns and Capitol?**
**A:**
- **Slots:** each town you own has 9 building slots (8 open slots and an Armory). Your Capitol has 24 (see the Capitol).
- **Building:** walk into a rubble slot and pick Card Shop > tier > category (By Color, By Card Type, Tribal, Special) > type. An open slot can also become a Mine, a Trader, an Outlook, a Teleporter and so on.
- **Tier cost** (Normal; x0.75 on Easy, x1.25 Hard, x1.5 Insane):
  - Common: 100 gold + 5 Wood
  - Uncommon: 150 gold + 10 Wood
  - Rare: 200 gold + 50 Shards, **Capitol only**
  - Mythic: 300 gold + 100 Shards, **Capitol only**
- **Rules:** you can only build types you have the blueprint for, and each type only once per town. The menu lists every type anyway: buildable ones first, then "(built)", then grayed "(locked)", each showing how many cards it could stock for you right now.
- **Stock:** your shops sell only cards from your unlocked sets. Every shop restocks weekly, and Refresh re-rolls it now for a few Shards.
- **Re-assign Shop Type** (inside your own card shops) switches to another type you know, crediting half the old shop's gold cost. **Destroy Building** clears a slot, with no refund.
- **Town reputation caps building:** each point of a town's own reputation allows 3 buildings there. You get +1 for restoring the town, +2 when it becomes your Capitol, +1 for each attacking mage you beat there, +1 for winning its Inn tournament, +1 for clearing a dungeon near it, +1 for driving raiders off it, and more from its Job Board quests.
- **Capitol Land Shops:** a colored Land Shop can be repaired (50 gold) once you've visited that color's AI capital.
- **Capital hearts:** each AI capital's Items shop sells its +1 max life once per game, not every week."""))

M.append(("Blueprints", "Blueprints", r"""**Q: How do Blueprints work?**
**A:** A blueprint teaches you a card shop type (there are about 250) so you can build it in your own towns.
- **You start with 5:** your color's 3 Common shops and your race's 2 tribal shops. Your color's 3 Uncommon shops and its Rare shop are the ladder to climb. (A Chaos or Custom start has no color, so it only gets the race shops.)
- **Buy the shop you're standing in:** almost any card shop whose type you don't know shows a **Buy Blueprint** button - in AI towns and capitals, neutral towns, even your own towns.
- **Price, in Shards:** 20 Common, 40 Uncommon, 100 Rare, 200 Mythic. The tier is the type's tier in your own build menu, so a blueprint costs the same in every town that sells it.
- **Reputation gate (the five colors' towns only):** you must be at least Neutral with that color to buy any blueprint, Happy for Uncommon, Partner for Rare and Mythic. Happy takes 15% off the price, Partner 30%. Neutral towns and your own towns have no gate.
- **Mythic tier** (Capitol only): the five-color Domain of Dominaria, the Gods shop, and the three-color, Phyrexian, Planeswalker and Legend shops.
- **Drops:** Mystery pickups (the diamond) and chests each have a 25% chance to give you a blueprint you don't have yet, and an Archaeologist expedition a 15% chance. It arrives as a card you turn over. A level-up reward can be one too.
- **Not learnable:** the Cartographer's Guild basic-land shops, Armories and Ring City shops.
- Blueprints are for building only. You can shop in any store you can enter, blueprint or not."""))

M.append(("Reputation", "Reputation", r"""**Reputation**

**Q: How does Reputation work?**
**A:** You have a separate standing with each of the five colors (see the World Standings screen). There are five statuses:
- **Partner (80+):** card shops in that color's towns 30% off. Its war mages are 75% less likely to pick your towns. Entering its towns heals you to full plus 2 extra life until your next duel. Its Spellsmiths serve you, and all its blueprints are for sale, Rare and Mythic included.
- **Happy (30 to 79):** 15% off. Mages 50% less likely. Its Spellsmiths serve you. Common and Uncommon blueprints for sale.
- **Neutral (-29 to 29):** normal prices. Common blueprints only.
- **Unhappy (-30 to -79):** 25% dearer. Mages 15% more likely. No blueprints, entering its towns no longer heals you, and its **legends** start roaming its land.
- **War (-80 or worse):** its towns bar you (you can storm one instead, once a week per town). Its capital charges a 500 gold toll and prices there are 40% dearer. Mages 50% more likely, and it may target your Capitol. No healing in its towns. Its legends and arena champions roam its land - and its castle's front gate is open to a fight.

Standing also changes:
- **Travel speed** on that color's land: +10% Partner, +5% Happy, -5% Unhappy, -10% War.
- **Its creatures:** at Partner you meet them a third as often, at War three times as often, and on its land they lean weaker when it likes you and tougher when it doesn't.

None of this touches your own towns. Each town also keeps its own **town reputation**, separate from the colors: it shifts prices in that town by up to 10%, and in your own towns it caps how many buildings you can have and helps fend off war mages."""))

M.append(("The Net-0 rule", "Net-0", r"""**Q: What is the "Net-0" rule? Why does beating one color change the others?**
**A:** Reputation is never created, only moved around the color wheel, so your five standings add up to zero.
```
Color  Allies        Enemies
White  Green, Blue   Black, Red
Blue   White, Black  Red, Green
Black  Blue, Red     Green, White
Red    Black, Green  White, Blue
Green  Red, White    Blue, Black
```
**Every duel you win** against a creature of one color:
- that color **-2**
- each of its 2 allies **-1**
- each of its 2 enemies **+2**
That's -2 -1 -1 +2 +2 = 0. A boss counts x3 and an attacking war mage x2. A multicolor creature applies half the pattern for each of its colors. Colorless creatures, losses, Arena and tournament games change nothing.

**Example, mono-Red starting deck:** your deck seeds Red +10, its allies Black and Green +5 each, its enemies White and Blue -10 each (total 0).
You beat a mono-Black creature: Black -2, Blue -1, Red -1, Green +2, White +2.
Now: White -8, Blue -11, Black +3, Red +9, Green +7. Total: still 0.

**What that means for you:**
- Fighting your own color's creatures annoys your own color.
- To raise Red, beat White and Blue creatures: +2 Red per win, but White and Blue sink with every one.
- You can't be everyone's Partner. If one color is at +80, the other four share about -80.
- Attacking a color's town costs 4 with it (its allies -2, its enemies +4). Capturing the town costs 8 more.
- **The exceptions:** some color quests give a small flat bonus, the five "Find the ... Capital" quests give +2 with your own colors, bringing down a color's castle costs a flat 50 with it - and the **Envoy** and **Warmonger** perks tilt every win in your favor."""))

M.append(("Challenge Coins", "Coins", r"""**Challenge Coins**

**Q: What does the Bronze Coin do? What about the Silver and Gold coins?**
**A:** Your starting kit includes 1 Gold, 1 Silver and 3 Bronze Challenge Coins.
- **Gold:** free entry to a Draft tournament at an Inn.
- **Silver:** free entry to a Sealed tournament.
- **Bronze, use 1 - free Jumpstart entry** instead of the 500 gold or 50 Shard fee. You only ever get ONE Jumpstart tournament per player (New Game+ included), so only one Bronze coin can go this way.
- **Bronze, use 2 - ante ransom.** Lose an ordinary duel while playing for ante, and the "Card Lost" screen offers a **Bronze Coin** button. You get back every card you anted in that match (every game of a best-of-3) and you keep your gold. The life loss still applies.

**Getting a Bronze coin back, or a new one:**
- The enemy keeps your coin. Beat any enemy of that kind again (any Fox, if a Fox took it) and the coin comes back as loot.
- Or use the **Coin Challenge** in your Capitol's Arena once it's Level 2: 50 gold on Easy/Normal or 100 on Hard/Insane, plus 5/10/15 Shards on Normal/Hard/Insane. No ante, losing costs nothing more, one try per opponent each week.
- Or buy one: your own **Armory** can stock it as a Mythic item (list price 1,000 gold), from week 3 in your Capitol and week 4 in your other towns.
- Or pick one as a level-up reward.

**Rules:**
- One coin per kind of enemy: while a Fox holds one, the ransom isn't offered against Foxes.
- Never offered against bosses, in the Arena or in tournaments.
- It's offered on the ante screen, so it needs Play for Ante on (the default).
- Winning 2+ rounds of a Draft pays a Gold coin; 2+ wins in Sealed pays a Silver coin."""))

M.append(("Dungeons, fog and the History replay", "Dungeons & map", r"""**Dungeons and the map**

**Q: Why did a dungeon disappear from my map?**
**A:** Dungeons and caves rotate. Each one lasts 20-40 in-game days, then vanishes, and a new one opens somewhere else. One also leaves early when:
- you clear it out - every enemy and every pickup, on every floor,
- you lose a duel inside it (a side-quest target gives you 3 tries and 30 extra days; story locations never vanish),
- you take its loot while its enemies still stand: then it keeps only a quarter of its remaining days.
Boss lairs are different: they only leave once cleared, and come back 10-30 days later, restocked. Return visits pay half, and a +Life reward or a boss's own signature item only pays once.

**Q: Why is most of the map black?**
**A:** That's Fog of War (on by default; Settings can turn it off). Black = never explored, dimmed = explored but out of sight, bright = what you can see right now. Creatures and resource pickups only show in the bright. Your own land and towns stay lit, and an Outlook widens a town's view (x2, or x3 in your Capitol). A Torch in your utility slot widens your sight, and a Bonfire clears the fog around the spot where you build it.
Where you can't see right now, creatures come more often, lean tougher and move a little faster; in your light they come less often and lean easier.

**Q: What's the History button?**
**A:** The map screen's **History** button replays your game from day one: the whole world with the fog lifted, each color's land filling in, towns changing hands and the AI castles falling, with captions for your own milestones. Play, pause, change the speed or drag to any day. The Victory and Defeat screens have it too."""))

M.append(("Quests, updates, saves and bug reports", "Quests, updates & logs", r"""**Quests, updates and bug reports**

**Q: My quest says to return to town, but nothing happens.**
**A:** Walk back into the town that gave the quest - the reward pays on arrival. If the stage started while you were already standing in that town, step out and back in.

**Q: How many quests can I have, and how long do they last?**
**A:** Up to 10 side quests at once, each lasting 15 days. Story quests never expire.

**Q: How do I update the game to the latest version of Forge?**
**A:** You can't, and you don't need to. Each release is pinned to one tested Forge build, named in its release notes. Forge's own updater is switched off on purpose, because a different engine underneath can break your world. Engine updates come with new Forsaken Realms releases.
To update the game itself, download the new release zip from GitHub and unzip it (a fresh folder is cleanest). Your saves live outside the game folder and carry over. On PC the start screen tells you when a new version is out: a small notice with a Download page button (it downloads nothing and sends nothing about you). On Android, install the new APK over the old one.

**Q: How can I help?**
**A:** See the #how-can-i-help channel.

**Q: Where are my saves and logs? How do I report a bug?**
**A:** Easiest, PC or Android: **Settings > Export Logs**. It puts all your logs in one zip in Downloads.
On Windows everything is in **%APPDATA%\ForsakenRealms** (paste that into File Explorer's address bar). Saves are in its adventure\The Forsaken Realms folder. The log is **forge.log**. Older ones are forge.<date>-<time>.log (the last 10).
On Android the logs are in Android/obb/com.thesaguy.forsakenrealms/ForsakenRealms/data (Android 10 and older: ForsakenRealms/data). Newer phones hide it; use the export.
To report a bug, post here what happened, your difficulty and your logs. Screenshots and your save help too.
(macOS: ~/Library/Application Support/ForsakenRealms. Linux: ~/.forsakenrealms)"""))

M.append(("Support The Forsaken Realms", "Support", r"""**☕ Support The Forsaken Realms**

The Forsaken Realms is free, and it always will be. No ads, no paywalls, nothing locked behind a purchase.

Building it isn't free, though. 😅 TFR is made with a lot of help from Claude, an AI assistant, and Claude is not free 😉 Add in the hours of testing and tuning, and every update has a real cost behind it.

If you've enjoyed your time in the Realms and want to help keep the updates coming, you can buy me a coffee on Ko-fi:
👉 **https://ko-fi.com/thesaguy**

**What a tip helps with:**
🛠️ new features, quests and fixes, sooner
🎨 the tools behind the new art and sprites
🤖 keeping Claude busy on the next update

**Can't tip? You still help a lot:**
🐞 report bugs here; shared logs make fixes fast
💬 tell me what you love and what bugs you
📣 tell a friend who likes Magic about TFR

Thanks for playing. ❤️
— TheSAguy"""))

M.append(("Town specialties and realm milestones", "Specialties & milestones", r"""**Town specialties and realm milestones**

**Q: My town wants a specialty. What are they?**
**A:** Every town you restore picks one trade, right after the restore fee (a town you take another way asks the first time you walk in; "Later" leaves the pick at its Job Board). Your Capitol and Orazca never take one.
- **Mining:** this town's mines pay 50% more
- **Research:** all your research 5% faster for each Research town (up to 50%)
- **Outpost:** lit as if it had an Outlook, and 5% harder to capture
- **Smithy:** its Armory rolls on your Capitol's table
- **Garrison:** 20% harder to capture, and never raided
- **Market:** its card shops 10% cheaper, and one free restock a week
Change it at the Job Board for half the town's restore fee in gold; then it's locked for 7 days. The World Map's Names view shows each town's badge.

**Q: What are realm milestones?**
**A:** Every **10 towns** your realm reaches (the most you've ever held at once, Capitol included), you pick 1 of 3 **realm boons**, yours for the rest of the game. The first offer upgrades the specialty you hold most (Deep Veins, Grand Library, Watchtowers, Master Smiths, Iron Garrisons, Merchant Guild). Others: **Tithe** (15 gold a town each payday), **Town Watch** (every town 10% harder to capture), **Lamplighters** (+1 tile of light) and **Royal Grant** (your next research free and done at once). World Standings shows your boons and the next milestone.

**Q: Why don't I have them?**
**A:** They come only with games started (or taken into New Game+) on v1.24 or later."""))

M.append(("Achievements, enemy plates, loops and duel settings", "Achievements & duels", r"""**Achievements, enemy plates and duels**

**Q: Does the game have achievements?**
**A:** Yes, 16 of its own (two hidden), listed first on the trophy page (Quest log > **Status** > the trophy button): castles, capitals, legends, Arena weeks, treasures, Partner standings, research and more. They belong to your profile, so a new game or New Game+ keeps them, and loading an older save awards what it has already done.

**Q: How can I see an enemy's rank before I fight?**
**A:** Hold **Alt** (touch: the Units button; controller: R3). A plate over every enemy on screen shows its rank. Fought another rank of its kind? You see its deck colors too. Fought this exact rank? Also the life it would start with now, in orange when it's above normal.

**Q: My duel is stuck in an endless loop. What now?**
**A:** The AI can't repeat the same ability more than 25 times in a turn, and a trigger that comes back 25 times with nobody acting ends the game in a draw. After 10 repeats the AI accepts the duel menu's **Offer Draw**. A draw is nothing won or lost: no ante, no gold, no notoriety.

**Q: Which duel settings are worth knowing?**
- **Show land drops in duels** (off by default): lands played this turn out of lands allowed, under each player's counts.
- **Always Yes** for a "you may" trigger like Valakut: open the stack, click the trigger, click it again and tick Always Yes. It holds for the rest of the duel.
- **Keep Mouse in Window** (PC, fullscreen): keeps the cursor inside the game, handy with two monitors.
- **Disable Notoriety:** see Enemies that adapt to you."""))

if __name__ == "__main__":
    for i, (topic, short, text) in enumerate(M, 1):
        print("%2d %5d %s %s" % (i, len(text), "OVER" if len(text) >= 2000 else "  ok", topic))
