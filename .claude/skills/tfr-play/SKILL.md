---
name: tfr-play
description: Play The Forsaken Realms (TFR) as the player through the in-game agent bridge - start the ISOLATED agent game (its own folder and APPDATA profile under C:\TFR\agent, never the user's game or saves), then loop read state -> decide -> one command -> settle; Forge's AI plays the duels. Use when the user asks Claude to play TFR, run an agent session, soak-test, or check a feature in a running game.
---

# Playing The Forsaken Realms through the agent bridge

The bridge (MOD_SCOPE #117, `forge.adventure.agent`, round 161; isolated setup round 175) is inside the normal game
jar and OFF unless the game starts with `TFR_AGENT_PORT`. Claude drives the overworld, towns, shops, dialogs, items
and decks over loopback HTTP; **Forge's AI plays every duel on the player's seat** (the fight stays the player's:
equipment, ante, rewards, statistics). Design: `docs/design/2026-09-09-agent-play.md`. Client:
`dev-tools/agent/tfr_agent.py` (its docstring lists every verb and command). Repo root:
`C:\TFR\repo` - run the commands below from there. `C:\TFR` is a junction to `C:\CLAUDE\PROJECTS\TFR1` (since
2026-10-26); the scripts and the filters below use the `C:\TFR` path.

## 1. Isolation - the rule that makes this safe

- The agent game runs from **`C:\TFR\agent\The Forsaken Realms`** (a mirror of the live folder) with
  **`APPDATA=C:\TFR\agent\profile`**, so Forge keeps its saves, preferences and `forge.log` in
  `C:\TFR\agent\profile\ForsakenRealms`. The user's `%APPDATA%\ForsakenRealms` is never opened, and it can
  run while the user plays their own game.
- **Never** start the bridge from the live folder with the user's profile, and never touch the user's slots.
- Check isolation after a launch: the agent's log is `C:\TFR\agent\profile\ForsakenRealms\forge.log`; the
  user's `%APPDATA%\ForsakenRealms\forge.log` must NOT rotate (a second game on the same profile renames it to
  `forge.<timestamp>.log` at launch). The Load screen lists only the agent's own saves.
- Card art is shared (`%LOCALAPPDATA%\Forge\Cache\pics`), which is intended.
- **Packaging**: the standing rule is "never repackage while javaw.exe runs" - stop the agent game first. After a
  package, refresh the agent folder: `dev-tools\agent\agent_sync.cmd` (robocopy /MIR of the game folder only, a few
  seconds; the profile is untouched). Stop the agent game before a sync: the script's own running-game check
  still looks for the old `TFR-Agent` folder name, so it never sees the agent game.

## 2. Start and stop

```
python dev-tools/agent/agent_setup.py        # once: seeds the profile (1280x720 window, sound off); keeps existing files
Start-Process -FilePath cmd.exe -ArgumentList '/c "C:\TFR\repo\dev-tools\agent\agent_launch.cmd"' -WindowStyle Hidden
python dev-tools/agent/tfr_agent.py boot     # waits for the bridge; prints the first usable scene
```

- Launch with **`Start-Process`** from the PowerShell tool (as above). `agent_launch.cmd` starts javaw detached with
  its own `start`, so the game survives the tool call returning (checked in round 301). Do NOT use the Task
  Scheduler (`schtasks /create`): auto mode blocks it as persistence. Do not redirect the game's stdout. Confirm it
  runs: `Get-CimInstance Win32_Process -Filter "Name='javaw.exe'"` lists a javaw whose command line contains
  `C:\TFR\agent\The Forsaken Realms\`.
- Arguments to `agent_launch.cmd` go after the script path, inside the single quotes (`'/c "C:\...\agent_launch.cmd"
  cheats'`): `cheats` enables console commands (`cmd console text="give gold 100"`) and the fog-free state
  (`state --cheat`) - for testing; a real play session is fair (fog of war). A second argument is a folder of freshly
  compiled classes put AHEAD of the jar - the dev loop, no Maven (`agent_launch.cmd cheats C:\...\classes`; compile
  with javac against the agent jar, `C:/` style paths inside `-cp`).
- Stop, from the PowerShell tool (it matches only the agent folder's jar, so the player's game from `C:\TFR\live` is
  never touched):
  `Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -like '*C:\TFR\agent\The Forsaken Realms\*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Confirm:$false }`
  `dev-tools\agent\agent_stop.ps1` does the same, but this machine's execution policy refuses to run scripts
  ("running scripts is disabled on this system").
- The agent's `forge.log` **cannot be read at all** while the game runs - Forge holds a byte-range lock over
  the whole file, so a `FileShare.ReadWrite` handle opens and then every read throws "another process has
  locked a portion of the file", `Get-Content -Tail` included. There is no readable prefix to scrape (round
  276 wrote a helper to try, and deleted it). Stop the game to read the log, or read `/state`'s `agent.log`
  during the session - it carries the `[TFR-Agent]` walk lines, which is what diagnoses a failed walk.

