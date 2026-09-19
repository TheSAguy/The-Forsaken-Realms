## The Forsaken Realms — v1.12 — Reward Balancing

This one is about what a victory is worth. Duels pay fewer cards and better ones, what they pay in gold and
resources now depends on who you beat, and the world gives you more reasons to go looking for a particular
fight. **Your v1.11 save carries straight over**; the two world-generation changes apply to new worlds only.

## New

- **The card budget.** A win pays cards by the enemy's **rank**: 1 / 2 / 2 / 3 for an Apprentice, Adept,
  Master or Archmage. Your **first victory** over each kind of enemy is the big one: 2 / 3 / 4 / 5, best
  rarities first, plus one extra non-land card from its deck. Masters and Archmages always keep their best
  cards; a short list is topped up from the enemy's own deck, mostly with commons. Gear that adds reward
  cards still adds them, and Easy pays one more. Expect roughly half the cards per win you were used to -
  and the hunt for a particular card to matter again.
- **The resource purse.** Gold, Shards, Wood and Stone now follow the enemy's **color**. Every win pays
  gold plus, usually, one bonus resource: **White** leans to more gold, **Blue** to Shards, **Red** to
  Stone, **Green** to Wood; **Black** is balanced, a two-color enemy leans both ways, and colorless ones
  are balanced with a smaller purse. The purse grows with rank and difficulty, is half again as large on a
  first victory, and a quarter larger when you beat an enemy that had the better record against you. The
  old hidden rule that turned a quarter of your duel gold into wood or stone is gone.
- **Legend sightings.** When one of the realm's oversized legends appears you are told which one and in
  which direction, a gold dot marks it on the minimap and the map, and it stays three times as long as an
  ordinary roamer.
- **Your Capitol draws fire.** The moment it stands, every color still in the game sends a war mage, and
  each may keep one more in the field for as long as it stands.
- **Two kinds of quest.** "Sweep the Wilds" - clear three dungeons or caves - is offered at every town's
  board. Each color's towns now ask you to defeat **five** enemies of an opposing color (it was one), for
  600 gold and twice the reputation - and those kills count wherever they happen, out in the wilds or down
  in a dungeon. The quest log shows progress on any counted quest, "(2/5)".
- **Teleporter towns on the overworld.** A town or Capitol of yours with a Teleporter wears a small
  shimmering portal at its lower right, opposite the guard icons.
- **Arena countdowns.** The map's Reputation view lists, for your Capitol and each AI capital you have
  found, whether its Arena is ready or how many days remain.
- **Smaller additions:** resource pickups on the overworld float a "+12 Wood" label; the Mystery diamond
  can bless you with +3 starting life for your next duel; roaming guards with no assignment stroll
  around outside the Capitol.

## Changed

- **A ruined town's Inn is boarded up** until you restore the town - restoring it is the only thing a
  ruin offers. (A tournament you had already entered there can still be finished.)
- **The Coin Challenge costs Shards as well** on Normal, Hard and Insane: 5 / 10 / 15 on top of the gold.
- **Harder worlds start with fewer towns.** A new world on Normal / Hard / Insane has 1 / 2 / 3 fewer
  functioning Neutral towns and 6 / 9 / 18 fewer ruins.
- **Dungeons turn over faster.** A dungeon or cave stays 20 to 40 days (it was 20 to 60), and one you strip
  of its loot while its guards still stand keeps only a quarter of the days it had left (it was half). A
  dungeon already on your map with more than 40 days left is pulled in to 40.
- **The richest quests pay fewer cards.** Seven boss-dungeon quests paid 5 to 10 guaranteed cards plus as
  many random extras, nearly all rare or mythic - Mechanical Problems, Spores of Death, Pest Control, Slimy
  Business, Kiora's Fall, Teferi's Fall, The Drunken Plea. They now pay 4 or 5 (plus a random extra or two
  below Insane), in line with what a duel pays. Their gold is unchanged.
- **More of the balance is yours to edit.** `config tables/settings.json` gained the card counts per rank
  (1 to 5 each), the purse per rank and every factor above, the Coin Challenge's Shard fee, the
  world-generation cuts and how long dungeons last.

## Fixed

- **v1.11's new Names view drew a portal wherever a lowercase "l" belonged** ("B▮ack Tower") and marked
  portal towns with a bare "+". Fixed the day after the release; this is the first build that carries it.
- **Eleven dungeons could never leave the map** - Black Dragon Mountain among them - because they wore a
  protection meant for maps with a key and a locked door, which they do not have. Two Blue Towers that DO
  hold a key now have it instead. The log says why any dungeon stays.
- **Invisible walls** on land a color had claimed from the wasteland, mostly in Green and Red territory.
- **"Rescue the White Captive"** (and its four siblings) ticked itself off the moment the castle was found.
  A save that was hit re-opens the stage on load.
- **The weekly payday froze the game** for a moment per Mine; nineteen buildings made it over half a
  second. One coin sound now, no freeze.
- A town's guard icons floated above its drawn base on the overworld.
- Five creatures showed their hind legs where a portrait belongs (the Shellback Ankylosaur on an Inn's standings
  page, the Magmaback Crawler, Mossback Dragon, Skyreef Shark and Ashen Spinewyrm).
- "Participate in an Inn Tournament" is no longer handed to a player who has already played one, and never
  pops up inside a ruined town.

## Engine

Forge's 2026-09-18 daily, up from the 09.16 daily of v1.11: card scripts and editions are current to the
18th of September (more of Reality Fracture), foil cards are drawn with a shader, and wide token cascades
resolve faster.

**Saves from v1.11 load.** The save format is unchanged.

## 📱 Android

**Install:**
1. On your Android device (Android 8.0+), download `forsaken-realms-1.12-signed-aligned.apk` from the
   assets below.
2. Tap the downloaded file and allow your browser/file manager to install unknown apps when
   prompted (the game is signed by us, not the Play Store).
3. Grant the storage permission the app asks for — it stores the game data it downloads.
4. On first launch, tap **Download** when offered the resource files (~180 MB — use Wi-Fi). The app
   restarts itself when finished. After that it plays offline.
5. Works alongside the official Forge app — different app, different data. Updating over any earlier
   version keeps your saves.

Known on Android: at the Capitol's Level 2 Arena the third button of the row does not fit the portrait
screen. Screenshots of any other cut-off layout on Discord (https://discord.gg/TTRPKc9HYJ) with your
device model are the fastest way to get it fixed. `assets.zip` in the file list is downloaded by the app
automatically — you don't need to grab it yourself.
