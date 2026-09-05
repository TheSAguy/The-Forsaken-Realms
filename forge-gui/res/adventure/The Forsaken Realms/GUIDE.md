# The Forsaken Realms — Player Guide

*A custom Adventure-mode world for Card-Forge/forge.*

This guide walks through what's different about The Forsaken Realms compared to the base
Adventure experience, how the plane's custom systems fit together, and what to expect as you
explore. It's meant to sit alongside the game, not replace discovering things yourself — read as
much or as little as you want before diving in.

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

The Forsaken Realms is built on top of stock Forge Adventure mode, but changes how the world
itself behaves: towns take sides, dungeons come and go, your reputation with each color actually
means something, and there's a real path from "wandering duelist" to "ruler of your own Capitol."
None of it requires you to play differently deck-wise — it's a layer on top of the normal
duel loop, not a replacement for it.

**Fair warning: this is a HARD game, and that's intentional.** You start with little, the world
doesn't wait for you, and losses have teeth. Digging yourself out is the fun — but go in knowing
the early game is meant to be a fight.

*Windows, macOS and Linux launchers all ship. An Android build is available as a community test - it is playable but has had far less testing than the desktop builds.*

## Starting Out

- **Race selection** sets your starting color identity and a small set of starting expansions
  (extra cards available from the very beginning, scaled to your chosen difficulty). A `?` help
  button on the race-selection screen explains what each race actually grants before you commit.
- **Difficulty** affects more than combat: enemy roaming-encounter tiers, AI shop pricing, and how
  many editions you start with unlocked are all difficulty-scaled.
- Your starting deck is a real, playable toolkit — expect to reshape it as you loot and buy cards,
  not to carry it unmodified into the late game.

## Changes from the Base Game

### Reputation & Color Alliances

Every color tracks its own reputation toward you, independent of the others. Reputation moves in
five tiers - **Partner** (80+), **Happy** (30-79), **Neutral**, **Unhappy** (-30 to -79), and
**War** (-80 and below) - and each tier changes real things: card-shop pricing (30% cheaper as a
Partner, 40% pricier at War), how often that color's mages target you, and whether you can enter
their towns at all. At War, ordinary towns are barred outright and a color's own Capitol charges a
steep gold toll just to set foot inside. Your standing with a color also shapes what you'll run
into on their land - roaming enemies skew noticeably weaker with a Partner or Happy standing, and
tougher the worse things get, down to War. Your own territory is always the safest place to fight,
regardless of anyone else's standing. Separately, the world as a whole trends toward tougher
roaming enemies the longer a run goes on, week by week, capped well short of an endless escalation
- so the opening weeks are the gentlest part of any run, by design.

### Territory Control & Color Defeat

