# START HERE — read this first, every session

**You do NOT need to read previous chat threads.** They are expensive and they are not the source
of truth. Everything a session needs is in five files, and they are kept current by a standing rule
that every round updates them in the same action as its commit. If you find something missing, fix
the docs rather than going back to chat history.

## How the user wants me to work (supplied 2026-09-07)

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

Read in this order, and stop when you have what you need:

| Read | For |
|---|---|
| **this file (`CLAUDE.md`)** | ground rules, release workflow, deploy path, build commands |
| **`MOD_SCOPE.md`** | the feature list — 103 numbered items with live status. Start at the Currency line |
| **`MOD_CHANGELOG.md`** | the engineering log. Newest rounds at the **bottom**. ~15k lines — read the last few rounds, then grep by keyword |
| **`CORE_ENGINE_CHANGES.md`** | every stock-engine file this mod edits, for upstream-merge conflict work |
| **`ANDROID_RELEASE.md`** | the authoritative Android release procedure. Read before ANY Android work |

Then run `git log --oneline -15` and `git status` — those two tell you the rest.

## STATE 2026-09-10 (round 168; v1.08 RELEASED, rounds 137-168 are post-release; ENGINE = 09.09 daily since round 165) - READ THIS FIRST, DO NOT REPEAT WORK

- **v1.06 "Deeper Caves" is RELEASED** (round 131, 2026-09-06): tag `tfr-v1.06` @ `17d3fcbf54b`, published
  2026-09-07 01:31 UTC and marked Latest. Three assets: `The-Forsaken-Realms-v1.06.zip` (237.3 MB),
  `forsaken-realms-1.06-signed-aligned.apk` (12.5 MB), `assets.zip` (175.5 MB). `RELEASE_NOTES_v1.06.md` is the body.
  **Rounds 120-130 are all shipped - nothing is unreleased.** Stamps: modVersion 1.06 / modVersionDate 09.06 /
  tfr.version 1.06 / manifestVersionCode 10600 / engineBuildVersion 2.0.15-SNAPSHOT-09.06.
  **The Android build now takes 2 minutes**, not 2h17m: `git -c safe.directory='*' clone F:/... C:/TFR-build`, copy
  in the gitignored `forge-gui-android/forge.keystore` + `local.properties`, `subst R: C:\TFR-build`, then
  ANDROID_RELEASE.md's maven line from `/r/`. Keystore fingerprint verified EE:60:39:25 before upload.
- **v1.05 "Fight Back"** (round 119, 2026-09-05): tag `tfr-v1.05` @ `5f520118bdd`.
- Round 168 (2026-09-10, Built 14:09, NOT packaged - the game was open; package before the next test): **the Armory storage is a SCREEN** (user mock-up): `scene/ArmoryScene` +
  `ui/armory.json`/`armory_portrait.json`, the inventory layout with the storage grid where the description was and a
  Transfer button; player mode from the Capitol Armory's Storage button, guard mode from a guard's Equipment button
  (doll + lower grid = the guard's worn items; only Transfer = Give / Take Back). Back re-opens the guard's page via
  `RoamingGuardUI.pendingGuard`. `ArmoryStorageUI` is just `fit()` now. NOT yet playtested.
- Round 167 (2026-09-10, PACKAGED 13:46 - live folder = the 09.09 engine with rounds 158-167): **HOTFIX** - round 162's `Touchable.disabled` on every layout Window also disabled
  the children six scenes add INTO their Window (SaveLoadScene `saveSlots`, DeckSelectScene `deckSlots`, EventScene /
  PlayerStatisticScene / QuestLogScene / ResearchScene `scrollWindow`): no save slot, deck slot, event, statistic, quest or
  research row could be tapped since the round-162 package (user: "I can't seem to load or save. the interface seems
  locked"). `UIActor.readWindowProperties` now clears the Window's CAPTURE listeners (the toFront on touchDown) and leaves
  it touchable. Before changing a shared loader again: grep `ui.findActor(` in every scene, not just the JSON.
- Round 166 (2026-09-10, Built 12:55, PACKAGED 13:05 (358 MB) - live folder = the 09.09 engine with rounds 158-166): **the user's release-blocker answers.** Storage button = Capitol's
  Level 2 Armory only (`RewardScene`, user decision); guard gear never cracks (true by construction, documented in
  `ArmoryStorage`); a beaten cave champion's roll is spent (`CaveChampions.onChampionDefeated` from `MapStage`'s
  defeat handler - the killed placement used to shift the placement hash onto another spot); simulated guard fights
  record reputation + statistics like watched ones (`DuelScene.recordReputation/recordStatistics`, static, called
  from `WorldStage.simulateGuardDuel`) and a spectated fight no longer spends the player's blessing/overheal; a mage
  arriving during a guard fight WAITS at the gate (`RoamingGuardRuntime.onArrival` -> FIGHT/PASS/WAIT; a winning
  guard holds the gate if another attacker waits there); deck picker + deck return page six at a time; war
  champions spawn past 150 wins (`WarChampions.injectFor` moves the rank-3 zero-weight copy to the tail). Draw/
  timeout/quit = guard loss stays by design; Balance Sheet lumping ruled fine. NOT yet playtested. **The release
  notes' Known Issues section is now stale** - the user said they will update the notes once this round is in.
- Round 165 (2026-09-10, PACKAGED 12:48 after the full stock-resource copy (engine changed) - live folder = the 09.09 engine with rounds 158-165): **upstream engine merge @ `06a3c05731c` = Forge_2's 09.09 daily** (35 commits /
  162 files / 122 java since `6155ef58a50`). One conflict (`UIScene.enter()` shader backdrop vs the round-116 null
  guard - both kept); six both-sides files, every mod line verified present. Merged to the INSTALL's exact commit (content
  probes: has `8aa0c3d0a35`, lacks `43e6b5a1397`), NOT to upstream's tip - the 22 commits after it (09-09 evening + all
  of 09-10, ten java) are the next merge. New dependency gson 2.13.2: the first Maven after the merge had to run online.
  `engineBuildVersion` 09.09. Android identity list re-checked clean (no Android file in the delta). **Everything must be
  re-tested on this engine** (rules changes: CR 605.1a mana abilities, Adventure/Omen abilities, hidden-keyword refactor,
  AI attack/fight/pump changes). Next: round 166 = the release-blocker fixes the user asked for (Capitol-L2 storage gate,
  guard gear never cracks, cave champion defeat remembered, guard fights count as player results in simulated duels too,
  attack queue while a guard fight runs, deck picker paging, war champions past 150 wins), then the notes and the stamps.
- Round 164 (2026-09-10, PACKAGED 08:15, 356 MB - live folder carries rounds 158-164): **Android / portrait pass over rounds 137-163** (user: "make sure they are
  Android friendly"). All layout twins match. Fixed: the shop page's seven programmatic buttons (2.2 x Back = 281px in
  portrait, off the left edge, Storage off the bottom) now go through `RewardScene.placeModButton` (portrait: Back's
  width/column, stacked above Detail, Storage = row 4); `map_portrait.json`'s five overlay-cycle buttons moved from
  mid-map (y 245) to the bottom bar (y 455); hire/rank-change labels `[%62]` in portrait, deck names through
  `ArmoryStorageUI.fit()` (cap 25 in portrait); standings portrait Balance/Status 2px off Back; dismiss confirm gets
  the scroller; Change Rank's Back is a half button; `names()` closes the cycle on planes without an `attacks` button.
  Left alone on purpose: Balance Sheet (no scroller - desktop readability), Exchange widths, checkbox grids (round 155),
  1px lines. KNOWN GAP: deck picker / deck return button tables cannot scroll - twenty decks overflow (needs the paged
  picker). Nothing verified on a device - the user has none; the tester checklist is in the changelog entry.
- Round 163 (2026-09-10, built 07:17; PACKAGED 07:41, 354 MB - live folder carries rounds 158-163): **the Armory storage + roaming guard equipment** (MOD_SCOPE #118,
  design `docs/design/2026-09-10-armory-storage.md` - read its decision table before changing scope). ONE storage
  per character on `AdventurePlayer.getArmoryStorage()`, `Storage (N)` on every player-owned Armory page (any level,
  one row BELOW Done), `Equipment (N)` on the roaming guard's manage screen; roaming guards only; one item per doll
  slot, no Ability items, no cracked items, no gauntlet twins. Effects apply in watched AND simulated fights
  (`DuelScene.useGuardLoadout(deck, life, effects)`; `DuelScene.applyEffects` now static; `DeckTesterSimulator.runBatch`
  overload with per-seat `Consumer<RegisteredPlayer>`); boots multiply walking speed. Gear returns to the storage on
  dismiss (all cases) and unpaid disband; a downed guard keeps it. **One owner per item**: every move is one of
  `ArmoryStorage`'s five verbs, each logging `[TFR-Armory]`. Saved as `ItemData[]` (`armoryStorage` on the player,
  `equipment` in each guard's sub-data), both `containsKey`-guarded - old saves load empty. Built WITHOUT the user's
  answers on scope (they were playing): global-vs-per-town, level gate, forfeit-with-deck are all one-line reversals
  listed in the design note. NOT yet playtested.
