# Agent play: Claude plays The Forsaken Realms as the player

**Status**: design + build plan, 2026-09-09 evening (user ask: *"figure out how we can implement it where you
can play the game as the player. Not just duels that's currently possible, but fully play the game in the
place of the player."*). MOD_SCOPE #117. Round 161 builds the first cut.

## 1. What "Claude plays" has to mean

Three things could be meant, and they need different machinery:

| Model | Who decides | What the user watches | Verdict |
|---|---|---|---|
| A. In-game autopilot | a Java policy (state machine) written once | the game playing itself | the round-142 #115 analysis; cheap but it is not Claude playing |
| B. **Claude drives the overworld, Forge's AI plays the duels** | Claude, live, one decision per command | the real game window, with Claude's moves arriving every few seconds | **this design** |
| C. Claude also pilots the duels | Claude, card by card | a Magic game played slowly through text | worse than Forge's AI and 50x slower; not worth building |

B is the one that matches the ask. The game already has everything B needs on the duel side: `DuelScene.initDuels(...,
aiControlsPlayerSide)` puts Forge's AI on the player's seat and the match runs as a spectated `humanCount == 0` game
(the Deck Tester's "AI vs. AI (Watch)" mode, and every roaming-guard fight). What is missing is the overworld half: a
way for something outside the process to SEE the game state and SEND the player's actions, at the pace of a
turn-based game.

The honest framing from #115 still holds and is now a feature, not a caveat: the duels are Forge's AI; every
overworld, town, shop, quest and inventory decision is Claude's, made live from the same information the player
sees. The user can watch the window, read the log, or read the agent's own transcript.

## 2. The key fact that makes it feasible: the world is already turn-based at the command level

`WorldStage.onActing()` advances in-game time ONLY inside `if (player.isMoving() || waitingForTime)`. Standing still,
nothing happens: no day tick, no mage moves, no spawn rolls. Inside a town or dungeon there is no day tick at all.
So a command loop of *read state -> think -> send one action -> wait for it to finish* has no real-time pressure
between actions. The seconds an LLM takes to decide cost nothing. The only real-time part is a duel, and the AI
plays that.

## 3. Architecture: the Agent Bridge

A new mod-added package `forge.adventure.agent` (no upstream merge burden except three one-line hooks), default OFF,
switched on only by an environment variable or system property on the user's own machine:

```
TFR_AGENT_PORT=8765   (or -Dtfr.agent.port=8765)      -> bridge listens on 127.0.0.1:8765
TFR_AGENT_CHEATS=1                                     -> console commands and the full (fog-free) state allowed
```

### 3.1 Transport

`com.sun.net.httpserver.HttpServer` (in every JDK 17+ image, no dependency) bound to loopback only. JSON in and out
via libGDX's `Json`/`JsonReader` (already used for every data file). Three endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /state` | the observation, built on the GL thread as one consistent snapshot |
| `POST /cmd` `{"cmd": "goto", "poi": "Waste Town Tribal"}` | run one action on the GL thread; returns `{ok, message}` once the synchronous part is done |
| `GET /wait?timeout=30` | block until the game is idle (no walk in progress, no scene transition, no duel, no pause) or the timeout; returns the state |
| `GET /screenshot` | write the current frame to a PNG in the scratch dir and return its path, so the agent can LOOK (Claude Code reads PNGs) |

Every handler marshals onto the render thread with `Gdx.app.postRunnable` and waits on a `CompletableFuture`
(bounded). The bridge never touches scene2d from its own thread.

### 3.2 Observation (`/state`)

Fair-play by default: only what the player could know. `cheats` adds everything.

- `scene`: current scene name (GameScene = world map, TileMapScene = town/dungeon, DuelScene, RewardScene,
  InventoryScene, InnScene, DeckSelectScene, QuestLogScene, SaveLoadScene, StartScene, NewGameScene, ...),
  `transition` (a `TransitionScreen` is up), `paused`, `frozen` (`Forge.advFreezePlayerControls`).
- `player`: name, race, day + time of day, gold, shards, wood, stone, life/max, tile position, current location
  name, selected deck (name, size), all decks (name, size, legal), inventory (name, count, equipped, slot),
  equipment by slot, statistics (wins/losses), color reputation per color, towns owned.
- `world` (on the map): every DISCOVERED point of interest (`World.isExploredWorld` at its tile) with name, type,
  color, owner, distance in tiles, bearing, and whether a road connects it; every enemy sprite the player can
  currently see (not hidden/inactive), with name, rank, life, distance, bearing, and whether it is a territory
  mage and what it is heading for; towns under attack; the player's roaming guards.
- `map` (inside a POI): the map's name and type, every interactable actor with a stable id, kind (enemy / shop /
  reward / npc / quest / portal / exit / building), label, position, distance; the exit.
- `dialog`: when a scene2d Dialog is open on the active stage: its text and its option buttons `[{index, text,
  enabled}]` - this covers `MapDialog` NPC dialogs, entry-barred/toll/legendary prompts, confirmations, the
  hire/manage-guard dialogs, everything that goes through `UIScene.showDialog` or `GameStage.showDialog`.
- `ui`: for every UIScene, the visible, enabled buttons with text and an id (RewardScene's buy buttons carry
  the price; Inn's heal cost; Inventory's item list with the selected item's description). This is the generic
  fallback: anything the player can click, the agent can click.
- `quests`: active quests (name, description, tracked) and the tracked objective.
- `notifications`: the last 20 HUD notifications and `[TFR-*]` events since the previous `/state` (a hook in
  `GameHUD.addNotification`), so "your guard broke the attack" and "Mardrake Steading fell" reach the agent.
- `agent`: `busy`, `action` (what is executing), `lastResult`, `frame`.

### 3.3 Actions (`POST /cmd`)

World map:
- `goto {poi | x,y}`: A* over `World.isColliding` tiles (8-way, corner-cutting forbidden), then steer the player
  along the path each frame through `GameStage.setTouchKnobInput()` - the virtual joystick the touch UI already
  feeds, applied in `GameStage.act()` after actors act, so no stock movement code changes. Arriving on a POI
  triggers the game's own entry path (`handlePointsOfInterestCollision` -> autosave -> `loadPOI`), including its
  entry-barred / toll / legendary dialogs, which the agent answers with `click`.
- `stop`, `wait {days}` (`setWaitingForTime(true)` until the day counter reaches the target), `fasttime {on|off}`.
- `explore {direction}`: walk N tiles in a compass direction (for fog).
Inside a map: `goto {actor id | x,y}` using the map's own `NavigationMap` (`MapStage.navMaps`, the enemy AI's
graph) - walking onto a shop, NPC, reward or exit triggers it exactly as the player would; `leave` (the exit
actor / `exitDungeon`).
Any scene: `click {index | text}` (fires a real `touchDown`/`touchUp` on the target actor through the stage, so
modality, capture listeners and dialogs behave exactly as with a mouse), `back`, `key {name}`.
Model-level actions where clicking is clumsy: `equip/unequip/use/sell {item}`, `deck select {n}`, `deck add/remove
{card}` (Adventure decks are views over one collection - the editor's own rules apply), `buy {index}` in a shop,
`save {slot}`, `load {slot}`, `newgame {name, race, color, difficulty, mode}` (what `NewGameScene.start()` does),
`console {text}` (cheats only), `screenshot`.
Duels: none. With `autoBattle` on (default when the bridge is up), every duel the player enters is piloted by the
AI on the player's seat - `DuelScene` gets a second flag, `aiPilotsPlayerSeat`, that only swaps the LobbyPlayer to
`GamePlayerUtil.createAiPlayer` at the seat construction. Everything else stays the PLAYER's fight: equipment,
blessings, ante, rewards, statistics, reputation (the existing `aiControlsPlayerSide` means "spectator/guard" and
strips all of that - see review finding G3 - which is exactly wrong for the agent).

### 3.4 Pacing and watchability

Commands are atomic: `goto` returns when the player arrives (or gets stuck, with a reason), `wait` when the days
have passed, `click` when the dialog has processed. `/wait` covers the rest (a duel that started by collision, a
transition). At normal speed the user can watch every move; `fasttime` uses the existing 10x world clock for
long walks.

### 3.5 Threading, saves, fairness

- All game mutation on the GL thread; the HTTP threads only wait.
- No persisted state is added. The bridge holds nothing that survives a save. Save format untouched.
- The cheats gate is an environment variable, never a settings key, so it cannot ship on by accident.
- **Testing discipline**: an agent session autosaves like any session and overwrites `auto_save.sav`. Before any
  test run, copy `%APPDATA%\ForsakenRealms\adventure\The Forsaken Realms\` aside and restore it afterwards; test
  on a NEW game (slot chosen by the test), never on the user's slot 1.

## 4. Seams already in the code (why this is a few hundred lines, not a rewrite)

| Need | What exists | Where |
|---|---|---|
| Steer the player without keyboard | `GameStage.setTouchKnobInput(x, y)`, read in `act()` when `len > 0.2` | `GameStage.java:308`, `:539` |
| Enter a POI | walking onto it: `handlePointsOfInterestCollision()` -> `loadPOI()` | `WorldStage.java:622-703` |
| Leave a town/dungeon | `EntryActor.onPlayerCollide()` (exit tiles), `MapStage.exitDungeon()` | `MapStage.java:1579` |
| Advance time standing still | `WorldStage.setWaitingForTime(true)`; `onActing` ticks while set | `WorldStage.java:157, 336` |
| Speed | `WorldStage.setFastTimeEnabled` (the HUD's 10x toggle) | `WorldStage.java:312` |
| Pathing inside maps | `MapStage.navMaps` (`NavigationMap.findShortestPath`) | `MapStage.java:57, 799` |
| Pathing on the world | `World.isColliding(x, y)` tile bit; `roadConnectedTownIds` | `World.java:1053, 473` |
| Fog of war | `World.isExploredWorld(x, y)`; enemy sprites hide themselves outside vision | `World.java:3891` |
| Dialog options | `MapDialog` builds `TextraButton`s into the Dialog's button table | `MapDialog.java:233-330` |
| Generic UI | every scene is a `UIScene` with a `Stage` and a `UIActor`; buttons are `TextraButton`s | `UIScene.java:129, 352` |
| Which scene is up | `Forge.getCurrentScene()`, `Forge.transitionScreen` | `Forge.java:350, 88` |
| AI on the player's seat | `DuelScene` seat construction, `aiControlsPlayerSide` | `DuelScene.java:725-727` |
| Console (cheats, teleport, give) | `ConsoleCommandInterpreter.command(String)` - 56 commands | `ConsoleCommandInterpreter.java:92` |
| Screenshot | `ScreenUtil.takeScreenshot()`; `Pixmap.createFromFrameBuffer` | `forge/util/ScreenUtil.java:27` |
| Shop prices / buy | `RewardScene.BuyButton` (price, click listener) | `RewardScene.java:1140` |
| New game programmatically | `WorldSave.generateNewWorld(...)` as `NewGameScene.start()` does | `WorldSave.java:274`, `NewGameScene.java:396` |
| Save / load | `WorldSave.save(text, slot)`, `WorldSave.load(slot)` | `WorldSave.java:105, 330` |

Stock-file hooks (one line each): `Adventure.render()` starts the bridge on the first frame if configured;
`DuelScene` seat construction reads the pilot flag; `GameHUD.addNotification` feeds the notification buffer.
Everything else lives in `forge.adventure.agent`.

## 5. Build plan

| Step | What | Done when |
|---|---|---|
| 1 | Bridge core: server, GL marshaling, `/state` (scene, player, agent), `/cmd console`, `/screenshot` | `curl localhost:8765/state` answers from a running game |
| 2 | World map: discovered POIs + visible enemies in state; `goto poi` (A* + knob steering), `stop`, `wait`, `fasttime` | the agent walks to a named town and the town loads |
| 3 | Generic UI: `dialog` + `ui` in state; `click`, `back` | the agent answers an entry-barred prompt and buys a card |
| 4 | Inside maps: actors in state; `goto actor`, `leave` | the agent visits the Inn and walks out |
| 5 | Auto-battle: `aiPilotsPlayerSeat` through the seat construction; the reward flow returns to the map | a collision duel plays itself and the agent sees the result |
| 6 | Model-level actions: equip/use/sell, deck select/add/remove, save/load/newgame | a new game started, a deck edited, a save written, all from curl |
| 7 | `dev-tools/agent/tfr_agent.py` client + a `play` loop skeleton; first Claude-played session; iterate on whatever the agent could not see | a session transcript the user can read |

Steps 1-4 and 6 are pure additions; 5 touches `DuelScene` in one expression. Estimated 900-1,200 lines of Java
plus 150 of Python.

## 6. What this is NOT

- Not a policy. The bridge exposes and executes; the decisions are made by whoever holds the other end (Claude in
  a Claude Code session, or a scripted test driver for soak tests - "play 100 days and report every exception" is
  a free by-product).
- Not shipped-on. Off unless the environment variable is set; players never see it.
- Not a screen scraper. State comes from the objects, not pixels; screenshots are for the agent's eyes only.
