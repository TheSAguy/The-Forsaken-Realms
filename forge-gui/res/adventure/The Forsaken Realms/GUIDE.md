# The Forsaken Realms — Player Guide

*A standalone card-adventure game built on the Forge rules engine.*

This guide walks through what's different about The Forsaken Realms compared to the base
Adventure experience, how the plane's custom systems fit together, and what to expect as you
explore. It's meant to sit alongside the game, not replace discovering things yourself — read as
much or as little as you want before diving in. For quick answers, the FAQ (`FAQ.md`) sits beside
this guide in the game folder.

## Table of Contents

1. [Introduction](#introduction)
2. [Starting Out](#starting-out)
3. [Changes from the Base Game](#changes-from-the-base-game)
4. [Early Game Advice](#early-game-advice)
5. [Mid and Late Game Advice](#mid-and-late-game-advice)
6. [The World](#the-world)
7. [Dungeon Guide, by Color](#dungeon-guide-by-color)
8. [Item Guide](#item-guide)
9. [Notes on Difficulty](#notes-on-difficulty)
10. [Appendix: Mechanics in Detail](#appendix-mechanics-in-detail)

---

## Introduction

The Forsaken Realms grew out of stock Forge Adventure mode, but changes how the world
itself behaves: towns take sides, dungeons come and go, your reputation with each color actually
means something, and there's a real path from "wandering duelist" to "ruler of your own Capitol."
None of it requires you to play differently deck-wise — it's a layer on top of the normal
duel loop, not a replacement for it.

**Fair warning: this is a HARD game, and that's intentional.** You start with little, the world
doesn't wait for you, and losses have teeth. Digging yourself out is the fun — but go in knowing
the early game is meant to be a fight.

*Windows, macOS and Linux launchers all ship (Windows is the tested one), and every release includes an Android version - playable, but it gets far less testing than the desktop builds.*

## Starting Out

- **Race selection** decides your hero's look, your starting expansions (four per race - how many
  you start with depends on difficulty - and the cards your starting deck is built from) and two
  starting shops. Your color comes from the Color picker. A `?` help button on the race-selection
  screen explains what each race actually grants before you commit. Twenty races, including the
  Goblin, Angel, Merfolk and Vampire.
- **Difficulty** affects more than combat: enemy roaming-encounter tiers, what buildings and
  research cost, what your cards sell for, and how many editions you start with unlocked are all
  difficulty-scaled.
- **You start with next to nothing.** You wake in a cave a short walk from **Orazca**, the ruined
  town at the heart of the map, with a **Homeward rune** that will always carry you home. The
  opening quest, "Oaths at the Ring", sends you around the five **Ring Cities** that circle Orazca,
  and each hands over part of your starting kit: gold, Shards, Wood, Stone, your starting items and
  your Challenge Coins. (Skip the introduction and you get the whole kit at once.)
- Your starting deck is a real, playable toolkit — expect to reshape it as you loot and buy cards,
  not to carry it unmodified into the late game.

## Changes from the Base Game

### Reputation & Color Alliances

Every color tracks its own reputation toward you, independent of the others. Reputation moves in
five tiers - **Partner** (80+), **Happy** (30-79), **Neutral**, **Unhappy** (-30 to -79), and
**War** (-80 and below) - and each tier changes real things: card-shop pricing (30% cheaper as a
Partner, 40% pricier at War), how often that color's mages target you, and whether you can enter
their towns at all. At War, ordinary towns bar you (you can storm them instead) and a color's own
capital charges a steep gold toll just to set foot inside. Your standing with a color also shapes
what you'll run into on their land - roaming enemies skew noticeably weaker with a Partner or Happy
standing, and tougher the worse things get, down to War - and how often its creatures turn up at
all: three times as often at War, a third as often as a Partner. **Legends** - the realm's oversized
dragon lords, gods and Slivers, and the Arena's wandering champions, many of them best-of-3 fights -
turn up **only on the land of a color that is Unhappy or at War with you**, and rarely: roughly one
sighting every few days of travel there, sooner at War. A legend walks a color's land when it
carries that color; the few colorless ones walk any hostile land. The ones you haven't met come
first - a legend you have already seen returns only once the others have had their turn - and the
same legend is never out twice at once. A legend never arrives unannounced: you are told which one
it is and in which direction, a **gold dot** marks it on the minimap and the map, and it **holds its
ground for three in-game days** - it only comes for you once you walk within a few tiles, so the
fight is yours to pick. Every legend starts its duels with a **Gemstone Mine** in play. Your quest
log lists every legend roaming right now, with its direction and the days it has left. At War a
color also sends its own arena champions roaming. Your own territory is always the safest place to
fight, regardless of anyone else's standing. Separately, the world as a whole trends toward
tougher roaming enemies the longer a run goes on, week by week, capped well short of an endless
escalation - so the opening weeks are the gentlest part of any run, by design.

### Territory Control & Color Defeat

Towns aren't static. Each color's territory expands or contracts around its castle, capital and
towns over time, and whose land you're on changes your travel speed: 15% faster on your own, and
on a color's land anywhere from 10% faster (Partner) to 10% slower (War). Push a color all the way
to **War** and you can take the fight to it: storm one of its towns (once a week per town) and a
win makes the town yours, or storm its capital against two Archmages at once - taking a capital
halves the war mages that color can field. Bring down a color's **castle** by beating the Lord
inside and the color falls for good, and the consequences ripple outward: its towns and capital
revert to the neutral Wasteland, the two colors beside it on the wheel each send their next mage
straight at you, and every surviving color can field one more mage from then on.

Here is how that looks on the world map, at three moments of one game. (These shots predate the
mountain barrier described under [The World](#the-world), but the colors spread the same way.)

![The world on day one](guide/territory_start.png)

**Day one.** The Forsaking left the land a gray Wasteland. Each of the five colors holds only the ground
around its Capital on the rim - white to the north, blue east, black south-east, red south-west, green west -
and you begin in the middle.

![The world a few weeks in](guide/territory_middle.png)

**A few weeks in.** Every color has pushed out from its Capital, and your own realm - the circle in the middle -
has grown around your Capitol. The blurred dots are mages on the march, each in its color's hue (black's are
purple); a dot inside someone else's land is an attack on its way.

![The world late in the game](guide/territory_late.png)

**Late in the game.** The Wasteland is gone. Every border now touches another, and the frontier towns are
where the colors - and you - fight it out.

### Winning and Losing

Losing a duel never ends a run - it costs gold, life and your ante (see
[Notes on Difficulty](#notes-on-difficulty)). A run has one way to win and three ways to lose:

- **You win** by holding all five **Ring Cities** *and* bringing down all five colors' castles. You
  can keep playing afterward. A Ring City held by an AI color challenges you at its gate, whatever
  your standing: two of its champions fight you at once.
- **You lose if your Capitol falls.** A war mage that gets past your Capitol's guards forces you
  into a best-of-three defense duel, wherever you are. Lose it and the run is over.
- **You lose if one AI color holds three of the five Ring Cities.** You get a warning the moment
  any color holds two.
- **You lose if no free town is left:** you hold no town and no Capitol, and an AI capture leaves
  no wasteland town anywhere - no working or ruined one, and no unclaimed Ring City - for you to
  take.

### Time, Day & Night

The Forsaken Realms runs on a living clock. Every in-game day the world ticks forward: territory
spreads, mages march, shops restock on their weekly cycle, mines pay out and guards draw their
wages on payday (the first day of each new week - days 8, 15, 22 and so on), quest timers count
down (side quests fail after 15 days - story quests never expire), and dungeons age toward their
rotation. A HUD clock shows the time of day and a Day/Week tracker keeps the calendar visible; a
**Speed-Up** toggle fast-forwards time when you're waiting on the world rather than exploring it.

Day and night change the fights themselves. Between **6am and 6pm**, enemies you battle on the
overworld get a life bonus or penalty based on the terrain you fight them on - and the effect
flips at night:

| Terrain fought on | Day (6am-6pm) | Night |
| --- | --- | --- |
| White (plains)    | +10% life | -10% life |
| Green (forest)    | +5% life  | -5% life  |
| Black (swamp)     | -10% life | +10% life |
| Red (mountain)    | -5% life  | +5% life  |
| Blue / neutral / your land | no change | no change |

In practice: raid the swamps at high noon and the plains after dark. The modifier applies only to
roaming overworld fights - dungeons, towns, Arenas, and Inn tournaments are unaffected.

### Fog of War

Fog of War is on by default (Settings can switch it off). **Black** is land you have never
explored, **dimmed** is land you have seen but can't see right now, and **bright** is what's in
view: the circle around you, your own land and towns, an Outlook's reach, a Bonfire's light.
Creatures only show in the bright.

The fog also changes what you meet. Where you can't see right now - dimmed or black - roaming
creatures turn up about a quarter more often, lean toward the tougher ranks, and move 10% faster.
In the light, a quarter of the spawns never happen, the mix leans easy, and creatures move at their
normal pace. Your own light counts, so a Torch pushes the danger back - and a creature chasing you
slows down as it steps into your light. (With the fog switched off, the whole map counts as lit.)
The [Item Guide](#item-guide) covers the Torch and the Bonfire.

### The Capitol

Your Capitol can rise in one place only: **Orazca**, the ruined town at the very center of the
map. Restore it at its Job Board like any other ruin, hold five towns in all (Orazca counts), and
the same Job Board offers the upgrade. The Capitol is a walled, castle-sized town with a layout of
its own, two guards instead of one, and **24 building slots** - 16 open ones plus five colored Land
Shops, a Utility Land Shop, a Booster Shop and an Armory, against an ordinary town's 9 (8 open
slots and an Armory). It also offers buildings no ordinary town does: the Bank, the Exchange, the
Archaeologist, the Research Lab, Rare and Mythic card shops, and an Arena you can upgrade.
Upgrading carries Orazca's reputation, buildings and guards forward, and adds +2 town reputation
on top for good measure.

Raising it draws fire. The moment your Capitol stands, every color still in the game sends an
Archmage at your realm, and for as long as it stands each color may keep one more attack mage in
the field.

**Your roads.** Raising the Capitol also paves your realm: cobblestone roads join it to every town
you hold (captured Ring Cities included), taking over the old sand roads wherever they already run,
and every town you restore or capture later joins the network. Any road speeds you up, but your own
are faster still - about 1.7x your normal pace against the old roads' 1.5x, and the extra is yours
alone. The Capitol and each of your towns sit on a small cobbled plaza.

### Wood & Stone

Beyond Gold and Shards, you'll collect **Wood** and **Stone**. They come from winning duels
(Green enemies lean to Wood, Red ones to Stone - see below), from world-map resource sparkles and
dungeon pickups, from chests and quest rewards, and - once you own a town - as steady weekly income
from a Lumber Mill or Stone Mine. They're spent on building and upgrading structures, and on the
Capitol upgrade itself.

### What a Win Pays

Roaming and dungeon enemies pay by **rank** - Apprentice, Adept, Master, Archmage, shown in the
enemy's name - and your **first victory** over each kind of enemy is the big one.

- **Cards.** A repeat win pays 1 / 2 / 2 / 3 cards by rank. A first victory pays 2 / 3 / 4 / 5,
  keeps the best rarities the enemy rolled, and adds one more non-land card from its deck (Common
  or Uncommon; Rare too on Easy). Masters and Archmages always keep their best cards first; an
  Apprentice or Adept pays at random on a repeat win. Gear that adds reward cards still adds them,
  and Easy pays one more card per win.
- **Gold and resources.** Every win pays gold plus, usually, one bonus resource that leans to the
  enemy's color: **White** to more Gold, **Blue** to Shards, **Red** to Stone, **Green** to Wood.
  **Black** is balanced, an enemy of two colors leans both ways, and a **colorless** one is
  balanced with a smaller purse. The purse grows with rank, is half again as large on a first
  victory, and a quarter larger when you beat an enemy that had the better record against you. So
  hunt the color whose resource you need. A color's "defeat five" quest counts those kills wherever
  they happen, out in the wilds or down in a dungeon.
- Bosses, tournament opponents, chests and quest rewards pay what they always did. An Arena
  champion you beat pays one Rare card from its deck.
- Every number here is a setting: `cardBudget...` (1 to 5 cards each) and `resourcePurse...` in
  the plane's `config tables/settings.json`.

### Buildings & the Economy

Towns you restore can be built up with dedicated economy buildings: **Gold/Wood/Stone/Shard
Mines** for steady weekly income, a **Trader** (any town, including your Capitol) for converting
Gold into Wood/Stone at a markup, a **Bank** and **Exchange** (Capitol-only - a Trader built at
your Capitol can also be upgraded into an Exchange, which trades at better rates and adds
Shards - the Exchange takes over the Trader's slot, so a town has one or the other, never
both), an **Outlook** (expands your fog-of-war vision radius - 2x in a town, 3x in your
Capitol), a **Teleporter** network for fast travel between any two Teleporter-equipped locations,
and an **Archaeologist** who can be sent on week-long expeditions for a chance at boosters and
rare items. Guards can be hired to defend a town, paid weekly out of your own coffers.

**Restoring a town.** A ruin's Job Board offers the restoration. Once it's paid you are sent back
out to the map - step back in and the town stands in your own town layout, its Inn open and its
other slots still rubble for you to build on. Ruins and neutral towns you don't hold keep the old
Wasteland look, and Orazca, once restored, keeps the Warden inside. A town you capture from a color
starts out the same way. How many buildings one of your towns can hold depends on its own town
reputation - three per point, and restoring a town earns the first point.

### Progressive Set Unlocks

Not every card set is available from turn one. Editions unlock gradually as you play (scaled by
your starting race and difficulty), and the **Research Lab** in your Capitol lets you formally
unlock the ones you have collected enough of. Research takes a week per edition, and several can
run at once. (Don't confuse this with the **Archaeologist**, who runs week-long expeditions for
cards and items - a different building doing a different job.)

**Your shops stock only what you have unlocked.** A card shop you build sells cards from the sets
*you* have unlocked - nothing else. Unlock more sets to stock your shops. In return they sell cheaper
than anyone else's: 25% under a neutral town's prices and 40% under an AI town's.

*Full detail — what you start with, where the other sets live, and exactly how to unlock them —
in [Card Sets](#card-sets-what-you-have-and-how-to-get-the-rest).*

### Dungeons That Actually Rotate

Dungeons and caves aren't fixed forever. Every visible one has a lifespan - 20 to 40 days - after
which it despawns on its own (at once if you lose a fight there and it isn't a story target), and a
fresh one appears elsewhere to take its place, drawn from a much larger reserve pool than what's
ever visible at once. Clearing a dungeon out completely also retires it, making room for something
new - and stripping one of its loot while its guards still stand leaves it only a quarter of the
days it had left.
Side-quest-linked dungeons get extra grace: three failed attempts before they're gone for good,
and their lifespan extends automatically while a quest still points at them. Story-critical
locations never disappear.

**Dungeons feed the land around them.** While an ordinary dungeon or cave stands, creatures pour
out of it: within about a dozen tiles of one, monsters turn up half again as often, and half of
them walk out of its door - creatures of its color before you have been inside, the very ones
that live there after. They grow stronger the longer they stand: every week adds a quarter to that
rate, up to double. Clearing out its enemies silences it for good, and the nearest town is
grateful - +1 local reputation. Story places, boss lairs and castles don't do this, and the
creatures that come out are never its boss, its champion or anyone you could talk to.

Loot fights back, too: a chest or booster pack inside a dungeon usually has a guard of its own.
Grab it while the guard still stands and the guard comes after you - a little faster than you
walk. Loot behind a locked gate or door is the exception: the lock is its guard, so once you've
found the key, pulled the switch or beaten what opens it, what's inside is yours.

**Boss lairs** follow their own rule. A lair leaves the map only once you've beaten its boss and
walked out with nothing left inside - losing there doesn't make it vanish - and 10 to 30 days later
it comes back to the same spot, fully restocked. Return visits pay half the gold, resources and
cards; a +Life reward and the boss's own signature item pay only once, and every other item is a
coin flip - though a key you need to get deeper in always drops.

### Ante, Tournaments & Hostile Lands

Ordinary duels are played for ante (on by default): each side stakes a card, winner takes it. If
you lose a card you value, a **Buy Back** option lets you repurchase it on the spot (priced by
rarity), and an escalating-cost **Re-roll** lets you swap out an ante you don't want to risk
before the duel starts — re-rolls won't repeat a card you just rejected.

Innkeepers run weekly **tournaments** (Draft, Sealed, and - once per player - Jumpstart) — these
are entry-fee events with prize support, **no ante at stake**. They also offer an opt-in "simulate
the AI rounds" mode if you'd rather not watch every AI match play out.

Beyond the tavern, remember the world itself takes sides: depending on your standing with each
color, their lands are more hostile or more friendly — travel speed, shop prices, town access,
and who their mages hunt all follow your reputation. And once you've built your Capitol, it hosts
an **advanced Arena** with a challenge tier (and champion fights) no ordinary town offers.
Arena fighters play their own decks, the same ones they'd bring to a fight in the wild. The
brackets at the five AI capitals, and at your own Arena until you upgrade it to level 2, never
seat Apprentice-tier fighters: about half the field are Adepts, a third Masters and the rest
Archmages.

### Item Economy & Shops

Shops restock on a weekly cycle (or sooner, if you pay a few Shards to refresh one), a card shop
in your own town can be re-assigned to another type you know, and prices differ depending on
who's buying: your own shops sell to you under market, AI shops charge you a premium. Rare items
exist as genuine chase rewards, not just vendor filler - several bosses across the world
(including all-new content, see below) drop items nobody else carries.

### World Standings & Mod Details

Two dedicated info screens track the state of the world and the mod itself: a World Standings
page showing every color's current reputation and territory at a glance (with a running history
graph), and a Mod Details page documenting the custom systems in-game, without needing this guide
open in another window.

## Early Game Advice

Your starting deck is a foundation, not a finished product - expect to add and cut cards
constantly for the first several in-game weeks. Prioritize a town of your own early - and make
Orazca, the ruin at the center, one of the first, since it's the only place your Capitol can rise.
Even a small town gives you a Mine or two, a place to restock cards, and a foothold toward the
five you need for that Capitol. Watch your reputation with the color you're camped nearest to -
it's much easier to stay Happy than to climb back from Unhappy once shops start charging you extra.

Carry a light. The dark is where the tougher creatures come from, and they come more often
there; a Torch (your own Armory keeps one on the shelf until you've bought your first) doubles how
far you see and keeps more of the map around you lit.

Push toward your Capitol as early as you reasonably can - five towns is a real early-game
investment, but it's the only place a Trader can be upgraded into an Exchange, on top of unlocking
the Bank, the Archaeologist and the Research Lab outright. Build a **Trader** well before then,
even though its rates are worse than an Exchange's - it's a guaranteed early way to turn spare
Gold into the Wood and Stone your buildings actually need, instead of waiting on Mines or dungeon
loot alone. It's also a good home for Gold you don't need sitting in your pocket: if you lose an
ante duel and want the card back, Buy Back costs real Gold, priced by rarity - a cost that bites
hardest on Insane, where you start with barely any cushion. Gold you've already converted into
Wood or Stone isn't there tempting you into a buy-back you hadn't planned for.

## Mid and Late Game Advice

By the midgame you should have a Capitol, at least one or two economy buildings generating
passive income, and enough reputation with your home colors to move through their territory
freely. The late game is about picking your fights: which colors you push toward War (and can
actually back up with a real deck), which capitals you're strong enough to storm, and how far
you push into the plane's hardest dungeons and boss fights - including the newest, hardest content
(see below). The run itself is won by holding all five Ring Cities and bringing down all five
castles - see [Winning and Losing](#winning-and-losing).

## The World

The Forsaken Realms uses the standard five-color-plus-colorless biome layout: each color has its
own territory, its own AI-controlled capital and castle, and its own flavor of dungeon. The five
colors sit evenly around the center of the map, where **Orazca** - your future Capitol - lies in
ruins, ringed by the five **Ring Cities** (Benalia, Tolaria, Urborg, Shiv and Llanowar) and joined
to them by roads like the spokes of a wheel. A Ring City's shops sell every card set, at double
price. Between the colors' lands, and along the coasts, runs **the barrier**: mountain ranges that
no territory ever claims and no road crosses - you go around them, unless you can fly. (Worlds made
before v1.14 have no barrier.) Beyond the color biomes, the world is dotted with named landmarks
worth seeking out - ancient castles like **Von Gant's Fortress**, **Emrakul's Castle**, and
**Black Dragon Mountain**; strongholds like the **Djinn's Palace** and **Necromancer's Study**; and
quieter finds like **Grolnok's Bog** or the **Secluded Elven Encampment**. Not every location is
hostile - some are just worth the detour.

## Dungeon Guide, by Color

This isn't an exhaustive list (the world generates far more dungeons than any one playthrough will
see), but a starting point for what to expect in each color's territory. Nearly every dungeon has
an entrance icon of its own on the map, so you'll soon learn to tell them apart at a glance.

### White

Forts and camps dominate white territory - watch for **Kor Outposts**, **Pirate Forts**, and the
**Cloud Fort**. The newest addition here is **Peaceful Clearing**, a full dungeon housing seven
distinct boss encounters (Cerise, Emiel, Grakk, Kwain, Phelia, Preston, and Thurid), each with
their own custom deck and drops.

### Blue

Blue's territory leans heavily on caves and flooded ruins - the **Sea Temple**, **Deep Caverns**,
and **Djinn's Palace** among them. **Idyllic Beachfront** is the newest full dungeon here, home to
six new bosses plus a returning favorite (Plagon) now folded into the same fight.

### Black

Black territory is graveyards, cursed groves, and worse - **Grolnok's Bog**, the **Undead Grove**,
**Shade's Lair**, and **Emrakul's Castle** among the notable stops. Two new locations landed here:
**Eclipsed Elven Court**, a full seven-boss dungeon (including the previously-buggy High Perfect
Morcant fight, now fixed), and **Isolated Hut**, a smaller bonus dungeon built around a single
tough boss, Istvan.

### Red

Barbarian camps, mercenary outposts, and the **Furnace Host Base** define red's territory.
**Ashling's Domain** is the newest full dungeon here - five bosses (Ashling herself among them)
guarding one of the plane's toughest early fights.

### Green

Expect groves, forests, and the occasional cursed grove - **Garruk's Forest**, **Copper Host
Forest**, **Satyr Grove**. **An-Havva Inn** is the new full dungeon in green territory, with five
bosses including a rebuilt Autumn Willow encounter distinct from the plane's own pre-existing one.

### Colorless

Colorless territory holds some of the plane's strangest fights - the **Gitaxian Laboratory**,
**Autonomous Factories**, and now two new additions: the **Planeswalker Dueling Club**, a
seven-boss gauntlet (plus a joke encounter worth finding), and the **Ancient Opal Cavern** - a
single, brutally difficult best-of-three duel against Nephilim Epochal for the Mox Opal. Bring
your best deck.

### Legendary Dungeons

Nine locations stand apart from everything else on the map: the eight dungeons ported from the
Realm of Legends (Ashling's Domain, Eclipsed Elven Court, Planeswalker Dueling Club, Idyllic
Beachfront, Peaceful Clearing, An-Havva Inn, Ancient Opal Cavern, Isolated Hut) and the Eldrazi
Prison. These are **true endgame content** - their bosses and decks were built to a far higher
power level than the surrounding world, and they are deliberately not scaled down. You'll know
them by the **red triple-skull marker** on the minimap and a warning at the door. Treat them as
your character's final exams, not a mid-game detour.

## Item Guide

Items are a real part of building your character, not an afterthought - between shop purchases,
dungeon rewards, and boss drops, expect to be actively hunting for upgrades throughout a run.
Several items exist only as drops from specific bosses and can't be bought anywhere, including a
wave of new equipment tied to the plane's newest dungeons (boots, crowns, armor, and more, each
built around the specific card it grants you at the start of a fight). Check what a boss drops
before you commit to fighting them if a specific item is your goal.

**Two utility slots.** Runes, Omenstones, Torches and the Bonfire all go in your utility slot, and
you have two of them, so you can carry, say, a Torch and a rune at once. Two lights don't add up:
only the stronger one counts.

**Runes and Omenstones** are reusable teleports that cost a shard per use. Everyone starts with a
**Homeward rune**, which takes you home - to just outside Orazca, or your Capitol once it stands.
The Quick Travel Mart in your Capitol and your towns sells the Omenstones, the Ghost rune, and the
**Rally rune**, which carries you to just outside whichever of your towns - or whichever of the
five Ring Cities, whoever holds it - is under attack. With several besieged, each use goes to the
next one in turn, so four uses reach four places before the cycle starts over. While none of them
is targeted it does nothing and costs nothing. The main quest hands you your first one: hire a
guard for Orazca ("Raise the Banner") and the stage ends with a briefing on how guards fare against
attacking mages and a Rally rune to answer the next attack.

**Light in the fog.** A **Torch** (200 gold list price) doubles how far you see through the fog of
war, and a **Grand Torch** quadruples it; use either (1 shard) to flare your sight to three times
its radius for a moment, and everything the flare touches stays on your map. The **Bonfire**
(1,200 gold) lights the way like a Torch while you carry it. Use it on the world map (1 shard) to
build a fire where you stand: the fog lifts for 15 tiles around it, one tile less each day, until
the fire dies out two weeks later. A kit makes ten fires, and a spent kit is rebuilt for 50 Shards.
It needs Fog of War on - with the fog off, a use is refused and the shard handed back.

The **Yin-Yang rune** (2,000 gold) is two halves of one stone. Use it on the world map to set the
light half down where you stand, then use it again from anywhere under the open sky to return to
that spot and take the half back up - a teleport anchor you place yourself. One shard a use; inside
a town or dungeon it does nothing and the shard comes back. The Bonfire and the Yin-Yang rune are
Rare items: look for them in Armories.

## Notes on Difficulty

Insane difficulty roughly doubles the stakes of everything above: reputation swings matter more,
War states are more likely to actually happen, and the newest boss fights (several of which run
best-of-three) are tuned to be a real test even with a well-built deck. If you're finding a
specific new boss unfair, it's worth checking whether an easier difficulty changes that fight's
deck tier before assuming it's just you.

Losing a duel costs gold: a flat 50 on Easy, 100 on Normal, 150 on Hard and 200 on Insane - or all of it, if you
carry less. It also costs life - 10% of your max life on Easy, 20% on Normal, 30% on Hard and Insane - and your
ante. Handing over a Bronze Coin at the ante prompt saves your gold and your anted cards (the life loss still
applies). If your life runs out you're carried home: to your Capitol, or to Orazca before it stands.

Difficulty also scales what a win pays in gold and resources (half again as much on Easy, a quarter more on
Normal, the base amount on Hard and a fifth less on Insane), and a new world on Normal, Hard or Insane starts
with slightly fewer functioning Neutral towns and ruins to restore.


---

## Appendix: Mechanics in Detail

Everything above is the tour. This is the reference — the systems this plane adds that stock
Adventure mode has no equivalent for, written out properly so you can look one up mid-game rather
than guess. Nothing here is required reading.

### Color Reputation, in Detail

Five factions, one shared pool. **Your standing with the five colors sums to zero** — the five are
a wheel, and almost every action pushes one way and pulls the others. You cannot be everyone's
friend; picking allies is the point. (The few exceptions are listed below.)

**The wheel.** Each color has two allies and two enemies:

| Color | Allies | Enemies |
|---|---|---|
| White | Green, Blue | Black, Red |
| Blue | White, Black | Red, Green |
| Black | Blue, Red | Green, White |
| Red | Black, Green | White, Blue |
| Green | Red, White | Blue, Black |

**What moves it.** Beating an enemy in a duel shifts the whole wheel relative to that enemy's
color(s):

- The color you beat: **−2**
- Its two allies: **−1** each
- Its two enemies: **+2** each

So killing Black creatures makes Green and White like you, and annoys Blue and Red. A **boss**
counts triple; a **territory attack mage** counts double. A multicolor enemy applies half the
pattern for each of its colors, and colorless creatures, losses, Arena and tournament games
change nothing. Attacking one of a color's towns costs **−4** with that color and capturing it
**−8** more, spread over the wheel the same way. Your **starting deck** seeds the wheel too, which
is why you begin already liked by some and disliked by others.

**The exceptions.** Only three things add or take reputation outside the wheel: some color quests
pay a flat +1 or +2 with the color that asked, the five "Find the ... Capital" quests (from your
Capitol) pay +2 with each of your own colors, and bringing down a color's castle costs a flat 50
with that color.

**The five tiers**, and what each actually does:

| Standing | Range | Card prices | Attacks on you | Other |
|---|---|---|---|---|
| **Partner** | 80+ | **30% off** | 75% less likely | Entering its towns heals you to full plus 2 extra life until your next duel; all its blueprints on sale, Rare and Mythic included |
| **Happy** | 30 to 79 | 15% off | 50% less likely | Its Spellsmiths serve you; Common and Uncommon blueprints |
| **Neutral** | −29 to 29 | — | — | Common blueprints only |
| **Unhappy** | −30 to −79 | 25% pricier | 15% more likely | No blueprints; entering its towns no longer heals you |
| **War** | −80 or worse | **40% pricier** | 50% more likely, and it may target your Capitol | Towns barred (you may storm them instead); its capital charges a 500 gold toll; no healing in its towns |

Two consequences worth planning around. At **War** that color's towns shut their gates on you —
you can storm them, once a week each, and you can still buy your way into its capital, but at a
toll and at the worst prices in the game. And at **Partner** its Rare and Mythic blueprints and a
free heal to full plus 2 on every visit to its towns come on top of Happy's Spellsmiths and
Uncommon blueprints, which is a genuinely different game from Neutral.

**Town reputation is a separate thing.** Each individual town also remembers how you've treated
it, and that adjusts prices there by up to 10% either way. It goes up when you restore the town or
complete work for it, and it is per-town — unlike color reputation, it is not zero-sum and costs
you nothing elsewhere. In your own towns it also sets how many buildings you may put up (three per
point) and helps fend off war mages.

### Shop Blueprints — learning what you're allowed to build

Card shops have a **type** — Goblins, Instants, Azorius, Dragons and so on, about **250** of them
— and the type decides what that shop sells. In this plane you can only build a type you actually
know, so your towns are shaped by which blueprints you've collected. Whatever the type, a shop you
build stocks ONLY cards from the sets you have unlocked - unlock more sets to fill its shelves.

**What you start with, and why.** Five types, drawn from who you are:

- **Three from your chosen color** — its Common-tier trio. Pick Red and you start able to build
  the three basic Red shops.
- **Two from your race** — its tribal shops. An Undead start knows Skeletons and Zombies.

That's deliberately a weak opening hand. Your color's *Uncommon* trio and its Rare capstone are
withheld, so there is an obvious ladder to climb within your own color before you ever look
outward.

**Where the rest went.** Nowhere — they all exist, and the build menu shows you every one of
them, grayed out, with a live count of how many cards each could stock for you. Nothing is hidden;
you can see exactly what you're missing and decide what's worth hunting.

**Two ways to learn a new one:**

1. **Buy the shop you're standing in.** Walk into almost any card shop whose type you don't know —
   in a rival AI capital, a neutral town, even one of your own — and there's a **Buy Blueprint**
   button. Crawling other people's towns is the main acquisition loop, and it's why exploring is
   worth doing even when you're not shopping.
2. **Find one.** World-map Mystery pickups (the diamond) and chests each carry a **25%** chance of
   a blueprint you don't know yet, and an Archaeologist expedition a 15% chance. A drop arrives as
   a card you turn over, like any other reward.

- **The build menu shows everything**, tier first, then By Color / By Card Type / Tribal /
  Special, sorted **Available → Built → Locked**. Locked types are grayed rather than hidden, so
  you can see what exists to hunt for. Each entry shows how many cards it could actually stock for
  you right now — a number that grows as you research more sets.
- **Building a shop costs by tier** (Normal; 25% less on Easy, 25% more on Hard, 50% more on
  Insane): Common 100 gold + 5 Wood, Uncommon 150 gold + 10 Wood, Rare 200 gold + 50 Shards,
  Mythic 300 gold + 100 Shards. Rare and Mythic shops can only be built in your Capitol.
- **The Mythic tier** holds the five-color Domain of Dominaria, the Gods shop and the three-color,
  Phyrexian, Planeswalker and Legend shops. The Angel, Demon, Dragon, Hydra, Nobles and Sphinx shops
  are Rare - their stock is mostly Rare and Mythic cards. (The five colored booster-pack shops and
  five Instant shops sit in the Common tier.)
- **Blueprint prices are in Shards, set by the type's tier in your own build menu**: 20 Common,
  40 Uncommon, 100 Rare, 200 Mythic — the same in every town that sells it.
- **Reputation gates the five colors' towns**, by the same tier. At one of their towns — capital
  or ordinary — you need to be at least **Neutral** with that color to buy anything at all, and
  Neutral buys Common blueprints only; **Happy** adds Uncommon; **Partner** sells everything, Rare
  and Mythic included. Standing also discounts the price — 15% off at Happy, 30% at Partner.
  Neutral towns and your own towns have no standing to check: flat price, no gate.
- **Re-assign Shop Type**, a button inside your own card shops, switches a shop to another type
  you know, crediting half the old shop's gold cost.
- **One type per town.** A type already standing in a town can't be built there again, so each
  town ends up with a spread rather than six copies of your favorite.
- The five **Cartographer's Guild** basic-land shops are outside this system entirely — no
  blueprint needed, and none is ever sold or dropped for them.

### The Armory — what's on the shelf, and when

Only **your own towns and your Capitol** have a working Armory you can develop. Neutral towns have
one too, though roughly a third of them had theirs wrecked before you ever arrived, permanently.
AI color *towns* have no Armory at all; the five AI *capitals* have equipment shops that work
quite differently (fixed hand-picked stock, no rarity roll).

Your Armory rolls each of its **6 slots independently** (8 once it's Level 2) — so it's six
separate chances at something good, not one shop-wide rarity.

**Stock improves over the first month.** The odds by week:

| | Week 1 | Week 2 | Week 3 | Week 4+ |
|---|---|---|---|---|
| **Your Capitol** | 60/30/0/0 | 60/30/8/0 | 60/30/8/2 | **45/35/16/4** |
| **Your towns** | 60/30/0/0 | 60/30/8/0 | 60/30/8/0 | 60/30/8/2 |
| **Neutral towns** | 60/30/0/0 | 60/30/8/0 | 60/30/8/0 | 60/30/8/0 |

*(Common/Uncommon/Rare/Mythic.)* In plain terms: **no Rare anywhere in week 1, no Mythic anywhere
until week 3**, and then only in your Capitol. Your own towns catch up at week 4, when the Capitol
also sharpens considerably. **Neutral towns never sell Mythics**, ever — that's what makes owning
your own Capitol worth the trouble.

Other Armory notes:
- **Upgrading to Level 2** costs 150 Stone and takes the shelf from 6 items to 8. Player-owned
  only — neither an AI nor a neutral Armory can ever be upgraded.
- **Your first Torch is guaranteed.** A player-owned Armory keeps one in stock until you actually
  buy one, so you'll often see 7 items rather than 6 early on.
- **What the roll can turn up.** The **Bonfire** and the **Yin-Yang rune** are among the Rares
  (see the [Item Guide](#item-guide)), and the **Bronze Challenge Coin** is one of the Mythics
  (1,000 gold list price) — so your Capitol can offer a coin from week 3 and your other towns from
  week 4.
- **Everything refreshes weekly** on its own, everywhere. **Re-roll Inventory** (a paid, once-a-week
  override) is player-owned only.
- Prices are 25% cheaper in your own towns and 25% pricier in an AI color town, before reputation.
- **Storage.** Your Capitol's Level 2 Armory has a storage: put spare equipment (anything with a slot
  that you are not wearing) in from your inventory, and take it back out there. A roaming guard's
  **Equipment** button dresses it from that storage, one item per slot.
  What a guard wears fights with it - its own bonuses on its side, its items' opponent effects on
  the mage's - whether you watch the duel or let it simulate, and boots make it walk faster.
  Dismissing or losing a guard returns its equipment to the storage; only the deck can be forfeited.
  A guard carries no shards, so a shard ability on its gear (a Flame Sword's, say) goes unused.

### Card Sets: What You Have, and How to Get the Rest

Not every card set is available to you, and this is the system most worth understanding early.

**What you start with.** Each race is tied to four thematic editions. You begin with a random
subset of *those four*, sized by difficulty:

| Difficulty | Starting editions |
|---|---|
| Easy | 4 (all of them) |
| Normal | 3 |
| Hard | 2 |
| Insane | 1 |

Two runs as the same race on Hard can start with different sets. This is why race choice matters
beyond flavor, and why the race-select `?` button is worth reading before you commit.

**Where the other sets went.** Every new world deals the sets out among six owners — the five
colors and a neutral pool. Twelve go to the neutral pool and the rest are split evenly among the
five colors, and the deal is fresh for every world. **Your race's four sets never go to a color:**
they're added to the neutral pool, so neutral towns sell them and colorless enemies drop them.
Those shares aren't locked away in the abstract: they decide **what the shops sell**.

- **Your own towns and Capitol** stock cards only from the editions *you* have unlocked. Unlock more
  sets to stock your shops - and they sell cheaper than any neutral or AI shop (25% and 40% under).
- **An AI color's town** stocks cards from that color's own share.
- **A neutral town** stocks from the neutral pool.
- **A Ring City** stocks every set, at double price.

The practical consequence: **traveling is how you shop.** If you want cards from a set you have
not unlocked, you buy them in whichever faction's towns hold that set — which is exactly where
your standing with that color starts to matter.

**How to unlock a set properly.** The **Research Lab** in your Capitol is the formal route:

1. **Collect the cards first.** A set becomes researchable once you own **10%** of it (minimum 5
   cards). You'll get a popup the moment you cross that line. The Lab lists every edition with
   your progress as `(owned/needed)` - research under way first, then the sets ready to research -
   and two checkboxes hide the sets you have found no cards for, or not yet enough.
2. **Pay 50 Shards** (on Normal - 38 on Easy, 63 on Hard, 75 on Insane) and start the research.
3. **Wait a week.** Each edition runs on its own 7-day timer, and you can research several at once.

Once researched, that edition joins your unlocked pool permanently: your own shops start stocking
it, and it becomes legal in your own towns' Inn tournaments.

**Two things that don't wait for research.** Cards you own are always yours to play regardless of
which sets are unlocked — the restriction governs what shops *sell*, never what your deck may
*contain*. And loot ignores your unlock list: an enemy drops cards from its **own color's** sets
(its first color, if it has several; a colorless enemy uses the neutral pool), while a dungeon's
chests follow whoever owns the land the dungeon stands on (the neutral pool on the Wasteland and on
your own land). Bosses and other special fighters can drop anything. So fighting a color's
creatures, and exploring its ground, are real ways to pick up cards you couldn't buy.

### The Bronze Challenge Coin

Your starting kit includes three, and they have two separate uses.

1. **Free entry to a Jumpstart tournament** at an Inn, in place of the 500 gold or 50 Shard fee -
   and since you only ever get one Jumpstart tournament (New Game+ included), only one coin ever
   goes this way; the other two are for ransom.
2. **Ante ransom.** Lose an ordinary duel and you can hand the winner a coin instead of losing
   your anted cards — you get every anted card back *and* keep your gold. Beat that same enemy
   later and you take the coin back as part of the reward.

**Or buy one.** Your own Armory can stock a Bronze Coin as a Mythic item (1,000 gold list price) -
from week 3 in your Capitol and week 4 in your other towns.

**Or challenge for it.** A **Level 2 Arena** offers the **Coin Challenge**: a staged duel against any
enemy holding one of your coins. Entry is 50 gold on Easy and Normal and 100 on Hard and Insane, plus
5 / 10 / 15 Shards on Normal / Hard / Insane. One attempt per opponent each week, no ante, and a
loss costs nothing beyond the entry - the coin is the only prize.

**One coin per enemy.** If a Fox already holds a coin of yours, the option won't be offered again
against Foxes until you've won it back. (Bosses, Arena fights and tournament matches never take
one at all.)

The gold coin is a free Draft entry and the silver a free Sealed entry; winning two or more rounds
of a Draft pays a gold coin, and of a Sealed a silver one.

### Inn Tournaments

Every Inn runs one, refreshed on a cooldown. The entry fee scales with the town's opinion of you.

- **Your own towns run on your own stock**: the card pool is your race's editions plus everything
  you've researched. It widens as you unlock more sets, and a tournament you haven't entered yet
  will re-roll itself when your pool changes. (If your pool is still too narrow to form a legal
  draft block, the Inn falls back to the wider pool rather than offering nothing.)
- **AI and neutral Inns** draw from the neutral pool plus the sets you've unlocked instead — which
  is a real reason to travel if you want to draft sets you don't own.
- **Re-roll** the offered tournament for 15 Shards; it's guaranteed to come back different.
- **Jumpstart comes around once per player.** Every Inn draws its Jumpstart from the same list of
  Jumpstart products, whatever your race, and the entry is 500 gold or 50 Shards (or a Bronze
  Coin). After you have played one Jumpstart tournament - on any run, New Game+ included - Inns
  only offer Draft and Sealed.
- Tournament wins **don't** count toward your win/loss record, and don't push up the enemy tiers
  you meet in the world.
- A **ruined town's** Inn is boarded up until you restore the town at its Job Board — restoring it
  is the only thing a ruin offers. (A tournament you had already entered there can still be
  finished.)

### Territory, and Defending What's Yours

The five colors expand their borders over time and dispatch attack mages at towns — yours
included. Each color sends one every 2–5 days.

- **Your Capitol can only be targeted once a week by each color.** Once a color aims a mage at it,
  that color can't pick it again for 7 days — win, lose, or kill the mage on the road. With five
  colors that's a hard ceiling of five Capitol attacks a week.
- Mages **walk** to their target, so you can intercept one in the field before it arrives.
- The **Rally rune** (Quick Travel Mart) drops you outside one of your towns - or one of the five
  Ring Cities, whoever holds it - that a mage is heading for; use it again to jump to the next one
  under attack. It stays quiet while none of them is. "Raise the Banner" gives you one for hiring
  your first guard; more are sold at the mart.
- **Guards** you've hired fight first. If they fall, your Capitol's own town reputation may still
  turn the mage away (1% a point, up to 20%); if it doesn't, you defend your Capitol in person in a
  forced best-of-three — **and losing that ends your run.**
- A **roaming guard's** fight counts like one of yours, watched or simulated. A draw, a stalled fight,
  quitting out of a watched one, or a fight cut short by closing the game all count as the guard losing -
  the attacker then walks on into the town.
- **Neutral towns defend themselves**: 15% base, 20% if the town still has a working Armory.
- Your standing with a color changes how likely it is to come for you — the exact weights are in
  the next section.

### How the AI Picks Its Targets

Every color runs the same routine, so most of it can be predicted.

**When.** Each color attacks on its own clock, waiting 2–5 days between mages. It can only have so
many mages on the road at once — 1 on Easy, 2 on Normal, 3 on Hard, 4 on Insane — plus one more
for every 11 / 10 / 9 / 8 towns you own (Easy through Insane; your Capitol counts as one), one
more for every color that has already fallen, and one more while your Capitol stands. Take a
color's capital and that cap is halved.

**What it may attack.** Any wasteland town — untouched ruins, working neutral towns, and every town
you own — plus the ordinary towns of its two *enemy* colors on the wheel (White fights Black and
Red, Blue fights Red and Green, Black fights Green and White, Red fights White and Blue, Green
fights Blue and Black). It never touches an ally's towns, its own, or another color's capital.
Your Capitol is a special case — see below.

**Before the roll**, three things leave the list: any Ring City this color already went for in the
last 7 days, any town one of its mages is already marching toward, and your Capitol for 7 days
after that color last sent a mage at it.

**The pick.** What's left is sorted by walking distance - around the mountain barrier, never
through it - to the color's *nearest* holding — castle, capital or any town, so its reach grows
with its borders — and the **5 nearest** go into a weighted roll. The mage itself always sets out
from the castle. Every candidate starts at weight 1, then:

| Candidate | Weight |
|---|---|
| Your town — Partner | ×0.25 |
| Your town — Happy | ×0.50 |
| Your town — Neutral | ×1.00 |
| Your town — Unhappy | ×1.15 |
| Your town — War | ×1.50 |
| Working neutral town (not yours) | ×0.85 |
| A Ring City (on top of the above) | ×1.25 |
| Enemy-color town or bare ruin | ×1.00 |

So one town of yours sitting among four AI towns is picked about 6% of the time at Partner, 11% at
Happy, 20% at Neutral, 22% at Unhappy and 27% at War. Reputation only ever changes *your* towns'
share — it never makes a color prefer one rival over another; distance does that.

**Your Capitol** is never a normal candidate. Only at **War** does a color add it to the roll, as a
sixth option worth 5% of the pool.

**The one guaranteed attack.** When a color's *neighbor* is wiped out, that color's next mage goes
to a random town of yours, anywhere on the map, Capitol included if it's off cooldown. If you own
nothing yet, the debt waits until you do.

**On arrival.** Your hired guards fight first, strongest first — each fight pits the guard's rarity
weight against the mage's (Common 1, Uncommon 2, Rare 4, Mythic 8), with a flat 10% edge to the
attacker and 5% back to you if the town has an Outlook. Then the capture roll uses the mage's own
rarity: Common 10%, Uncommon 30%, Rare 70%, Mythic 90%, again minus 5% for an Outlook and 1% for
every point of the town's own reputation (up to 20%), though never below 5%. A successful capture
has a 20% chance to sack the town to ruins instead of keeping it. Working neutral towns repel 15%
of attacks on their own (20% with an Armory). Against a rival color, the defending town's guard
fights the same way and the same rarity roll then decides whether the town changes hands - a
failed attack leaves it with its defender. Ring Cities are captured or repelled, never sacked. A
mage that reaches your Capitol past both guards forces the duel described above.

### New Game+

A New Game+ is a **new game plus your collection**. You keep cards, decks, equipment, inventory
and every resource — gold (your bank's balance is paid into your purse), shards, wood and stone.
Everything else resets to a fresh run: shop blueprints, researched editions, research in progress,
quests and story flags, color reputation, statistics, blessings, and any Bronze Coins enemies were
holding. Your challenge-coin purse is topped back up to 1 gold / 1 silver / 3 bronze, keeping any
surplus you'd hoarded. The one thing that doesn't come back is the Jumpstart tournament: that's
once per player.

Two things worth knowing before you press it: your **max life returns to the difficulty's
starting value** (accumulated bonuses are not carried), and an **in-progress tournament is
discarded**, including cards you've drafted but not yet banked.

### Smaller Things Worth Knowing

- **Hold Z** to move at 1.5x on the overworld. Time moves faster too.
- **Selling** cards pays a share of value set by your difficulty - 60% Easy, 50% Normal,
  25% Hard, 5% Insane (shown as the sale price on the new-game screen); the town's opinion of
  you adjusts it from there.
- **Leaving a town** lights up the land around it for a moment - the same flash as when you first
  discovered it - so you can re-orient before setting off.
- **A thin black outline** on the world map means you can't walk through it - trees, rocks, water,
  mountains. The plain decorations you can walk over have none.
- **Max life grows with your realm:** +1 for every five towns you hold, +1 for the Capitol, and +1
  for each Ring City you have visited, for as long as no AI color holds it.
- **+Life rewards pay once per game** - beat the same legend twice and only the first win raises
  your max life.
- **Caves wear their biome.** Cave mouths on the map come in dozens of looks - mossy in the
  forests, red rock in the mountains, crystal-lit in the swamps, icy on the coasts - instead of
  one shared icon.
- **78 new caves**, thirteen per biome - Frosthollow, Tidecutter Grotto, Bonepile Hollow, Cinder
  Hollow, Mossback Hollow, Wasteland Cleft and their kin. Small, medium and large chambers with
  three to five patrolling creatures - most of them small fry, but one or two in every cave can be
  anything from an Apprentice to an Archmage, so look before you step in. Each holds a card chest,
  gold and building stone; the deeper ones add wood, mana shards, a second gold pile and sometimes
  a booster pack. They spawn in new worlds and join the dungeon rotation.
- The **blue dot** in the quest list marks the quest you're currently tracking. Quests that count -
  "defeat five Blue enemies", "clear three dungeons" - show their progress there, "(2/5)".
- A **Mystery pickup** (the diamond) can bless you: about one pickup in ten grants **+3 starting
  life in your next duel**, added to whatever blessing you already carry.
- Enemy names carry their tier — "Clay Golem (Master)" — so you can judge a fight before taking
  it. Dispatched mages are capped at Adept in week 1 and Master in weeks 2–3.
- Settings has an **"avoid restricted edition art"** toggle (on by default) that steers card art
  away from the black-and-white printings.
- A run is only lost in the three ways listed under [Winning and Losing](#winning-and-losing):
  your Capitol falls, one AI color holds three Ring Cities, or no free town is left while you hold
  nothing. A lost duel never ends it.

---

*This guide covers The Forsaken Realms v1.15, as of 2026-09-26. See `MOD_CHANGELOG.md` in the
repository for the full history of how the game got here, if you're curious.*

## Support & Community

The Forsaken Realms is free and open source. If you're enjoying it:

- **Join the Discord** for feedback, bug reports, and balance talk: https://discord.gg/TTRPKc9HYJ
- **Support development on Ko-fi**: https://ko-fi.com/thesaguy
