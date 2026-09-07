## The Forsaken Realms — v1.06 — Deeper Caves

v1.05 gave you a world to fight over. v1.06 is about what happens once you are fighting in it: **78
hand-cut caves**, thirteen for every biome, each with its own name, its own patrols and a wildcard
monster that may be far above its weight class. Around that, an Arena that finally plays fair, a
long-session memory problem that had been quietly growing for months, the loss-and-reload bug that
made dungeons vanish, and a rules engine brought up to Forge's 2026-09-06 daily.

---

## ⚠ The caves need a New Game (or New Game+)

**Your existing save still loads,** and every fix and balance change below applies to it. But the new
caves are placed when a **world is generated**, so an old world simply does not have them. A **New
Game+** is enough — it regenerates the world while keeping your cards, decks, items and resources.

---

## New: 78 caves, thirteen per biome

Every biome — white, blue, black, red, green and the colorless wasteland — gets thirteen new caves
with names of their own: Frosthollow, Drowned Cistern, Gallows Cave, Devil's Chimney, Frogsong
Grotto, Hollow of Echoes, and seventy-two more.

- **They are cut, not stamped.** Each map is carved with the tileset's own corner rules — a main
  chamber with rock islands, one to three side chambers joined by corridors, a three-wide mouth,
  bones and rubble on the floor — on a biome-appropriate base: snow, ice, violet stone, embers,
  swamp, dirt.
- **They get deeper.** Caves 1–5 of a biome are shallow (three patrols), 6–10 deep (four), 11–13
  rich (five). Loot scales with depth: a chest placed as far from the mouth as the map allows,
  gold, building stone, then wood and mana shards in the deeper ones, and a second gold pile plus a
  booster pack in the richest.
- **Every cave has a wildcard.** One monster per shallow cave (two in the rich ones) has its tier
  rolled completely at random, so a Mythic — Aegis Paladin, The Reaper, a Hydra, a Young Red Dragon
  — can be waiting in a shallow cave. The rest are Common and Uncommon roamers.
- **They move faster.** Cave patrols carry a speed bonus over their overworld cousins, and patrol
  four to six waypoints rather than pacing a line.

## New: caves wear their biome

Eighty-one caves used to share one generic map icon. They now draw from 48 new per-biome icons —
gray stone and ice in the north, purple crystal in the black lands, red rock and gold flecks in the
mountains, moss and sprouting browns in the forests. **Existing saves get the variety too**: a cave
whose icon was locked in before this release has its variant re-derived from its own name and
position, so it stays the same cave every time you look at it.

## New: looted caves clear out sooner

Strip a rotating dungeon of every last pickup but leave the monsters standing, and the time before
it cycles off the map is **cut in half**. A place you have already emptied stops holding a slot that
a fresh one could use. Dungeons an active quest points at are left alone.

## New: the Rally rune

A rune that takes you straight to a town of yours that is **under attack** — and if several are
besieged at once, to a different one each time you use it, cycling before it starts over. One mana
shard per use, 1,600 gold at the Quick Travel Mart. If nothing is under attack it stays quiet and
refunds the shard.

It is also the reward for completing **"Hire a guard"** in the main quest now, with a proper briefing
from the guard you just hired. (Quests already in your log keep their old reward — a new run gets
the rune.)

## New: the Torch pulse

Torches were decoration once you had explored a little. Using a **Torch or Grand Torch** now costs
one mana shard and flares your vision to **three times its current radius for two seconds** — a wide
sweep of the dark that shows you what is moving out there. Everything it touches stays explored
afterwards. On the world map with fog on only; anywhere else the shard is refunded.

A Torch on Insane opens 4 tiles to 12; a Grand Torch on Normal reaches the 24-tile cap.

**This one reaches your existing save.** Items were previously frozen into a save exactly as they
were the day you picked them up, so no balance change to an item ever reached a game in progress —
your two torches would have stayed inert. Saved items are now re-read from the catalog on load.

---

## Changed: the Arena plays fair

If you have fought an Adept in the Arena and wondered how they had a tournament deck, this is why:
every Arena opponent on Hard and Insane was being dealt a random deck from Forge's 786 genetic-AI
tournament lists, regardless of who they actually were. That is gone. **Arena fighters play their
own decks now**, the same ones they would bring to a fight in the wild.

That fix reaches **every** arena, including the Illegal Arena a Chest can open — which is where a
tester met an opponent that played Mana Vault into Mana Crypt on turn one and then did nothing for
ten turns.

The brackets changed with it. At the **five AI capitals** and at a **player Arena still at level 1**,
the seven seats are filled **50% Adept / 35% Master / 15% Archmage** — no Apprentices at all. A
player Arena upgraded to level 2 keeps the open field, as does the Chest's arena.

## Changed: one fewer attacking mage

Each color now fields **Easy 1 / Normal 2 / Hard 3 / Insane 4** attacking mages before the bonuses
for the towns you hold and the colors you have defeated (and halved while that color's capital is
lost). It was three across the board.