Towns aren't static. Each color's territory expands or contracts around its castles and Capitol
over time, and controlling more territory brings real bonuses (or penalties, if it's not yours) to
travel speed. Push a color's reputation low enough and a Capitol duel becomes winnable - defeat a
color's castle for good and the consequences ripple outward: their remaining towns react, their
threat to you changes, and the balance of the whole map shifts.

### Time, Day & Night

The Forsaken Realms runs on a living clock. Every in-game day the world ticks forward: territory
spreads, mages march, shops restock on their weekly cycle, mines pay out on paydays (days 7, 14,
21, 28), guards draw their wages, quest timers count down (side quests fail after 20 days -
story quests never expire), and dungeons age toward their rotation. A HUD clock shows the time of
day and a Day/Week tracker keeps the calendar visible; a **Speed-Up** toggle fast-forwards time
when you're waiting on the world rather than exploring it.

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

### The Capitol

Once you personally own five towns, one of them can be upgraded into your Capitol - "Orazca." It's
a bigger, more defensible base with its own castle-sized map, two guards instead of one, and
access to buildings no ordinary town offers (Bank, Exchange, Archaeologist). Upgrading carries your
old town's accumulated reputation and buildings forward, and adds a flat reputation bonus on top
for good measure.

### Wood & Stone

Beyond Gold and Shards, you'll collect **Wood** and **Stone**. They come from winning duels,
from world-map resource sparkles and dungeon pickups, from chests and quest rewards, and - once
you own a town - as steady weekly income from a Lumber Mill or Stone Mine. They're spent on
building and upgrading structures, and on the Capitol upgrade itself.

### Buildings & the Economy

Towns you restore can be built up with dedicated economy buildings: **Gold/Wood/Stone/Shard
Mines** for steady weekly income, a **Trader** (any town, including your Capitol) for converting
Gold into Wood/Stone at a markup, a **Bank** and **Exchange** (Capitol-only - a Trader built at
your Capitol can also be upgraded into an Exchange, which trades at better rates and adds
Shards), an **Outlook** (expands your fog-of-war vision radius - 2x in a town, 3x in your
Capitol), a **Teleporter** network for fast travel between any two Teleporter-equipped locations,
and an **Archaeologist** who can be sent on week-long expeditions for a chance at boosters and
rare items. Guards can be hired to defend a town, paid weekly out of your own coffers.

### Progressive Set Unlocks

Not every card set is available from turn one. Editions unlock gradually as you play (scaled by
your starting race and difficulty), and the **Research Lab** in your Capitol lets you formally
unlock one you have collected enough of. Research takes a week per edition. (Don't confuse this
with the **Archaeologist**, who runs week-long expeditions for cards and items - a different
building doing a different job.)

**Your shops stock only what you have unlocked.** A card shop you build sells cards from the sets
*you* have unlocked - nothing else. Unlock more sets to stock your shops. In return they sell cheaper
than anyone else's: 25% under a neutral town's prices and 40% under an AI town's.

*Full detail — what you start with, where the other sets live, and exactly how to unlock them —
in [Card Sets](#card-sets-what-you-have-and-how-to-get-the-rest).*

### Dungeons That Actually Rotate

Dungeons and caves aren't fixed forever. Every visible one has a lifespan - it'll despawn on its
own after a few weeks (faster if you lose a fight there and it isn't a story target), and a fresh
one appears elsewhere to take its place, drawn from a much larger reserve pool than what's ever
visible at once. Clearing a dungeon out completely also retires it, making room for something new.
Side-quest-linked dungeons get extra grace: three failed attempts before they're gone for good,
and their lifespan extends automatically while a quest still points at them. Story-critical
locations never disappear.

### Ante, Tournaments & Hostile Lands

Ordinary duels are played for ante (on by default): each side stakes a card, winner takes it. If
you lose a card you value, a **Buy Back** option lets you repurchase it on the spot (priced by
rarity), and an escalating-cost **Re-roll** lets you swap out an ante you don't want to risk
before the duel starts — re-rolls won't repeat a card you just rejected.

Innkeepers run weekly **tournaments** (Draft, Sealed, Jumpstart) — these are entry-fee events
with prize support, **no ante at stake**. They also offer an opt-in "simulate the AI rounds" mode
if you'd rather not watch every AI match play out.

Beyond the tavern, remember the world itself takes sides: depending on your standing with each
color, their lands are more hostile or more friendly — travel speed, shop prices, town access,
and who their mages hunt all follow your reputation. And once you've built your Capitol, it hosts
an **advanced Arena** with a challenge tier (and champion fights) no ordinary town offers.

### Item Economy & Shops

Shops restock on a weekly cycle, re-roll for a shard cost if you want a different type entirely,
and price differently depending on who's buying: your own shops sell to you slightly under market,
AI shops charge you a premium. Rare items exist as genuine chase rewards, not just vendor filler -
several bosses across the world (including all-new content, see below) drop items nobody else
carries.

### World Standings & Mod Details

Two dedicated info screens track the state of the world and the mod itself: a World Standings
page showing every color's current reputation and territory at a glance (with a running history
graph), and a Mod Details page documenting the custom systems in-game, without needing this guide
open in another window.

## Early Game Advice

Your starting deck is a foundation, not a finished product - expect to add and cut cards
constantly for the first several in-game weeks. Prioritize a town of your own early: even a small
one gives you a Mine or two, a place to restock cards, and a foothold toward eventually owning
five (and thus a Capitol). Watch your reputation with the color you're camped nearest to - it's
much easier to stay Happy than to climb back from Unhappy once shops start charging you extra.

Push toward your Capitol as early as you reasonably can - five towns is a real early-game
investment, but it's the only place a Trader can be upgraded into an Exchange, on top of unlocking
the Bank and Archaeologist outright. Build a **Trader** well before then, even though its rates
are worse than an Exchange's - it's a guaranteed early way to turn spare Gold into the Wood and
Stone your buildings actually need, instead of waiting on Mines or dungeon loot alone. It's also a
good home for Gold you don't need sitting in your pocket: if you lose an ante duel and want the
card back, Buy Back costs real Gold, priced by rarity - a cost that bites hardest on Insane, where
you start with barely any cushion. Gold you've already converted into Wood or Stone isn't there
tempting you into a buy-back you hadn't planned for.

## Mid and Late Game Advice

By the midgame you should have a Capitol, at least one or two economy buildings generating
passive income, and enough reputation with your home colors to move through their territory
freely. The late game is about picking your fights: which colors you push toward War (and can
actually back up with a real deck), which Capitols you're strong enough to duel for, and how far
you push into the plane's hardest dungeons and boss fights - including the newest, hardest content
(see below).

## The World

The Forsaken Realms uses the standard five-color-plus-colorless biome layout: each color has its
own territory, its own AI-controlled Capitol and castles, and its own flavor of dungeon. Beyond
the color biomes, the world is dotted with named landmarks worth seeking out - ancient castles
like **Von Gant's Fortress**, **Emrakul's Castle**, and **Black Dragon Mountain**; strongholds like
the **Djinn's Palace** and **Necromancer's Study**; and quieter finds like **Grolnok's Bog** or the
**Secluded Elven Encampment**. Not every location is hostile - some are just worth the detour.

## Dungeon Guide, by Color

This isn't an exhaustive list (the world generates far more dungeons than any one playthrough will
see), but a starting point for what to expect in each color's territory.

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

Expect groves, forests, and the occasional cursed grow - **Garruk's Forest**, **Copper Host
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

## Notes on Difficulty

Insane difficulty roughly doubles the stakes of everything above: reputation swings matter more,
War states are more likely to actually happen, and the newest boss fights (several of which run
best-of-three) are tuned to be a real test even with a well-built deck. If you're finding a
specific new boss unfair, it's worth checking whether an easier difficulty changes that fight's
deck tier before assuming it's just you.


---

## Appendix: Mechanics in Detail

Everything above is the tour. This is the reference — the systems this plane adds that stock
Adventure mode has no equivalent for, written out properly so you can look one up mid-game rather
than guess. Nothing here is required reading.

### Color Reputation, in Detail

Five factions, one shared pool. **Your standing with the five colors always sums to zero** — the
five are a wheel, and every action pushes one way and pulls the others. You cannot be everyone's
friend; picking allies is the point.

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
pattern for each of its colors. Your **starting deck** seeds the wheel the same way, which is why
you begin already liked by some and disliked by others.

**The five tiers**, and what each actually does:

| Standing | Range | Card prices | Attacks on you | Other |
|---|---|---|---|---|
| **Partner** | 80+ | **30% off** | 75% less likely | Free Inn overheal; Rare blueprints unlocked |
| **Happy** | 30 to 79 | 15% off | 50% less likely | Spellsmith opens; Uncommon blueprints unlocked |
| **Neutral** | −29 to 29 | — | — | Blueprints purchasable at full price |
| **Unhappy** | −30 to −79 | 25% dearer | 15% more likely | No blueprints sold |
| **War** | −80 or worse | **40% dearer** | 50% more likely | Towns barred entirely; capitals charge a 500 gold toll; no Inn healing |

Two consequences worth planning around. At **War** you are locked out of that color's towns
completely — you can still buy your way into its capital, but at a toll and at the worst prices in
the game. And at **Partner** that color's Spellsmith, its Rare shop blueprints and a free Inn
overheal all open at once, which is a genuinely different game from Neutral.

**Town reputation is a separate thing.** Each individual town also remembers how you've treated
it, and that adjusts prices there by up to 10% either way. It goes up when you restore the town or
complete work for it, and it is per-town — unlike color reputation, it is not zero-sum and costs
you nothing elsewhere.

### Shop Blueprints — learning what you're allowed to build

Card shops have a **type** — Goblins, Instants, Azorius, Dragons and so on, around **215** of them
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

**Where the other ~210 went.** Nowhere — they all exist, and the rebuild menu shows you every one
of them, grayed out, with a live count of how many cards each could stock for you. Nothing is
hidden; you can see exactly what you're missing and decide what's worth hunting.

**Three ways to learn a new one:**

1. **Buy the shop you're standing in.** Walk into any shop whose type you don't know — including
   in a rival AI capital — and there's a **Buy Blueprint** button. Crawling other people's towns
   is the main acquisition loop, and it's why exploring is worth doing even when you're not
   shopping.
2. **Find one.** Mystery pickups and chests each carry a **25%** chance of a blueprint, delivered
   as a card you turn over.
3. **Destroy and rebuild** — a rebuilt shop rerolls among types you already know, so this
   reshuffles rather than expands.

- **The rebuild menu shows everything**, sorted **Available → Built → Locked**. Locked types are
  grayed rather than hidden, so you can see what exists to hunt for. Each entry shows how many
  cards it could actually stock for you right now — a number that grows as you research more sets.
- **Buy a blueprint where you find it.** Walk into any shop whose type you don't know — including
  in a rival AI capital — and there's a **Buy Blueprint** button. That's the main way to learn new
  types: exploring other people's towns.
- **Blueprints also drop**, at 25%, from Mystery pickups and chests. A drop arrives as a card you
  turn over, like any other reward.
- **Prices are in Shards, by tier**: 20 Common, 40 Uncommon, 100 Rare.
- **Reputation gates the rival capitals.** At one of the five colors' towns you need to be at
  least **Neutral** with them to buy anything at all, **Happy** for an Uncommon blueprint, and
  **Partner** for a Rare one. Standing also discounts the price — 30% off at Partner, 15% at
  Happy. Neutral towns have no standing to check, so they sell at the flat price.
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
- **Everything refreshes weekly** on its own, everywhere. **Re-roll Inventory** (a paid, once-a-week
  override) is player-owned only.
- Prices are 25% cheaper in your own towns and 25% dearer in an AI color town, before reputation.

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

**Where the other sets went.** Everything else is dealt out among six owners — the five colors and
a neutral pool. Those aren't locked away in the abstract: they decide **what the shops sell**.

- **Your own towns and Capitol** stock cards only from the editions *you* have unlocked. Unlock more
  sets to stock your shops - and they sell cheaper than any neutral or AI shop (25% and 40% under).
- **An AI color's town** stocks cards from that color's own share.
- **A neutral town** stocks from the neutral pool.

Your race's four editions are carved out of the AI shares deliberately, so they stay yours.

The practical consequence: **traveling is how you shop.** If you want cards from a set you have
not unlocked, you buy them in whichever faction's towns hold that set — which is exactly where
your standing with that color starts to matter.

**How to unlock a set properly.** The **Research Lab** in your Capitol is the formal route:

1. **Collect the cards first.** A set becomes researchable once you own **10%** of it (minimum 5
   cards). You'll get a popup the moment you cross that line. The Lab lists every edition with
   your progress as `(owned/needed)`.
2. **Pay 100 Shards** and start the research.
3. **Wait a week.** One edition at a time — you can't research two at once.

Once researched, that edition joins your unlocked pool permanently: your own shops start stocking
it, and it becomes legal in your own towns' Inn tournaments.

**Two things that don't wait for research.** Cards you own are always yours to play regardless of
which sets are unlocked — the restriction governs what shops *sell*, never what your deck may
*contain*. And loot, chests and enemy drops follow the territory they're found in, not your unlock
list, so exploring hostile ground is a real way to pick up cards you couldn't buy.

### The Bronze Challenge Coin

You start with three, and they have two separate uses.

1. **Free entry to a Jumpstart tournament** at an Inn.
2. **Ante ransom.** Lose an ordinary duel and you can hand the winner a coin instead of losing
   your anted cards — you get every anted card back *and* keep your gold. Beat that same enemy
   later and you take the coin back as part of the reward.

**One coin per enemy.** If a Fox already holds a coin of yours, the option won't be offered again
against Foxes until you've won it back. (Bosses, Arena fights and tournament matches never take
one at all.)

