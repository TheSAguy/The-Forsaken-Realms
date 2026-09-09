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
        sb.append(" - [+Life] ").append(guard.maxLife).append(", speed ").append((int) RoamingGuards.speedFor(guard.tier));
        sb.append(guard.deckCards.length == 0 ? "" : ", \"" + guard.deckName + "\" (" + RoamingGuards.cardCount(guard) + ")");
        if (guard.isOutOfCommission(day))
            // Round 152 (user read "until day 236" as "236 days left" - it was 30, from day 206).
            // The absolute day alone made a correct number look alarming; lead with the countdown.
            sb.append(" [RED](hurt - ").append(guard.downUntilDay - day)
                    .append(" more days, back on day ").append(guard.downUntilDay).append(")");
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
            String label = "[%75]" + RoamingGuards.displayName(tier) + " [+Life]" + RoamingGuards.lifeFor(tier)
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

    private static void openDeckPicker(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                       String poiName, int objectId, RoamingGuardData guard) {
        AdventurePlayer player = AdventurePlayer.current();
        Dialog dialog = new Dialog("Give a Deck", Controls.getSkin());
        int minimum = RoamingGuards.minDeckSize();
        EconomyBuildings.addContentRow(dialog, "Choose a deck for your " + RoamingGuards.displayName(guard.tier)
                + ". [RED]Those cards leave your collection and the slot empties[] until you take the deck "
                + "back. Cards shared with your other decks are removed from those too - you only own one copy.");
        int[] column = {0};
        boolean any = false;
        for (int i = 0; i < player.getDeckCount(); i++) {
            Deck deck = player.getDeck(i);
            int size = deck == null ? 0 : deck.getMain().countAll();
            if (size == 0)
                continue;
            any = true;
            int slot = i;
            java.util.LinkedHashMap<String, Integer> impact = RoamingGuards.sharedCardImpact(slot);
            // Round 148 (user spec: "An invalid deck, (less than 40 cards), should not be possible
            // to give"). Gated on what the COLLECTION can supply, not on what the deck lists - a
            // deck whose cards already went out with another guard still lists 40 of them.
            int deliverable = RoamingGuards.deliverableCount(slot);
            boolean playable = deliverable >= minimum;
            String suffix = !playable ? " [RED]X" : impact.isEmpty() ? "" : " [RED]!";
            String count = deliverable == size ? String.valueOf(size) : deliverable + " of " + size;
            EconomyBuildings.addHalfButton(dialog, column,
                    "[%75]" + deck.getName() + " (" + count + ")" + suffix, playable, () -> {
                RoamingGuards.giveDeck(guard, slot);
                scene.removeDialog();
                openManageGuard(scene, changes, poiName, objectId, guard);
            });
            if (!playable)
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
        if (!any)
            EconomyBuildings.addContentRow(dialog, "You have no built decks to give.");
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
        AdventurePlayer player = AdventurePlayer.current();
        Dialog dialog = new Dialog("Take the Deck Back", Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, "Choose an empty slot for \"" + guard.deckName + "\" ("
                + RoamingGuards.cardCount(guard) + " cards). The cards return to your collection.");
        int[] column = {0};
        boolean any = false;
        for (int i = 0; i < player.getDeckCount(); i++) {
            Deck deck = player.getDeck(i);
            if (deck != null && deck.getMain().countAll() > 0)
                continue; // only offer empty slots, so nothing the player built gets overwritten
            any = true;
            int slot = i;
            EconomyBuildings.addHalfButton(dialog, column, "[%75]Slot " + (i + 1), true, () -> {
                RoamingGuards.returnDeckToSlot(guard, slot);
                scene.removeDialog();
                openManageGuard(scene, changes, poiName, objectId, guard);
            });
        }
        if (!any)
            EconomyBuildings.addContentRow(dialog, "[RED]Every deck slot is full. Clear one first.");
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
            boolean returned = RoamingGuards.dismiss(guard, day);
            GameHUD.getInstance().addNotification(returned
                    ? "Your guard was dismissed and the deck returned to your collection."
                    : "[RED]Your guard was dismissed while out of commission - the deck is lost.");
            scene.removeDialog();
            openRoster(scene, changes, poiName, objectId);
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
            boolean canAfford = AdventurePlayer.current().getGold() >= difference;
            String label = "[%75]" + RoamingGuards.displayName(tier) + " [+Life]" + RoamingGuards.lifeFor(tier)
                    + " spd" + (int) RoamingGuards.speedFor(tier)
                    + (current ? " (current)" : difference > 0 ? " " + difference + "[+Gold]" : " (free)");
            EconomyBuildings.addHalfButton(dialog, column, label, !current && canAfford, () -> {
                int charged = RoamingGuards.retier(guard, tier);
                if (charged > 0)
                    AdventurePlayer.current().takeGold(charged);
                scene.removeDialog();
                openManageGuard(scene, changes, poiName, objectId, guard);
            });
        }
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        EconomyBuildings.addButtonRow(dialog, "Back", true, () -> {
            scene.removeDialog();
            openManageGuard(scene, changes, poiName, objectId, guard);
        });
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

    public static boolean consumeReopenRoster(UIScene scene) {
        if (!reopenRosterOnReturn)
            return false;
        reopenRosterOnReturn = false;
        openRoster(scene, pendingChanges, pendingPoiName, pendingObjectId);
        return true;
    }

    private static void showInfo(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                 String poiName, int objectId) {
        reopenRosterOnReturn = true;
        pendingChanges = changes;
        pendingPoiName = poiName;
        pendingObjectId = objectId;
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
