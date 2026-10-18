# Cross-plane imports: the full checklist

Read before the first edit of any import from Realm of Legends or another plane. `CLAUDE.md` carries the
headline rules; this is the full text of the standing user rule (2026-10-16).

**Importing content from Realm of Legends or any other plane (standing user rule, 2026-10-16: "No Commander. No quest
lines not pertinent to our mod. No gifts they promise ... I don't want another 'Liliana's Stone' situation, or Commander
cards or quest lure that's specific to RoL.")** Every map, enemy, deck, item, shop or dialog taken from another plane
passes ALL of these before it is committed, and the round's changelog says it did:
1. **No Commander.** No deck relies on a `[Commander]` section (we never play it - move the commander into `[Main]` or
   rebuild the deck); no card that needs a commander or a color identity to work - the card DB's
   `AI:RemoveDeck:NonCommander` marker, `ColorIdentity` mana (Command Tower, Opal Palace, Study Hall, Witch's Clinic),
   "commander" in the oracle text, `restricted_cards.json` `commanderOnlyCards`; nothing in a reward, shop or item.
2. **No quest lines that are not ours.** No dialog, item or quest that points at the other plane's story: its
   capitals' turn-ins ("Return to the swamp capitol for a reward"), its NPCs, keys, cartouches, gates or places we do
   not have. A quest item whose turn-in does not exist here is not imported. Text speaks our story (the Guardian /
   Warden / Five / Seals - `dev-tools/text-audit/apply_rol_text_audit.py` shows the refit).
3. **No promised gifts.** Every promise in a dialog, sign or description - "take this stone", "you can now access the
   Mirror Gallery", "come back and I'll reward you" - is delivered by our game (the item exists and does what it says,
   the reward is wired, the door opens) or the line is rewritten. Round 630's Liliana's Stone (a quest item with no
   turn-in, the Warden's dialog looping) is the case this rule exists for.
4. **Every reference resolves** in our plane: items, enemies, decks, shops, sprites, maps (`validate_plane_data.py`
   ref checks clean), copy limits kept (`deck_legality_audit.py`), new ordinary enemies get their tier family
   (`dev-tools/enemy_tiers/make_tier_families.py`).
5. **Seen in the agent game** before packaging: walk the place, open every dialog, fight one of its enemies, use the
   item.
