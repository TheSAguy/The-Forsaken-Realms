> **DRAFT for review (2026-09-10) — delete this block before publishing.**
> Covers rounds 137-164, everything since v1.08. Before this goes out: (1) rounds 162-164 have not been
> playtested yet, and the standing release rule is the user's pass first; (2) the other standing rule is to
> take the upstream engine update first, as its own round, then re-test; (3) stamps to bump: `modVersion`
> 1.08 → 1.09 and `modVersionDate` in config.json, `tfr.version` 1.08 → 1.09 and `manifestVersionCode`
> 10800 → 10900 in the Android pom; (4) two features from this range are deliberately NOT in these notes
> because they do not work yet — the hostile-terrain "stranded legends" spawns and Arzakon's chest fallback;
> (5) the Armory storage shipped on three default decisions you may want to reverse (one storage for the whole
> character rather than one per town, no Level 2 gate, a downed guard's equipment returns rather than being
> forfeited with the deck); (6) the release tag must be `tfr-v1.09` and the APK `forsaken-realms-1.09-signed-aligned.apk`.

## The Forsaken Realms — v1.09 — The Roaming Guard


## New: Roaming guards

Until now a town defended itself with a hired garrison and a hidden dice roll. A **roaming guard** is
something else: a champion you hire at your Capitol's Armory, hand **one of your own decks**, and send out
onto the world map. When an enemy mage sets out for one of your towns, the guard races it there, and if it
arrives first the attack is decided by **a real duel** between the guard's deck and the mage's. You can
watch that duel or let it play out in the background; it is the same match either way, at the same life
totals.

| Rank | Starting life | Speed | Wage per week |
|---|---|---|---|
| Apprentice | 12 | 34 | 30 gold |
| Adept | 16 | 36 | 60 gold |
| Master | 22 | 38 | 100 gold + 5 shards |
| Archmage | 30 | 40 | 150 gold + 15 shards |

You may keep **up to four**. Wages are the Normal-difficulty figures; Easy pays 0.75x and Insane 1.5x, as
with every price in the game. A guard's speed is measured against yours: an Archmage matches you exactly,
and each rank below loses two.

**It is a race, not a chase.** The guard runs to the town, not after the mage, so it wins only if it can
get there first. Most attackers are slower than any guard, but a Mythic mage outruns all of them, and the
intended answer to that is a **teleporter at the threatened town**, which lets the guard skip the race
entirely. Teleporters got much cheaper in this release for exactly that reason (below).

**Your cards travel with the guard.** Giving a deck to a guard takes those cards out of your collection and
empties the deck slot; the guard remembers the exact list and hands it back when you take the deck back.
The cards come home on a normal dismissal and if the guard leaves because you could not pay it. They are
forfeited only if you dismiss a guard that is **out of commission** — the price of not waiting out its
thirty days of recovery, or paying 100 shards to heal it at once. Dismissing now asks you first and says
which of those it is about to do.

Other things worth knowing:

- **Engagement orders.** Two rows of checkboxes on each guard's page decide which attackers it will go
  after, by rank and by color. Both must allow the attacker before the guard is sent.
- **A guard that loses lets the mage through**, and the town then defends itself exactly as it always has.
  A guard that wins breaks the attack and walks home to the Capitol.
- **An unarmed guard is not paid** and is never sent out. Arm it before you expect anything of it.
- **A guard fights with its own deck and its own gear** — never with your equipment or your blessing.
  Guards do not fight for ante either; ante stakes your cards, and you are not in that fight.
- Guards appear on the minimap as **green dots**, and on the new Attacks view (below) as lines showing
  where each one is walking.

## New: the Armory storage, and equipment for your guards

Every Armory you own has a **Storage** button. It holds spare equipment — anything with a slot that you are
not wearing — and you can put things in or take them out at any Armory; it is one store for the whole
character. At the Capitol, a roaming guard's page gains an **Equipment** button that dresses the guard from
that store, one item per slot, the same slots your own character has.


## New: starter decks that belong to your race

Your opening deck used to be built from fourteen expansions that had almost nothing to do with the race
you picked. It now comes from **your race's own four sets** — a Kor opens with Zendikar-block cards, a
Phyrexian with Mirrodin-block ones, and neither ever sees the other's. Every difficulty is **mono-color**
now: you pick a color at new game and you get that color, because a two-color deck drawn from four sets
would have unreliable mana, and unreliable is not the same as weak.

It is still a *starter* deck: commons and uncommons only, creature-heavy, curve stopping at six. It should
hold its own against Apprentice opponents and struggle against a Master.

| Difficulty | Constructed deck |
|---|---|
| Easy, Normal | 40 cards (17 lands) |
| Hard, Insane | 60 cards (24 lands) |

**Standard** is no longer a pack-opening mode — ten of the sixteen races had no sealed template at all in
their sets, which was a failure rather than a weak deck. It now builds a looser deck with 23 lands and
**exactly one rare**, and its set dropdown lists your race's four sets plus "(All my sets)". **Pile** stays
the two-color, rare-heavy mode, but its rares now scale with difficulty — **7 / 4 / 2 / 1** on Easy /
Normal / Hard / Insane — where before Easy and Normal got nine each and Hard and Insane got none.

Fixed along the way: a "no rares" deck full of rare printings, five copies of a card in one deck, and
starter decks the game itself called illegal.

## Changed: an enemy's size tells you its rank