- Round 162 (2026-09-10, built 06:57; PACKAGED 07:41 together with round 163 - live folder carries rounds 158-163): **size classes applied + minimap overlays fixed + guard lines + dismiss
  warning + JSON Windows take no input.** (1) `world/enemies.json` now carries round 160's size classes: 396 of the 419 in-scope scales rewritten
  by the new `dev-tools/sprite_sizes.py --apply overrides.json --write` (Critter 23 / Person 211 / Medium 93 / Large 60
  / Huge 9); the user's two review overrides (Xolatoyac, The Pride of Hull Clade -> Large) are in
  `scratchpad/overrides.json`'s shape `{"name": "Class"}`. Critters below 14px, the 16 already-huge and on-grid sprites
  untouched. Data only - saves pick it up on load. (2) MapViewScene's Attacks view: lines live in their own lists
  (`attackEnds` = WORLD coords), every view + `done()` call `clearAttacks()`, `layoutAttacks()` uses the labels'
  zoom transform and re-runs on zoom (review S5/S6, user: "lines do not refresh/remove when you click through the
  views"). Mage/guard dots no longer wiped by the view. (3) Guard lines on that view: LIME outbound, dimmed green
  homeward, via new `RoamingGuardRuntime.destination(guard, day)` = the `moveGuards()` rule. (4) Roaming-guard Dismiss
  opens `RoamingGuardUI.openDismissConfirm` (disbanded vs forfeited vs no deck spelled out; red Dismiss / Back).
  (5) Overlay labels keep WORLD anchors (`detailAnchors`) and zoom re-lays them via `layoutDetails()` - the old
  transform+`resolveLabelOverlaps()` step could only push labels DOWN, so they walked off their towns (user
  screenshot). (6) Every JSON-built `Window` is `Touchable.disabled` in `UIActor.readWindowProperties` - its capture
  listener `toFront()`'d the parchment over the page on any background click (Standings page blank; third report).
  NOT yet playtested by the user. Next: the Armory storage + guard equipment feature (user: "the last item I have for
  this round, before release") - design questions in the round-162 chat, then build as round 163.
- Round 161 (2026-09-09 night, PACKAGED 23:02 - live folder carries rounds 158-161): **the agent bridge - Claude plays the game as the player** (MOD_SCOPE
  #117, design `docs/design/2026-09-09-agent-play.md`). New package `forge.adventure.agent`, OFF unless
  `TFR_AGENT_PORT` (or `-Dtfr.agent.port`) is set; `TFR_AGENT_CHEATS=1` allows console commands + fog-free state.
  Loopback HTTP: `GET /state`, `POST /cmd`, `GET /wait`, `GET /screenshot`; client `dev-tools/agent/tfr_agent.py`
  (`state --brief`, `wait`, `shot`, `cmd goto poi=...` etc.). Claude drives overworld/towns/shops/items/decks/quests;
  Forge's AI plays the duels on the player's seat (`AgentBridge.aiPilotsPlayer()` changes only the LobbyPlayer -
  equipment/ante/rewards/stats stay the player's, unlike `aiControlsPlayerSide`). Movement = A* over
  `World.isColliding` + the map `NavigationMap`, steered through `GameStage.setTouchKnobInput()` from an invisible
  ticking actor - no stock movement code touched. Clicks are real `touchDown/touchUp` on the actor's stage.
  Hooks: 3 lines in `Forge.render()` (start + end-of-frame), 3 in `MatchController.revealAnteCards` (no ante
  prompts when the agent pilots), 1 in `DuelScene`, 1 in `GameHUD.addNotification`; Forge-toolkit buttons (match
  screen, win/lose view, option panes) are exposed as `forgeUi` and tapped through `FButton.tap()`; `AgentStageAccess` /
  `AgentSceneAccess` expose package-private state. **Dev loop**: `java -cp "<classes>;<live jar>" forge.app.Main`
  from the live folder with the env vars set (scratch `agent_launch.cmd`) - no Maven per iteration. BACK UP
  `%APPDATA%\ForsakenRealms\adventure\The Forsaken Realms\` before any agent session (it autosaves) and never test
  on slot 1. Tested: Driven end to end from a scripted client against a fresh game, several times over the evening: the start menu, New Game with the screen's defaults, the intro's typing dialogs (`advance` + `click`, the "Skip the introduction" branch), the Ring gift arriving (250 gold, 10 shards, the Homeward rune from round 160's map fix), the portal out of the Secluded Encampment (the walk ends on the scene change), the world map with discovered POIs and bearings, A* walks with replans (a cave five tiles away needed an 86-waypoint detour round a ridge; a stuck walk reported the tile it stuck on), a Ring City entered and a shop purchase made (Apothecary Stomper, 100 gold - `buy` clicks the card's own buy button), a cave entered, `leave` back to the world, `wait days` stepping clear of the POI first, `explore` legs with stuck detection at mountains, and THREE roaming-enemy interceptions on the way to a cave each fought and WON by Forge's AI on the player's seat (statistics 0-0 -> 3-0, reward cards and shards paid, the win/lose view's "Back to Adventure", the reward popup's "OK" and the reward screen's Done all pressed through the bridge, control back on the world map each time). Screenshots came back from the menu, the encampment, the world map, a shop and a running match. Next: the play-loop skill and the first full Claude-played session (plan step 7).
- Round 160 (2026-09-09, PACKAGED 21:06 - live folder carries rounds 158-160): **both sprite decisions settled + 12 code-review fixes.** (1) Tier cue
  ANCHORED TO ONE TILE: `TuningData.tierSizeMultiplier(tier, baseHeight)` keeps the straight multiplier at or below
  16px and applies `(tierScale - 1) x 16` PIXELS above it, so an Archmage is +4px whether wizard or 96px boss (Akroma
  was 96 -> 120, now 100). Still game-wide. **Guards were never scaled** (a plain CharacterSprite on the hero atlas) -
  `setTierCue(guard.tier)` gives them the cue now. (2) **The size-class rule**: Tiny 8 / Critter 12 / Person 16 /
  Medium 24 / Large 32 / Huge 48, pre-tier, by SUBJECT not art, `scale = class / frame height`. New
  `dev-tools/sprite_sizes.py` (`--csv`, `--json`, `--check new.atlas`) scopes it to 419 enemies (14-48px, off
  grid; 45 critters and 16 huge left alone) and proposes Critter 23, Person 212, Medium 115, Large 58, Huge 11; 156 marked "needs eyes".
  `dev-tools/sprite_review_page.py` builds the review page the user is going through; decisions land in that page's
  database (`overrides` collection) - READ THEM BACK before round 161 applies anything. **enemies.json untouched.**
  (3) **Post-v1.08 code review** (five subsystem reviews; the changelog entry lists every finding). Fixed here: the
  roaming sweep charged the LOCAL wage table; **no Ability2 item could be equipped since round 137** (`dropUngrantedSlots`
  matched `endsWith("2")` - the user hit it: "my torch can't be attached to Aux slot 2"); the round-158 scroll-focus
  hand-off was overwritten by `UIScene.showDialog` AND `Dialog.show`; **giving a deck to a guard gutted every other deck
  sharing its basics** (strip the shortfall, not the overlap); NG+ kept the guards' old-run calendar; `chaosBattle`
  was no longer recomputed for ordinary duels; watched vs simulated guard fights used different mage life (raw vs
  x enemyLifeFactor); an in-list Rare printing survived a "no rares" starter bucket; Equip was a one-shot after the
  round-141 rebuild; round 158's town-map ability hiding was undone by `updateAbility()`; two map dialogs still granted
  "Colorless rune" (new characters got "Missing item"); the validator's field lists were stale. New `[TFR-DuelEffects]`
  line per seat - the user's "the Medal is not working" report is unconfirmed by code reading, so the next duel's log
  is the test. **NOT fixed (round 161+)**: watched guard duels run the player's post-match reputation/statistics;
  frontier spawns are DEAD CODE; cave champions can be farmed; Arzakon's fallback is unreachable; the Attacks overlay
  ignores zoom and has no exit; war champions stop past 150 wins and leak into re-themed dungeons; a failed world load
  leaves a hybrid state the next autosave persists; and the rest of the list in the changelog entry.
- Round 159 (2026-09-09, PACKAGED 13:41 - live folder carries rounds 158 AND 159): **sprite-size audit + tier scaling**. Rendered size is now
  `atlasRegionSize x EnemyData.scale x TuningData.tierScale(tier)` - tier is a SECOND multiplier, never folded into
  `scale`, because `scale` carries the artist's per-creature intent (a Ladybug is 0.5 on purpose). Defaults
  0.9/1.0/1.1/1.25 for Common/Uncommon/Rare/Mythic = Apprentice/Adept/Master/Archmage, all four in settings.json,
  all-1.0 restores the old look with no code change. Applied game-wide since showEnemyTierInName already labels
  every enemy with its rank. **DO NOT blanket-normalise sub-1.0 scales** - the audit found only 27 enemies with 16px
  art below one tile and MOST ARE DELIBERATE (Ladybug/Cat/Fox/Bat/Scarab/Crab...); round 126 also set four scales to
  0.5 on user request, so a blanket pass reverts earlier decisions. Only 10 were fixed, all humanoids/large monsters
  rendering below tile size (Zo-Zu was 4.8px, the smallest sprite in the game). STILL OPEN: **413 enemies are on
  odd-SIZED ART** (17-103px raw) and render off the 16px grid - that is 27% of the roster and the real remaining
  source of raggedness; not attempted (settled in round 160 by the size-class rule).
- Round 158 (2026-09-09, PACKAGED): **map labels DRIFT** - placeDetailLabel()
  shifted a label down without limit until it cleared its neighbours, so garrison labels came to rest over OTHER
  towns (user saw "Roaming Guard" on black towns; the data was right, the labels had walked). Capped at 4 shifts,
  dropped after that. **The Attacks overlay was unreachable** - names() closed the cycle back to Details so round
  156's 5th button was never shown (its log line appeared 0 times); names -> attacks -> details now. **The scroll
  pane engaged but could not scroll**: UIScene.showDialog sets scroll focus to the DIALOG, not the pane - focus is
  handed over now - and the 132px cap ignored the button table, so it is 96px. "Back on day X" removed. Ability
  buttons hidden inside town maps. ANSWERED FROM THE LOG: a mage that beats a roaming guard DOES carry on to the
  town (Mardrake trace), and a town does not fall just because its local guard died - capture is a separate roll.
- Round 157 (2026-09-09): **guard wages retuned** (user spec, after round 156 measured 137 gold/week income against
  1,725 out). LOCAL halved with shards added: 25/50/75/100 gold, Master +5 and Archmage +15 shards. ROAMING now has
  its OWN gold table instead of delegating to the local one: 30/60/100/150, same shards. Both still via
  `scaledCost()` so those are Normal figures (Easy 0.75x, Insane 1.5x). **mineWeeklyGoldPayout 75 -> 100.** Note the
  new shape: SHARDS are the ceiling on top-tier guards now (4 Archmages = 60/wk vs a shard mine's 20), not gold.
- Round 156 (2026-09-09): eight playtest items. **`EconomyBuildings.makeContentScrollable()`** - Dialog.show()
  packs with no cap on a 480x270 screen, so any dialog whose rows scale with what the PLAYER owns overflows; lifts
  rows into a ScrollPane only once past the cap (roster / deck picker / manage guard). **The Exchange overflows via
  its BUTTON table** instead, so its three bottom buttons pair into two rows. **Guards now `moveBy()`** rather than
  `setPosition()` - that is what sets the Walk animation and eight-way facing, so they were frozen on the Idle frame.
  **PLAYER EQUIPMENT WAS APPLYING IN GUARD DUELS**: round 145 cleared `playerExtras` (extra CARDS) but equipment is
  `EffectData` in `playerEffects`, applied by addEffects() inside initDuels BEFORE useGuardLoadout runs - gated on
  `aiControlsPlayerSide` now, blessings too. New **"Attacks" overlay** (5th in the cycle) draws mage->target lines as
  a stretched+rotated dot texture. Set-name labels removed from the Details overlay. **One enemy per game is hidden**
  - the one sharing the player's hero atlas, matched on FILE name (dragonplayer_x -> dragonkin_x), via
  `isEnemyIncluded()` so the catalog stays resolvable for quests/saves/stats.
  NOT a bug: 4 guards cost 1,725 gold/week against 137 income - a guard LOSS costs nothing (setWinner returns before
  defeated()), the payroll is the whole drain.
- Round 155 (2026-09-09): guard dialog **fits the screen** - the nine engagement checkboxes are ONE row per group
  (4 ranks, 5 colors) at font 0.55 instead of two-per-row over five rows, and the dismissal warning is one line.
- Round 154 (2026-09-09, REPO ONLY - user was playing, NOT PACKAGED): **THE ABILITY2 SLOT HAS BEEN INVISIBLE SINCE
  ROUND 137.** The gauntlet feature hid any slot whose key `endsWith("2")` - written for Left2/Right2, it also caught
  Ability2, the largest ability category (22 items). Anything equipped there was stuck and unreachable. Tests the two
  granted names explicitly now. Also: **Command Tower produces NO mana without a commander**
  (`Produced$ Combo ColorIdentity`), so two Medals were handing the AI a dead land - **round 148's Commander audit
  missed it** because it searched for ActivationGameTypes/IsCommander/command-zone and not for the card DB's own
  reliable marker, `AI:RemoveDeck:NonCommander`. Re-swept all 2,030 card refs in items.json + enemies.json with that
  marker: Command Tower was the only miss, in TWO medals. Both now give **Gemstone Mine**. Jeska's Will trips the
  detector but is fine (its commander clause only adds a third mode).
- Round 153 (2026-09-09, REPO ONLY - user was playing, NOT PACKAGED): **Pile rares now SCALE** 7/4/2/1 across
  Easy/Normal/Hard/Insane, remainder of the 9-slot top bucket filled with uncommons (user spec). They ran BACKWARDS
  before - Easy and Normal got 9 rares, Hard and Insane got 0, because **Insane had no pile template of its own and
  shared Hard's**. 20 plane-local `decks/starter/pile_<color>_<e|n|h|i>.json`; the shared common/ ones are untouched
  for other planes. Also: the round-152 deck AUDIT line counted BASIC LANDS toward the 4-of check, so all ten decks
  in the log falsely read "ILLEGAL, more than 4" over 17 Island - non-land cards only now. And **an owned item keeps
  its old name forever** (inventory is stored as whole serialized ItemData, never re-resolved by name), so renaming
  in items.json does NOT reach existing saves - new `dev-tools/save-editing/RenameItem.java` does; ran it over all
  six saves for Colorless -> Homeward rune.
- Round 152 (2026-09-08): **EVERY WATCHED GUARD DUEL WAS SCORED A LOSS.** A watched guard duel is AI-vs-AI on both
  seats = the Deck Tester shape, so `HostedMatch.endCurrentGame()` nulls `game` before GameEnd() runs and the
  2026-08-13 null-guard left `winner` at its false default. `match` outlives `game` and knows the winner - read from
  there. Also: the item **use dialog was cached in a field**, so it kept the FIRST used item's name/description all
  session ("Use Rally rune?" on the Colorless rune) and did not wrap - rebuilt per use. **Guard travel used the raw
  frame delta**, so the first frame back from a duel covered the whole journey home in one step - clamped to 0.05s.
  `assignMissions` released a guard DURING its own duel (its mage is off the enemies list because it is being
  fought) - now skips the duelling guard. NOT a bug: "out of commission until day 236" was day 206 + 30, correct;
  the label leads with the countdown now. Requests done: **Heal a downed guard for 100 shards**
  (`healShardCost`), **green minimap dots** for deployed guards (in the mageMarkers list so zoom moves them),
  **minZoom 0.25 -> 0.12**, **Colorless rune -> Homeward rune** (items serialize whole, so owned copies still work),
  and a **starter-deck AUDIT log line** (lands, rarity split, editions, most copies of one card).
- Round 151 (2026-09-08, REPO ONLY - user was playing, NOT PACKAGED): six playtest bugs. **(1) RARITY TRAVELS WITH
  THE PRINTING** - a `["Common"]` filter passed on a card's Common printing, then `remapToEditionList` swapped it for
  the only in-list printing, which was RARE (Narcomoeba: Common in SLZ, Rare in GRN, and GRN is a Viashino set). The
  predicate now needs rarity+edition on ONE printing whenever editions are constrained; rarity-only is untouched.
  **(2) `generateCards` picks WITH REPLACEMENT and had no cap** -> 5x Air Marshal, "Invalid Deck". New
  `RewardData.maxCopies` (0 = unlimited = every other caller), stamped at 4 by deck generation only; all 15 templates
  rebuilt with DISJOINT mana-cost ranges so the per-entry cap is deck-wide. **(3)** `clearNotifications()` never
  cleared the pane's queued Actions, so seven test games' banners played over a later loaded save. **(4)** The info
  page's `scrollWindow` is a scene2d Window whose touch listener calls toFront(), burying the title/text laid out
  beside it - now Touchable.disabled. **(5)** Guard checkboxes: one grid instead of a nested Table per pair, so the
  columns align. **(6)** NOT a bug - the balance sheet's Mines line reads exactly 75 gold every week in the saves;
  the varying figure was the payday PROJECTION folding in bank interest unnamed. Now broken out.
  **(7) THE RING GIFT WAS GRANTED 3x** in two of the seven test games (found in the log, not reported) - the
  skip-intro dialog action grants unconditionally, so a second click pays again; those characters started with
  triple the kit. Now gated on a `ringGiftGranted` character flag (NG+ clears characterFlags, so NG+ still gets it).
- Round 150 (2026-09-08): **every starting mode this plane offers now follows the race's sets.** Modes actually
  available here are Standard / Constructed / Pile / Chaos / Custom - **Commander needs a `commanderDecks` table this
  config lacks, and Precon/CommanderPrecon need `decks/starter/precon|commanderprecon/` folders that do not exist**,
  so those three never appear. Chaos is random by design, Custom is the player's own decks.
  **Standard could not just be filtered**: its jumpstartPacks shape needs an edition with a booster TEMPLATE, only 18
  editions have one, and only 6 of 16 races have such an edition among their four - the other ten would have hit
  `nextInt(0)`. It is `mainDeck` filters now, shaped as a POOL (looser curve to 7, 23 lands, exactly ONE rare) so it
  stays distinct from Constructed's tuned deck. Standard's set dropdown lists the RACE's four expansions + "(All my
  sets)", rebuilt on race change. **Pile** just needed routing through the restricted path (templates already were
  mainDeck filters); stays two-color and rare-heavy on purpose. `Config.racedStarterDeck()` is the shared fallback:
  requested sets -> race's four -> unrestricted, logged at each step. Constructed sizes FLIPPED per user: **40 for
  Easy/Normal, 60 for Hard/Insane**.
- Round 149 (2026-09-08): **Constructed starter decks are generated from the player's RACE editions**. The old
  "Adventure - Low <color>" .dck files drew on fourteen expansions, only two of which appear in any race's four sets.
  Now ten `decks/starter/constructed_<color>_<60|40>.json` templates (`mainDeck` RewardData filters) are narrowed at
  new-game time by `EditionProgression.raceEditionCodes(race)` -> 16 races x 5 colors x 2 sizes from ten files.
  `CardUtil.generateDeck/getDeck` gained an edition-LIST overload with a `restrictRewards` flag (the old
  single-CardEdition ones delegate with it OFF, so nothing else changed); `Config.starterDeck` takes the race and
  rebuilds unrestricted if the race's sets cannot reach `minDeckSize`. Commons/uncommons only, no rares, curve stops
  at 6 - Easy/Normal 60 cards, Hard/Insane 40 (user spec). **Every difficulty is mono-color now** (Normal/Hard/Insane
  used to hand out two-color guild decks, whose mana is unreliable once narrowed to four sets). Verified offline:
  all 80 race/color combinations have a non-empty pool in every bucket; tightest is Metathran red (2 choices at
  creature 5-6).
- Round 148c (2026-09-08, save only): Moat Keep + Skyfall written into **`1_save_slot.sav`** (slots 0 and 4) beside
  the three originals, all rewritten War-Room-free. `2_save_slot.sav` left untouched as the clean pre-test backup.
- Round 148b (2026-09-08, save only): **Skyfall (G_R)** in slot index 2 - an anti-flier deck, built on REACH rather
  than the user's suggested fliers-vs-fliers, since reach is far cheaper per point of stats and a flier deck has no
  ground defense. Spitting Spider (sac a land: 1 damage to EACH creature with flying - repeatable and one-sided),
  Arbor Colossus (monstrosity destroys a flier), Katabatic Winds, Clip Wings, Plummet, Broken Wings, Sarkhan's
  Resolve; red splashed for Bolt/Shock/Electrickery-overload. List in `dev-tools/save-editing/skyfall.txt`.
- Round 148 (2026-09-08): new **`ResourceLedger`** - the balance sheet's "everything else" lines could never be
  recomputed (a quest reward leaves no state behind), so every resource movement is now RECORDED into one of five
  buckets. Attribution is an AMBIENT bucket defaulting to OTHER: only the weekly sweep declares itself, everything
  else falls into "other" by saying nothing, and bank/Exchange transfers declare IGNORED so a deposit is not an
  expense. Hooks the four AdventurePlayer mutators (recording the ACTUAL delta, since takeGold clamps) plus three
  bank-side paths and the defeat gold burn. Sheet shows this week / last week + next payday. Persisted as four
  comma-joined int rows under `player`. Also: guard **engagement checkboxes** for rank AND color (`engageColor[5]`,
  WUBRG, riding on the same `engage` string - a pre-148 save reads 4 chars and leaves every color on); the deck
  picker gates on the **deliverable** count, not the listed one. **THE COMMANDER AUDIT**: War Room is the only card
  in 30k scripts gated to Commander game types; Acorn Amulet and Helm of Myth granted cards that did literally
  nothing (now Nut Collector / Myth Realized, names kept so saved copies are not orphaned); the Jeska reward entered
  with 0 loyalty and died on arrival (now Jeska's Will). **905 of 1,432 enemy decks carry a `[Commander]` section
  that Adventure never plays** - reported, NOT changed, it is a balance call. Save: slot 1 is **Moat Keep (W_B)**
  (Norn's Verdict was unrebuildable - both its parent decks went out with guards), guards' own decks fixed in place.
- Round 147 (2026-09-08): guard duels fight an **ante-free clone** of the mage (the player is not in the fight);
  **garrison labels on the Details minimap overlay** - that overlay already carried "Under Attack!", so guards went
  there rather than into a 5th mode; new **`BalanceSheet`** (weekly mine income, bank interest, local vs roaming guard
  wages, net, on hand) reachable from the Bank, the Exchange and World Standings, recomputed live from the same
  helpers the weekly sweep pays from. Standings title `[%55]` -> `[%80]`.
- Round 146 (2026-09-08): **THE SHARED-CARD BUG**. Adventure decks are VIEWS over one collection - the editor lists the
  same single card in several decks. Handing a deck to a guard removed its cards but left other decks still listing
  them, so those decks looked full, built short, and could still be PLAYED (real duplication at the deck level).
  `giveDeck()` now strips the cards from every other deck and the picker warns first. Verified no cards were ever
  lost: 1,615 in the collection + 83 held by guards = 1,698, the clean save's total. Also: an **unarmed guard draws no
  wage** and says so in red; every roaming button is one width (fixes Android too, via the shared 118px portrait
  helper); Info returns to the roster via a one-shot flag in `RewardScene.enter()`; `[+Life]` glyph; Archmage not
  Mythic.
- Round 145 (2026-09-08, REPO ONLY - **NOT PLAYTESTED, NOT PACKAGED**): **the roaming guard**, MOD_SCOPE #116,
  built in one pass. Capitol-only Local/Roaming fork; a roaming guard carries one of the player's DECKS (cards leave
  the collection, slot empties), walks the overworld with the player's sprite, races an attacking mage TO THE TOWN
  (not a chase - mages run 50-60 at Mythic vs a guard's 40 cap, so only a race to a fixed destination can work; a
  teleporter at the target town skips the race), and fights a real AI-vs-AI duel. Max 4. Watch or Simulate - the same
  match either way (`DeckTesterSimulator` gained a starting-life overload so they cannot diverge). Death = 30 days out;
  dismissing then forfeits the deck, every other exit returns it.
  NOTE **launching a duel from the day tick is safe** because in-game time only advances inside WorldStage's
  `player.isMoving() || waitingForTime` block - a guard can never intercept while the player is in a town or dungeon.
  NOTE the guard stores the EXACT card list it was given; never recompute the round-trip from the deck.
  **FIRST PLAYTEST CHECKS**: hire at the Capitol Armory (needs Level 2), give a deck, confirm the slot empties and the
  collection drops; let a mage target one of your towns and watch for `[TFR-RoamGuard] dispatched`; confirm the guard
  sprite appears and moves; confirm a win shows "broke the attack" and the guard walks home; confirm taking the deck
  back restores the exact cards. Every line is `[TFR-RoamGuard]`.
- Round 144 (2026-09-07): review items **4.7** (`WorldSave.SAVE_FORMAT_VERSION` = 1, written by save() and checked
  at the top of load() BEFORE any sub-object is read - a refusal sets lastLoadError, which the menu already shows;
  absent key reads as 1. **Bump only for a change older code cannot read - adding a field does not qualify**) and
  **4.1** (`[TFR-Mem]` native/Java heap line per in-game day beside `[TFR-DayTick]`; four native leaks shipped once
  because the Java heap never showed them). The user's **working agreement is now at the top of this file** - treat
  their input as a proposal to evaluate, analyse before agreeing, correct a bad premise at the TOP of the reply.
  Log review 2026-09-07 19:29 (240 in-game days): no exceptions, `[TFR-Render]` count 0, one upstream Scryfall 404.
  Day-tick cost drifting up - first 50 days avg 177ms, last 50 avg 305ms, worst 631ms, territory dominant.
  **MOD_SCOPE #116 holds the roaming-guard design** (analysed, not started).
- Round 143 (2026-09-07): **code review S4-6 closed** - `Adventure.render()` logs the FIRST of each distinct
  swallowed exception (class + top stack frame) plus a count every 600 repeats, instead of silencing outright.
  `[TFR-Render]`. **The five "Find the X Capital" quests (87-91) now pay +2 reputation with the PLAYER'S OWN colours**
  via new `ActionData.addColorReputationPlayerColors` + `ColorReputation.addToPlayerColors()` - a FLAT add per identity
  colour, deliberately not the zero-sum applyPattern() wheel (a 5-colour player would net zero). Answering the user's
  "I got rep with the green capitol vs. my capitol" - the quests paid no reputation at all, and there is no
  player-side reputation score in this game; identity colours are the nearest true thing. **Jodah's 3 Sol Rings
  restored** (`Alt-Art_Staples.dck`); the 280-card `High_End_Alt-Art.dck` stays out. NOTE `[+Reputation]` is NOT a
  glyph - no atlas defines it; only `[+Gold]`/`[+Shards]` and the item icons exist.
  **Decks written to `1_save_slot.sav`** (backup `prededit7.bak`): slot 1 "Norn's Verdict (W_B)" 40 (new - Elesh Norn,
  Reaper, Angel of Sanctions, Sidisi, 2x Mirror Entity, Bitterblossom, 9 removal), slot 2 "Gravetithe (B)" 40, slot 3
  "Dawn Bulwark (W)" 44 (their own build kept, weakest cards swapped). Lists live in `dev-tools/save-editing/`.
- Round 142 (2026-09-07, REPO ONLY - user was playing, do not package): **frontier spawns**. The 111 enemies that were
  reachable NOWHERE now roam terrain whose colour is UNHAPPY (10%) or at WAR (15%), matched per colour LETTER so a
  multicoloured legend is eligible in several biomes; the 3 colourless ones take NEUTRAL terrain (2%). New
  `FrontierSpawns` + `config tables/frontier_spawns.json`. Defined by PREDICATE not a name list (spawnRate<=0, rewards,
  !boss, no questTags, NOT Mythic, scale>1.5, life<maxLife=60) - that is exactly what stranded them, and `maxLife` keeps
  the 70-life hand-placed Eldrazi titans out. Respects the rank filter (unlike war champions). Both injected groups are
  now weighted against the ordinary pool total captured BEFORE either is added.
  **MOD_SCOPE #115 parks the autopilot/spectator findings** - the duel half already ships
  (`DuelScene.aiControlsPlayerSide`, Deck Tester "AI vs. AI (Watch)"); only the overworld half is missing.
- Round 141 (2026-09-07): **arena weekly lock moved from PAYOUT to ENTRY** (user correction - fighting for nothing was
  worse than being turned away); `weeklyArenaLocked()` gates the button, the click AND the fee point, because
  `setDisabled()` does not detach handlers here. **Inventory sell exploit fixed**: `itemLocation` was NEVER cleared and
  `updateInventory()` builds new actors, so a sold item stayed sellable - repeatable gold. Also `setSelected(null)` after
  sell/delete. **Three jackpots retuned** to the user's numbers (Meloku 1,500g/200 shards/2 random power cards; Jodah
  1,500g/150 shards/Lotus+Crypt; Arzakon 1,200g/150 shards) using new `RewardData.cardNames` (pool -> `count` distinct
  picks, card twin of `itemNames`). **112 of 1,787 enemies are reachable NOWHERE** - all of them fail the same two
  filters (not Mythic so barred from the chest pool, scale 2-4 so barred from round 139's cave pool); Arzakon is now
  reached via a new 100+-life fallback pool in `ChestEvents.pickRandomArchmage()`. NOTE the user's 100,000-gold Meloku
  win was NEVER saved (autosave 13:54 shows 219 gold, no power cards) - nothing to undo.
- Round 140 (2026-09-07): code-review items **S1-1** (world load refuses instead of silently regenerating the world;
  `[TFR-Load] WORLD LOAD FAILED` + a menu dialog), **S2-4** (a town changing hands keeps NOTHING - new
  `TerritoryControl.forgetTownState()` destroys the changes entry under both the old and new POI id plus every id-keyed
  World record; user's rule, and it closes the name-round-trip resurrection), **S2-6** (a config.json/settings.json that
  exists but will not parse records `Config.fatalDataError` and the menu says so), **S6-1** (`.claude/settings.json`
  untracked - it carried `bypassPermissions`). Still open from the review: the whole "Eventually" table, `saveFormatVersion`,
  the `[TFR-Mem]` heap line, S4-6 (Adventure.render still swallows silently).
- Round 139 (2026-09-07): **the arena-exclusive roster reaches the world**, two ways, WITHOUT touching enemies.json -
  `spawnRate <= 0` is both the roaming exclusion AND ArenaScene's champion-bounty flag, so editing it would cancel the
  bounty and release them at full uniform weight. (1) **Cave champions**: every `type: "cave"` POI (all 209) rolls once
  on first entry, `caveChampionChance` 0.25, for one of **679** eligible arena-exclusive enemies to take over one
  ordinary roamer - `CaveChampions.java`, persisted in `World.caveChampion` **including the misses** so re-entry cannot
  re-roll or farm, picked in `MapStage.prepareCaveChampion()` before the layer loop, never displacing a boss or quest
  target. `[TFR-CaveChampion]`. (2) **War champions**: `config tables/war_champions.json` casts 5 mono-coloured
  Archmage champions per colour who roam that colour's biome at 20% of its rolls **only while at WAR** -
  `WarChampions.java`, appended in `BiomeData.getEnemy()` AFTER the rank filter (every arena champion is difficulty 3;
  `rank()` needs 150 wins) with the share solved against the rest of the distribution, not set as a flat weight.
  NOTE why not just give them a small spawnRate: `SpawnTierWeighting.rawSpawnWeight()` IGNORES spawnRate for
  non-exempt candidates, so 0.01 would make one exactly as likely as any other Mythic in the biome.
- Round 138 (2026-09-07, REPO ONLY - not packaged): **Teleporter repriced by LOCATION and the network widened to
  six**. Capitol base 200 -> 100 shards (75/100/125/150 across Easy..Insane); towns an exact 10 shards at every
  difficulty; `MAX_TOWN_TELEPORTERS` 4 -> 5. `buildCostFor()` now has one location-dependent entry
  (`teleporterCost()`). NOTE the town price could NOT be a normal base - every cost in EconomyBuildings is a base
  scaled by 0.75/1.0/1.25/**1.5**, and no integer reaches 10 at Insane (7 -> 11, 6 -> 9) - so it goes through new
  un-scaled siblings `exactCostLabel()` / `canAffordExactCost()` / `spendExactCostAction()`. Use those, not a flag on
  the originals, for any future pinned price.
- Round 137 (2026-09-07, REPO ONLY - not packaged): two new dungeon entrance icons from the user's temple art
  (plane-local `sprites/ruins.atlas`, `TempleOvergrown`/`TempleRuined`, 32x32) re-pointing Satyr Grove, Leonin Sphinx,
  Sea Temple and Pharaoh's Fort - one from each of four different over-shared groups. Plus **Sinistral/Dextral
  Gauntlet**: each occupies one hand and GRANTS a second slot for the other (`ItemData.grantsEquipmentSlot` ->
  "Right2"/"Left2"), -2 duel life, 5,000g (the exact median), Mythic (this game has no "Legendary" tier).
  `AdventurePlayer.equip()` reworked to fill the first free candidate slot; `dropUngrantedSlots()` takes the extra
  slot's item off when the gauntlet comes off. Icons came from row 26 cols 3-4 of common items.png - **1,128 unmapped
  cells there still carry usable art**. NOTE `points_of_interest.json` is hand-edited (irregular indent) - edit it
  surgically, never reserialise.
- Round 136 (2026-09-07): **hard two-item cap on every Arena payout** (`capArenaItems` in `done()`, keeps the two
  highest-`cost` items, logs `[TFR-ArenaPayout] item cap:`), applied to the whole assembled payout but BEFORE the
  Bronze Coin ransom reclaim (that coin is the player's own item coming back, not loot). L1/AI/Chest were already at
  two; level 2 could reach thirteen. **v1.08 released** (v1.07 shipped an hour earlier without the cap; published as
  a new version rather than swapping a published tag's binary).
- Round 135 (2026-09-07): enemy TITLES resynced to tiers on the DISPLAY side only - `getTieredDisplayName()` now
  strips any of the four tier labels, not just a matching one, so `Master Blue Wizard` (Adept) reads `Blue Wizard
  (Adept)`. **Deliberately not a data rename**: the raw name keys quests, .tmx refs, deck numbers, biome/arena lists
  AND the save's enemyPermanentKillCount + coinRansomedEnemies, so renaming six enemies would touch 9-12 plane files
  each and silently orphan save keys mid-run. Also: **one Arena tournament WIN per venue per week** - seven venues
  (5 AI capitals by POI id, the player's arena split by MODE into :L1/:L2), new persisted `World.arenaWinWeek` keyed
  to `getCurrentWeek()` = day/7, enforced at the top of `ArenaScene.done()`; only a FULL bracket win consumes or is
  refused by the allowance, entering/fighting/partial runs are never blocked. `[TFR-ArenaWeekly]`. v1.07 stamps.
- Round 134 (2026-09-07): `Equipment_Medal` added to the paperdoll (ONE slot - the user was asked and chose one, not
  the six in their mock; equipment is Map<slotName,longID>, one item per slot NAME); Jewel of Blessings 30,000/Uncommon
  -> 12,000/Rare (Jewel of War and Jewel of Rage deliberately left at 30,000/Uncommon); Arena payout spec finished -
  a bonus Common item at 0.3/round from round 2 with a hard cap of ONE (rolled in `done()`, since a reward table cannot
  cap across rounds), level 2 rebuilt to 300/500/800 gold + four item tiers 0.25/0.40/0.15/0.05 + one guaranteed win
  item + a two-Mythic cap, the Chest's Illegal Arena following level 1 at 0.4/Uncommon, and `capitolPayoutBracket`
  widened so a **level-2 arena in Normal mode** is paid like level 1 (round 133 gated on level<2 and left it card-less);
  the Torch banner now fires only on first use (`torchPulseSeen` character flag). Log reviewed 2026-09-07: no
  exceptions, and ArenaTier/AnteReroll/ItemRefresh/DungeonClear all confirmed working in the user's own game.
- Round 133 (2026-09-07, REPO ONLY): Arena payouts for the **Player Capitol level 1 and the five AI capitals** are
  now 200 / 350 / 500 cumulative gold (round tables 200/150/150 - `done()` SUMS tables 0..roundsWon-1, which is why a
  single win paid SIX items: the Capitol's three tables each repeated the same four probabilistic item rolls, 13 in
  all) + one rare+ card per round won, themed to THAT round's beaten opponent (new `defeatedThisBracket` +
  `capitolPayoutBracket`, `[TFR-ArenaPayout]`; the engine only had a single Challenge-mode last-foe drop) + exactly
  1 item on a win. **Level 2 (`arenaChallenge`), the Chest's arena and wild arenas are UNTOUCHED** - level 2 still
  pays 1,600g + 9 item rolls with its jackpot tier at 15%. The item tiers are NOT value-banded (the 0.6 "common"
  pool held the three 30,000-gold Jewels). The round-107 champion bounty still stands - flagged to the user.
  MOD_SCOPE #111.
- Round 132 (2026-09-07, REPO ONLY - not built into the live folder): the Courier chain is three quests, not two -
  44 "Find the Caravan" now ENDS on the bandit-cave Clear (stage 5 removed, its silver-ore reveal + payment + amulet
  merged into the Clear epilogue and reworded so a runner brings the pouch to the cave), 45 renamed **"Explore the
  Crystal Mines"** with a standalone description and only its three mine stages, and new **86 "Word to the Courier"**
  holds the reporting tail; chain reads 44 -> 45 -> 86 -> 46. Both `(+500 Gold)` lines now use the `[+Gold]` glyph.
  New quests **87-91 "Find the <Colour> Capital"** at the Player Capitol (250 gold / 5 shards / 2 rares of the
  colour), gated by the new out-of-band `requiresCharacterFlagUnset` in AdventureQuestController on
  `visitedCapital_<colour>`, which TileMapScene sets on capital entry. The Player Capitol needed its own quest giver
  (`questtype "player_capital"`, object 104 in player_capital.tmx) - its only giver was tagged `waste_town_generic`,
  shared with every wasteland town. MOD_SCOPE #110.
- **HEAD = round 132 (verify with `git log -1`), `main` level with `origin/master`, tree clean.** Rounds 120-130 are
  post-release fixes and additions. Round 126 = **a loss's deferred follow-up no longer survives a save load**
  (`GameStage.cancelPendingActions()` from `WorldStage.clearCache()`, `[TFR-LoadReset]` - the user's "load after losing
  and the dungeon disappears when you enter") and four mis-sized sprites (Arcane Golem was scale 3 on a 96 px atlas =
  288 px; Shorikai, Dementia Beast, Blech to 0.5). Round 125 = **Arena fighters play their own decks** (upstream's Hard/Insane genetic-AI
  override in DuelScene is gone) with **tier-weighted brackets** at AI capitals and level-1 player arenas (Adept 50 /
  Master 35 / Archmage 15, never Apprentice, `[TFR-ArenaTier]`), **78 generated caves** (13 per biome, `dev-tools/gen_caves.py`,
  POIs `Cave<L>Gen01..13`, NEW WORLDS ONLY), **attacking-mage base 3 -> 2** (Easy 1 / Normal 2 / Hard 3 / Insane 4) and
  **decks v6** in save slot 1. Round 124 = the review's data fixes (56 dangling enemy rewards removed, 45 map enemy names, Horror shop, Random sign, the
  plains_town_generic slot), the **Torch pulse** (Torch/Grand Torch use = 1 shard, vision x3 for 2 s, `torch pulse`,
  settings.json torchPulse*), and saved items re-read from items.json on load (`[TFR-ItemRefresh]`). Round 123 = the **deep code review** (`docs/review/2026-09-05-code-review.md` -
  read its section 1 and 5 before touching World/WorldBackground/TerritoryControl/save code) with its 11 applied fixes:
  the minimap re-bake and per-tile ground pixmap native-memory leaks, per-map tileset texture leaks, a sacked player
  town staying player-owned, the free Ante Re-roll, `TerritoryControl` statics surviving loads, a truncated save on a
  runtime failure, three `serialVersionUID` pins, a `BiomeStructure` index bug; plus `dev-tools/validate_plane_data.py`
  (run it before packaging) and the **Rally rune as the "Hire a guard" reward** (quest 43 stage 2 epilogue: guard
  briefing, then `grantRewards`; only quests issued AFTER this round carry it - the user's NG+ save keeps its old copy).
- **Live folder** `F:\FORGE\TFR-Standalone\The Forsaken Realms\` = the round-128 jar (built 14:53), `PACKAGE_OK` 16:13,
  on the 09.06 stock assets. **This package is the user's re-test of the merged engine** (release step 4). Verified in
  the shipped jar: `[TFR-DungeonLooted]`/`poiLootedDay` (r128) and upstream's `FrameRate.sampleAdventure`/
  `updateHistoricalPeak` (r127); shipped plane data reads `engineBuildVersion` 09.06 + `dungeonLootedDespawnFactor` 0.5.
  The full stock copy took ~75 min on F: (deletion ~25, copy ~45) - the fast path does not apply after a base-install
  change. Note `build_standalone.py` now retries `rmtree` (round 128b) - the first attempt died on WinError 145 and
  left the folder half-deleted. The user plays their NG+ Insane game from save slot 1 (12/12 life; slots 2 and 3 are
  older copies of the same character). Decks v6 (09:47, lists in `dev-tools/save-editing/`): slot 1 "Ichor Crown" (W/B
  toxic control), slot 2 "Gravetithe" (mono-B), slot 3 "Dawn Bulwark" (mono-W, 44 cards, SELECTED - the user tunes this
  one in-game, keep their additions when updating). Backups `1_save_slot.sav.prededit2/3/4/5.bak` sit beside the save -
  do not delete them (WriteDecks now takes the first free number instead of overwriting prededit4). The saves + log live in `%APPDATA%\ForsakenRealms\` (`adventure\The Forsaken
  Realms\<n>_save_slot.sav`, `forge.log`).
- **Android testers are still on the v1.05 APK**: rounds 120 (Guards dialog behind the cards), 121, 122 and 123 ship with
  the next APK. Build it from a C: copy of the repo - the F: USB build took 2h17m (antrun copying 20k files). ANDROID_RELEASE.md.
- **Upstream: MERGED, round 127.** `main` now carries upstream `master` @ `6155ef58a50` (14 commits / 37 files /
  15 java past the old merge base `042b3267af7`). **Zero conflicts**; all seven both-touched files auto-merged and
  354 mod-added lines across them were re-checked present. Review section 4.8's predicted conflicts (World,
  WorldStage, MapStage, AdventurePlayer, RewardScene) did not happen - upstream touched none of those five.
  `engineBuildVersion` is now `2.0.15-SNAPSHOT-09.06`. See MOD_CHANGELOG round 127 + CORE_ENGINE_CHANGES' merge log
  for what upstream changed (FrameRate sampling refactor, GameHUD owns its Batch, `delayedSwitchBack(title,message)`,
  two AI behavior changes in `AiBlockController`/`ChangeZoneAi`).
- **`E:\GAMES\Forge_2` needs NO reinstall for v1.06** - it is already the 09.06 daily
  (`.installationinformation`: `forge-installer-2.0.15-SNAPSHOT-09.06.jar`; `build.txt` `2026-09-06 18:21:40`),
  which content probes place at upstream `53a103721d6` = 13 of the 14 merged commits. The 14th is Realm-of-Legends
  plane data with no java, so repo and install run identical engine code and the packager's daily guard passes.
  **How to re-identify a daily** (do this instead of trusting dates): read `.installationinformation` for
  `snapshot-version`, then probe two files whose commits straddle the candidate window - one that must be present,
  one that must be absent.
- **v1.06 RELEASE, where it stands** (started 2026-09-06 evening). Release rule order, with status:
  (1) upstream engine merge as its own round - **DONE, round 127**; (2) base install at the matching daily -
  **DONE, no reinstall was needed**; (3) rebuild - **DONE** (full Maven `package` of `forge-gui-mobile-dev` on the
  merge); (4) **the user re-tests the merged engine** - the gate before anything is stamped; (5) then: bump plane
  `config.json` `modVersion` 1.06 / `modVersionDate`, `forge-gui-android/pom.xml` `tfr.version` 1.06 +
  `manifestVersionCode` 10600, write `RELEASE_NOTES_v1.06.md` (rounds 120-127: the merge plus every post-v1.05
  round), desktop zip via `python standalone-packaging/build_standalone.py --out C:\Users\User\TFR-Release --zip`
  (**NEVER repackage the live folder while `javaw.exe` runs** - the user may be playing; the `--out` build is the
  release/backup copy), Android APK + `assets.zip` per ANDROID_RELEASE.md **from a C: copy of the repo**, tag
  `tfr-v1.06`, publish with `gh -R TheSAguy/The-Forsaken-Realms`.
- **Open**: (0) playtest-confirm round 126 - lose in a dungeon, reload from the menu, re-enter: the dungeon must stay
  and life must stay at the loaded value (`[TFR-LoadReset]` line on the load); the Mages' Fort golem must be three
  tiles tall; (0b) playtest-confirm round 125 - an AI-capital Arena bracket must show no Apprentice fighters, each playing
  its own deck (`[TFR-ArenaTier]` line); a NEW world must place the Cave<L>Gen caves (walk one: mouth, patrols, loot, no
  stuck enemy); `[TFR-MageCap]` must read base=2; the three v6 decks must load; (a) playtest-confirm rounds 121-122 (Trading Post/Exchange exclusivity, town-exit flash, cave icons per biome,
  Rally rune from the Quick Travel Mart); (b) playtest-confirm round 123 - process memory should stay flat over a long
  session (watch Task Manager while walking with fog on; before this round it grew ~100 KB per tile stepped and 31 MB per
  minimap bake), a sacked player town must read as neutral the next day (`[TFR-Ownership]` line), an Ante Re-roll must
  cost shards that stay gone after the match (`[TFR-AnteReroll] in-match mana shards` line), and a new game must get the
  guard briefing + Rally rune when "Hire a guard" completes; (c) the review's "fix before release" list (section 5):
  loud failure on a broken save instead of silent world regeneration (S1-1), re-keying per-town state on capture (S2-4 -
  needs the user's answer to open question 1: what does a lost town keep?), loud config parse failures (S2-6), the data
  fixes the validator lists (33 missing reward items, 45 unknown map enemies incl. the Skep Slivers, 2 unknown shop
  types, Slobad's `"Card"`, the `RandomShop` sign), logging the exceptions `Adventure.render` swallows (S4-6), untracking
  `.claude/settings.json`'s `bypassPermissions` (S6-1); (d) MOD_SCOPE #84 Building Upgrades and #85 New Quests are the
  only Not Started items; #11 Map Polish is In Progress; #104 Rally Rune awaits playtest.
- Rules that held all week: package only when `tasklist | grep javaw.exe` is 0 - the user plays between rounds and
  "repo only" means exactly that; one Maven at a time, always backgrounded (~15-17 min); pushes are routine since
  2026-09-05 (push after each round); every round updates MOD_CHANGELOG + this file + CORE_ENGINE_CHANGES in its commit.
- Round history (this section's list, newest first) and the round-86 state block that used to head this file follow.

## Round history 2026-09-02 -> 2026-09-05 (the round-86 state block is kept as written; newest rounds first below it)

**Token budget warning.** This session hit the 5-hour usage limit THREE times running multi-agent
Workflows (each attempt burned ~1.3M tokens before dying). Do NOT relaunch review/research
workflows. Work solo or with single agents; the user asked for economy.

Done today (committed):
- Round 83 `d76f3f343ff`: NG+ and Arena-coin log lines (Array.size fix), Android-build trap docs.
- Round 84 `4509df9c0ae`: **upstream merge @ c817743ecbd = Forge_2's Snapshot 09.01**. 4 conflicts
  resolved, CON->CFX edition sweep (20 plane refs), engineBuildVersion 09.01, packager marker.
- Round 85 `99b6856e9a9`: dungeon audit - 94 stone/wood drops relocated (verified in bounds, no
  collision, no overlap), 11 teleports retargeted, 50 card rewards re-keyed cardName, Mantle of
  Denial typo, 3 boosters, 1 effect, 1 dialog. MOD_SCOPE #101 updated. Left by design: 4 zedruu
  drops, inn_cave_river_entrance enemy id 16 (empty enemy name).
- Round 86 (this commit): World.generateNew resets fogOfWarStage2Revealed; DungeonRotation.
  resetSessionState() + TerritoryControl.clearPendingCapitolDefense() from WorldStage.clearCache();
  triggerGameLost clears suppressDefeatGoldLoss for every loss path; [TFR-MageCap] de-duplicated;
  NG+ log labels fixed; build_standalone.py daily-stamp guard (--allow-base-mismatch) + early
  launcher checks. STAR_TOWNS_RESEARCH.md has the re-verification addendum.
- Live folder: PACKAGE_OK with the round-85 jar (built 11:29). **After round 86 the jar must be
  rebuilt** (`mvn -pl forge-gui-mobile-dev -am package -DskipTests -o`, ~15 min, ALWAYS backgrounded)
  and `python standalone-packaging/build_standalone.py` re-run. Check the live jar's mtime against
  the round-86 commit before assuming it was done.
- Game log reviewed (forge.log 10:54-11:09, idle 139-day Viashino game): no exceptions; only the
  MageCap spam (fixed). Session #11 (Opus) stood down; its save backup
  `1_save_slot.sav.prededit2.bak` in the profile dir must NOT be deleted.
- Code review: only the newgameplus and android/packaging lenses ever completed. Never run:
  merge-integration, save-compat, economy, ui-dialogs, territory-spawns, data-integrity. If wanted,
  run ONE lens as a single agent.
- NOT pushed. Standing rule: the user playtests the live folder first, then `git push origin
  main:master`.
- Upstream moved 5 commits past c817743ecbd; take them with the next engine update + Forge_2
  reinstall. Optional: upstream added MSH to common starterEditions; TFR's list untouched.
- Round 130 (2026-09-06): ConfigData.disableGeneticDeckOverrides (default FALSE, true only in TFR's config.json)
  switches off BOTH of upstream's Hard/Insane deck substitutions - the LDA archetype branch (discarded the authored
  deck of any enemy with catalog life > 16, i.e. 1,415 of 1,787, for a random Standard/Modern/LEGACY list when the
  "Generate LDA Decks" setting is on) and CardUtil.getDeck's random-precon swap of .json deck TEMPLATES (.dck paths
  always returned early and were safe). [TFR-DeckOverride] logs once per session. Traced from the v1.03 tester report
  about "mana vault / mana crypt turn 1 then 10 turns of nothing"; the Arena half of that was round 125 and was still
  live in the RELEASED v1.05. The report's "treasure chest sprite" was not a bug - both encounters were Chest world
  spawns whose 1-in-6 Illegal Arena event opened the arena; Progenitus is spawnRate 0 = Arena-exclusive.
  **v1.06 stamps applied**: modVersion 1.06 / modVersionDate 09.06 / tfr.version 1.06 / manifestVersionCode 10600.
- Round 129 (2026-09-06): floating "+N Shards" pickup labels are tracked on GameStage and dropped on a map swap or
  load (they were parked on the MapStage singleton mid-animation and resumed over the NEXT map - the user's "+2 Shards
  when I enter a town", text only); 51 winged enemies gain flying (15 basic/dragon, Vampire Bat, Fluttering Pixie, 5
  Dragonkin, 29 stranded on atlases whose siblings fly) - NOTE flying is a MOVEMENT flag (ignores terrain, beelines),
  and it tracks the CARD not the sprite, which is why Santa and a hippo fly; decks v7 (prededit6.bak).
  **The round-125 v6 decks were never in the save the user plays** - all four slots held the v5-era lists; verify decks
  against the save, not the changelog.
- Round 128 (2026-09-06): a rotatable dungeon walked out of with every reward pickup taken but enemies still alive
  halves the days left on its despawn timer (DungeonRotation.onDungeonLooted, [TFR-DungeonLooted]); once per visible
  lifetime via the new persisted World.poiLootedDay; active quest targets exempt; MapStage.clearDungeonIfEmptied ->
  applyDungeonExitRules; tunable dungeonLootedDespawnFactor 0.5 in settings.json. MOD_SCOPE #108.
- Round 127 (2026-09-06, repo only): upstream merge @ 6155ef58a50 = Forge_2's 09.06 daily (14 commits / 37 files /
  15 java, ZERO conflicts); FrameRate sampling refactor, GameHUD owns its Batch, delayedSwitchBack(title,message),
  AiBlockController + ChangeZoneAi behavior changes; Android revert-watch list re-checked clean; engineBuildVersion 09.06.
- Round 126 (2026-09-06, PACKAGED 11:56): GameStage.cancelPendingActions()/scheduleResultTask() + MapStage/WorldStage
  overrides, called from WorldStage.clearCache() ([TFR-LoadReset]); enemies.json scale fixes (Arcane Golem 3->0.5, Shorikai,
  Dementia Beast, Blech 0.5).
- Round 125 (2026-09-06, PACKAGED 09:54): DuelScene arena genetic-AI override removed; ArenaScene tier-weighted
  bracket pick (pickTierWeighted/isAiCapitalArena, [TFR-ArenaTier]); 78 generated caves (dev-tools/gen_caves.py ->
  cave_<biome>_NN.tmx, POIs Cave<L>Gen01-13 + biome lists, new worlds only); baseAttackingMagesPerColor 3->2; decks v6
  (WriteDecks, prededit5.bak); WriteDecks backup naming; GUIDE.md.
- Round 124 (2026-09-06, repo only until round 125's package): review data fixes (enemies.json rewards/colors/Slobad, 45 map enemy renames, Horror
  shop + Random sign in shops.json, plains_town_generic slot 51); Torch pulse (items.json + TuningData/settings.json torchPulse*,
  World.flashArea(seconds), WorldBackground/WorldStage.pulseVision, `torch pulse` command); AdventurePlayer.refreshItemDefinitionsFromCatalog.
- Round 123 (2026-09-05, PACKAGED 23:51): deep code review (`docs/review/2026-09-05-code-review.md`) + its 11 fixes
  (S2-1/S2-2 pixmap leaks in World/WorldBackground, S4-1/S4-2 TiledMap leaks in TileMapScene/MapStage, S2-3 sacked-town
  ownership + S2-5 static reset in TerritoryControl, S3-1 Ante Re-roll charge in DuelScene/MatchController, S1-2 WorldSave
  RuntimeException catch, S1-3/S1-4 serialVersionUID pins, S2-7 BiomeStructure bounds); `dev-tools/validate_plane_data.py`;
  quest 43 stage 2 epilogue grants the Rally rune. Build 06:32 min, package fast path 9 min.
- Round 122 (2026-09-05, PACKAGED 19:30): `[TFR-DungeonClear]` gated on `DungeonRotation.isRotatableData`; 48 cave icons ->
  `caves.atlas` per-biome sets + `PointOfInterest.spreadZeroSpriteIndex`; Rally rune item/shop/icon + `teleport rally`
  (`TerritoryControl.playerTownsUnderAttack/nextRallyTarget`, `World.rallyLastTargetId`). MOD_SCOPE #104.
- Round 121b (2026-09-05): live folder PACKAGED with the round-121 jar (PACKAGE_OK 17:05); log reviewed (two stock AI
  TimeoutExceptions, nothing else); WriteDecks `select=<n>`; decks v5 written to save slot 1 (Ichor Crown selected). 121c = handoff docs.
- Round 121 (2026-09-05, repo only): one Trading Post OR Exchange per town (Trader gate also checks EXCHANGE); leaving a
  town/Capitol/castle replays the discovery flash (World.flashArea -> WorldBackground.flashDiscoveryAround, from MapStage.exitDungeon).
- Round 120 (2026-09-05): UIScene keeps the top dialog in front every frame + toFront after show - fixes the portrait Armory
  Guards dialog hidden behind RewardScene's re-added card actors (Android tester report). Android gets it with the next APK.
- Round 119 (2026-09-05): **v1.05 'Fight Back' RELEASED** - tag tfr-v1.05 @ 5f520118bdd, desktop zip + Android APK/assets.zip on
  GitHub; stamps 1.05 / 10500; packager `--out DIR` builds a release beside the live folder (30 s on C:); R: subst needed for Android.
- Round 118 (2026-09-05, repo only): one Jumpstart per run (jumpstartPlayed flag set in startEvent, checked in createEvent);
  TileMapScene ruined-town flag also skips Ring Cities (log showed Llanowar flagged ruined); slice v2 = Mythic 20 / Rare 30 (B 40) /
  rest split C-U, generated decks re-picked on tier change, own standard decks resized 40..80 (r118_rebalance.py).
- Round 117 (2026-09-05, repo only): remaining packs (PUNY MYTH, LPC 64px, Pixel Character Pack, Dark and sharp) -> 86 enemies
  incl. 7 pinned Mythics (U x3, G x4, hand-built decks); LPC rows 2/6/10/14/18/20 are the down-facing spellcast/thrust/walk/slash/shoot/hurt;
  PUNY battle portraits as Avatar regions; slice re-run with an idempotent jitter (template stats only).
- Round 116 (2026-09-04, PACKAGED with 112-115): per-colour tier slice by deck strength 30/30/30/rest (B 40/40/40/rest),
  wizard families + bosses pinned, r115 Mythics pinned, promoted enemies never weaker; life/speed jitter (name-seeded);
  enemies.json re-serialised uniformly (json.dumps indent 4) - edit it via json from now on.
- Round 115 (2026-09-04, repo only): 29 enemies from the 'More Animations' packs (15 Mythic, 3 per colour, hand-built 60-card
  decks; 14 ordinary with library decks) via r115_sprites/r115_decks/r115_apply; quest-30 arrows skip Ring Cities;
  addGold/addWood/addStone play the pickup sound (dungeon drops were silent). PUNY MYTH + LPC packs NOT used (licences).
- Round 114 (2026-09-04, repo only): upstream merge @ 042b3267af7 = Forge_2's Snapshot 09.05 (25 commits/101 files/63 java);
  one conflict (WorldSaveHeader serialVersionUID pin vs upstream comment); 2,010 mod lines verified; stamps 09.05. Not packaged.
- Round 113 (2026-09-04): grantRingGift('items') skips the Challenge Coins and already-owned start items when the
  newGamePlus flag is set (NG+ reset already tops the purse up); side-boss POI types get the unvisited magnifier.
- Round 112 (2026-09-04, repo only - not packaged): ringLifeBonus now persisted (every load re-added +5; pre-fix
  saves load as -1 and adopt the target); addMaxLife clamps life to [1,max]; reputation targeting weights
  Partner .25 / Happy .50 / Unhappy 1.15 / War 1.50; guide section 'How the AI Picks Its Targets'.
- Round 111 (2026-09-04): 23 legacy decks deduped (r111_dedupe picks, roster-wide hash exclusion); quest 30 fix =
  TileMapScene skips enteredSurvivingTown for Ring Cities + quest 75 epilogue resets both entry flags before
  issueQuest 30; towers.atlas (5 user towers, 26x50) on 14 mage-tower/wizard-fort dungeons.
- Round 110 (2026-09-04): generator excludes library decks identical (sorted card list) to any deck the older
  roster uses; 63 picks changed; verified zero identical lists. Roster 1672 enemies. Data-only.
- Round 109 (2026-09-04): decks re-picked with a difficulty gate (C = precons/easy duels, U = +medium, R = medium/hard,
  M = hard/very hard; precons first for C/U) - user: 'the player should have a chance'. Data-only.
- Round 108 (2026-09-04): 105 'Enemy Art' enemies (sprites/enemy/basic/<class>/) + decks for all 152 new
  enemies from Forge precons/duels (generator r108_gen.py in the session scratchpad; roster in
  ENEMY_ROSTER_R108.md), rarity-rated tiers, biome spawn lists. Data-only. Needs a NEW game for spawns.
- Round 107 (2026-09-04): glyph notifications = authored markup + [BLACK] text (actor color multiplies images);
  Ring shops never broken (seed 0, guard, flag strip on entry); capital quests 76-85 (Partner-only, capturedFrom_<color>
  flag reset on accept, 3 rares + 1500g + 100 shards); 47 hero-based enemies (sprites/enemy/heroes, 2-page atlases).
  Needs a NEW game for the quests/spawns. Awaiting playtest.
- Round 106 (2026-09-04): Ring City layouts (ring_city_<color>.tmx + player variants with Armory lot 48,
  TileMapScene.resolveMapPath), 15 'Ring*' shops (mono inner / dual outer, all editions, 2x price, 3x escalating
  restock, no blueprints), quest 30 surviving-first, [WHITE] glyph wrap for the red heart, ringCityPullFactor 3,
  no lands at Ring 1v2, MapDialog pane height from the HUD stage. Needs a NEW game. Awaiting playtest.
- Round 105 (2026-09-04): win/lose back-splash images (ui/*_splash.jpg, WorldStage.showEndSplash; victory
  deferred while inside a map); Ring stages now CharacterFlag enteredRingCity1..5 (set in TileMapScene on
  entry - Travel fired outside the gate); [+Life] pop-ups black (authoredMarkup=false); factory_4_large.tmx
  from the user. Needs a NEW game for the quest change.
- Round 104 (2026-09-04): data-only - card shop quest popup (stock = unlocked sets, cheaper shops, 100g+5
  shard refund) + GUIDE.md wording. Packager-only chain (no Maven).
- Round 103 (2026-09-04): Warden soft-lock fixed (rune node -> reward card -> Thank you -> portal; coin branch
  removed from spawn.tmx); skip-intro 'all' gift grants directly + counts the Ring as visited (+5 life).
  Lesson: a RewardScene opened from the new-game intro dialog is lost. Needs a NEW game.
- Round 102 (2026-09-04): Ring gifts fixed (config difficulty, not the player's partial copy) and shown as
  RewardScene cards; Llanowar also gives the Warden's coins (rune stays with the Warden; coin node retired in spawn.tmx);
  nav arrows via navPOIFilter 'tagged'; ringCityTownExclusionTiles 14; VICTORY = 5 Ring Cities + 5 colors
  DEFEATED (castles), capitals only halve mages; [+Life] glyph notifications. Needs a NEW game.
- Round 101 (2026-09-03): NEW STORY OPENING - quest 75 'Oaths at the Ring' (53 -> 75 -> 30): start with
  nothing (ringGiftStart), five Ring Cities hand over the difficulty's gold/shards/wood/stone/items via the
  grantRingGift dialog action; life ladder 20/15/10/5 (+5 Ring). quests.json was re-serialized (strict JSON
  now). Needs a NEW game. Awaiting playtest + the user's read of the story text.
- Round 100 (2026-09-03): Ring Cities named Benalia/Tolaria/Urborg/Shiv/Llanowar; world-gen roads min 1 /
  max townMaxRoadLinks 5 (Ring/Spawn edges uncounted); capital Attack = 1v2 Archmages -> player town +
  halved mage cap; victory = 5 Ring Cities + 5 capitals (triggerGameWon); ring life bonus; [TFR-Perf];
  portrait layout fixes. Story/tutorial rewrite PENDING user answers (see MOD_CHANGELOG r100 notes).
- Round 99 (2026-09-03): Ring Towns - weekly per-color targeting cooldown, x1.25 weight when among the
  5 nearest, never sacked; roads back to nearest-neighbor minus 25% + capture roads go to the closest
  SEAT-connected town (road flood fill); AI-vs-AI guard fights (table in MOD_CHANGELOG); 1v2 assault at
  AI-held Ring Towns (test); TEST targeting hook REMOVED. Needs a NEW game. Awaiting playtest.
- Round 98 (2026-09-03): star 17 tiles; townMinSpacingTiles 10; per-color road trees; repaint keeps
  water; townMaxTerritoryRadius 450; TEST-ONLY debugStarTownTargetChance 0.5 (REMOVE after testing).
  Needs a NEW game. Awaiting playtest.
- Round 97 (2026-09-03): Center Towns at 20 tiles, always functioning-neutral with their own art (map +
  minimap), 24-tile no-other-town zone. Awaiting playtest on a NEW game.
- Round 96 (2026-09-03): Center Towns also road-linked to each other (full 15-edge star mesh).
- Round 95 (2026-09-03): Center Towns (MOD_SCOPE #102) - 5 star towns around the campfire with
  the user's castle art, spoke roads, loss at 3 held by one color. NEW WORLDS ONLY. NOT playtested.
- Round 94 (2026-09-03): defenders follow kill decay, life x1/1.5/1.75/2 by difficulty, -4 rep on
  attack and -8 on capture (spread), former owner dispatches a mage on capture. NOT playtested.
- Round 93 (2026-09-03): one assault per town per week (aiTownAssaultCooldownDays), the barred
  dialog states the remaining days. NOT playtested.
- Round 92 (2026-09-03): AI guard dots (28 days/level, 4 levels, capitals two Archmage) drive the
  assault defender tier; clock starts at first sight (save load/capture). NOT playtested.
- Round 91 (2026-09-03): assault win captures the town (restored-ruin state). Dungeon-on-load report
  was by design (single-enemy cave). OPEN questions to the user: tapped-land intent; AI guard-dot
  tier system spec (see MOD_CHANGELOG round 91) - do not build the dot system before they answer.
- Round 90 (2026-09-03): SAVE-WIPE REGRESSION from round 88 fixed (EffectData serialVersionUID
  pinned to the v1.04 value; nine other save-bound classes pinned). Saves written by the round-88
  build (today 10:02-10:10) are unrecoverable; pre-update saves load again. Rebuilt + PACKAGE_OK.
- Round 89 (2026-09-03): user's player-biome art update (player_terrain/doodads/structures PNGs,
  same dimensions as before). `*_original.png` backups sit UNTRACKED in the plane folder - they
  ship in the live folder until moved; do not delete them without asking.
- Round 88 (2026-09-03): multi-slot research + settings.json researchDays/researchShardCost;
  War town assault first cut (Attack/Leave at a War town, random roamer, tapped basic land via new
  engine plumbing). NOT playtested. Follow-ups the user announced: defender tier system, town
  capture. Live folder rebuilt at the end of that round - verify PACKAGE_OK.
- Round 87 `(see git log)`: `[TFR-Life]` logging at every life mutation - the diagnostic for the
  next item; live folder rebuilt with it (check PACKAGE_OK).
- OPEN USER REPORT (2026-09-02 evening): player life total wrong after losing a fight and
  loading. Screenshots in C:/Users/User/Pictures/Screenshots/LOG. Investigation notes, if any,
  are in MOD_CHANGELOG round 86/87.

## Where things stood after v1.05 (2026-09-05 evening)

**v1.05 "Fight Back" is released.** PC + Android, both live at
`https://github.com/TheSAguy/The-Forsaken-Realms/releases/tag/tfr-v1.05`. Tag `tfr-v1.05` is on `5f520118bdd`; rounds
87-119 shipped in it (town assaults, Ring Cities, the "Oaths at the Ring" opening, victory, 267 new enemies / 22 Mythic
decks, the tier slice, engine 09.05). Rounds 120-121b are committed and pushed and are in the desktop live folder, but
in no release yet.

## What v1.06 starts with — NOT open to reordering

**Step 0 is the upstream engine merge**, as its own round (standing user rule 2026-09-01). Measured 2026-09-05 22:00 at
6 commits / 9 files / 6 `.java` past `042b3267af7` (Snapshot 09.05) - small, but it still swaps the rules engine under
what was just tested and **blocks packaging until the user reinstalls `E:\GAMES\Forge_2`** at the matching daily. Raise
it early. Then the Android APK carrying rounds 120-121.

## Open items

- **MOD_SCOPE #84 / #85** — the only Not Started items (Building Upgrades: Mine Upgrades, City Walls, Mage War Camp,
  Armory Upgrade; New Quests). **#11** Map Polish is In Progress.
- **Round 121 unconfirmed in play**: the Trader no longer offered beside an Exchange; the town-exit discovery flash.
- **Cosmetic log**: the `[TFR-DungeonClear]` "despawning" line prints when leaving ruined towns too (harmless - the
  despawn self-gates). Gate the log on dungeon/cave/sideboss POI types in `MapStage.clearDungeonIfEmptied()`.
- **Shipped in v1.05 but never confirmed by the user**: the AI-vs-AI guard fights, capital 1v2 capture quests 76-85,
  Ring City 1v2 duels, one Jumpstart per run, the Ring-City exclusion from the entry flags (round 118).
- **Android has real testers** and the user has no Android device — feedback arrives via Discord. The v1.05 APK still
  carries the portrait Armory Guards-dialog bug (fixed in round 120).
- **Older saves may carry inflated life** from the pre-round-112 Ring bonus re-add; advice given: heal to full, then
  `give life -N` in the console.

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
- **Another Claude session may be live on this same checkout.** On 2026-09-02 two sessions worked
  the tree at once; the tells were a `java.exe` running Maven that this session had not started, and
  doc edits appearing in `git status` unbidden. Before committing, merging or building, run
  `ListAgents`; if a peer session is listed, message it to stand down and wait for its build to
  exit. Never kill a build you did not start.
- **`--zip` packaging always does the full stock-asset copy** and takes well over ten minutes. Do
  not give it a short timeout — killing it mid-run strips `PACKAGE_OK.txt` and leaves the live
  folder in the half-rebuilt state that marker exists to catch.
- **Read `PACKAGE_OK.txt` before telling the user it is safe to play.** File-existence checks are
  not a substitute; the packager deletes it first and writes it last for exactly this reason.
- **Edit saves with Java, never Python.** See `dev-tools/save-editing/README.md`.
- **Write the changelog entry in the SAME action as the code commit.** Rounds 73-76 and then round
  78 all shipped with detail only in commit messages and had to be backfilled. A thorough commit
  message is not a substitute and reads as done when it is not.
- **An "empty set means everything" convention must be read through its predicate at every site.**
  A raw `Set.contains()` near one is a latent bug — that pattern was a save-corrupting release
  blocker caught in round 78.
- Diagnostic logging is not optional. Anything probabilistic, AI-driven, or off-screen gets a
  `[TFR-<Name>]` line **as part of building it**. Round 82 exists because a feature shipped without
  one and a user report could not be diagnosed from `forge.log`.
# This Repo

This is a fork of [Card-Forge/forge](https://github.com/Card-Forge/forge) (the open-source MTG
engine) used to build a personal Adventure-mode mod called **"The Forsaken Realms"**. The user
works across two machines and may or may not have git sync available at any given time - **read
`MOD_SCOPE.md`, `MOD_CHANGELOG.md`, and `CORE_ENGINE_CHANGES.md` before touching any mod-related
code**, since a prior Claude Code session may have made changes here that this session doesn't
have in its own memory.

- **`MOD_SCOPE.md`** — the feature wish-list: what we want to build, current status per item.
- **`MOD_CHANGELOG.md`** — the engineering log: what's actually built, how it works, key
  gotchas. This is the source of truth for implementation details, not chat history.
- **`CORE_ENGINE_CHANGES.md`** — tracks every edit to a *stock* (non-mod-plane) engine file, so
  that when the user pulls a Card-Forge/forge update (upstream ships several a week), it's fast
  to cross-reference what upstream changed against what this mod already changed in the same
  file, instead of re-diffing everything from scratch.

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
- After committing changes to `MOD_SCOPE.md` or mod source files, push to `origin` without
  waiting to be asked (standing user preference). This was briefly reversed on 2026-08-13 after
  the user hit ~90% of their GitHub Actions monthly minutes cap (every push was triggering CI) -
  user addressed it on the repo side (trimmed/disabled the relevant workflow(s), including this
  same round's `.github/workflows/test-build.yaml` auto-trigger removal) and explicitly asked to
  resume pushing the same day. Back to the original standing preference.
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

## Release rule: take the upstream engine update FIRST

**Standing user preference (2026-09-01): always merge the latest `upstream/master` before cutting a
release.** It has to be its own round, planned ahead of the release rather than bolted onto it:

- An engine merge is large - measured 2026-09-01 at 34 commits / 1,812 files / 174 `.java` - and it
  swaps the rules engine underneath whatever was just playtested. **Everything must be re-tested
  after it.** Round 58 was the last one, and round 59 immediately after it was a playtest-fix round.
- **It blocks packaging until `E:\GAMES\Forge_2` is reinstalled** at the matching engine version.
  `build_standalone.py`'s first step verifies its jar version against the repo's and aborts on a
  mismatch. Only the user can do that step - flag it early, do not discover it mid-build.
- **Upstream clobbers our Android branding and version stamps** - `forge-gui-android/pom.xml`, the
  launcher icons, the splash art, `Zone.java`. `ANDROID_RELEASE.md` carries the revert-watch list;
  read it as part of the merge, not afterwards.
- Resolve `README.md` conflicts to OURS (it is the game's readme, not upstream Forge's).
  `CORE_ENGINE_CHANGES.md` exists to make the conflict pass fast - grep it per conflicting file.

So the release order is: **merge upstream -> reinstall BASE_INSTALL -> rebuild -> user re-tests ->
then tag and publish.** v1.04 shipped WITHOUT the merge by explicit user decision, because the merge
would have invalidated a full day of playtesting; it is the first work of v1.05.

## Build/toolchain

Maven + JDK are installed portably on each machine (not tracked in git). Verify with
`mvn -pl forge-gui-mobile -am compile -DskipTests -o` (add `-o` once dependencies are already
cached locally) before considering a change done.

## Deploy (live game folder at `F:\FORGE\TFR-Standalone\The Forsaken Realms\`)

**This is the folder the user actually plays.** The old `E:\GAMES\FORGE` three-jar splice-deploy
target is **retired** (since round 26, 2026-08-19) - don't touch it, and don't reintroduce `jar uf`
splicing: the standalone packaging script rebuilds the whole folder instead.

**Do not confuse the retired folder with `E:\GAMES\Forge_2`, which is very much live.** That is
`BASE_INSTALL` - where the user installs the latest **stock** Forge whenever we take an upstream
engine update. The packaging script *reads* the launcher shells and the installer-shaped `res/`
tree from it (`cardsfolder.zip` etc., which this repo does not hold in that shape), and its very
first step refuses to build if `BASE_INSTALL`'s jar version does not match the repo-built jar's.
So an upstream engine merge is **two** steps, not one: merge the engine code here, *and* have the
user install stock Forge at that same version into `E:\GAMES\Forge_2`. A version mismatch also
auto-forces the full stock-asset re-copy, so the first package after an engine update is slow by
design. Read-only input for us - never write to it.

Deploy loop, in order:
1. **Compile check** - `mvn -pl forge-gui-mobile -am compile -DskipTests -o -q`. Fast; catches
   errors and checkstyle before the slow step. Don't skip it - a Maven build that fails unnoticed
   is a real failure mode here (see the freshness guard below).
2. **Build the jar** - `mvn -pl forge-gui-mobile-dev -am package -DskipTests`. Produces the
   `forge-gui-mobile-dev-<ver>-SNAPSHOT-jar-with-dependencies.jar` *and* the launch4j exe carrying
   the TFR icon. This is the only jar the game ships - the three-jar problem is gone with the old
   deploy target.
3. **Package** - `python standalone-packaging/build_standalone.py`. Assembles
   `F:\FORGE\TFR-Standalone\The Forsaken Realms\` from `BASE_INSTALL` (stock engine shell) + the
   repo-built jar + `forge-gui/res/adventure/The Forsaken Realms/` + a git-derived overlay of the
   mod's non-adventure `res` edits (so future rounds' res edits are picked up automatically).
   The plane folder is **always** rebuilt fresh, so a changed resource needs no separate copy step -
   and unlike the old `cp -r` mirror, files deleted from the repo really do disappear from the live
   folder.
   - `--zip` **only** when building a release asset; it also forces the full stock-asset copy. No
     local zips are kept - upload it, then delete it from `F:\FORGE\TFR-Standalone\`.
   - `--full` forces the stock-asset re-copy on a local build - needed only for suspected local
     corruption. An engine-version change forces it automatically without the flag.
4. **Read `PACKAGE_OK.txt`** in the live folder before telling the user it is safe to play. The
   script deletes it first and writes it only after full verification, so its presence is the
   playability contract. **Never substitute file-existence checks for reading this marker** - a
   half-rebuilt folder looks complete right up until the game hangs forever on the stock splash
   (2026-08-21 incident).

Two guards the script enforces - know them before debugging a refusal:
- **Jar freshness**: it refuses to package a jar older than any `.java` under the built modules.
  Added 2026-08-30 after a failed Maven build got silently packaged and declared "safe to play".
  Plane resources are deliberately exempt - they are copied, not compiled, so a resource newer than
  the jar is legitimate.
- **Game-running lock probe**: it refuses to start if a jar in the live folder is locked, i.e. the
  game is open. It aborts *before* removing `PACKAGE_OK.txt`, so a refusal leaves the folder exactly
  as it was and still playable - just close the game and re-run.

Release order is unchanged and still applies on top of this: build + update the live folder, **user
tests it**, and only after their explicit pass does anything get pushed or published.
