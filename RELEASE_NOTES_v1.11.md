<!-- DRAFT (round 221, 2026-09-16) - delete this block before publishing.
  Before tagging tfr-v1.11:
  1. Round 222 (the 09.16 engine merge) must be in, packaged and PLAYTESTED - everything below runs on it.
  2. Stamps: config.json modVersion 1.10 -> 1.11, modVersionDate 09.16, engineBuildVersion 2.0.15-SNAPSHOT-09.16;
     forge-gui-android/pom.xml tfr.version 1.11, manifestVersionCode 11000.
  3. Desktop zip: build_standalone.py --out C:\Users\User\TFR-Release --zip; Android per ANDROID_RELEASE.md from a C: copy.
  4. gh release create tfr-v1.11 --draft -R TheSAguy/The-Forsaken-Realms, upload all three assets, then --draft=false --latest.
  Rounds covered: 186-222. Nothing here needs a new world - every change reaches an existing save.
-->
## The Forsaken Realms — v1.11 — Standing Ground

A smaller update than v1.10, and a safe one: **your v1.10 save carries straight over** and every change
below reaches it. Most of this came out of playing the last release, plus a full review of the code that
went in since.

## New

- **Coin Challenge.** The Capitol's Level 2 Arena has a third button. Any enemy still holding one of
  your Bronze Challenge Coins can be challenged there once a week for a flat fee (50 gold on Easy and
  Normal, 100 on Hard and Insane). No ante, no gold penalty and no other loss if you lose; win and the
  coin comes back on the loot page like any other reward. The button is greyed until someone holds a coin
  of yours.
- **A town's reputation defends it.** Every point of reputation in a town you own takes 1% off an
  attacking mage's capture chance, up to 20 points, on top of an Outlook's bonus. At your Capitol it is
  the chance the mage is turned away before the duel is even queued. No town is ever fully safe: a mage
  always keeps at least a 5% chance.
- **Dungeons remember their creatures.** Whatever a cave or dungeon held on your first visit is what it
  holds on the next one. When it fades from the map and comes back, it rolls fresh again.
- **The five AI capitals grow.** They spread territory like any captured town now, out to twice a normal
  town's reach. They never did before.
- **The arena bracket favors rank.** In the AI-versus-AI matches the higher-ranked fighter wins 60% of
  the time; an equal-rank pairing keeps the old life-weighted roll.
- **A quest remembers where you have been.** "Go there" objectives complete on the spot if you already
  visited the place before the quest was issued, and an Inn tournament played before "Participate in an
  Inn Tournament" was offered counts for it.
- **Smaller additions:** the arena says "Won this week (N days)" instead of silently refusing; a crowned
  enemy is never drawn smaller than a Master; enemies in a cave with a dungeon effect carry a small cyan
  pip so the buff stays visible; a delivery quest pays the town that sent you as well as the one you
  reach; the game opens straight into Adventure; the guard quest explains what the Armory upgrade buys.

## Changed

- **Loot duplicates are rarer, and a pile of one card is impossible.** A card reward re-rolls a repeated
  name twice, and no reward pays more than two copies of one card. A deck too thin to pay its promised
  rarity relaxes the filter, and anything still unpayable becomes 50 gold per card. The +1-reward-card
  items apply once per payout, not once per reward line, and never to a lands-only drop. Authored fixed
  rewards (the Gitrog Bog land chests, Three Tree City's twenty Hare Apparent) pay exactly what they say.
- **Resource buildings:** the Shard, Gold and Stone Mines cost 250 gold, 25 wood and 50 stone; the
  Lumber Mill mirrors that at 50 wood and 25 stone; the Shard Mine is 38 wood and 38 stone.
- **Selling is done at the Armory's item storage** (the Capitol's Level 2 Armory), not from the
  inventory screen.
- **Starter decks are 50 cards**, so ten lost antes no longer put you under the 40-card floor.
- Speed-Up and Wait no longer carry over from the previous session when you load a save.

## Fixed

- A cave could fail to load and leave you stuck on "Autosaving" (a null tag in eleven enemies).
- Seven generated caves had a sealed corridor cutting off part of the map.
- The defeat screen at the end of a lost run had no way out.
- Being one Center Town from losing is now a real dialog, not a corner notice.
- A cave that had rotated away kept its name on the minimap.
- The day rollover stuttered in a long game (one full minimap rebake per day, now at most every three
  days, plus a much cheaper territory pass).
- The F12 collision overlay followed you out of a cave, and the Flooded Cave had three invisible walls.
- Oversized enemy portraits swallowed the arena bracket.
- Stray pixels under 21 enemy sprites; a backup file that had shipped inside the game folder.
- The Level 2 arena's buttons are centered, and the weekly-lock text no longer runs under the Start
  button.

## Engine

Forge's 2026-09-16 daily, up from the 09.11 daily v1.10 shipped on: card scripts and editions are
current to the 16th of September (the Reality Fracture set is in), the AI's combat prediction is
faster, and Adventure mode gained the groundwork for translations.

**Saves from v1.10 load.** The save format is unchanged.

## 📱 Android

**Install:**
1. On your Android device (Android 8.0+), download `forsaken-realms-1.11-signed-aligned.apk` from the
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
