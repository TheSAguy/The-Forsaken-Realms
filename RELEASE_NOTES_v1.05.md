# The Forsaken Realms — v1.05 (draft release notes)

*Draft prepared 2026-09-05 from rounds 83–118. Paste into the GitHub release once the build is
tagged; the checklist at the end is what still has to happen before that.*

## The Forsaken Realms — v1.05 — The Ring

The world has a shape now. Five Ring Cities stand in a star around the Warden's cave, the story
opens on them instead of on a pile of starting gold, and the Five you freed can be beaten for good:
hold all five Ring Cities and take all five castles and the run is **won**. Around that, the biggest
bestiary expansion the game has had (267 new enemies with real animations and 22 hand-built Mythic
decks), a rebalanced roster where every colour has a real Common-to-Mythic ladder, a rules engine
brought up to Forge's 2026-09-05 daily, and three weeks of fixes from playtesting.

---

## ⚠ Start a New Game — required for the headline features

**An existing save still loads.** Every fix and every balance change below applies to it. But the
Ring Cities, the new opening quest, the road network, the victory condition and the capital-capture
quests are laid down when a **world is generated**, so an old world simply does not have them. This
is the release to start over on.

---

## New: the Ring Cities

Five neutral cities — **Benalia, Tolaria, Urborg, Shiv and Llanowar** — stand in a star around the
Warden's campfire, one per colour, each with its own layout and its own shops.

- **Their shops are special.** Two inner shops carry every set of the city's colour; two outer shops
  carry that colour's allied pairs. Prices are doubled, re-rolls escalate, and nothing there ever
  falls to ruin.
- **Every Ring City you visit while it is neutral or yours gives +1 max life**, lost if an AI colour
  takes it and regained when it changes hands back.
- **The AI wants them.** A colour may aim at a given Ring City once a week; once one is among its
  five nearest targets it is 25% more likely to be picked; Ring Cities are captured or repelled,
  never sacked. An AI-held Ring City challenges you to a **1-vs-2** duel against two of its mages —
  win and the city is yours.
- **Roads.** Every town starts with at least one road link and never more than five; the Ring's
  spokes and rim are always built; a captured town gets a road to the closest town already connected
  to its owner's seat.

## New: "Oaths at the Ring" — the opening

You start with **nothing**. The Warden sends you around the Ring: each city hands over one part of
your starting kit for your difficulty — gold, shards, wood, stone, then items and the Challenge
Coins — as reward cards you turn over. Skip the introduction and the Ring's whole gift arrives at
once (with the +5 life). Starting life is now 20 / 15 / 10 / 5 by difficulty, plus the Ring.

## New: a way to win, and a third way to lose

- **Victory:** hold all five Ring Cities **and** defeat all five colours at their castles.
- **Capitals can be assaulted.** Attack an AI capital and you face two distinct Archmages at once;
  win and it becomes your town, and that colour's active-mage cap is halved for the rest of the run.
  Capturing one also unlocks **Partner-only capture quests** at that colour's rivals' capitals
  (three rares, 1,500 gold, 100 shards).
- **Defeat:** losing the Capitol duel, running out of life — or an AI colour holding three of the
  five Ring Cities.

## New: town assaults, guards and AI-versus-AI fights

- At **War** you can assault a colour's town from its gate. Win and the town is yours (as a
  restored ruin); once per town per week; the assault costs reputation and provokes a mage from the
  former owner.
- **AI towns grow guards** — one level every 28 days, four levels, capitals fielding two Archmages
  — and those guards are the assault's defenders, scaling with difficulty.
- **AI colours fight each other properly.** When a mage reaches a rival's town the defending guard
  fights first, with the same odds table your hired guards use; the winner then rolls the capture.

## New: 267 enemies, 22 hand-built Mythic decks

- **47 hero-based enemies** from the hero portraits, **105 creatures** from the Enemy Art pack, **29**
  from the first batch of new sheets (Tiny Fantasy, the Mini soldiers, Molfar, Opiven, Rusalka, two
  side-view battlers, the axe minotaurs) and **86** from the rest (the Philippine-folklore PUNY MYTH
  creatures, the LPC humanoid ladders, the Pixel Character Pack, the Dark and sharp adventurers).
  Real Idle / Walk / Attack / Hit / Death animations wherever the sheet had them; battle portraits
  as duel avatars where the pack shipped one.
- **Every new enemy has a thematic deck**, rated by the rarity of its cards, gated by the deck's own
  difficulty so Commons and Uncommons stay beatable, and guaranteed not to duplicate any other
  list in the roster. The 22 Mythics — three per colour plus four extra for Green and three for
  Blue — run **hand-built 60-card decks**: knights, angels, tokens, merfolk, mill, wizards, faeries,
  illusions, sea monsters, reanimator, discard, goblins, minotaurs, burn, elves, stompy, hydras,
  treefolk, trolls, wolves and elf archers.
