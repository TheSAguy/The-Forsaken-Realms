## The Forsaken Realms — v1.10 — Know Your Enemy

**This is a big balancing update, and a new game is recommended.** Old saves load and play, but most of
what changed here is decided when a world is made or when an enemy first spawns: the 197 new monsters go
into the spawn tables of a world being built, and a character who has already cleared half the map will
not see much of them. Nothing is lost by carrying a save forward — it simply will not show you the update.

This is probably the last release for a while, barring a game-breaking bug. **What would help most now is
balancing feedback**: what felt too easy, what felt unfair, where the gold or the shards ran out, which
fights you could not win and which you never had to think about. Rough notes are fine.

## New: 197 new enemies

The number of monsters that actually roam the world goes from 874 to **1,071** — a 23% increase, out of a
catalog of 1,984 entries counting bosses and arena-only fighters. Every one of the new ones has its own
sprite, its own themed deck built to its colors and rank, and a real place in the world:

- **On the overworld** they join the biome roster of each of their colors, under the same week-based rank
  pacing everything else follows.
- **In the Wasteland**, the undead, horrors and constructs below Archmage join the colorless roster.
- **In tournaments**, each AI capital's arena pool gains up to 8 new Masters and 5 new Archmages of its
  own color.
- **In caves**, the 78 generated dungeons re-picked their roamers from the new rosters.

The mix by color came out even on purpose — a rank or a color should not be the thing that decides what
you meet. Mono-colored, two-color and three-color decks are also roughly balanced across the new set.

## Changed: whose land you are standing on decides what attacks you

This is the heart of the balancing pass. There are now **seven kinds of ground**, ordered from safest to
most dangerous, and each one shifts what ranks roam it. Your own land is the friendliest; an AI color's
own land is more dangerous than the ownerless Wasteland, and grows much worse as that color's standing
with you falls.

| Where you are | Apprentice | Adept | Master | Archmage |
|---|---|---|---|---|
| Your own territory | 46% | 29% | 24% | 1% |
| A **Partner** color's land | 39% | 31% | 27% | 3% |
| A **Happy** color's land | 31% | 28% | 28% | 13% |
| The Wasteland (no owner) | 22% | 24% | 30% | 24% |
| A **Neutral** color's land | 16% | 25% | 33% | 26% |
| An **Unhappy** color's land | 11% | 20% | 35% | 34% |
| A color you are **at War** with | 2% | 13% | 38% | 48% |

Those are the late-game figures (week 21 and after) — the week pacing still comes first, so early weeks
stay early everywhere. Hostile ground can no longer conjure a rank the calendar has not reached yet; it
can only tilt the mix it is allowed to roll.

**Attacking mages follow the same ladder.** The rank of the mage a color sends at your towns is now scaled
by its standing with you: a color you are at **Partner** with will very rarely send an Archmage (about one
attack in two hundred, down from one in twenty), while a color at **War** sends them two and a half times
as often as a neutral one.

**And a color that hates you shows up more often.** Wherever a monster of a given color could appear, its
odds now scale with your standing with that color: **three times as likely at War, a third as likely at
Partner** (Happy 0.6x, Unhappy 1.7x). This changes *which* colors you meet, not how many monsters there
are or how strong they are — a multicolored creature averages its colors, and colorless ones are
unaffected. Make an enemy of black and you will be fighting black.

Along with the two existing reputation levers that already worked this way — a Partner color's monsters
never wander across your border at all, and a Partner color is a quarter as likely to aim an attack at one
of your towns — being on good terms with a color is now worth something concrete on the map.

## Changed: a monster's loot comes from its own color's sets

A monster's card drops are meant to be restricted to the sets of its color, which is how you find a
color's cards before you research them. In practice 88.6% of all roaming enemies were exempt from that
rule by accident and dropped cards from any set at all. Now only bosses and the arena/event-only fighters
are exempt, as intended. You will see fewer deck-card drops early on and a great many more of the color's
own cards — and finding a set you have not unlocked yet means something again.

## Changed: an enemy's size tells you its rank, properly this time

v1.09 put every enemy on a size ladder by rank. A few sprites still read wrong — a Master could be drawn
smaller than an Apprentice when its idle pose happened to be a crouch. The size is now measured from the
sprite's real body across its first idle and walk frames, capped so one unusual pose cannot inflate a
monster, so a rank reads the same whatever the animation is doing.

## Changed: smaller balance and rule changes