Every enemy is now drawn at a size that says **what it is**, and then a little bigger for a higher rank.
Sprites were rescaled onto a ladder by subject — a person is one tile tall, a critter three-quarters of
one, a large monster two, a huge one three — so a goblin warlord is no longer the smallest thing in the
game (he was: 4.8 pixels). On top of that, an Adept is drawn at full size, an Apprentice slightly smaller,
a Master and an Archmage slightly larger, by a fixed few pixels, so a boss does not balloon. Roaming guards
carry the same cue, so a Master guard and the Master mage it is racing read as the same rank. Deliberately
tiny creatures — the ladybug, the cat, the bat, the crab — are untouched.


## New: the minimap says more

- **Guards:** each of your towns is labelled with its garrison, and with any roaming guard on its way there.
- **Attacks view:** a fifth view in the cycle, drawing a line from every attacking mage to its target town
  in that mage's color, and a green line for each roaming guard — bright on its way to a town, dimmer on
  its way home.
- **Zoom out much further** than before, and labels now stay on their towns as you zoom. Any label that
  cannot find room at the current zoom is hidden rather than parked somewhere untrue.

## New: the Balance Sheet

From the Bank, the Exchange or the Standings page: this week and last week, income and expenses in four
lines each (mines, bank interest, everything else; local guards, roaming guards, everything else), the
net, what is on hand, and what the **next payday already owes**. It records what actually moved, so a lost
duel's gold shows up and a bank deposit does not.


## Changed: smaller balance and rule changes

- **Teleporters.** The Capitol hub costs **100 shards** (75 / 100 / 125 / 150 by difficulty; it was 200),
  town teleporters are **10 shards flat**, and the network takes **five** towns instead of four.
- **A captured town keeps nothing.** When the AI takes one of your towns, its buildings, resources and
  reputation go with it, as when it takes a neutral one; taking it back is a fresh start. This also closes
  an exploit where a reverted town handed back everything you had ever built there, for free.
- **The Arena turns you away** for the rest of the week once you have won there, instead of letting you
  fight a whole bracket for nothing. Normal and Challenging still count as separate venues.
- **Three jackpots retuned.** Meloku paid 100,000 gold, 1,000 shards and the entire Power Nine for one chest
  duel. Now 1,500 gold, 200 shards and two random power cards. Jodah drops to 1,500 gold, 150 shards, Black
  Lotus, Mana Crypt and three cards from his alt-art staples; Arzakon to 1,200 gold and 150 shards.
- **The "Find the Capital" quests pay reputation with your own colors** (+2 with each color of your
  starting deck). They were paying none, and the enemy-color cards in the reward read as the wrong favour.
- **Two gauntlets grant a second hand slot.** The Sinistral and Dextral Gauntlets (5,000 gold, Mythic,
  -2 life each) each unlock a second slot for the other hand while worn.
- **Cave champions.** One cave in four holds an arena-exclusive enemy standing in for one of its usual
  roamers — 679 enemies that were previously only reachable through arenas.
- **War champions.** While you are at war with a color, twenty-five named Archmage-tier champions roam its
  land, five per color, taking a fifth of its spawns between them.
- **Commander-only cards that did nothing.** Acorn Amulet now grants Nut Collector and Helm of Myth grants
  Myth Realized (their old cards only worked with a commander); a reward that gave Jeska, Thrice Reborn
  gives Jeska's Will; two Medals that handed the AI Command Tower — a dead land without a commander — hand
  it Gemstone Mine instead.
- **The Colorless rune is the Homeward rune.** A copy you already own keeps its old name and keeps working.


## Known issues

- Beat a cave champion and it can be back in another spot of the same cave when you return.
- A **watched** guard duel counts toward your own reputation and statistics; a simulated one does not.
- A draw, a stalled fight or quitting out of a watched guard duel is scored as the guard losing.
- While one guard fight is simulating, a second attack arriving elsewhere goes unopposed.
- On the Balance Sheet, hire fees, upgrades and heals are lumped into "Everything else", and the bank
  interest line runs a day early.
- The deck picker still runs off the screen for players with more than eight or nine built decks.
- War champions stop appearing once you pass 150 wins.

## Engine

Unchanged from v1.08 — Forge's 2026-09-06 daily. (Replace this line if an engine merge is taken before
release.)

**Saves from v1.08 load.** Nothing in this release changes the save format; a save from before the roaming
guards simply has none, and one from before the Armory storage has an empty store. The one visible quirk:
a Colorless rune you already own keeps its old name.

## 📱 Android

**Install:**
1. On your Android device (Android 8.0+), download `forsaken-realms-1.09-signed-aligned.apk` from the
   assets below.
2. Tap the downloaded file and allow your browser/file manager to install unknown apps when
   prompted (the game is signed by us, not the Play Store).
3. Grant the storage permission the app asks for — it stores the game data it downloads.
4. On first launch, tap **Download** when offered the resource files (~180 MB — use Wi-Fi). The app
   restarts itself when finished. After that it plays offline.
5. Works alongside the official Forge app — different app, different data. Updating over any earlier
   version keeps your saves.

This release includes a portrait-layout pass over every screen added since v1.08, done without a device.
If the Armory page, the minimap's bottom bar or the guard screens look wrong on your phone, a screenshot
on Discord (https://discord.gg/TTRPKc9HYJ) with your device model and Android version is the fastest way to
get it fixed. `assets.zip` in the file list is downloaded by the app automatically — you don't need to
grab it yourself.