## 3. The loop

1. `python dev-tools/agent/tfr_agent.py state --brief` - scene, player (day, gold, shards, life, location, deck),
   discovered POIs with distance and bearing, visible enemies, the open dialog, clickable UI, quests,
   notifications since the last read.
2. Decide ONE action. Think like the player: the quest log, the town's needs, the deck, the risk of the fight.
3. `python dev-tools/agent/tfr_agent.py cmd <name> key=value ...` - commands block until done (a walk arrives
   or stops, a wait passes days).
4. `python dev-tools/agent/tfr_agent.py settle` - returns to an idle map scene: sits out a duel (the AI plays, sped to 50x - the user's rule, round 688),
   taps the win/lose view's "Back to Adventure", the ante prompt's "OK" (keeps a won card; on a "Card Lost" prompt
   it accepts the loss - never Buy Back or a Bronze Coin, since TFR round 535), Done on a reward screen, and walks
   single-option dialogs. It stops at a real choice, and at once at a Forge prompt whose buttons it does not know
   (since round 592; it used to wait ~2 minutes first), and prints it - answer with `cmd click id=...` (the `forgeUi`
   ids) or `dialogs choose=TEXT`, then `settle` again. `settle --ask-ante` also stops at the Card Lost choice, for a
   session where the player decides.
5. Repeat. Every few steps `shot` a screenshot (`tfr_agent.py shot C:/path/x.png`) and look at it - the state is
   objects, the screenshot is what the player sees.

## 4. Commands (see the client docstring for the full list)

- World: `goto poi="Name"` | `goto tile=x,y` | `explore dir=NE tiles=10` | `wait days=2` | `fasttime on=true` |
  `stop`
- Map (town/dungeon): `goto actor=<id>` (shops, NPCs, rewards, enemies, the exit or portal) | `leave`
- Any scene: `click id=<id>` | `click text="Done"` | `advance` (finish typing text) | `back` | `key key=ESCAPE`
- Model: `equip item=...` | `use item=...` | `sell item=...` | `deck op=list|select index=1|add card=... count=2`
  | `buy index=0` | `save slot=1 name="agent"` | `load slot=1` | `newgame`
- Duels: none - auto-battle is on while the bridge runs.

## 5. A new game

On a fresh profile the scene is StartScene: `cmd click text="New Game"`, then `cmd newgame` (it uses the New Game
screen's current settings - name, race, difficulty, mode; a fresh profile offers a random name, Normal, Constructed).
An InfoTextScene follows - it is the plane's WELCOME PAGE (once per save; to check it, screenshot it before pressing Back) - (`cmd click text="Back"`), then `dialogs choose="Skip the introduction"` (or play the
intro) and `dialogs`. Walk out through the portal: `state --brief` lists it as a map actor -> `cmd goto
actor=<portal id>`. The profile is the agent's own - save to any slot (`cmd save slot=1 name="agent start"`) before
anything risky, and `cmd load slot=1` from the Load screen to get back.

## 6. Traps (all learned the hard way)

- **Leaving a town.** The game switches the player's world collision OFF for ~2 s after an exit (the arrival
  flicker, so a person can step away); a walk issued after that, still standing on the town, used to walk straight
  back in. Since round 175 the walker exempts the town it stands on (`[TFR-Agent] standing on ...`) and never paths
  through the ring right next to a point of interest. If a walk still re-enters the town you just left, report it
  with the `[TFR-Agent] walk start:` line from `agent.log` (player rectangle, collision height, nearby POI
  rectangles).
- **Touching ANY point of interest enters it** - footprints are obstacles for the planner; the destination is the
  only one it walks onto.
- **Long walks through unexplored land can end "stuck near (x,y)"** after four replans. Walk in `explore` legs
  toward the target's bearing, then `goto` again.