- **Defeat costs a flat amount of gold**, not a percentage of what you carry: **50 / 100 / 150 / 200** on
  Easy / Normal / Hard / Insane, and all of it if you carry less. Gentler on a heavy purse at the hard
  settings, harsher on a light one at the easy settings. A Bronze Coin at the ante prompt still waives it.
- **Archmage attackers come from their own color's roster.** Every color's Archmage attack used to be
  drawn from the whole catalog, so a red castle could send a blue one.
- **The colorless mix-in on your own land works again** — a share of the spawns on your territory are the
  Wasteland's colorless monsters, as designed. It had never actually fired.
- **Cave champions take a slot again.** One cave in four hosts an arena-exclusive fighter standing in for
  one of its usual roamers; a data mix-up had quietly reduced the candidates to none in most caves.
- **Three enemy pools were drawing from the wrong list.** A town under assault is now defended by a real
  roaming creature of that town's own color (an off-color arena legend could turn up defending an Adept
  town); an attacking Archmage comes from a pool of 19–26 per color instead of the same handful; and the
  Chest's Illegal Arena bracket now draws from **every** Archmage in the game, the new ones included.
- **Your own land has more to meet.** 36 of the new monsters joined the player-territory roster, which
  also stocks the dungeons standing on your land.
- **A dungeon on land that changes hands takes on its new owner's creatures** — the re-theme that existed
  for the overworld now reaches the dungeons standing on it, without touching scripted or story encounters.
- **Under-attack markers on the minimap** no longer stack on top of each other: one marker per town, with
  a count when several mages are inbound.
- **Resource glyphs everywhere.** Gold, shards, wood and stone appear as their symbols in every reward and
  cost line, instead of "375g".
- **The Duplicate chest shows you the card it copied**, and a random rare from your race's own sets is
  hidden in the starting camp.
- **A new quest step** between founding your Capitol and hiring a guard: prove the banner by completing
  three quests, for 100 stone.
- **The loading screen shows the TFR medallion** while you are on your own land, instead of a color symbol.

## Fixed

- **A save that fails to load no longer leaves the game half-loaded.** A corrupt or truncated save used to
  overwrite the running world as it read, so a failed load left a broken mixture of two games that could
  then be saved over the good one. The world is now snapshotted first, restored if anything goes wrong,
  and saving is blocked until you load or start something that worked. Quick-load (F8) tells you when a
  load failed instead of appearing to do nothing.
- **New Game+ resets the ledger**, so week one no longer opens with the previous run's income and expenses.
- **Roaming guard fixes:** a guard no longer stalls at low frame rates while the mage it is racing keeps
  moving at full speed; a duel result that lands late can no longer resolve the *next* interception; the
  "change rank" dialog now charges the shard part of the wage difference it promised; a dismissed or
  released guard's deck is rebuilt into an empty deck slot instead of coming back as loose cards; the deck
  picker greys out the deck you are actively using; and a mage arriving with no deck no longer benches your
  guard for thirty days.
- **A render-loop error is now reported once per distinct problem** instead of being silently swallowed.
- Starter-deck set filtering on the New Game screen no longer fires in modes that do not use it.

## Engine

Forge's 2026-09-11 daily, up from the 09.09 daily v1.09 shipped on. The AI's attack planning and spell
choice changed upstream, so duels may play a little differently; card scripts and editions are current to
the 11th of September, including the new FRA cards.

**Saves from v1.09 load.** The save format is unchanged. As above, a new game is still recommended — the
new monsters and the territory rebalance land properly in a world that is built with them.

## 📱 Android

**Install:**
1. On your Android device (Android 8.0+), download `forsaken-realms-1.10-signed-aligned.apk` from the
   assets below.
2. Tap the downloaded file and allow your browser/file manager to install unknown apps when
   prompted (the game is signed by us, not the Play Store).
3. Grant the storage permission the app asks for — it stores the game data it downloads.
4. On first launch, tap **Download** when offered the resource files (~180 MB — use Wi-Fi). The app
   restarts itself when finished. After that it plays offline.
5. Works alongside the official Forge app — different app, different data. Updating over any earlier
   version keeps your saves.

**Screenshots of bad layouts are the other thing that would help most.** Every screen was laid out for
portrait without a device to check it on. If a menu is cut off, overlapping or unreadable on your phone, a
screenshot on Discord (https://discord.gg/TTRPKc9HYJ) with your device model and Android version is the
fastest way to get it fixed. `assets.zip` in the file list is downloaded by the app automatically — you
don't need to grab it yourself.
