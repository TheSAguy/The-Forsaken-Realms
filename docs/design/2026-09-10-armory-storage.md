# Armory storage and guard equipment

User ask (2026-09-10, during the round 158-161 playtest): *"add a storage to the armory. The player can add items
from his inventory there. Then on the Guard management screen a way to access the inventory and add equipment to a
guard."* Called "the last item I have for this round, before release". MOD_SCOPE #118.

This is the design as built in round 163. The decisions below were taken by Claude under the co-author rules
(smallest blast radius, save compatibility, data over Java, one owner per item) and are the ones the user can veto
cheapest if they read differently: each says what changes if it is reversed.

## What it is

1. **The Armory storage.** The Capitol's Level 2 Armory shows a `Storage (N)` button beside its other
   buildings buttons (round 163 showed it on every player-owned Armory at any level; the user's 2026-09-10
   decision - "We only need the Item Storage at the Capitol, so let's do it only at Level 2 Armory" - narrowed
   it in round 166). It opens the character's ONE storage: a list of what is stored, `Deposit` (pick from the
   inventory) and `Withdraw` (pick from the storage). Only wearable things go in: an item with an equipment slot
   that is not a quest item and is not currently worn (take it off on the inventory screen first - depositing
   never silently strips the doll).
2. **Guard equipment.** The roaming guard's manage screen gains `Equipment (N)`. It lists what the guard wears by
   slot, `Remove <item>` per worn piece (back to the storage) and `Add from storage` (a picker over the storage's
   wearable items). One item per slot, the same slot names as the paperdoll; picking an item for an occupied slot
   swaps the old one back into the storage.
