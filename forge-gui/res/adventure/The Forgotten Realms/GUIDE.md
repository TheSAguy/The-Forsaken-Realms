# The Forgotten Realms — Player Guide

*A custom Adventure-mode world for Card-Forge/forge.*

This guide walks through what's different about The Forgotten Realms compared to the base
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

---

## Introduction

The Forgotten Realms is built on top of stock Forge Adventure mode, but changes how the world
itself behaves: towns take sides, dungeons come and go, your reputation with each color actually
means something, and there's a real path from "wandering duelist" to "ruler of your own Capitol."
None of it requires you to play differently deck-wise — it's a layer on top of the normal
duel loop, not a replacement for it.

**Fair warning: this is a HARD game, and that's intentional.** You start with little, the world
doesn't wait for you, and losses have teeth. Digging yourself out is the fun — but go in knowing
the early game is meant to be a fight.

*The game is currently tested on Windows PC only.*

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
steep gold toll just to set foot inside.

### Territory Control & Color Defeat

Towns aren't static. Each color's territory expands or contracts around its castles and Capitol
over time, and controlling more territory brings real bonuses (or penalties, if it's not yours) to
travel speed. Push a color's reputation low enough and a Capitol duel becomes winnable - defeat a
color's castle for good and the consequences ripple outward: their remaining towns react, their
threat to you changes, and the balance of the whole map shifts.

### Time, Day & Night

The Forgotten Realms runs on a living clock. Every in-game day the world ticks forward: territory
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

Beyond Gold and Shards, you'll collect **Wood** and **Stone** - dedicated resources fed by Forts
(wood) and Caves/dungeons (stone), and by the buildings you construct once you have a town of your
own. They're spent on building and upgrading structures, and on the Capitol upgrade itself.

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
your starting race and difficulty), and you can actively research new editions at an Archaeologist
or through play to speed things up.

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

The Forgotten Realms uses the standard five-color-plus-colorless biome layout: each color has its
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

*This guide covers the state of The Forgotten Realms as of 2026-08-21. See `MOD_CHANGELOG.md` in
the repository for the full history of how the mod got here, if you're curious.*

## Support & Community

The Forgotten Realms is free and open source. If you're enjoying it:

- **Join the Discord** for feedback, bug reports, and balance talk: https://discord.gg/TTRPKc9HYJ
- **Support development on Ko-fi**: https://ko-fi.com/thesaguy