- A MapDialog hides its buttons until its text finishes typing: `advance`, or `dialogs` does it for you.
- An interception on the way stops a `goto`: `settle`, then issue the same `goto` again.
- The win/lose view and option panes are Forge widgets, listed as `forgeUi` in the state - `settle` taps them.
- **Loot is collected only through the reward screen's Done** (listed by NAME, `done`; its text is the `[+OK]`
  glyph). `cmd back` on a RewardScene leaves WITHOUT collecting (UIScene.back -> switchToLast). Before round 179
  `settle` matched the button's text only, missed it and pressed `back` - every loot screen it passed was thrown
  away (the round-176 watched session included). It clicks Done by name now and never backs out of a loot screen.
- The ante Re-roll prompt is skipped for the agent's seat (it would be a modal nobody can click).
- Console arguments split on spaces, but quotes group them: `console text='spawn enemy "Wild Rat"'` works,
  `console text="spawn enemy Wild Rat"` looks for "Wild". A spawn lands 3-10 tiles away, usually under the fog -
  `console text="torch pulse"` reveals 9 tiles for a screenshot; `state --cheat` lists them regardless.
- The stock AI pays any "PayShards" cost with 0 shards and a spectated seat's shards are never written back - in an
  auto-battle the AI can use the player's shard abilities for free. Report it rather than exploit it.
- `wait days` steps off the POI first; a roaming enemy often finds a waiting player - that is a duel.

- **Testing the Player Capitol map** (round 343): a fresh world's Orazca uses `orazca.tmx`; `player_capital.tmx` only
  loads once the Capitol is raised. Cheats on, stand in Orazca: `console text="give gold 2000"`, `give wood 200`,
  `give stone 200`, `give shards 100`, then `console text="capitol raise"`, `cmd leave`, and
  `console text='teleport to poi "Player Capitol"'` (the walker's `goto poi` stops at the doorstep of a town you
  stand beside; the teleport enters it). `player_town.tmx` is what OTHER restored towns render from.
- **`cmd goto tile=x,y` inside a map is y-up**: Tiled row = map height - 1 - y. `state` gives `player.pos` in map
  pixels (16 a cell), also y-up.
- **Walking inside maps (round 448).** The walker plans on the map's own collision with the player's box and keeps off
  every stairs/portal/exit except the one you `goto` - plain `goto actor=<stairs id>` works through multi-level places
  (Cultists' Outpost's four floors needed hand waypoints before). Taking stairs ends the walk with "the level changed";
  read the new floor's actors before the next goto. A snag logs `[TFR-Agent] snagged at (x,y): box ..., walls near:
  ...` in `/state`'s agent log - the walls the player is up against. An enemy beside an arrival point still starts a
  duel before any `remove enemy` lands.
- **`leave` (console or bridge) refuses during a duel** since round 448 - leaving a map mid-duel closed the game.
- **Tappable text lines are clickable** (round 452): a label with a click handler (the Research Lab's set lines) is
  listed in `ui` after the buttons, by its text - take a fresh `state`, then `cmd click id=uN`. The Lab itself is a map
  trigger, not an actor: in the Capitol (`teleport to poi "Player Capitol"`) walk to `goto tile=10,33`.
- **Run the client with `PYTHONIOENCODING=utf-8`** (`export PYTHONIOENCODING=utf-8` in the Bash tool): a reward screen's
  checkbox glyph (U+2610) otherwise crashes `settle`'s print on the Windows console.
- **A fresh New Game is the clean test for one-time map things** (a hidden pickup, a first-visit dialog): a long-running
  save has usually used them up. `console text="set charflag NAME 1"` / `... 0` (0 removes it) fakes a run state such
  as `newGamePlus`.
- **Killing the agent game loses the end of `forge.log`** (it is buffered). To read a startup line, quit through the
  menu: `cmd click text="Exit"`, then a fresh `state` (dialog ids need one), then `cmd click id=d1`; the log is
  complete a second or two later. Kill only when the log does not matter.
- **The bridge cannot scroll a list** (Settings, long shops): `click id=` on a widget below the fold answers
  `clicked null` and does nothing, and arrow keys do not scroll. For a setting, edit the AGENT profile's
  `C:\TFR\agent\profile\ForsakenRealms\adventure\settings.json` (back it up first, restore after).
- **Console commands with quoted arguments go through the Bash tool**: PowerShell 5.1 strips the embedded quotes
  (`teleport to poi "White Castle"` arrives as `White`).

## 7. Reporting

Keep a short running log (what, why, result). At the end: where the character is, what changed (gold, cards,
quests, towns), every surprise or bug with the `[TFR-*]` log lines around it, and a few screenshots. Leave the
game stopped unless the user wants to watch.