3. **The equipment works.** A worn item's duel effects ride into the guard's fights exactly as the player's do
   (starting life, extra starting cards, cards that start on the battlefield or in the command zone, mana shards,
   and the item's `opponent` effects onto the mage) - in the WATCHED fight and the SIMULATED one alike, which is the
   standing rule for guards ("simulate is presentation, not a different resolution"). Boots and blessings with a
   movement bonus make the guard walk faster (the same product `AdventurePlayer.equipmentSpeed()` uses). Effects
   that only mean something to a player on the map (Manasight, vision radius, shop discount, bonus card rewards)
   do nothing on a guard; the picker shows each item's effect text so that is visible before choosing.

## Decisions, and what reversing each would cost

| Decision | Chosen | Why | If reversed |
|---|---|---|---|
| Storage scope | ONE storage per character, on `AdventurePlayer`; its button is on the Capitol's Armory only (user decision 2026-09-10, round 166) | No per-town state in the POI blob (the bigger save surface); the guards it feeds are hired at the Capitol | Per-Armory storage: move the list onto `PointOfInterestChanges` (its `guardTiers` idiom), one more `storeObject` per town |
| Armory level gate | Level 2, Capitol only (user: "We only need the Item Storage at the Capitol, so let's do it only at Level 2 Armory") | The same gate Manage Guards has - the storage exists for the guards | Drop the two conditions in `RewardScene`'s visibility block |
| Which guards | Roaming guards only | Local garrison guards are tier strings on the town with a dice-roll fight - there is nothing to equip and no per-guard screen | A local-guard equipment model would be a new data object per garrison guard and a new fight rule; out of scope |
| Slots | The paperdoll's own slot names, one item each; `Ability1`/`Ability2` items refused (player-triggered abilities); no gauntlet twin slots (`Left2`/`Right2` are not granted to a guard) | The item catalog already says where each item goes; abilities need a player to trigger them; the twin-slot rule (round 137) exists for the doll's UI and would double the picker's cases for two items | Allow twins: `ArmoryStorage.slotOf()` would have to consult the guard's worn gauntlets the way `AdventurePlayer.slotCandidates()` does |
| Cracked items | Cannot be given to a guard (can be stored) | Same as the doll - a cracked item is unusable until repaired | One condition in `guardCanWear()` |
| Effects in the simulated fight | Applied, through a new `Consumer<RegisteredPlayer>` hook on `DeckTesterSimulator.runBatch` | Parity between watched and simulated is the rule the user set in round 145 and the round-160 review restored for mage life | Drop the hook and the guard's items would only matter when the player watches - a balance decision hidden behind a presentation toggle |
| Where gear goes when a guard leaves | Back to the storage on dismiss (every case, including a downed guard - only the DECK is forfeited, not the steel), on unpaid disband, and on a rank change nothing moves | The user's deck-forfeit rule is about cards; equipment is the player's property lent to a contractor | Forfeit gear with the deck: one line in `RoamingGuards.dismiss()` |
| New Game+ | Guards keep their gear (they keep their decks); the storage rides along like the inventory | Same treatment as everything else the character owns | Clear `armoryStorage` in `resetForNewGamePlus()` |

## One owner per item

The round-141 sell exploit and the round-146 deck desync were both "two containers disagreed about who owns a
thing". The guard deck fixed that by recording an exact list and replaying it. Equipment gets the same discipline:
an `ItemData` object is in exactly one of {the inventory, the storage, a guard's `equipment`} and every move is one
of the five `ArmoryStorage` verbs (`deposit`, `withdraw`, `giveToGuard`, `takeFromGuard`, `returnGear`), each of
which removes from the source before adding to the destination and prints one `[TFR-Armory]` line. `deposit()` goes
through `AdventurePlayer.removeItem()`, which already unequips and drops granted slots.

## Persistence (no new serializable class)

- `AdventurePlayer.armoryStorage`: saved as `storeObject("armoryStorage", ItemData[])`, the exact idiom the
  inventory uses (`ItemData` is already serialized whole into every save). Read guarded by `containsKey`, so a
  save from before this round loads with an empty storage.
- `RoamingGuardData.equipment`: saved inside the guard's own sub-data as `storeObject("equipment", ItemData[])`,
  read guarded the same way. `RoamingGuardData` itself stays a plain holder, never written as an object.
- `refreshItemDefinitionsFromCatalog()` (round 124) now walks the storage and every guard's gear too, so a balance
  change to an item reaches stored and worn copies.

## Code map

| File | Change |
|---|---|
| `util/ArmoryStorage.java` (new) | The five verbs, `canDeposit`, `guardCanWear`, `effectsOf(guard)`, `speedOf(guard)`, `describe(item)` |
| `scene/ArmoryScene.java` + `ui/armory*.json` (round 168) | The screen: the inventory layout with the storage grid in the description's place and a Transfer button; player mode and guard mode. Replaced round 163's `ArmoryStorageUI` dialogs (that class keeps only `fit()`) |
| `util/RoamingGuardUI.java` | `Equipment (N)` on the manage screen opens `ArmoryScene` in guard mode (round 168; `pendingGuard` re-opens the page on Back); gear count in `describe()` |
| `data/RoamingGuardData.java` | `equipment` list |
| `util/RoamingGuards.java` | save/load of `equipment`; `dismiss()` returns gear first |
| `util/EconomyBuildings.java` | unpaid disband returns gear |
| `util/RoamingGuardRuntime.java` | walk speed x `ArmoryStorage.speedOf(guard)`; dispatch log names the gear |
| `player/AdventurePlayer.java` | `armoryStorage` + accessor, save/load, `clear()`, catalog refresh |
| `scene/RewardScene.java` | `Storage (N)` button one row BELOW Done (the Armory hides Restock, so that spot is free; rows 1-3 above Done are taken and row 4 is off-screen) |
| `scene/DuelScene.java` | `useGuardLoadout(deck, life, effects)`; `applyEffects()` static so the simulator can share it; guard effects added after the round-156 spectator gate |
| `stage/WorldStage.java` | both guard-duel starters pass the gear |
| `util/DeckTesterSimulator.java` | `runBatch` overload with a per-seat `Consumer<RegisteredPlayer>` |

## How to see it work

`[TFR-Armory]` on every move. `[TFR-DuelEffects] <Rank> Guard: N effect(s) ...` in a watched fight (the round-160
line, now with the guard's seat name). `[TFR-RoamGuard] simulating: ... gear: [...]` for a simulated one, and the
dispatch line carries `speed 40 x 1.25`. The manage screen's description line shows `, 2 item(s)`.
