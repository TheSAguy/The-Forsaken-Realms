# The Forsaken Realms — Main Quest Redesign Plan

Working document for the pre-release main-quest rework (2026-08-26). Part A/B are the story
bible and dialog drafts (creative content, edit freely). Part C maps every new objective onto
what the quest engine can actually express today (verified against the code, see
`AdventureQuestController.ObjectiveTypes` / `AdventureQuestStage.handleEvent()`). Part D lists
the small Java hooks that must be added first. Part E is the side-quest conversion checklist.
Nothing in quests.json has been changed yet — this is the reviewable plan.

---

## A. The Backstory — "The Forsaking"

*(Why the lands are ruins. Why the Five grab land. Why you wake. Every beat maps to a real
game mechanic — noted in [brackets].)*

Long before the greying, this was a confluence plane — mana pooled here like water finding a
low place. Its heart was **Orazca**, the Golden City [the player Capitol], seat of the realm's
Guardian: **you**. An oldwalker who gave up wandering, you bound your own spark into the land
itself to keep it whole. Under your wardenship the **Weave** — the living lattice of the
realm's magic, recorded in the great **Volumes** [the card editions, recovered at Research
Labs] — held every color in balance.

Five planeswalkers came as guests. Scholars, they said. Each attuned to a single color, each
hungry for the confluence. They could not take a realm whose Guardian was fused to its
leylines — killing you meant killing the prize. So they forged another way: the five **Seals**
— pearl, jet, sapphire, emerald, ruby [the five Moxen the castle bosses drop] — instruments
that could bind what could not be killed. On the **Night of Chains** they buried you beneath
the earth in a prison of stasis [the Spawn cave], anchored to the realm's own veins.

They misjudged. You *were* the keystone. With the Guardian sealed, the Weave began to
unravel — and the color drained out of the world. Literally: the land greyed to ash and dust
[the colorless wasteland biome]. Cities crumbled into ruins [the ~300 ruined towns]; the
Volumes scattered and burned [why sets must be re-researched]; the Weave itself shattered into
crystal splinters that men now dig from the ground and trade like coin [Shards, shard mines].
The Five fled the backlash rather than be consumed by it. Millennia passed. Among
planeswalkers the plane became a byword: **the Forsaken Realms** — forsaken by its Guardian,
forsaken by fortune, forsaken by hope. Only embers endured: a handful of towns huddled around
surviving mana springs [the ~20 pre-seeded functioning neutral towns], and a quiet order — the
**Wardens** — who remembered the truth and kept vigil over your prison, generation after
generation, until a single old man remained [the hooded mage at Spawn].

