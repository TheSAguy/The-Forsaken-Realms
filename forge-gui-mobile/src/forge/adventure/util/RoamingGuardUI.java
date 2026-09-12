package forge.adventure.util;

import forge.adventure.stage.GameHUD;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.scene.UIScene;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;

import java.util.List;

/**
 * Every dialog for roaming guards (MOD_SCOPE #116, round 145). Kept out of EconomyBuildings - which
 * is already 2,800 lines - but in the same package so it can reuse that file's four dialog helpers
 * rather than duplicating them.
 * <p>
 * Flow: the Armory's "Manage Guards" button opens {@link #openGuardChooser} at the Capitol, which
 * asks Local or Roaming. Local goes straight to the existing dialog, unchanged. Roaming opens the
 * roster, and from there hire / manage / dismiss.
 */
public class RoamingGuardUI {
    private RoamingGuardUI() {}

    /**
     * Capitol-only fork (user spec): "when you hire guards, have a screen asking if it's local or
     * roaming. Local will work just like the currently works, so you'll go to that screen."
     * Everywhere else, and on any plane without roaming guards configured, this is never reached -
     * EconomyBuildings goes straight to the local dialog.
     */
    public static void openGuardChooser(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                        String poiName, int objectId) {
        Dialog dialog = new Dialog("Guards", Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, "Local guards defend this town and nothing else. "
                + "A roaming guard carries one of your decks, walks the world, and races attackers to "
                + "whichever of your towns they threaten.");
        EconomyBuildings.addButtonRow(dialog, "Local Guards", true, () -> {
            scene.removeDialog();
            EconomyBuildings.openLocalGuardsDialog(scene, changes, poiName, objectId);
        });
        EconomyBuildings.addButtonRow(dialog, "Roaming Guards (" + RoamingGuards.roster().size()
                + "/" + RoamingGuards.maxGuards() + ")", true, () -> {
            scene.removeDialog();
            openRoster(scene, changes, poiName, objectId);
        });
        EconomyBuildings.addButtonRow(dialog, "Close", true, scene::removeDialog);
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    // ------------------------------------------------------------------ roster

    public static void openRoster(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                  String poiName, int objectId) {
        List<RoamingGuardData> roster = RoamingGuards.roster();
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        Dialog dialog = new Dialog("Roaming Guards", Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, "Roaming guards: " + roster.size() + "/" + RoamingGuards.maxGuards());
        if (roster.isEmpty())
            EconomyBuildings.addContentRow(dialog, "None hired.");
        for (RoamingGuardData guard : roster)
            EconomyBuildings.addContentRow(dialog, "- " + describe(guard, day));
        EconomyBuildings.addContentRow(dialog, "Your gold: " + AdventurePlayer.current().getGold()
                + " [+Gold]   Your shards: " + AdventurePlayer.current().getShards() + " [+Shards]");

        int[] column = {0};
        for (int i = 0; i < roster.size(); i++) {
            RoamingGuardData guard = roster.get(i);
            EconomyBuildings.addHalfButton(dialog, column, "[%75]Manage " + RoamingGuards.displayName(guard.tier),
                    true, () -> {
                        scene.removeDialog();
                        openManageGuard(scene, changes, poiName, objectId, guard);
                    });
        }
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        // Round 146 (user screenshot: "the Hire a Roaming Guard button seems a little big and
        // misaligned"). It was a 240-wide addButtonRow sitting among 140-wide half buttons, so it
        // neither matched the row above nor the pair below. Everything in this file is a half
        // button now - one width for every row, and the shared helper already carries the portrait
        // size (118) the rest of the mod uses, so Android gets the same treatment for free.
        EconomyBuildings.addHalfButton(dialog, column, "Hire a Guard", RoamingGuards.hasRoom(), () -> {
            scene.removeDialog();
            openHireTier(scene, changes, poiName, objectId);
        });
        EconomyBuildings.addHalfButton(dialog, column, "Info", true, () -> showInfo(scene, changes, poiName, objectId));
        EconomyBuildings.addHalfButton(dialog, column, "Close", true, scene::removeDialog);
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        EconomyBuildings.makeContentScrollable(dialog); // round 156: four guards ran off the screen
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    private static String describe(RoamingGuardData guard, int day) {
        StringBuilder sb = new StringBuilder(RoamingGuards.displayName(guard.tier));
        sb.append(" - [+Life] ").append(guard.maxLife).append(", speed ")
                .append((int) (RoamingGuards.speedFor(guard.tier) * ArmoryStorage.speedOf(guard))); // round 163: with its boots
        sb.append(guard.deckCards.length == 0 ? "" : ", \"" + guard.deckName + "\" (" + RoamingGuards.cardCount(guard) + ")");
        if (!guard.equipment.isEmpty())
            sb.append(", ").append(guard.equipment.size()).append(" item(s)"); // round 163
        if (guard.isOutOfCommission(day))
            // Round 152 (user read "until day 236" as "236 days left" - it was 30, from day 206).
            // The absolute day alone made a correct number look alarming; lead with the countdown.
            // Round 158 (user: "Remove the 'Back on Day x' text... That means nothing to the
            // player"). The countdown is the part anyone acts on.
            sb.append(" [RED](hurt - ").append(guard.downUntilDay - day).append(" more day(s))");
        else if (!RoamingGuards.isArmed(guard))
            sb.append(" [RED](GIVE DECK - unarmed, it will not be sent out or paid)");
        else if (RoamingGuards.engagesNothing(guard))
            sb.append(" [RED](engages nothing - tick a rank AND a color)");
        else if (guard.returningHome)
            sb.append(" (returning to the Capitol)");
        else if (!guard.isIdle())
            sb.append(" (defending)");
        else
            sb.append(" (ready)");
        return sb.toString();
    }

    // ------------------------------------------------------------------ hire

    private static void openHireTier(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                     String poiName, int objectId) {
        Dialog dialog = new Dialog("Hire a Roaming Guard", Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, "Rank sets starting life and travel speed. Most "
                + "attackers are slower than any rank, but an Archmage mage outruns all of them - "
                + "a teleporter at the threatened town is the answer to those.");
        int[] column = {0};
        for (String tier : RoamingGuards.TIERS_ASCENDING) {
            int gold = RoamingGuards.weeklyGoldCost(tier);
            int shards = RoamingGuards.weeklyShardCost(tier);
            boolean canAfford = AdventurePlayer.current().getGold() >= gold
                    && AdventurePlayer.current().getShards() >= shards;
            // Round 164 (Android pass): this is the longest half-button label in the mod; the portrait
            // half button is 118px, so the font drops a step there.
            String label = (forge.Forge.isLandscapeMode() ? "[%75]" : "[%62]") + RoamingGuards.displayName(tier)
                    + " [+Life]" + RoamingGuards.lifeFor(tier)
                    + " spd" + (int) RoamingGuards.speedFor(tier) + " " + gold + "[+Gold]"
                    + (shards > 0 ? "+" + shards + "[+Shards]" : "") + "/wk";
            EconomyBuildings.addHalfButton(dialog, column, label, canAfford, () -> {
                AdventurePlayer.current().takeGold(gold);
                if (shards > 0)
                    AdventurePlayer.current().takeShards(shards);
                RoamingGuardData guard = RoamingGuards.hire(tier,
                        WorldSave.getCurrentSave().getWorld().getCurrentDay());
                AdventurePlayer.current().setCharacterFlag("guardHired", 1);
                scene.removeDialog();
                // Straight into the deck picker - a guard with no deck cannot fight, so leaving
                // the player on the roster screen would just be a second click to get here.
                openDeckPicker(scene, changes, poiName, objectId, guard);
            });
        }
        EconomyBuildings.addHalfButton(dialog, column, "Back", true, () -> {
            scene.removeDialog();
            openRoster(scene, changes, poiName, objectId);
        });
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    // ------------------------------------------------------------------ deck give / take

    private static final int DECK_PAGE = 6;

    private static void openDeckPicker(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                       String poiName, int objectId, RoamingGuardData guard) {
        openDeckPicker(scene, changes, poiName, objectId, guard, 0);
    }

    /**
     * Round 166 (user: "Not exactly sure what this means" - the deck picker ran off the screen past
     * eight or nine decks): one button per built deck sits in the dialog's BUTTON table, which
     * makeContentScrollable() cannot scroll, so twenty decks were ten rows of buttons on a 270px-tall
     * screen. Paged now, DECK_PAGE decks at a time with Prev/Next - the same shape as the Armory
     * storage's item picker - and the per-deck warnings ride along with their page.
     */
    private static void openDeckPicker(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                       String poiName, int objectId, RoamingGuardData guard, int page) {
        AdventurePlayer player = AdventurePlayer.current();
        int minimum = RoamingGuards.minDeckSize();
        List<Integer> slots = new java.util.ArrayList<>();
        for (int i = 0; i < player.getDeckCount(); i++) {
            Deck deck = player.getDeck(i);
            if (deck != null && deck.getMain().countAll() > 0)
                slots.add(i);
        }
        int pages = Math.max(1, (slots.size() + DECK_PAGE - 1) / DECK_PAGE);
        int at = Math.max(0, Math.min(page, pages - 1));
        Dialog dialog = new Dialog("Give a Deck", Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, "Choose a deck for your " + RoamingGuards.displayName(guard.tier)
                + ". [RED]Those cards leave your collection and the slot empties[] until you take the deck "
                + "back. Cards shared with your other decks are removed from those too - you only own one copy."
                + (pages > 1 ? " Page " + (at + 1) + "/" + pages + "." : ""));
        int[] column = {0};
        int from = at * DECK_PAGE, to = Math.min(slots.size(), from + DECK_PAGE);
        for (int k = from; k < to; k++) {
            int slot = slots.get(k);
            Deck deck = player.getDeck(slot);
            int size = deck.getMain().countAll();
            java.util.LinkedHashMap<String, Integer> impact = RoamingGuards.sharedCardImpact(slot);
            // Round 148 (user spec: "An invalid deck, (less than 40 cards), should not be possible
            // to give"). Gated on what the COLLECTION can supply, not on what the deck lists - a
            // deck whose cards already went out with another guard still lists 40 of them.
            int deliverable = RoamingGuards.deliverableCount(slot);
            boolean playable = deliverable >= minimum;
            // Round 183 (code review G15): the deck you duel with is not on offer - handing it over emptied the
            // active slot, and the next duel was padded to 40 Wastes.
            boolean active = slot == player.getSelectedDeckIndex();
            String suffix = !playable || active ? " [RED]X" : impact.isEmpty() ? "" : " [RED]!";
            String count = deliverable == size ? String.valueOf(size) : deliverable + " of " + size;
            EconomyBuildings.addHalfButton(dialog, column,
                    ArmoryStorageUI.fit(deck.getName() + " (" + count + ")") + suffix, playable && !active, () -> { // round 164: long deck names
                RoamingGuards.giveDeck(guard, slot);
                scene.removeDialog();
                openManageGuard(scene, changes, poiName, objectId, guard);
            });
            if (active)
                EconomyBuildings.addContentRow(dialog, "[RED]X " + deck.getName() + "[] is the deck you duel with -"
                        + " select another deck first to hand this one over.");
            else if (!playable)
                EconomyBuildings.addContentRow(dialog, "[RED]X " + deck.getName() + "[] can only supply "
                        + deliverable + " of the " + minimum + " cards a legal deck needs"
                        + (deliverable == size ? "." : " - the rest are already out with a guard."));
            if (!impact.isEmpty()) {
                StringBuilder warn = new StringBuilder("[RED]! " + deck.getName() + "[] shares cards with: ");
                boolean first = true;
                for (java.util.Map.Entry<String, Integer> e : impact.entrySet()) {
                    if (!first)
                        warn.append(", ");
                    warn.append(e.getKey()).append(" (").append(e.getValue()).append(")");
                    first = false;
                }
                EconomyBuildings.addContentRow(dialog, warn.toString());
            }
        }
        if (slots.isEmpty())
            EconomyBuildings.addContentRow(dialog, "You have no built decks to give.");
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        if (pages > 1) {
            EconomyBuildings.addHalfButton(dialog, column, "Prev", at > 0, () -> {
                scene.removeDialog();
                openDeckPicker(scene, changes, poiName, objectId, guard, at - 1);
            });
            EconomyBuildings.addHalfButton(dialog, column, "Next", at < pages - 1, () -> {
                scene.removeDialog();
                openDeckPicker(scene, changes, poiName, objectId, guard, at + 1);
            });
        }
        EconomyBuildings.addHalfButton(dialog, column, "Back", true, () -> {
            scene.removeDialog();
            openManageGuard(scene, changes, poiName, objectId, guard);
        });
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        // Round 156 (user: "need to take into account if someone has 20+ decks"). One warning row
        // per shared deck on top of one button per deck - this is the dialog that grows fastest.
        EconomyBuildings.makeContentScrollable(dialog);
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    private static void openDeckReturn(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                       String poiName, int objectId, RoamingGuardData guard) {
        openDeckReturn(scene, changes, poiName, objectId, guard, 0);
    }

    /** Round 166: paged like the deck picker - a fresh character has twenty EMPTY slots, ten rows of buttons. */
    private static void openDeckReturn(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                       String poiName, int objectId, RoamingGuardData guard, int page) {
        AdventurePlayer player = AdventurePlayer.current();
        List<Integer> empty = new java.util.ArrayList<>();
        for (int i = 0; i < player.getDeckCount(); i++) {
            Deck deck = player.getDeck(i);
            if (deck == null || deck.getMain().countAll() == 0)
                empty.add(i); // only empty slots, so nothing the player built gets overwritten
        }
        int pages = Math.max(1, (empty.size() + DECK_PAGE - 1) / DECK_PAGE);
        int at = Math.max(0, Math.min(page, pages - 1));
        Dialog dialog = new Dialog("Take the Deck Back", Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, "Choose an empty slot for \"" + guard.deckName + "\" ("
                + RoamingGuards.cardCount(guard) + " cards). The cards return to your collection."
                + (pages > 1 ? " Page " + (at + 1) + "/" + pages + "." : ""));
        int[] column = {0};
        int from = at * DECK_PAGE, to = Math.min(empty.size(), from + DECK_PAGE);
        for (int k = from; k < to; k++) {
            int slot = empty.get(k);
            EconomyBuildings.addHalfButton(dialog, column, "[%75]Slot " + (slot + 1), true, () -> {
                RoamingGuards.returnDeckToSlot(guard, slot);
                scene.removeDialog();
                openManageGuard(scene, changes, poiName, objectId, guard);
            });
        }
        if (empty.isEmpty())
            EconomyBuildings.addContentRow(dialog, "[RED]Every deck slot is full. Clear one first.");
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        if (pages > 1) {
            EconomyBuildings.addHalfButton(dialog, column, "Prev", at > 0, () -> {
                scene.removeDialog();
                openDeckReturn(scene, changes, poiName, objectId, guard, at - 1);
            });
            EconomyBuildings.addHalfButton(dialog, column, "Next", at < pages - 1, () -> {
                scene.removeDialog();
                openDeckReturn(scene, changes, poiName, objectId, guard, at + 1);
            });
        }
        EconomyBuildings.addHalfButton(dialog, column, "Back", true, () -> {
            scene.removeDialog();
            openManageGuard(scene, changes, poiName, objectId, guard);
        });
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    // ------------------------------------------------------------------ manage one guard

    public static void openManageGuard(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                       String poiName, int objectId, RoamingGuardData guard) {
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        Dialog dialog = new Dialog(RoamingGuards.displayName(guard.tier), Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, describe(guard, day));
        if (guard.isOutOfCommission(day))
            EconomyBuildings.addContentRow(dialog, "[%90][RED]Dismissing now forfeits the deck.[] Wait "
                    + (guard.downUntilDay - day) + " day(s) free, or heal below.");
        // Round 148 (user spec + mock-up: "I think we need to re-work the Mage Attack orders, I
        // want to add Color as an option... let's make it check-boxes"). Checkboxes rather than the
        // old YES/no buttons because nine of those would not fit, and because a checkbox is read at
        // a glance where "no Master" has to be parsed. They also write straight to the guard's own
        // arrays, so the dialog no longer has to be torn down and rebuilt on every single toggle.
        EconomyBuildings.addContentRow(dialog, "[%85]Okay to attack (rank):");
        String[] rankLabels = new String[RoamingGuards.TIERS_ASCENDING.length];
        for (int i = 0; i < rankLabels.length; i++)
            rankLabels[i] = RoamingGuards.displayName(RoamingGuards.TIERS_ASCENDING[i]);
        addCheckGrid(dialog, guard, "rank", rankLabels, guard.engageTier);

        EconomyBuildings.addContentRow(dialog, "[%85]Okay to attack (color):");
        String[] colorLabels = new String[TerritoryControl.COLORS.length];
        for (int i = 0; i < colorLabels.length; i++)
            colorLabels[i] = Character.toUpperCase(TerritoryControl.COLORS[i].charAt(0))
                    + TerritoryControl.COLORS[i].substring(1);
        addCheckGrid(dialog, guard, "color", colorLabels, guard.engageColor);

        int[] column = {0};
        EconomyBuildings.addHalfButton(dialog, column,
                "[%75]" + (guard.watchMatches ? "Watch fights" : "Simulate only"), true, () -> {
                    guard.watchMatches = !guard.watchMatches;
                    scene.removeDialog();
                    openManageGuard(scene, changes, poiName, objectId, guard);
                });
        if (guard.deckCards.length == 0)
            EconomyBuildings.addHalfButton(dialog, column, "[%75]Give a deck", true, () -> {
                scene.removeDialog();
                openDeckPicker(scene, changes, poiName, objectId, guard);
            });
        else
            EconomyBuildings.addHalfButton(dialog, column, "[%75]Take deck back", true, () -> {
                scene.removeDialog();
                openDeckReturn(scene, changes, poiName, objectId, guard);
            });
        // Round 163 (MOD_SCOPE #118, user: "on the Guard management screen a way to access the
        // inventory and add equipment to a guard"): what the guard wears, from the Armory storage.
        EconomyBuildings.addHalfButton(dialog, column, "[%75]Equipment (" + guard.equipment.size() + ")", true, () -> {
            // Round 168: the Armory screen in guard mode (user mock-up). It is a scene switch, so this
            // dialog is gone by the time Back returns - the same one-shot re-open the Info page uses.
            reopenRosterOnReturn = true;
            pendingChanges = changes;
            pendingPoiName = poiName;
            pendingObjectId = objectId;
            pendingGuard = guard;
            scene.removeDialog();
            forge.adventure.scene.ArmoryScene.instance().open(guard);
        });
        EconomyBuildings.finishHalfButtonRow(dialog, column);

        if (guard.isOutOfCommission(day)) {
            // Round 152 (user request: "Let's add a Heal button for a defeated mage - 100 Shards").
            int healCost = RoamingGuards.healShardCost();
            boolean canAfford = AdventurePlayer.current().getShards() >= healCost;
            EconomyBuildings.addHalfButton(dialog, column, "[%75]Heal " + healCost + "[+Shards]", canAfford, () -> {
                AdventurePlayer.current().takeShards(healCost);
                RoamingGuards.heal(guard, day);
                GameHUD.getInstance().addNotification("[GREEN]Your " + RoamingGuards.displayName(guard.tier)
                        + " guard is back on its feet.", true);
                scene.removeDialog();
                openManageGuard(scene, changes, poiName, objectId, guard);
            });
        }
        EconomyBuildings.addHalfButton(dialog, column, "Change rank", true, () -> {
            scene.removeDialog();
            openRetier(scene, changes, poiName, objectId, guard);
        });
        EconomyBuildings.addHalfButton(dialog, column, "Dismiss", true, () -> {
            scene.removeDialog();
            openDismissConfirm(scene, changes, poiName, objectId, guard);
        });
        EconomyBuildings.addHalfButton(dialog, column, "Back", true, () -> {
            scene.removeDialog();
            openRoster(scene, changes, poiName, objectId);
        });
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        EconomyBuildings.makeContentScrollable(dialog);
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    // ------------------------------------------------------------------ dismiss confirmation

    /**
     * Round 162 (user: "give a warning before you can dismiss a guard - Are you sure, you will lose
     * the deck"). Dismiss is the one button on the manage screen that cannot be undone: a healthy
     * guard's deck comes home - since round 183 (code review G14) rebuilt into the first empty deck slot, or as
     * loose cards only when every slot is full - and a downed guard's deck is forfeited outright. So it
     * asks first, and says which of the two it is about to do.
     */
    private static void openDismissConfirm(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                           String poiName, int objectId, RoamingGuardData guard) {
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        Dialog dialog = new Dialog("Dismiss this guard?", Controls.getSkin());
        boolean hasDeck = guard.deckCards.length > 0;
        String deck = "\"" + guard.deckName + "\" (" + RoamingGuards.cardCount(guard) + " cards)";
        if (!hasDeck)
            EconomyBuildings.addContentRow(dialog, "Are you sure? This " + RoamingGuards.displayName(guard.tier)
                    + " guard carries no deck. Dismissing it ends its contract.");
        else if (guard.isOutOfCommission(day))
            EconomyBuildings.addContentRow(dialog, "[RED]Are you sure? You will lose the deck.[] This guard is out of"
                    + " commission, so " + deck + " is forfeited with it - the cards do not come back. Heal the guard"
                    + " first to keep them.");
        else
            // Round 183 (G14): the deck list survives a dismissal now, as long as a slot is free for it.
            EconomyBuildings.addContentRow(dialog, "[RED]Are you sure?[] " + deck
                    + " comes back to the first empty deck slot - or, with every slot full, as loose cards in your"
                    + " collection.");
        int[] column = {0};
        String deckName = guard.deckName; // dismiss() clears it on the way home, so the notification needs it now
        EconomyBuildings.addHalfButton(dialog, column, "[RED]Dismiss", true, () -> {
            int slot = RoamingGuards.dismiss(guard, day);
            GameHUD.getInstance().addNotification(!hasDeck ? "Your guard was dismissed."
                    : slot >= 0 ? "Your guard was dismissed - \"" + deckName + "\" is back in deck slot " + (slot + 1) + "."
                    : slot == RoamingGuards.DECK_RETURNED_LOOSE
                            ? "Your guard was dismissed - every deck slot is full, so its cards went back to your collection."
                    : "[RED]Your guard was dismissed while out of commission - the deck is lost.");
            scene.removeDialog();
            openRoster(scene, changes, poiName, objectId);
        });
        EconomyBuildings.addHalfButton(dialog, column, "Back", true, () -> {
            scene.removeDialog();
            openManageGuard(scene, changes, poiName, objectId, guard);
        });
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        EconomyBuildings.makeContentScrollable(dialog); // round 164: the forfeit text runs seven lines at 230px
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    /**
     * A grid of checkboxes two to a row inside the dialog's content table. scene2d stacks
     * CheckBoxes one per row by default and nine of them would run off the bottom of the screen -
     * the nested Table is the same trick addHalfButton() uses to pack buttons in pairs.
     * <p>
     * The listener writes directly into the guard's own array. There is no rebuild, so unticking
     * three boxes in a row is three clicks rather than three dialog tear-downs.
     */
    private static void addCheckGrid(Dialog dialog, RoamingGuardData guard, String kind,
                                     String[] labels, boolean[] state) {
        // ROUND 151 (user playtest: "the check boxes not in a line... can it be tightened up").
        // Each PAIR used to be its own nested Table, and two sibling tables size their columns
        // independently, so "Apprentice" and "Master" started at different x. One grid for the
        // whole block shares one column layout, which is what actually lines them up.
        // ROUND 155 (user playtest: "The manage guard page is off the screen. Anyway we can make the
        // check box items 1 line each?"). Two-per-row cost five rows for nine boxes and pushed the
        // dialog past the bottom of a 270px-tall screen. One row per group is two rows total; the
        // font drops to 0.55 so four ranks fit across 250px (~62px a column) and five colors across
        // 230px in portrait (~46px), which the longest labels - Apprentice and Archmage - clear.
        float width = forge.Forge.isLandscapeMode() ? 250f : 230f;
        Table grid = new Table();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            final String label = labels[i];
            CheckBox box = Controls.newCheckBox(label);
            box.getLabel().setFontScale(0.55f);
            box.getImageCell().padRight(1f);
            box.setChecked(index < state.length && state[index]);
            box.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (index >= state.length)
                        return;
                    state[index] = ((CheckBox) actor).isChecked();
                    System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier)
                            + " engage " + kind + " " + label + " -> " + state[index]);
                }
            });
            grid.add(box).width(width / labels.length).left();
        }
        dialog.getContentTable().add(grid).width(width).left().row();
    }

    private static void openRetier(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                   String poiName, int objectId, RoamingGuardData guard) {
        Dialog dialog = new Dialog("Change Rank", Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, "An upgrade costs the difference in weekly pay, once. "
                + "A downgrade refunds nothing - it just starts costing less.");
        int[] column = {0};
        for (String tier : RoamingGuards.TIERS_ASCENDING) {
            boolean current = tier.equals(guard.tier);
            int difference = Math.max(0, RoamingGuards.weeklyGoldCost(tier) - RoamingGuards.weeklyGoldCost(guard.tier));
            // Round 183 (code review G13): the weekly pay has a shard part too (Master / Archmage) - "the difference
            // in weekly pay" charged only the gold half of it.
            int shardDifference = Math.max(0, RoamingGuards.weeklyShardCost(tier) - RoamingGuards.weeklyShardCost(guard.tier));
            boolean canAfford = AdventurePlayer.current().getGold() >= difference
                    && AdventurePlayer.current().getShards() >= shardDifference;
            String price = (difference > 0 ? " " + difference + "[+Gold]" : "")
                    + (shardDifference > 0 ? " " + shardDifference + "[+Shards]" : "");
            String label = (forge.Forge.isLandscapeMode() ? "[%75]" : "[%62]") + RoamingGuards.displayName(tier) // round 164: portrait
                    + " [+Life]" + RoamingGuards.lifeFor(tier)
                    + " spd" + (int) RoamingGuards.speedFor(tier)
                    + (current ? " (current)" : !price.isEmpty() ? price : " (free)");
            EconomyBuildings.addHalfButton(dialog, column, label, !current && canAfford, () -> {
                int charged = RoamingGuards.retier(guard, tier);
                if (charged > 0)
                    AdventurePlayer.current().takeGold(charged);
                if (shardDifference > 0)
                    AdventurePlayer.current().takeShards(shardDifference);
                scene.removeDialog();
                openManageGuard(scene, changes, poiName, objectId, guard);
            });
        }
        // Round 164: a half button like every other button in this file (round 146's width rule) - the
        // 240px full row it was sat oddly under 118px pairs, and 240 is all a 270px portrait stage has.
        EconomyBuildings.addHalfButton(dialog, column, "Back", true, () -> {
            scene.removeDialog();
            openManageGuard(scene, changes, poiName, objectId, guard);
        });
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    /** Round 146 (user report: Info then Back lands on the Armory, not here). InfoTextScene
     *  switches SCENES, so the dialog is gone by the time its Back returns - the roster has to be
     *  re-opened when the Armory becomes active again. Same one-shot flag pattern RewardScene
     *  already uses for its empty-booster note. */
    private static boolean reopenRosterOnReturn = false;
    private static forge.adventure.pointofintrest.PointOfInterestChanges pendingChanges;
    private static String pendingPoiName;
    private static int pendingObjectId;
    /** Round 168: set when the Armory screen was opened from a guard's page, so Back lands on that
     *  guard's page rather than on the roster. */
    private static RoamingGuardData pendingGuard;

    public static boolean consumeReopenRoster(UIScene scene) {
        if (!reopenRosterOnReturn)
            return false;
        reopenRosterOnReturn = false;
        RoamingGuardData guard = pendingGuard;
        pendingGuard = null;
        if (guard != null && RoamingGuards.roster().contains(guard))
            openManageGuard(scene, pendingChanges, pendingPoiName, pendingObjectId, guard);
        else
            openRoster(scene, pendingChanges, pendingPoiName, pendingObjectId);
        return true;
    }

    private static void showInfo(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                 String poiName, int objectId) {
        reopenRosterOnReturn = true;
        pendingChanges = changes;
        pendingPoiName = poiName;
        pendingObjectId = objectId;
        pendingGuard = null;
        scene.removeDialog();
        forge.adventure.scene.InfoTextScene.show("Roaming Guards", java.util.Arrays.asList(
                "A roaming guard is hired at your Capitol and carries one of YOUR decks. Handing over a "
                        + "deck removes those cards from your collection and empties the slot; taking the deck "
                        + "back returns both.",
                "Rank sets starting life and overworld speed. Archmage matches your own speed and each rank "
                        + "below is 2 slower. Most attacking mages are slower than any rank, but a Mythic mage "
                        + "outruns all of them - build a teleporter at a town you want covered and the guard "
                        + "skips the race entirely.",
                "When one of your towns is targeted, an idle guard sets out to intercept the attacker before "
                        + "it arrives. It handles one threat at a time and returns to the Capitol before taking "
                        + "another. If it does not get there in time, the town defends itself as usual and the "
                        + "guard turns for home.",
                "Fights are real matches played by the AI with your deck. The Watch/Simulate toggle only "
                        + "decides whether you see them.",
                "A defeated guard is out of commission for a month. You may dismiss it during that time, but "
                        + "the deck is lost if you do."));
    }
}
