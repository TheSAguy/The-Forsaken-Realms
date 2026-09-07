## The Forsaken Realms — v1.08 — Two Items, Maximum

**This is the release to install.** It came out the same afternoon as v1.07 and contains all of it,
so if you are coming from v1.06 you have not missed anything — the whole arena rebuild is written
out further down.

## The one thing v1.08 adds

v1.07 rewrote every arena payout table to stop a single Capitol win handing over six items. It
worked for the tables it rewrote, and it still left a hole: the *number* of items was emergent
rather than stated. Level 2 rolls four separate item pools per round, payouts are cumulative across
three rounds, and a win adds a guaranteed item on top — thirteen chances at an item, with nothing
anywhere in the system saying "and no more than this".

**No arena run can ever pay more than two items now**, whatever the dice say. If more than two come
up, you are handed the two most valuable and the rest are discarded. It applies to every arena and
to all three sources at once — the round tables, the champion bounty, and the bonus roll — because
it is the total that is capped, not any one of them.

A Bronze Coin coming back from an ante ransom is exempt. That is your own property being returned,
not loot, and dropping it to honor a loot cap would destroy it permanently.

---

# Everything below arrived in v1.07

Repeated here in full, because v1.07 was only live for twenty minutes and most people will be
upgrading straight from v1.06.

## Fixed: Arena rewards

Arena payouts are cumulative — winning three rounds pays round one's table, round two's, *and*
round three's. Each of those tables carried the same four random item rolls, so a clean run rolled
**thirteen items**. Winning six of them was not a bug so much as an inevitability.

Every arena now pays a flat, readable table.

**Your Capitol's arena (level 1) · the five AI capitals · the arena a Chest can open**

| Result | Reward |
|---|---|
| Lose round 1 | Nothing |
| Lose round 2 | 200 gold + a rare-or-better card in the colors of the fighter you beat |
| Lose round 3 | 350 gold + a card from each of the two fighters you beat |
| **Win** | **500 gold + a card from all three + one item** |

From round two onward there is also a **30% chance at one extra common item**, capped at one for the
whole tournament. The arena a Chest opens follows the same table with slightly better odds — a 40%
chance, one rarity band up — and keeps its own better prize for winning.

**Your Capitol's arena (level 2, Challenging)** — entry 300 gold

| Result | Reward |
|---|---|
| Lose round 1 | Nothing |
| Lose round 2 | 300 gold + a card from the fighter you beat |
| Lose round 3 | 500 gold + a card from each of the two |
| **Win** | **800 gold + a card from all three + one guaranteed item** |

Level 2 keeps its item rolls — one per round from each of four pools, at 25% / 40% / 15% / 5% — but
the 90,000-gold jackpot pool dropped from a 15% chance to 5%, and **no more than two of the cards
you win can be Mythic**.

(This is the table v1.08's two-item ceiling sits on top of — thirteen possible rolls, at most two
of them ever paid.)

The cards are the real change: instead of a generic rare, each one is themed to the colors of the
fighter who lost it to you, and **you keep the cards from the rounds you did win** even when the
next one ends your run.

## New: one tournament win per arena, per week

Each of the five AI capitals is its own venue, and your Capitol's level 1 and level 2 arenas count
separately — seven in all. You can enter any of them as often as you like and fight as often as you
like, but **each venue only pays out one tournament win per week**. Take the prize at the Plains
Capital on day 3 and that particular arena has nothing more for you until the week turns; the other
six are untouched.

Losing a run does not use up a venue's week.

## Changed: Jewel of Blessings

30,000 gold and Uncommon, sitting in the most common item pool the Capitol arena drew from — the
single biggest reason a good arena run paid better than a dungeon. Now **12,000 gold and Rare**.

## Fixed: fighters whose title fought their rank

An earlier rebalance re-sorted the whole roster by deck strength without renaming anyone, which is
how you could be introduced to a "Master Blue Wizard (Adept)". Titles and ranks agree again.

## Smaller things

- **Medals show on your character.** An equipped medal had nowhere to appear on the portrait; it
  has a slot now.
- **The torch explains itself once.** The banner that appeared every single time you used a Torch
  now fires on the first use only. The flare itself is unchanged.

---

## Engine

Unchanged from v1.06 — Forge's 2026-09-06 daily.

**Saves from v1.06 load.** Nothing in this release changes the save format.

## 📱 Android

**Install:**
1. On your Android device (Android 8.0+), download `forsaken-realms-1.08-signed-aligned.apk` from the
   assets below.
2. Tap the downloaded file and allow your browser/file manager to install unknown apps when
   prompted (the game is signed by us, not the Play Store).
3. Grant the storage permission the app asks for — it stores the game data it downloads.
4. On first launch, tap **Download** when offered the resource files (~180 MB — use Wi-Fi). The app
   restarts itself when finished. After that it plays offline.
5. Works alongside the official Forge app — different app, different data. Updating over any earlier
   version keeps your saves.

Please keep reporting on Discord (https://discord.gg/TTRPKc9HYJ) with your device model and Android
version. `assets.zip` in the file list is downloaded by the app automatically — you don't need to
grab it yourself.
