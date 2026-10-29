# START HERE — read this first, every session

**You do NOT need to read previous chat threads.** They are expensive and they are not the source
of truth. This file holds the standing rules and the current state; `MOD_CHANGELOG.md` holds what
every round built. If you find something missing, fix the docs rather than going back to chat history.

## NOW (rewrite this block each round; never append a new one)

**v1.24 "Towns & Trophies" is the latest release** (tag `tfr-v1.24` @ `6412d1a6cb6`, published 2026-10-27 16:13 UTC;
rounds 702-783, 785 and 786 on top of v1.23, 761 and 784 not used; assets and notes in `docs/tfr/releases.md`).
Unreleased, for 1.25: round 789 (the blueprint list takes the player-town template's names, so level-ups, chests, Mystery pickups and digs offer blueprints from the first minute of a launch; `logs-1027c` P1), round 790 (Far Sight is +1 tile of sight per pick, added after the light item; its +15% rounded away on Normal, Hard and Insane; `[TFR-Vision]` line; FAQ message 3 edited, to post in Discord with 1.25) round 791 (hold Alt: inside a cave, dungeon or lair the top-left corner says how long it has on the map - "Fades in 12 days", "Fades when you leave", "Leaves when cleared" - and each timed entrance on the world map wears "12 DAYS"; `DungeonRotation.fadeDays`, `[TFR-Fade]` line). Round 792: the Android download prompt says the real sizes ("around 430MB" of game data, "around 16MB" for an update, decimal MB like the FAQ; was upstream's 160MB/12MB; release-checklist line in `docs/tfr/build-and-deploy.md`). Round 793: at Notoriety 50 the 50-win bonus Wall becomes one random permanent from the enemy's own deck, moved out of the deck (mana value 1-3; Apprentice common, Adept up to uncommon, Master up to rare, Archmage any; the Wall stays when none fits; seeded by save, so a retry brings the same card; start-in-play cards do not fire their enter triggers - tested; `util/NotorietyDeckCard`, `notorietycard` console command, `[TFR-Notoriety] ... deck card:` lines). Round 794: 72 enemy `.dck` decks with no Notoriety-eligible permanent (round 793's pool: 1-3 mana value, rarity under the rank's cap) got one fitting card each, none with an enter trigger; three generated templates (Djinn Apprentice, Hydra and Golem Good Archmage) can still roll an empty pool and keep the Wall. Round 795: a mid-duel quit ReloadJab counts also costs Notoriety -5 on the next load (no gold or life; not for an Arena, Inn, fixed-deck or guard duel - `DuelScene.notorietyGate()`, whose losses cost none); the world's count of them is kept in `tfr_reload_jab.properties`, the save's paid count in its new `notorietyQuitsPaid` key, so a reload without saving charges again from the same score; `[TFR-ReloadJab] notoriety a -> b (left mid-duel ...)` line. Round 799: the Notoriety deck card (793) is now EXTRA - the 50-win Wall is back, and the card comes on top of every Wall - from notoriety 50 on Normal, 45 on Hard and 40 on Insane, never on Easy or in the Arena, 5 sooner while 95%+ of the last 50 duels are won (`notorietyDeckCardWinsByDifficulty`, `notorietyDeckCardRatio*` - own keys with round 620's values); no eligible card = none; a Hard Apprentice at 40-44 with the drop gets the card and no Wall, by design; `[TFR-Notoriety] ... deck card: ... needs Hard 40 (45 - 5 for win ratio 96% over 50 duels)` and `deck card: not yet / not in the Arena / not on Easy` lines. Round 800: the AI reads "Double"/"Triple" pumps (Reckless Amplimancer, God-Eternal Rhonas, 38 scripts) as the pumped card's power/toughness (x2 for Triple) instead of +0 - upstream bug, still open upstream; one helper `ComputerUtilCombat.predictPumpAmount` in five forge-ai files; it ends the `SVar 'Double' not found` flood (41k-1.16M lines a duel) and the AI now activates Amplimancer in combat (tested). Round 802: `[TFR-Duel]` reads lives from `game.getRegisteredPlayers()`, so the loser's life is no longer `?` (round 777's gap; the in-game path only - the agent ends on the view path, which was always right). Round 798 (gauntlet dungeons, engine part; the maps come from the Art main thread's sub-thread in later rounds): a place back from the reserve forgets its map flags (`DungeonRotation.resetMapFlags`; the Mages' Fort ward no longer opens at once), a loss keeps a POI tagged `Gauntlet` on the map (`isGauntlet`), and the keepAuthored placement line names the rank. Round 804: the first gauntlet map, the Dawn Cathedral (white; `maps/map/gauntlet/dawn_cathedral.tmx`, POI `GauntletDawnCathedral`, GreyTemplePillared outlined), ranks pinned by `enemy="Name|Tier"` + keepAuthored, gates on map flags; `validate_plane_data.py` reads `Name|Tier` (tiers from EnemyTiers.java); its test found the clear-count bug fixed in round 806, and 804 is not packaged without 806. Round 806: in a place tagged Gauntlet (or ClearNeedsAll) an enemy whose defeatDialog removes itself holds the clear (`MapStage.holdsTheClear` / `countsSelfRemovingEnemies`, `MapDialog.removesSelf`); 43 other maps with such enemies keep the old rule (audit in MOD_CHANGELOG). Round 807: the Mages' Fort (Fort2) opts in with the questTag ClearNeedsAll, so it counts as cleared only once its five ward wizards are down too (the user's call on 806's audit). Round 805: six more gauntlets, the black and blue rounds (Bone Orchard, Gilded Mausoleum, Hollow Tiltyard, Drowned Library, Starglass Observatory, Sunken Bathhouse; POIs `Gauntlet*`, biomes black/blue); Spare_E_Wizard_Palace outlined onto entrances_outlined, Graveyard_A/D on reviewed_ok. Round 809: the last eleven gauntlets, the green, red, colorless and white rounds (Spore Cathedral, Thornwing Hive, Jade Ziggurat, Kiln Foundry, Anvil Labyrinth, Brass Palace, Clockwork Orrery, Buried Necropolis, Hollow Rift, Sky-Bridge Monastery, Marble Gallery; POIs `Gauntlet*`); eight entrances outlined in place on plane pages; chest themes widened where the edition shard left 0-1 cards (a `randomCard` entry has no round-474 fallback); the generator seals every void cell next to the floor with a Collision tile (the game takes walls only from tile boxes, and the walls had gaps round the gates), and every install is gated on the Art project's pixel-level `ingame_check.py`. Round 810: the seven 804/805 gauntlet maps get the same Collision seal (one line each, Collision data only); in the live 804 a player could walk round a shut gate. Round 811: no two dungeons share an entrance sprite - the 18 gauntlets get their own ChatGPT designs at 48x48, and 17 places that shared a drawing (Monastery2-4, Maze 0-3, the Satyr Grove, five mage towers, Merfolk Pool 2, Assembly Ground, Theorist's Sanctum, Nekoru Nest) their own at their old footprint; 35 regions appended to `dungeon_entrances_2` (page 512x1270); Hellion Cave / Hermit's Hideout and ~19 look-alike caves wait for a ChatGPT cave batch; `poi_footprints.py` shows 21 drifted existing regions, left for a later round. Round 812: each gauntlet draws one of 4-5 themed groups per appearance (84 in `world/gauntlet_groups.json`, `util/GauntletGroups.java`, a `gauntletSlot` on each enemy object), never the place's last group again (character flag `gauntletLast.<POI>`); the ranks stay the slot's (keepAuthored: no DungeonUpgrades, re-theme or tier roll); the Hollow Rift's rim is paved pale. Round 813 (the Temple's Wrath, first half; design `notes/temple-wrath-design-813.md`, approved): at a week turn with a Temple in the Capitol, the closed week's most-killed color is hunted for the new week (`util/WrathHunt`, `ConfigData.wrathHuntEnabled`, World keys `wrathColor`/`wrathWeek`/`wrathStatus`) - its land's roaming spawns (`BiomeData.getEnemy`, `DungeonSources`) roll the NEXT spawn-tier bracket (not on Easy), and the Temple tally's duels against a foe with that color pay +25% gold and shards (`DuelScene` marks, `WorldStage`/`MapStage` loot sites raise); a destroyed Temple ends it; `[TFR-Wrath]` lines, console `wrath`. Round 814: the Wrath hunter - one mage of the hunted color sets out from its castle at the turn (Master, Archmage from week 7 on Normal / 4 on Hard and Insane, held under the week's bracket; none on Easy), walks a full-grid A* route planned on a worker thread (`util/WrathPath`; straight within 20 tiles in a clear line; holds when no walk reaches the player) at the player's speed snapshot x1.05 x the player's road/land rule at its feet (`WorldStage.terrainSpeedAt`), waits 5 tiles outside a place entered, always starts its duel with round 799's deck card (Notoriety and Disable Notoriety aside; `NotorietyDeckCard.choose(..., wrath)`), pays a seeded rare (10% mythic) + 15/20/25 shards + `wrathFactor` 2 Power + the Wrath Breaker trophy, goes home on a loss or draw, fades at the next turn; its plate "WRATH OF RED - MASTER" shows without Alt (days left with Alt), a larger minimap dot through fog; the World keeps it (`wrathEnemy`/`wrathSpeed`/`wrathX`/`wrathY`), `WrathHunt.keep` rebuilds the sprite; console `wrath near`, `wrath at x,y`. Round 815: the log keeps writing after an AI think-time limit - `ExceptionHandler` writes through a `RandomAccessFile` stream (`LogFileStream`), the slot's `FileChannel` only holds the lock (a FileChannel write from the interrupted AI eval thread closed the log for the session; `logs-1028b` P1); a failed write prints `[TFR-Log]` once; crash reports (`BugReportDialog.show`) and `Classic.render` errors count in the session footer's `errors=` (`[TFR-Error] crash report shown ...`). Round 817: the Research Lab gets a set box ("All Sets" or one set, listing the sets the list would show) and "Found Cards (N)" (N = the set's distinct cards owned); Found Cards gets its own set box with All Sets (every set still under research; each card still feeds its own set), the deck builder's color/type icons (its own SFilterUtil color rule and StatTypes predicates, long press = only this one), SpellSmith-style rarity buttons, the deck builder's "Groups:" (GroupDef, less PW Deck Sort) with group headers and "N of M cards shown"; the window refills in place (no reopen), Sacrifice is disabled on a Ready card's set, and "Sell all over 4" takes the cards shown (`scene/ResearchCardFilters`, `[TFR-Research] Found Cards: ...` lines; agent-tested in both layouts, shots `notes/r817-*.png`). Round 818: an enemy's talk-only dialog (no battleWithActorID or deleteMapObject -1 anywhere: `MapDialog.canFightOrLeave`) no longer reopens on every touch - pure chatter (`isChatter`) speaks once per NPC per visit, talk with actions (the River Merfolk's dam task) again once the player is a tile clear (`MapStage.steppedAway`); a skipped touch neither resets nor duels; the Svyelunite Temple's 34 allied talkers (26 patrolling) trapped a v1.24 player (Discord); F4 was the workaround and is unchanged; `[TFR-Talk]` line; agent-tested 8 dialogs in 25 s -> 1 (diagnosis `C:\TFR\notes\r818-diagnosis.md`). Round 819: in the Capitol, a rubble lot's Card Shop opens with "Booster Shop - Packs from all your unlocked sets" above the tiers (`BoosterPackShop`, Pack To Basics; Common price, no blueprint - `isShopTypeUnlocked` exempts it; one per town, "(built)" greyed; not in player towns or a Re-assign; `[TFR-BoosterShop]` line; agent-tested, shots `notes/r819/`). Round 820: the era booster shops (Alpha/Beta/Gamma/Delta) are gone - their year windows never held where a town limits its sets; the Capitol's booster lot (object 85) is a 17th open lot (lists of the other 16, no `noMigrate`, tmx `boosterLot`), pinned once per Capitol to the Booster Shop (`EconomyBuildings.settleCapitolBoosterLot`, map flag `boosterLot820`: an old save's era shop there becomes it; not when a card shop is pinned there or a Booster stands elsewhere); a destroyed booster shop on a lot with card-shop lists opens the full build menu; 20 AI-town lists name `BoosterPackShop` instead; era names out of the blueprint pool; shops.json keeps the entries; agent-tested (a)-(e). Round 816: a mid-duel quit after an error is never counted - a crash report or a `Classic.render` error during the player's duel marks the record (`DuelHeartbeat.onError` -> `ReloadJab.duelError`, keys `duel.error`/`duel.errorAt`), and the next load judges it "after an error - not counted" (no count, no Notoriety, no honest reset; `logs-1028b` P2); `CardRenderer.drawText` skips a counter/marker tab's text for a frame on a layout exception (`[TFR-Render]` once a launch; the null-glyph NPE's cause unproven); GUIDE sentence. Round 796: Settings' "I won't cheat by loading after a loss" (checked by default, `SettingData.reloadJabOptOut` stored inverted) - unchecked, the reload feature is off for the player (no counting, quit judging, enemy life bonus, round 795 Notoriety cost, forgiveness or Notices lines; the counts are kept; DifficultyHints still runs); the enemy life cap is +25 (a world past +10 carries over at +10 once, `cap25.<seed>` marker); forgiveness every 3 honest losses, a counted reload restarting them; Arena losses and Arena quits never count. Round 801: a reload ReloadJab counts (a save from before the loss) of a loss that cost Notoriety charges that loss's -5 again on the load, through round 795's count (`notorietyQuits.<seed>`, now quits and reloads) and the save's paid count - not lost on a reload without saving, never paid twice; the pending loss carries `pendingNotoriety` (`notorietyGate() && !isArena`, so no Inn, fixed-deck or guard charge); "Reloaded after a loss - Notoriety a -> b (-5)" Notices line. Round 803: the info page (Status) shows "Win: 9  Loss: 8  (53%)" on one line - "(-)" before any duel, the old Win/Loss ratio row gone - and "Notoriety: 12 (level 2)" under it ("(off)" with Disable Notoriety; blank on a plane without Notoriety Walls); the stock layouts' own labels are reused, portrait at 80%. Round 808: the portrait player page gives shards their own row under gold (upstream had them inside the blessing box, under the Ascendance Power line), the Win and Notoriety lines 12 lower, and the blessing box 6 to the right (x 176, width 80) so a long Notoriety line clears it; `statistic_portrait.json` only. Round 797: in the world's first week (days 1-7) no overworld chest rolls Dangerous Enemy or the Illegal Arena Match and no diamond rolls its ambush - the roll falls through to the other outcomes, their shares kept (`TuningData.chestDuelFreeWeeks`, TFR 1; `ChestEvents.duelFreeWeek`; `[TFR-ChestEvents]` / `[TFR-ResourceSpawns]` skip lines; console `chest <0-6>` and `diamond ambush`; GUIDE line). Round 788 reordered the Discord FAQ's dev tools: Support is always the last message. Live = round 808 (2026-10-28 09:10 package, HEAD 3d71b8508e5). Round 787 stamped `modVersion` 1.24 / `modVersionDate`
10.27 (the PC update notice of round 782 compares `modVersion` with the newest `tfr-v` tag) and Android `tfr.version`
1.24 / versionCode 12400, wrote `RELEASE_NOTES_v1.24.md` and GUIDE's What's New in 1.24. Engine = the 10.26 daily (round 769, upstream `0dc8c66996e`; R1-R4 kept TFR's synchronous auto
save, eager shops, Inn events and place entry - see MOD_CHANGELOG; upstream's async save and lazy town loading stay
unused, possible later rounds); next merge from upstream after `0dc8c66996e`; BASE_INSTALL is that daily, so packages
need no `--allow-base-mismatch`. Next version: 1.25 / 12500. Round 821: 15 look-alike caves get their own 32x32 entrances from the user's "favorite older entrances" ChatGPT sheets (five recolored to their biome: Kelp Hollow's sea maw, colorless dry crag, bleached den, ash fire, dried heart); no two entrance places share exact pixels now; 5 caves (CaveR1, Foghorn Cleft, CaveC6, CaveCB, Gray Warren) wait for a cave batch. Round 822 (custom difficulty, part 1 of the round plan `C:\TFR\notes\custom-difficulty-round-plan.md`): a save can carry its own difficulty on top of its preset - knob id -> value in the save's `customDifficulty` key (absent = the preset), defined and clamped by `config tables/custom_difficulty.json` (11 knobs so far; an unknown id is dropped); `Config.getTuningData()` returns the save's copy of settings.json (`getBaseTuningData()` the plane's own), `{D}`/`[*]` knobs fill every difficulty column, `difficulty.*` knobs go on the save's DifficultyData and over the config preset (Ring gifts, New Game+); applied before world generation and first in the load; the preset name stays the key every difficulty rule reads; `[TFR-Custom]` lines, `difficulty=Custom(Hard) custom=N` and the footer's `custom=`/`customChanges=`; console `custom` (set/reset/knobs/next). Round 823 (custom difficulty, part 2): the new-game screen's "Advanced..." (landscape; portrait is 824) opens `scene/CustomDifficultyScene` - "Based on" preset, search (label/tooltip/group/category), Basic/Expert, Changed only, tabs from the json's `categories` with change counts, -/+ steppers (long press repeats) or check boxes, the preset's value in grey, tint + `<` reset once changed, a help line with the tapped row's tooltip, Done / Reset all / Share code (greyed until 827); Done hands the copy to `CustomDifficulty.setPending`; the difficulty box reads "Custom (Hard)" while changes are pending; the "?" dialog shows the custom values through `previewTuning` (its Territory base cap now `attackingMagesFor`); knob `format: percent`; console `custom panel [search TEXT]`; shots `notes/r823/`. Rounds 823-833 are the custom difficulty project (plan `C:\TFR\notes\custom-difficulty-round-plan.md`). Next free round: 834. The agent profile's window is
1280x720 - at 3840x2160 it covered the screen, got minimized, and a minimized game stops (the bridge times out, shots
come back 1x1). Log sharing (round 698) posts to Discord through a webhook kept OUT of git in
`C:\TFR\secrets\discord_log_webhook.txt` - never commit it; the packager and ANDROID_RELEASE.md step 2b put it into
builds. Release ledger (assets, versionCode, signer): `docs/tfr/releases.md`. Round history before 2026-10-18:
`docs/tfr/history/claude-md-rounds.md`.

## How the user wants me to work (supplied 2026-09-07)

**The TFR 1 main thread works through sub-threads** (the user, 2026-10-22: "Do all work in Sub-threads, and you should
auto select the correct level of effort needed, depending on the request"). In the user's main TFR 1 session, each
request's work runs in its own session (`start_session`, a self-contained brief) at an effort picked for the request -
high for balance, core rules, AI, engine merges and debugging; medium for UI, art, data, menus, save edits, docs and
packaging; low for lookups and log reads; xhigh only for a stubborn bug after one failed fix. The main thread
coordinates: it briefs, checks each result and reports to the user. A sub-thread does its own work and follows the rest
of this file.

Co-author, not an execution service. Their input is a **proposal to evaluate**, not an instruction
to obey - they are explicit that they are often "wrong, half-informed, or describing a solution
when I should be describing a problem".

**Before writing code** on anything non-trivial: restate the actual goal behind the request and say
so if the goal and the proposed method do not line up; state load-bearing assumptions and CHECK
them in the codebase rather than guessing; say what I do not know.

**Analyze before agreeing.** Give the unintended consequences (what else touches this, what breaks
downstream, the edge cases they did not mention - empty, first run, mid-game state). Say whether
their approach is the right one and lead with a better one if I have it, including its cost.
Correct a mistaken premise **at the top of the reply**, never after implementing on top of it.
Genuine agreement is one line and move on - but a conclusion, not a default.

**Opinions, not menus.** Multiple viable approaches get a pick and a reason. Volunteer
recommendations about things that actually matter. Disagree out loud and hold the position under
repetition; say what changed my mind if they convince me. No flattery openers.

**Mod-specific checks** whenever relevant: upstream merge burden (prefer a hook to a core edit -
see the merge-friction notes below); save/state compatibility; data formats confirmed against a
working example in the repo rather than inferred, because a wrong field name fails silently at
runtime; data over Java where possible; balance second-order effects; and **blast radius stated
before starting**, with a smaller first step proposed.

**Calibration**: a rename or one-line tweak just gets done. The full analysis is for behaviour
changes, shared systems, core/upstream edits, and anything hard to undo.

**Afterwards**: what changed and why for any judgment call they did not specify; what I
deliberately did NOT do; anything I could not verify and how to test it; and anything concerning
noticed outside the task, raised separately rather than silently fixed or silently ignored.

## Standing rules

**Commit locally; push only at a release (the user, 2026-09-20: "Let's not update the online repo until we are ready
to release").** Every round ends with a local commit; the push and the tag happen together at release time, when the
user calls the release.

**Docs travel in the same commit as the code.** Each round adds its entry to `MOD_CHANGELOG.md` (and to
`CORE_ENGINE_CHANGES.md` when it touches a stock engine file) and rewrites the NOW block above. Round narrative goes in
the changelog, never into this file; a release's block goes in `docs/tfr/releases.md`.

**Releases take the upstream engine update FIRST** (standing user preference, 2026-09-01), as its own round planned
ahead of the release: merge upstream -> reinstall BASE_INSTALL -> rebuild -> user re-tests -> then tag and publish. Why,
and what the merge clobbers: `docs/tfr/build-and-deploy.md`.

**Engine updates - conflicts go to the user (standing user rule, 2026-09-26: "If ever there is a real conflict when
updating to the latest Official game, please make me aware of it and the impact when merging.")** When a merge of
official Forge hits a REAL conflict - upstream changed or removed something our code depends on or also changed (a
behavior, a contract, a file both sides reworked), not whitespace or import noise - tell the user, as part of the merge
round and before it is packaged: each conflict (file and feature), what upstream changed, what we kept vs took, and the
impact on the game (what players will notice, saves, risk, what to test). Trivial textual conflicts get one line. If a
conflict forces a choice between upstream's behavior and ours, ask before choosing.

**Every piece of art also goes into the Art Library (standing user rule, 2026-10-16: "All art that we share, please make sure to put a copy also in the Art-Library repo we created. Make a rule of that please.")** Finished art made, imported, edited or shared in a round - sprites and atlas pages, icons, card faces, edited sprites (before and after) - gets a copy in the private Art Library repository (`C:\CLAUDE\PROJECTS\ART\Art-Library`, github.com/TheSAguy/Art-Library) in the same round, committed and pushed there: TFR's own art in `Projects/The Forsaken Realms/` (`Game Art/<kind>`, `Key Art`, `LPC/...`), creature work in `Projects/The Forsaken Realms/Creatures/<Creature>` (with its Pixelate stills, clips and sheets), third-party packs and game rips as the library's `README.md` files them, and art the user hands over with its source. Finished art only: no development shots, screenshots or review sheets (the user, 2026-10-21). The game repository keeps the working originals; the library holds copies.

**World-map entrances carry a 1px black outline (standing user rule, 2026-10-16: "I like the 1px outline more ... Let's also make that the rule for entrances moving forward.")** Every cave, dungeon and side-boss place sprite has a 1px black outline round its silhouette, drawn inside its own atlas region (size, position and footprint unchanged). New or changed entrance art: run `python dev-tools/poi_outline.py --audit <repo>` - it lists entrances with no outline (exit 1); outline them with `--apply` (add them to dev-tools/entrance_outlines.json "outline"), or, when the score is in the 0.30-0.65 band and the sprite does carry a dark outline (a soft drop shadow lowers the score), add it to "reviewed_ok". Never edit an adventure/common page: copy the sprite onto the plane (`--apply` does that) and repoint its places.

**Importing from Realm of Legends or any other plane (standing user rule, 2026-10-16)**: no Commander, no quest lines
that are not ours, no promised gifts our game does not deliver, every reference resolves in our plane, and the import
is seen in the agent game before packaging; the round's changelog says it passed. Read the full checklist,
`docs/tfr/cross-plane-imports.md`, before the first edit of any import.

**Every duel runs at 50x, the fastest speed (standing user rule, 2026-10-17, restated 2026-10-23: "Remember to always duel on the fastest setting. Please make that a rule.")** Any duel a session plays - agent tests, console-started fights, arena brackets, quick checks - runs at 50x. Sit duels out with `dev-tools/agent/tfr_agent.py settle` (its `duel_at_50x` taps the speed button until it reads "1/3x speed"), never a bare `/wait` loop; a duel started any other way gets 50x set before the first wait.

**Check the code graph before changing a shared method (standing user rule, 2026-10-18).** Before renaming, deleting or
changing the signature or behavior of a method or class that other code calls, list its callers from the local
graphify code graph and say in the round how many you found:
`C:/Users/User/.claude/Tools/graphify/venv/Scripts/python.exe C:/Users/User/.claude/Tools/graphify/gfy.py --repo tfr
affected Class.method --depth 2` (`explain Class` for a class). The `code-graph` skill has the details. It is not proof:
confirm with Grep before deleting anything; use Grep for text, config keys and data files (the graph covers code only).
`graphify-out/` is local (in `.git/info/exclude`) - never commit it, and never run `graphify install`,
`graphify claude install` or `graphify hook install`.

## Where to read

This is a fork of [Card-Forge/forge](https://github.com/Card-Forge/forge) (the open-source MTG engine) used to build
the Adventure-mode mod **"The Forsaken Realms"**. A prior session may have changed things this one does not remember,
so read in this order, and stop when you have what you need:

| Read | For |
|---|---|
| **this file (`CLAUDE.md`)** | standing rules, current state, ground rules, build summary |
| **`MOD_SCOPE.md`** | the feature list with live status per item. Start at the Currency line |
| **`MOD_CHANGELOG.md`** | the engineering log, ~40k lines, the source of truth for what is built. **Order**: rounds up to 121c run oldest-first; from round 122 on every new round is inserted NEWEST-FIRST right after 121c, so the newest round sits mid-file and the file's bottom is round 122. `grep -n "^## Round" MOD_CHANGELOG.md` lists them in file order; read the newest few, then grep by keyword |
| **`CORE_ENGINE_CHANGES.md`** | every stock-engine file this mod edits, for upstream-merge conflict work |
| **`ANDROID_RELEASE.md`** | the authoritative Android release procedure. Read before ANY Android work |
| **`docs/tfr/build-and-deploy.md`** | the deploy loop, the packager's guards, BASE_INSTALL, the release order in full |
| **`docs/tfr/cross-plane-imports.md`** | the full import checklist |
| **`docs/tfr/releases.md`** | the release ledger: assets, versionCode, signer, notes per release |
| **`docs/tfr/history/claude-md-rounds.md`** | frozen round history (rounds ~83-697) moved out of this file on 2026-10-18 |
| **`docs/design/`** | feature designs |

Then run `git log --oneline -15` and `git status` — those two tell you the rest.

## Ground rules for mod work

- All mod features are **opt-in per-plane config flags** on `ConfigData.java`
  (`forge-gui-mobile/src/forge/adventure/data/ConfigData.java`), defaulting to `false`, turned
  on only in `forge-gui/res/adventure/The Forsaken Realms/config.json`. Never make a mod
  feature apply unconditionally - it must not affect Shandalar or any other stock plane.
- `The Forsaken Realms/config.json` is a **full standalone copy** of `common/config.json`, not
  a small override - Forge does not merge per-plane config with common's. See
  `MOD_CHANGELOG.md` for details.
- Mod code changes live under `forge-gui-mobile/src/forge/adventure/`. Mod plane data/assets
  live under `forge-gui/res/adventure/The Forsaken Realms/`. Both need to travel together
  (e.g. via git) for the mod to actually work on another machine - copying just one half is not
  enough.
- **Prefer storing custom/edited assets and data under `forge-gui/res/adventure/The Forsaken
  Realms/`, not `common/`, whenever the engine's plane-aware file resolution makes that possible**
  (the same "full copy, not merge" override pattern already used for `config.json`,
  `points_of_interest.json`, `world.json`, the biome jsons, and every custom `.png`/`.atlas` this
  mod has added) - keeps the mod self-contained in one folder, which matters when it's eventually
  shared. Only touch a genuinely shared file (like `forge-gui/res/languages/en-US.properties` -
  Forge's localization strings have no per-plane override mechanism) when there's truly no
  plane-scoped alternative, and note it as an exception in `CORE_ENGINE_CHANGES.md` when you do.
- **Any edit to an existing engine file outside `forge-gui/res/adventure/The Forsaken Realms/`
  (or a new file added outside that folder) needs a matching entry in `CORE_ENGINE_CHANGES.md` in
  the same round** - same standing requirement as keeping `MOD_CHANGELOG.md` current, just scoped
  to upstream-conflict-relevant changes specifically.
- `origin` is the user's own fork, **`TheSAguy/The-Forsaken-Realms`** (renamed from
  `The-Forgotten-Realms` on 2026-08-27 with the game's rebrand; GitHub still redirects the old
  URLs). Local branch is `main` but the remote default is `master` - push with
  `git push origin main:master`. `upstream` is the original `Card-Forge/forge` project, for
  pulling in engine updates only - never push mod work there. Note `gh` in this repo resolves to
  UPSTREAM by default, so always pass `-R TheSAguy/The-Forsaken-Realms`.
- **Add a greppable diagnostic log line for any mechanic that's hard to observe by just playing**
  (standing practice, user request 2026-08-13) - anything probabilistic, AI-driven, or that fires
  rarely/off-screen (combat odds, AI targeting decisions, scaling formulas, timers). Follow the
  established `[TFR-<Name>]` tag convention already used by `[TFR-GuardFight]` (attacker tier,
  guard tier, computed chance, outcome), `[TFR-DayNight]`, and `[TFR-CaptureOdds]` - one line per
  relevant event, with enough values printed to verify the mechanic's actual behavior from
  `forge.log` alone, without needing to catch it live on screen. Add this as part of building the
  feature, not as an afterthought - it's what lets a future session validate a change the user
  can't easily reproduce themselves.

## Build, package, deploy

Maven + JDK are installed portably on each machine (not tracked in git). Verify with
`mvn -pl forge-gui-mobile -am compile -DskipTests -o` (add `-o` once dependencies are already
cached locally) before considering a change done.

- **The user plays `C:\TFR\live\The Forsaken Realms\`.** Build the jar with `mvn -pl forge-gui-mobile-dev -am package
  -DskipTests`, then package with `python standalone-packaging/build_standalone.py` (`--zip` only for a release asset).
- **`E:\GAMES\Forge_2` is BASE_INSTALL**: the stock Forge the packager reads. Read-only for us - never write to it. An
  engine merge also needs the user to reinstall it at the matching daily, or the packager refuses to build.
- **Read `PACKAGE_OK.txt`** in the live folder before telling the user it is safe to play. Never substitute
  file-existence checks for reading this marker.
- **After a live package, refresh the agent game** (`C:\TFR\agent`, a mirror of live with its own profile) with
  `dev-tools\agent\agent_sync.cmd`, run from PowerShell; check the agent jar's timestamp, not the exit code. Test new
  features there on copies of the user's saves (the `tfr-play` skill), never in the user's own profile.
- **Packaging**, a packager refusal (jar freshness, game-running lock), `--full`, or the retired `E:\GAMES\FORGE`
  target: read `docs/tfr/build-and-deploy.md` first.
- Release order: build + update the live folder, **user tests it**, and only after their explicit pass does anything
  get pushed or published.

## Hard-won lessons that will bite you again

- **Every class that goes into a `.sav` needs an explicit `serialVersionUID`, and changing one is a
  save-format change.** Round 88 added a field to `EffectData` (embedded in every inventory
  `ItemData`); Java's derived UID changed and EVERY existing save loaded with an empty inventory
  behind a "Data Migration completed" dialog. All ten save-bound classes are pinned since round 90.
  Before packaging a build that touches `forge/adventure/data`, `player`, `pointofintrest`, `world`
  or the two controllers, LOAD a v1.0x save and check the inventory.
- **Read Maven's own exit code, never a pipe's.** `mvn ... | grep ...; echo $?` reports *grep's*
  status. A round-78 compile reported success while Maven had failed. Redirect to a file and read
  `$?` immediately.
- **The Android release build leaves the desktop build unable to compile.** `mvn -pl
  forge-gui-android -am clean install -P android-release-build -Dmaven.repo.local=C:/m2` cleans the
  shared modules and rebuilds them against a different local repo, leaving `forge-game` /
  `forge-core` `target/classes` partial. The next ordinary compile then fails with `cannot find
  symbol` / `cannot access ... NoSuchFileException` in files nobody edited. Round 81 blamed this
  exact symptom on two concurrent Maven builds; that was wrong (reproduced 2026-09-02 with ONE
  Maven, right after the v1.04 Android release). Fix: `mvn -pl forge-gui-mobile -am clean compile
  -DskipTests` - the `clean` is load-bearing. Still never run two builds at once, but when untouched
  files stop compiling, suspect a stale `target/` first.
- **A Java COMMENT edited after `MVN EXIT 0` trips the packager's jar-freshness guard** (round 241) - rebuild the jar
  after any `.java` edit, comments included.
- **Another Claude session may be live on this same checkout.** On 2026-09-02 two sessions worked
  the tree at once; the tells were a `java.exe` running Maven that this session had not started, and
  doc edits appearing in `git status` unbidden. Before committing, merging or building, run
  `ListAgents`; if a peer session is listed, message it to stand down and wait for its build to
  exit. Never kill a build you did not start. With a peer on the checkout (the Procedural Pixel Creatures art
  session usually is), claim a round number and the write window by message before writing, commit by explicit
  pathspec only (never `git add -A`, never `git stash`), and run `git status` right before packaging - the packager
  ships whatever uncommitted files are in the tree.
- **`--zip` packaging always does the full stock-asset copy** and takes well over ten minutes. Do
  not give it a short timeout — killing it mid-run strips `PACKAGE_OK.txt` and leaves the live
  folder in the half-rebuilt state that marker exists to catch.
- **Edit saves with Java, never Python.** See `dev-tools/save-editing/README.md`.
- **Write the changelog entry in the SAME action as the code commit.** Rounds 73-76 and then round
  78 all shipped with detail only in commit messages and had to be backfilled. A thorough commit
  message is not a substitute and reads as done when it is not.
- **An "empty set means everything" convention must be read through its predicate at every site.**
  A raw `Set.contains()` near one is a latent bug — that pattern was a save-corrupting release
  blocker caught in round 78.
- **Any `Set.of` fed plane data needs a null guard** (round 194: one null quest tag in enemies.json threw in
  `loadMap()` and left the player stuck on "Autosaving").
- **Singleton state that must not outlive a save resets in `WorldStage.clearCache()`** - both a load and a new game
  reach it (`load()` calls it first, World generation calls it last).
