package forge.adventure.util;

import forge.adventure.stage.GameHUD;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
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
        EconomyBuildings.addButtonRow(dialog, "Hire a Roaming Guard", RoamingGuards.hasRoom(), () -> {
            scene.removeDialog();
            openHireTier(scene, changes, poiName, objectId);
        });
        EconomyBuildings.addHalfButton(dialog, column, "Info", true, () -> showInfo(scene));
        EconomyBuildings.addHalfButton(dialog, column, "Close", true, scene::removeDialog);
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    private static String describe(RoamingGuardData guard, int day) {
        StringBuilder sb = new StringBuilder(RoamingGuards.displayName(guard.tier));
        sb.append(" - ").append(guard.maxLife).append(" life, speed ").append((int) RoamingGuards.speedFor(guard.tier));
        sb.append(guard.deckCards.length == 0 ? ", NO DECK" : ", \"" + guard.deckName + "\" (" + RoamingGuards.cardCount(guard) + ")");
        if (guard.isOutOfCommission(day))
            sb.append(" [RED](out of commission until day ").append(guard.downUntilDay).append(")");
        else if (guard.returningHome)
            sb.append(" (returning to the Capitol)");
        else if (!guard.isIdle())
            sb.append(" (defending)");
        else if (guard.deckCards.length == 0)
            sb.append(" (idle - give it a deck)");
        else
            sb.append(" (ready)");
        return sb.toString();
    }

    // ------------------------------------------------------------------ hire

    private static void openHireTier(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                     String poiName, int objectId) {
        Dialog dialog = new Dialog("Hire a Roaming Guard", Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, "The rank sets starting life and how fast the guard "
                + "crosses the world. Speed matters: most attackers are slower than any rank, but a "
                + "Mythic mage outruns all of them - a teleporter at the threatened town is the answer to those.");
        int[] column = {0};
        for (String tier : RoamingGuards.TIERS_ASCENDING) {
            int gold = RoamingGuards.weeklyGoldCost(tier);
            int shards = RoamingGuards.weeklyShardCost(tier);
            boolean canAfford = AdventurePlayer.current().getGold() >= gold
                    && AdventurePlayer.current().getShards() >= shards;
            String label = "[%75]" + RoamingGuards.displayName(tier) + " - " + RoamingGuards.lifeFor(tier)
                    + " life, spd " + (int) RoamingGuards.speedFor(tier) + " (" + gold + " [+Gold]"
                    + (shards > 0 ? " + " + shards + " [+Shards]" : "") + "/wk)";
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
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        EconomyBuildings.addButtonRow(dialog, "Back", true, () -> {
            scene.removeDialog();
            openRoster(scene, changes, poiName, objectId);
        });
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    // ------------------------------------------------------------------ deck give / take

    private static void openDeckPicker(UIScene scene, forge.adventure.pointofintrest.PointOfInterestChanges changes,
                                       String poiName, int objectId, RoamingGuardData guard) {
        AdventurePlayer player = AdventurePlayer.current();
        Dialog dialog = new Dialog("Give a Deck", Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, "Choose a deck for your " + RoamingGuards.displayName(guard.tier)
                + ". [RED]Those cards leave your collection and the slot empties[] until you take the deck back.");
        int[] column = {0};
        boolean any = false;
        for (int i = 0; i < player.getDeckCount(); i++) {
            Deck deck = player.getDeck(i);
            int size = deck == null ? 0 : deck.getMain().countAll();
            if (size == 0)
                continue;
            any = true;
            int slot = i;
            EconomyBuildings.addHalfButton(dialog, column, "[%75]" + deck.getName() + " (" + size + ")", true, () -> {
                RoamingGuards.giveDeck(guard, slot);
                scene.removeDialog();
                openManageGuard(scene, changes, poiName, objectId, guard);
            });
        }
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        if (!any)
            EconomyBuildings.addContentRow(dialog, "You have no built decks to give.");
        EconomyBuildings.addButtonRow(dialog, "Back", true, () -> {
            scene.removeDialog();
            openManageGuard(scene, changes, poiName, objectId, guard);
        });
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
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        if (!any)
            EconomyBuildings.addContentRow(dialog, "[RED]Every deck slot is full. Clear one first.");
        EconomyBuildings.addButtonRow(dialog, "Back", true, () -> {
            scene.removeDialog();
            openManageGuard(scene, changes, poiName, objectId, guard);
        });
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
            EconomyBuildings.addContentRow(dialog, "[RED]Dismissing now forfeits the deck. "
                    + "Waiting until day " + guard.downUntilDay + " costs nothing.");
        EconomyBuildings.addContentRow(dialog, "Engages: " + engagementSummary(guard));

        int[] column = {0};
        // Engagement toggles - one per enemy rank, in the ladder's own order.
        for (int i = 0; i < RoamingGuards.TIERS_ASCENDING.length; i++) {
            int index = i;
            String rank = RoamingGuards.displayName(RoamingGuards.TIERS_ASCENDING[i]);
            EconomyBuildings.addHalfButton(dialog, column,
                    "[%75]" + (guard.engageTier[index] ? "YES " : "no  ") + rank, true, () -> {
                        guard.engageTier[index] = !guard.engageTier[index];
                        System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier)
                                + " engage " + rank + " -> " + guard.engageTier[index]);
                        scene.removeDialog();
                        openManageGuard(scene, changes, poiName, objectId, guard);
                    });
        }
        EconomyBuildings.finishHalfButtonRow(dialog, column);

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

        EconomyBuildings.addButtonRow(dialog, "Change rank", true, () -> {
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
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    private static String engagementSummary(RoamingGuardData guard) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < RoamingGuards.TIERS_ASCENDING.length; i++) {
            if (!guard.engageTier[i])
                continue;
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(RoamingGuards.displayName(RoamingGuards.TIERS_ASCENDING[i]));
        }
        return sb.length() == 0 ? "[RED]nothing - it will avoid every attacker" : sb.toString();
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
            String label = "[%75]" + RoamingGuards.displayName(tier) + " - " + RoamingGuards.lifeFor(tier)
                    + " life, spd " + (int) RoamingGuards.speedFor(tier)
                    + (current ? " (current)" : difference > 0 ? " (" + difference + " [+Gold])" : " (free)");
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

    private static void showInfo(UIScene scene) {
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
