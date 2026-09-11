---
name: tfr-play
description: Play The Forsaken Realms (TFR) as the player through the in-game agent bridge - start the ISOLATED agent game (its own folder and APPDATA profile on F:, never the user's game or saves), then loop read state -> decide -> one command -> settle; Forge's AI plays the duels. Use when the user asks Claude to play TFR, run an agent session, soak-test, or check a feature in a running game.
---

# Playing The Forsaken Realms through the agent bridge

The bridge (MOD_SCOPE #117, `forge.adventure.agent`, round 161; isolated setup round 175) is inside the normal game
jar and OFF unless the game starts with `TFR_AGENT_PORT`. Claude drives the overworld, towns, shops, dialogs, items
and decks over loopback HTTP; **Forge's AI plays every duel on the player's seat** (the fight stays the player's:
equipment, ante, rewards, statistics). Design: `docs/design/2026-09-09-agent-play.md`. Client:
`dev-tools/agent/tfr_agent.py` (its docstring lists every verb and command). Repo root:
`F:\FORGE\C--Users-vicwaver-MTG-Forge` - run the commands below from there.

## 1. Isolation - the rule that makes this safe

- The agent game runs from **`F:\FORGE\TFR-Agent\The Forsaken Realms`** (a mirror of the live folder) with
  **`APPDATA=F:\FORGE\TFR-Agent\profile`**, so Forge keeps its saves, preferences and `forge.log` in
  `F:\FORGE\TFR-Agent\profile\ForsakenRealms`. The user's `%APPDATA%\ForsakenRealms` is never opened, and it can
  run while the user plays their own game.
- **Never** start the bridge from the live folder with the user's profile, and never touch the user's slots.
- Check isolation after a launch: the agent's log is `F:\FORGE\TFR-Agent\profile\ForsakenRealms\forge.log`; the
  user's `%APPDATA%\ForsakenRealms\forge.log` must NOT rotate (a second game on the same profile renames it to
  `forge.<timestamp>.log` at launch). The Load screen lists only the agent's own saves.
- Card art is shared (`%LOCALAPPDATA%\Forge\Cache\pics`), which is intended.
- **Packaging**: the standing rule is "never repackage while javaw.exe runs" - stop the agent game first. After a
  package, refresh the agent folder: `dev-tools\agent\agent_sync.cmd` (robocopy /MIR of the game folder only, a few
  seconds; it refuses while the agent game runs; the profile is untouched).

## 2. Start and stop

```
python dev-tools/agent/agent_setup.py        # once: seeds the profile (1280x720 window, sound off); keeps existing files
schtasks /create /tn TFR-Agent /tr "cmd /c F:\FORGE\C--Users-vicwaver-MTG-Forge\dev-tools\agent\agent_launch.cmd" /sc once /st 00:00 /f
schtasks /run /tn TFR-Agent
python dev-tools/agent/tfr_agent.py boot     # waits for the bridge; prints the first usable scene
```

- Launch through the **Task Scheduler** (as above, from PowerShell), not from the tool's own shell: a child of the
  Bash/PowerShell tool can be killed when the call returns. Do not redirect the game's stdout.
- Arguments to `agent_launch.cmd` (inside the `/tr` string): `cheats` enables console commands (`cmd console
  text="give gold 100"`) and the fog-free state (`state --cheat`) - for testing; a real play session is fair (fog
  of war). A second argument is a folder of freshly compiled classes put AHEAD of the jar - the dev loop, no Maven
  (`agent_launch.cmd cheats C:\...\classes`; compile with javac against the agent jar, `C:/` style paths inside
  `-cp`).
- Stop: `powershell -File dev-tools\agent\agent_stop.ps1` (kills only the javaw whose command line names the
  TFR-Agent jar - the player's game is never matched), then `schtasks /delete /tn TFR-Agent /f` when done.
- The agent's `forge.log` is locked while the game runs - stop it to read the log, or read `/state`'s
  `agent.log` (the bridge's own lines).

## 3. The loop

1. `python dev-tools/agent/tfr_agent.py state --brief` - scene, player (day, gold, shards, life, location, deck),
   discovered POIs with distance and bearing, visible enemies, the open dialog, clickable UI, quests,
   notifications since the last read.
2. Decide ONE action. Think like the player: the quest log, the town's needs, the deck, the risk of the fight.
3. `python dev-tools/agent/tfr_agent.py cmd <name> key=value ...` - commands block until done (a walk arrives
   or stops, a wait passes days).
4. `python dev-tools/agent/tfr_agent.py settle` - returns to an idle map scene: sits out a duel (the AI plays),
   taps the win/lose view's "Back to Adventure", the ante prompt's "OK" (keeps a won card), Done on a reward
   screen, and walks single-option dialogs. It stops at a real choice and prints it - answer with `cmd click
   id=...` (or `dialogs choose=TEXT`).
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
An InfoTextScene follows (`cmd click text="Back"`), then `dialogs choose="Skip the introduction"` (or play the
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

## 7. Reporting

Keep a short running log (what, why, result). At the end: where the character is, what changed (gold, cards,
quests, towns), every surprise or bug with the `[TFR-*]` log lines around it, and a few screenshots. Leave the
game stopped unless the user wants to watch.