The gold coin is a free draft entry; the silver a free sealed entry.

### Inn Tournaments

Every Inn runs one, refreshed on a cooldown. The entry fee scales with the town's opinion of you.

- **Your own towns run on your own stock**: the card pool is your race's editions plus everything
  you've researched. It widens as you unlock more sets, and a tournament you haven't entered yet
  will re-roll itself when your pool changes. (If your pool is still too narrow to form a legal
  draft block, the Inn falls back to the wider pool rather than offering nothing.)
- **AI and neutral Inns** draw from the broader shared pool instead — which is a real reason to
  travel if you want to draft sets you don't own.
- **Re-roll** the offered tournament for 15 Shards; it's guaranteed to come back different.
- Tournament wins **don't** count toward your win/loss record, and don't push up the enemy tiers
  you meet in the world.
- A **ruined town's** Inn runs tournaments and nothing else — no card sales, no Potion of False
  Life.

### Territory, and Defending What's Yours

The five colors expand their borders over time and dispatch attack mages at towns — yours
included. Each color sends one every 2–5 days.

- **Your Capitol can only be targeted once a week by each color.** Once a color aims a mage at it,
  that color can't pick it again for 7 days — win, lose, or kill the mage on the road. With five
  colors that's a hard ceiling of five Capitol attacks a week.