- The roster is now 1,787 enemies.

## Changed: the roster has a real ladder now

Every colour's roaming and authored enemies were ranked by the strength of the deck they actually
play and sliced into tiers: **20 Mythic, 30 Rare (Black 40), and the rest split evenly between
Common and Uncommon.** Life, speed and rank follow the tier, with a little per-enemy variety so no
two feel identical; decks were re-picked where an enemy's tier moved, and deck sizes now vary from
40 to 80 cards. Legacy duplicate decks were given their own lists. Nothing was deleted — every
replaced list is kept in `decks/reserve/`.

| Colour | Common | Uncommon | Rare | Mythic |
|---|---|---|---|---|
| White | 41 | 41 | 30 | 20 |
| Blue | 33 | 33 | 30 | 20 |
| Black | 54 | 53 | 40 | 20 |
| Red | 42 | 42 | 30 | 20 |
| Green | 41 | 41 | 30 | 20 |

## Changed: reputation and targeting

- Your standing changes how often a colour's mages pick your towns: **Partner ×0.25, Happy ×0.50,
  Neutral ×1, Unhappy ×1.15, War ×1.50.**
- The full targeting routine — clock, mage cap, what is attackable, the three filters, the
  nearest-five weighted roll, the War-only Capitol rule, the arrival math — is now written up in the
  guide under **"How the AI Picks Its Targets"**.

## Changed: Inns, research, shops

- **One Jumpstart tournament per run.** After you have played one, Inns offer Draft and Sealed only;
  the other two Bronze Coins are for ante ransom.
- **Research runs in several slots**, with tunable days and cost.
- Shops you build stock **only the sets you have unlocked**; the build-a-shop quest says so, and
  the quest refunds the shop's price.

## Engine

- Forge engine updated to the **2026-09-05 daily** (two merges: 09.01 and 09.05). Conflux's edition
  code changed upstream from CON to CFX; every reference in the plane was swept.
- **Save integrity:** a serial-version drift introduced mid-development wiped inventories on load;
  it was caught the same day and every save-bound class is now pinned. Saves from v1.04 load.

## Fixes

- The Ring City life bonus was re-added on every load (an Insane character reached 25 life); it is
  persisted now. Inflated saves: heal to full, then `give life -N` in the console.
- New Game+ no longer hands out the Challenge Coins twice, and "Find a surviving / ruined town" can
  no longer be satisfied by a Ring City, before or after the quest is issued.
- The Warden's rune dialog could soft-lock the tutorial; the rune is a reward card now.
- Gold, wood and stone picked up in dungeons make a sound like they do outside.
- Victory and defeat have their own splash art; pop-up glyphs keep their colours with black text.
- Mage towers and wizard forts have distinct tower icons; side-boss lairs show the unvisited marker.
- Dungeon audit: 94 resource drops relocated out of walls and clusters, 11 broken doors, 50 mis-keyed
  card rewards and a handful of typo'd item and effect names fixed across 92 maps.
- Android: research text, standings and guard-hire layouts fixed for portrait.

## Credits

New enemy art from free sample packs — Tiny Fantasy, the Mini soldier set, Molfar/Opiven/Rusalka,
waechter-19 (designed for *Nekomata* by @Jitsu) and waechter-20 (TheRealFusion), the minotaur
charset, PUNY MYTH Creatures, LPC-format character sheets, The Pixel Character Pack (pidroudays)
and Dark and sharp Player Characters (oCosity). Full list in `CREDITS.md`.

---

## Release checklist (not yet done)

1. Playtest the packaged 117/118 build — it is the first live build on the 09.05 engine.
2. Stamp `modVersion` to `1.05` (and `modVersionDate`) in the plane `config.json`; commit.
3. `mvn -pl forge-gui-mobile-dev -am package -DskipTests -o`, then
   `python standalone-packaging/build_standalone.py --zip` → `The-Forsaken-Realms-v1.05.zip`.
4. Android per `ANDROID_RELEASE.md` (APK + assets.zip), if shipping.
5. `git tag tfr-v1.05 && git push origin tfr-v1.05`; `gh release create tfr-v1.05 -R
   TheSAguy/The-Forsaken-Realms --title "The Forsaken Realms v1.05 — The Ring" --notes-file
   RELEASE_NOTES_v1.05.md` with the zip attached.
6. Close MOD_SCOPE items 100, 102 and 103.