## Changed: one Trading Post or Exchange per town

Upgrading a Trading Post to an Exchange used to leave the town able to build a second Trading Post.
A town now holds one or the other, never both.

## Changed: leaving a town lights it up

Walking out of a town, your Capitol or a castle replays the bright discovery burst you got the first
time you found it, then settles back. Dungeons and caves still leave quietly.

---

## Fixes

**The big one — long sessions no longer bleed memory.** Two leaks were freeing nothing: the minimap
re-bake threw away a 2800×2800 image without releasing it (**~31 MB every time it ran**, and it ran
on every dungeon rotation, guard refresh, capture and Capitol upgrade), and every ground tile ever
composited leaked (**~100 KB for each tile you walked**, multiplied by the fog-of-war repatch). A long
session could shed hundreds of megabytes. Both are fixed, along with map textures that were never
released when you entered a map.

- **Losing in a dungeon and reloading no longer makes the dungeon disappear.** A loss schedules its
  consequences — the despawn, the life loss, the teleport — to run after the death animation. Loading
  a save inside that window left them armed, and the *loaded* game paid for a loss it never had, the
  next time you entered a dungeon. Loading now cancels anything left pending. (The same window could
  hand a reloaded game a reward screen it had not earned.)
- **A sacked town of yours reads as lost.** It stayed flagged as player-owned, so it was re-expanded
  as your land the next day and still counted as a Rally target.
- **The Ante Re-roll actually costs shards.** They were being refunded to you at the end of the match.
- **Your saves are pinned.** Three more save-bound classes got explicit version pins, so a future
  update adding a field to one of them can no longer void an old save.
- **The 288-pixel monster.** The Arcane Golem in the Mages' Fort was drawn eighteen tiles tall. A
  survey of all 739 enemy sprites found three more at the wrong scale — Shorikai, Dementia Beast and
  Blech — all now sized like their siblings.
- **45 monsters that were not the monsters they claimed to be.** Misspelled names in map data fell
  back to "spawn a random roamer for this biome". The Skep hive is Slivers again.
- **56 rewards that never dropped** have been removed from the enemy tables — they named items that
  do not exist, so the engine printed a warning and moved on. Slobad's card reward works now.
- **Enemies keep the decks they were built with.** Separately from the Arena, with the "Generate
  LDA Decks" setting on, any enemy with more than 16 base life - most of the roster, including
  every Mythic and every hand-built legend deck - discarded its own deck for a randomly generated
  archetype deck, one in ten of them Legacy. That is switched off for this world now.
- **A pop-up no longer follows you out of a dungeon.** Collect gold or shards and leave within a
  few seconds, and the little "+2 Shards" text used to reappear over the *next* place you entered.
  Display only - the resources were always credited once, at pickup.
- **Winged enemies fly.** 51 of them - the young dragons, drakes and wyverns, the Dragonkin, the
  Vampire Bat and Fluttering Pixie, plus Bone Dragon, Fire Dragon, Nicol Bolas, Avacyn, Kaalia and
  others that had been left grounded next to identical enemies that flew.
- **Android: the Armory's hire-guard buttons no longer hide behind the item cards** in portrait.
- Gold, wood and stone picked up in dungeons make a sound, like they do outside. A new Horror shop
  and a fixed shop sign. Mage towers and wizard forts have their own icons.

---

## Engine

Forge updated to the **2026-09-06 daily**. Two changes affect every duel you play:

- The AI **no longer blocks with creatures that die before they deal damage**.
- **Shared fetch effects work** when the other players have nothing to retrieve.

Also included: Pauper bans for Zeta, Reality Fracture edition updates, and card-script fixes for
Exhume, Seven of Nine and Perfected Theory.

**Saves from v1.05 load.** This release swaps the rules engine under an existing game, so it is worth
a careful first session either way.

---

## Credits

Enemy art from the free sample packs listed in `CREDITS.md`; the cave map icons are the project's
own. The caves in this release are generated from the base game's tilesets.

## 📱 Android

**Install:**
1. On your Android device (Android 8.0+), download `forsaken-realms-1.06-signed-aligned.apk` from the
   assets below.
2. Tap the downloaded file and allow your browser/file manager to install unknown apps when
   prompted (the game is signed by us, not the Play Store).
3. Grant the storage permission the app asks for — it stores the game data it downloads.
4. On first launch, tap **Download** when offered the resource files (~180 MB — use Wi-Fi). The app
   restarts itself when finished. After that it plays offline.
5. Works alongside the official Forge app — different app, different data. Updating over v1.03, v1.04
   or v1.05 keeps your saves.

The portrait Armory bug testers reported in v1.05 (hire-guard buttons behind the item cards) is fixed
in this build. Please keep reporting on Discord (https://discord.gg/TTRPKc9HYJ) with your device model
and Android version. `assets.zip` in the file list is downloaded by the app automatically — you don't
need to grab it yourself.