- Mages **walk** to their target, so you can intercept one in the field before it arrives.
- **Guards** you've hired fight first. If they fall, you defend your Capitol in person in a forced
  best-of-three — **and losing that ends your run.**
- **Neutral towns defend themselves**: 15% base, 20% if the town still has a working Armory.
- Your standing with a color changes how likely it is to come for you — the exact weights are in
  the next section.

### How the AI Picks Its Targets

Every color runs the same routine, so most of it can be predicted.

**When.** Each color attacks on its own clock, waiting 2–5 days between mages. It can only have so
many mages on the road at once — 2 on Easy, 3 on Normal, 4 on Hard, 5 on Insane — plus one more
for every 11 / 10 / 9 / 8 towns you own (Easy through Insane). Take a color's capital and that cap
is halved.

**What it may attack.** Any wasteland town — untouched ruins, working neutral towns, and every town
you own — plus the ordinary towns of its two *enemy* colors on the wheel (White fights Black and
Red, Blue fights Red and Green, Black fights Green and White, Red fights White and Blue, Green
fights Blue and Black). It never touches an ally's towns, its own, or another color's capital.
Your Capitol is a special case — see below.

**Before the roll**, three things leave the list: any Ring City this color already went for in the
last 7 days, any town one of its mages is already marching toward, and your Capitol for 7 days
after that color last sent a mage at it.