Now the binding is failing. Millennia of feeding on a dying plane has starved it thin — and
the Five felt it too. They have returned: not to mend what they broke, but to carve it up
before it wakes. Five castles at the compass points; five colors crawling back across the
grey, mile by mile, tile by tile [Territory Control's daily expansion]. Their wizards march on
the surviving towns [mage dispatches]. And when they began drawing on the Seals again to fuel
the conquest, the prison's anchor slipped — and the last Warden was waiting with a chisel.

You wake diminished. Your spark is dim [small starting collection], your memory is fog [sets
locked behind research], your city is a ruin among ruins. But the land remembers its Guardian:
where you walk, where you rebuild, the color returns [player territory spreading gold across
the map]. Raise Orazca from its bones. Gather the survivors. Recover the Volumes. Take back
the five Seals from the five thrones — and decide what kind of Guardian returns to the
Forsaken Realms: the one they buried, or something harder.

**Optional flavor hooks** (one-liners, use anywhere): the ante system = "the Law of Stakes,"
the binding-magic the Five wove into all dueling here — every contest of magic must be bound
by a wager; defeating a color permanently [Color Defeat] = "casting an invader from the
realm"; the Chapter-2 hook = the Seals were not only bindings — they were also **locks**, and
something older sleeps behind the doors they held shut.

---

## B. Dialog Drafts (in-game text)

### Quest 28 prologue (quests.json ~6905, replace opener paragraphs)
> Darkness. Silence. The weight of stone and centuries.
> Then — a crack. A line of light. A voice, old and urgent, speaking words you have not heard
> in three thousand years: your name.
> For the first time in an age... you wake.

### Spawn mage — the Warden (spawn.tmx object 69; four voiced lines WizPAR1-4)
- **WizPAR1 slot** (line ~49, replaces "This... is Shandalar..."):
  > "Easy — breathe. The stasis takes time to shed. ... You are awake. After three thousand
  > years — you are awake. And this... this is what remains of your realm, Guardian."
- **WizPAR2 slot** (~54):
  > "You remember the Night of Chains? Five walkers. Five Seals. A realm left to grey and die
  > while you slept beneath it. They have returned — their castles stand at the compass
  > points, and their colors crawl across the ash, claiming what was never theirs."
- **WizPAR3 slot** (~59):
  > "I am the last Warden. My order kept your vigil for a hundred generations — and died
  > waiting for this day. So rebuild, Guardian. Raise Orazca. Gather the survivors. And when
  > you are strong enough, take back the Seals they forged from your stolen power."
- **WizPAR4 slot** (~64, keep the rune-granting mechanics):
  > "Take this rune — [existing rune text]. The road out is open. The realm waited three
  > thousand years for you. It can wait until you are ready."
- Lines ~119/~143 "...the barrens of Shandalar which I, and now you, call home." →
  > "...the ash-barrens of the Forsaken Realms — which I, and now you again, call home."

*(Note: the four WizPAR .mp3 voice files will no longer match rewritten text — either drop the
voiceFile properties or re-record later. Flagged as an open decision.)*

### Quest 53 epilogue (quests.json ~11214, replaces "stranded on an unknown plane")
> The portal's light fades behind you. Grey ash to every horizon — and beneath it, faint as a
> pulse under skin, you feel the land recognize you. Far away, five alien presences press
> against your awareness like thumbs on a bruise: the Five, already carving up the corpse of
> your realm. Not for long.

### Ending rewrite (spawn.tmx object 69, 4th dialog, lines ~205-245)
- Greeting (~205): keep shape, re-word:
  > "Orazca stands. The five thrones are broken, and the Seals are yours again. Guardian... I
  > have waited my whole life to say this: welcome home."
- Explanation chain (~226-234), replacing "guardians were not captors":
  > "But hear an old man's last warning. The Five did not invent your prison — they found its
  > design. Beneath this realm there are older doors, and your Seals were also locks."
  > "Now those locks sit in your pocket instead of on the doors. Whatever slept behind them
  > has had three thousand years to grow hungry."
  > "Rest. Rebuild. And keep your deck close, Guardian. Chapter two of this realm's story is
  > coming."
- Developer's note (~238, 242 — also FIX the `[RED}` broken markup at 242):
  > "[RED]Developer's note:[] You have reached the end of The Forsaken Realms' current story
  > content. The realm itself fights on — territory, research, restoration and the Five
  > continue. Watch for the next release to continue the story."
- Button label ~245: keep "(Chapter 1 complete)".

---

## C. New Quest Structure (mainQuest spine 0 → 1 → 2 → 3, unchanged)

### Act 0 — Awakening (Q28, rewrite text only)
Unchanged mechanics (hardcoded start via NewGameScene; Skip-Tutorial branch intact).

### Act 0.5 — The Last Warden (Q53, rewrite text only)
Unchanged mechanics (talk to mage → mainQuest=1 → exit cave).

### Act 1a — "Where Am I?" (Q30, EXTENDED tutorial)
Existing stages (travel/town/dungeon/duel/cave) plus, per user notes:
| New stage | Objective type | Implementation | Feasible? |
|---|---|---|---|
| Find a ruined town | `CharacterFlag enteredRuinedTown >= 1` | **needs Java hook D1** | after hook |
| Find a surviving town | `CharacterFlag enteredSurvivingTown >= 1` | **needs Java hook D1** | after hook |
| Restore a ruined town | `MapFlag townRestored >= 1`, `anyPOI:true`, `POITags:["Town"]` | works TODAY | ✅ |
| → Reward: 50 Wood + 50 Stone | stage epilogue `grantRewards` `[{type:"wood",count:50},{type:"stone",count:50}]` | works TODAY (dialog-action path; the quest-level `reward` field is dead code — never use it) | ✅ |
| Build a Trader | `MapFlag economyBuilt_10 >= 1`, `anyPOI:true`, `POITags:["Town"]` | works TODAY | ✅ |
| Restore 4 more towns | `QuestFlag townsRestored >= 5` | **needs Java hook D2** (counter) | after hook |

### Act 1b — "Raise the Banner" (NEW quest, takes Q43's chain slot)
| Stage | Objective | Implementation | Feasible? |
|---|---|---|---|
| Build the Capitol | `CharacterFlag capitolBuilt >= 1` | **hook D3** | after hook |
| Hire a guard | `CharacterFlag guardHired >= 1` | **hook D4** | after hook |
| Build a mine | `CharacterFlag mineBuilt >= 1` | **hook D5** (any of the 4 mine types; the per-type `economyBuilt_<n>` map flags can't express OR across 4 keys — all stages must complete, parallel-OR isn't expressible) | after hook |
| Build a card shop | `CharacterFlag shopBuilt >= 1` | **hook D6** (existing `shopRebuilt_<objectId>` flags are object-id-keyed, unusable as one quest key) | after hook |
| Research a set | `CharacterFlag researchStarted >= 1`, `worldMapOK:true` | **hook D7**. Recommend keying on research *started* (immediate feedback), not the 7-day completion | after hook |
| Quest epilogue | `setQuestFlag mainQuest = 2` + `issueQuest 52` | **CRITICAL re-homing**: today mainQuest=2 is set by Q51's accept branch; converting the Donovan chain to side quests orphans it, which would also permanently deactivate Emrakul's Castle + both Temples (their `questFlagsToActivate` gate on mainQuest ≥ 2) | must move here |

Narrative framing for 1b: the Warden bids you rebuild before you strike — "A guardian with no
realm is just a wanderer with a grudge."

### Act 2 — "The Five Seals" (Q52, rewrite text; mechanics unchanged)
Reframe from "free 5 captured wizards" to "storm the five invaders' castles and take back the
five Seals" — the Mox each boss already drops IS the Seal, so the story now literally pays out
in the reward that already exists. Castle mechanics, flags (`Ch1<Color>CastleComplete`,
`Ch1CastlesComplete >= 5`), and the return-to-Spawn ending stage all stay as-is.

### Ending (spawn.tmx, rewrite text per Part B)
mainQuest=3, "(Chapter 1 complete)". Unchanged mechanics.

---

## D. Required Java Hooks (all small; each fires the quest-event plumbing that already exists)

| # | Where | Add |
|---|---|---|
| D1 | Town-map entry (TileMapScene.load / MapStage entry for a town POI) | `setCharacterFlag("enteredRuinedTown", 1)` when `TownRestoration.isWastelandTown(data) && !isTownRestored(changes) && !isNeutralSeededTown(changes)`; `setCharacterFlag("enteredSurvivingTown", 1)` when `isNeutralSeededTown(changes)`. (Ruined vs functioning is pure runtime state — POI templates/tags are identical, so Travel objectives cannot distinguish them.) |
| D2 | `TownRestoration` restore-success path | `Current.player().setQuestFlag("townsRestored", countPlayerTowns())` — the live counter already exists (`countPlayerTowns()`), it's just never published as a flag. NOTE: must use `setQuestFlag` (fires the quest event) not `advanceQuestFlag` (does NOT fire it). Byte-valued flags cap at 127 — fine. |
| D3 | `TownRestoration.upgradeToCapitol()` | `setCharacterFlag("capitolBuilt", 1)` — the existing code writes the map flag DIRECTLY into changes (bypasses the event-firing path), so no quest event fires today. |
| D4 | `EconomyBuildings` guard-hire (~line 469 `changes.hireGuard(...)`) | `setCharacterFlag("guardHired", 1)` — no flag of any kind exists today; a character flag is correct here (hire happens in a UIScene, not a MapStage). |
| D5 | `EconomyBuildings.buildChooseBuildingDialog()` complete-listener (chosenType known) | if chosenType is SHARD_MINE/GOLD_MINE/LUMBER_MILL/STONE_MINE → `setCharacterFlag("mineBuilt", 1)` |
| D6 | `EconomyBuildings.buildOption(NONE, ...)` (plain Card Shop rebuild) | add `setCharacterFlag("shopBuilt", 1)` to the option's action list |
| D7 | `AdventurePlayer.startResearch()` | `setCharacterFlag("researchStarted", 1)` (and optionally `researchComplete` in `checkResearchCompletion()` for future use) |

All seven are one-to-three-liners; the flag-objective machinery (`CharacterFlag`/`QuestFlag`/
`MapFlag`, numeric `>=` comparison) is implemented and battle-tested (castle flags use it).
Gotchas already accounted for: flag stages need `anyPOI:true`+`POITags` or `worldMapOK:true`
to pass the location gate; `set*Flag(key, 0)` DELETES a flag; never use the `Use` objective
type (operator-precedence NPE bug) or the 7 dead objective types (None/Escort/Find/Gather/
Give/Patrol/Rescue/Siege — enum values with no implementation).

---

## E. Donovan Chain → Standalone Side Quests (Q44–51; Q43's intro dialog retired)

Per-quest checklist (all eight):
1. `"storyQuest": false` (frees them from story-exemption; they gain the normal
   `sideQuestDays` = 20-day timer and count against the 5-active-side-quest cap — accepted).
2. **Author a real `offerDialog`** — all eight currently have `"offerDialog": {}`, which
   renders an EMPTY dialog (no text, no accept button) if ever offered. Move each quest's
   existing prologue text into its offerDialog with an accept option carrying
   `issueQuest: "<own id>"`.
3. **Cut every `issueQuest` chain link** (43→44→45→46→47→48→49→50→51→52; line numbers
   inventoried in the capability report).
4. **Re-home story side effects**: `mainQuest=2` and `exploreShand1` writes move to the new
   Act-1b quest (see C). Without this, Emrakul's Castle and the two Temples never activate.
5. Decide `questSourceTags` (which town boards offer them). No tags = offered everywhere.
6. Optional narrative order: offer-dialog `condition` blocks can gate on quest flags
   (e.g. Q45 only offered once Q44 complete) — supported via `getQuestFlag {key,op,val}`.
7. De-Donovan the text where desired (user note: "remove characters?") — or keep Donovan as
   the recurring side-quest patron; he no longer gates the main story either way.

Pre-existing latent issue worth fixing in the same pass: side quests 34–42 are already
`storyQuest:false` with `"offerDialog": {}` — same empty-dialog dead-click if drawn from the
board pool today.

Also fix while in quests.json: Q30 has two stages with duplicate `"id": 5`.

---

## F. Open Decisions (need user's call)

1. **Voice files**: the 4 WizPAR mp3s won't match the rewritten Warden lines. Drop the
   voiceFile properties (silent text), keep mismatched audio, or re-record?
2. **Donovan**: keep as side-quest patron flavor, or strip named characters from the converted
   side quests entirely?
3. **Research objective**: key on research *started* (recommended, immediate) vs *completed*
   (7-day wait mid-quest)?
4. **Act 1a "Restore 4 more towns"**: 5 total restored is a meaty ask on Insane where gold is
   tight — confirm the number, or scale it (e.g. 3)?
5. New quest 1b name: "Raise the Banner" (placeholder — alternatives: "A Realm Reborn",
   "The Guardian's Work").

## G. Sequencing for the release (user's stated targets)

1. This quest rework (data + 7 hooks + text) — implement after plan approval.
2. Forge engine update to latest release (separate, risky round: upstream merge + BASE_INSTALL
   refresh + `--full` repackage).
3. Android build (packaging round; toolchain reportedly already installed — verify then).
Recommended order: quests first (pure mod content, testable now), then engine update, then
Android — the Android build should be cut from the already-updated engine.