**The pick.** What's left is sorted by distance to the color's *nearest* holding — castle, capital
or any town, so its reach grows with its borders — and the **5 nearest** go into a weighted roll.
The mage itself always sets out from the castle. Every candidate starts at weight 1, then:

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
rarity: Common 10%, Uncommon 30%, Rare 70%, Mythic 90%, again minus 5% for an Outlook. A
successful capture has a 20% chance to sack the town to ruins instead of keeping it. Working
neutral towns repel 15% of attacks on their own (20% with an Armory). Against a rival color, the
defending town's guard fights the same way and the same rarity roll then decides capture or a
revert to neutral. Ring Cities are captured or repelled, never sacked. A mage that reaches your
Capitol past both guards forces the duel described above.

### New Game+

A New Game+ is a **new game plus your collection**. You keep cards, decks, equipment, inventory
and every resource — gold, shards, wood and stone. Everything else resets to a fresh run: shop
blueprints, researched editions, research in progress, quests and story flags, color reputation,
statistics, blessings, and any Bronze Coins enemies were holding. Your challenge-coin purse is
topped back up to 1 gold / 1 silver / 3 bronze, keeping any surplus you'd hoarded.

Two things worth knowing before you press it: your **max life returns to the difficulty's
starting value** (accumulated bonuses are not carried), and an **in-progress tournament is
discarded**, including cards you've drafted but not yet banked.

### Smaller Things Worth Knowing

- **Hold Z** to move at 1.5x on the overworld. Time moves faster too.
- **Selling** cards pays a share of value set by your difficulty - 60% Easy, 50% Normal,
  25% Hard, 5% Insane (shown as the sale price on the new-game screen); the town's opinion of
  you adjusts it from there.
- The **blue dot** in the quest list marks the quest you're currently tracking.
- Enemy names carry their tier — "Clay Golem (Master)" — so you can judge a fight before taking
  it. Dispatched mages are capped at Adept in week 1 and Master in weeks 2–3.
- Settings has an **"avoid restricted edition art"** toggle (on by default) that steers card art
  away from the black-and-white printings.
- The game only truly ends when you hold **no towns and no neutral town exists anywhere** — or if
  you lose a Capitol defense.

---

*This guide covers the state of The Forsaken Realms as of 2026-08-31. See `MOD_CHANGELOG.md` in
the repository for the full history of how the mod got here, if you're curious.*

## Support & Community

The Forsaken Realms is free and open source. If you're enjoying it:

- **Join the Discord** for feedback, bug reports, and balance talk: https://discord.gg/TTRPKc9HYJ
- **Support development on Ko-fi**: https://ko-fi.com/thesaguy
