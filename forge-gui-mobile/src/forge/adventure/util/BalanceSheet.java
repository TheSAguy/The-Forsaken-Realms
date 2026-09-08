package forge.adventure.util;

import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.UIScene;
import forge.adventure.world.WorldSave;

/**
 * Weekly balance sheet (round 147, user request: "We need a financial info page. Weekly Income and
 * Expenses. With all these guards, the player won't know how much he is spending.").
 * <p>
 * Every figure is derived live from the same helpers the weekly sweep itself uses, rather than
 * from a running tally - a tally would drift the moment a mine is built, a guard is hired or a
 * town changes hands, and there is no cheap way to notice. Recomputing means the sheet can never
 * disagree with what actually gets paid.
 * <p>
 * Reachable from the Bank, the Exchange and the World Standings page. Built as a Dialog rather
 * than an InfoTextScene because it has to open over three different screens without a scene
 * switch - switching scenes is what made the guard Info button lose its way in round 146.
 */
public class BalanceSheet {
    private BalanceSheet() {}

    /** One week's figures. Resources are kept apart because they are not interchangeable - a
     *  player can be gold-poor and stone-rich, and a single "net" number would hide that. */
    public static class Weekly {
        public int goldIn, woodIn, stoneIn, shardsIn;
        public int interestIn;
        public int localGuardGold, localGuardShards;
        public int roamingGuardGold, roamingGuardShards;

        public int goldOut() { return localGuardGold + roamingGuardGold; }
        public int shardsOut() { return localGuardShards + roamingGuardShards; }
        public int netGold() { return goldIn + interestIn - goldOut(); }
        public int netShards() { return shardsIn - shardsOut(); }
    }

    public static Weekly compute() {
        Weekly w = new Weekly();
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            if (changes == null)
                continue;
            w.goldIn += EconomyBuildings.weeklyMineOutput(changes, EconomyBuildings.GOLD_MINE);
            w.woodIn += EconomyBuildings.weeklyMineOutput(changes, EconomyBuildings.LUMBER_MILL);
            w.stoneIn += EconomyBuildings.weeklyMineOutput(changes, EconomyBuildings.STONE_MINE);
            w.shardsIn += EconomyBuildings.weeklyMineOutput(changes, EconomyBuildings.SHARD_MINE);
            w.interestIn += EconomyBuildings.weeklyBankInterest(changes);
            for (int i = 0; i < changes.getGuardCount(); i++) {
                String tier = changes.getGuardTier(i);
                w.localGuardGold += EconomyBuildings.guardWeeklyGoldCost(tier);
                w.localGuardShards += EconomyBuildings.guardWeeklyShardCost(tier);
            }
        }
        for (RoamingGuardData guard : RoamingGuards.roster()) {
            // An unarmed guard is not on the payroll (round 146), so it must not appear as a cost
            // here either - the sheet is meant to predict the next payday, not list contracts.
            if (!RoamingGuards.isArmed(guard))
                continue;
            w.roamingGuardGold += RoamingGuards.weeklyGoldCost(guard.tier);
            w.roamingGuardShards += RoamingGuards.weeklyShardCost(guard.tier);
        }
        return w;
    }

    /** MapStage flavour, for the Bank and Exchange - those dialogs live on the stage, not on a
     *  UIScene, and closing this one has to put the caller's dialog back rather than leave the
     *  player looking at an empty building. */
    public static void open(forge.adventure.stage.MapStage stage, Runnable onClose) {
        Weekly w = compute();
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        fill(dialog, w);
        int[] column = {0};
        EconomyBuildings.addHalfButton(dialog, column, "Back", true, onClose);
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        dialog.setKeepWithinStage(true);
        stage.showDialog();
    }

    public static void open(UIScene scene) {
        Weekly w = compute();
        Dialog dialog = new Dialog("Balance Sheet", Controls.getSkin());
        fill(dialog, w);
        int[] column = {0};
        EconomyBuildings.addHalfButton(dialog, column, "Close", true, scene::removeDialog);
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    /** The rows themselves, shared by both entry points. */
    private static void fill(Dialog dialog, Weekly w) {
        AdventurePlayer player = AdventurePlayer.current();
        EconomyBuildings.addContentRow(dialog, "[%110]Weekly Income[%]");
        EconomyBuildings.addContentRow(dialog, "Mines: " + w.goldIn + "[+Gold]  " + w.woodIn + "[+Wood]  "
                + w.stoneIn + "[+Stone]  " + w.shardsIn + "[+Shards]");
        EconomyBuildings.addContentRow(dialog, "Bank interest: " + w.interestIn + "[+Gold]");

        EconomyBuildings.addContentRow(dialog, "[%110]Weekly Expenses[%]");
        EconomyBuildings.addContentRow(dialog, "Local guards: " + w.localGuardGold + "[+Gold]"
                + (w.localGuardShards > 0 ? "  " + w.localGuardShards + "[+Shards]" : ""));
        EconomyBuildings.addContentRow(dialog, "Roaming guards: " + w.roamingGuardGold + "[+Gold]"
                + (w.roamingGuardShards > 0 ? "  " + w.roamingGuardShards + "[+Shards]" : ""));

        String goldNet = (w.netGold() >= 0 ? "[GREEN]+" : "[RED]") + w.netGold() + "[]";
        String shardNet = (w.netShards() >= 0 ? "[GREEN]+" : "[RED]") + w.netShards() + "[]";
        EconomyBuildings.addContentRow(dialog, "[%110]Net per week[%]");
        EconomyBuildings.addContentRow(dialog, goldNet + "[+Gold]   " + shardNet + "[+Shards]   +"
                + w.woodIn + "[+Wood]   +" + w.stoneIn + "[+Stone]");

        EconomyBuildings.addContentRow(dialog, "[%110]On hand[%]");
        EconomyBuildings.addContentRow(dialog, player.getGold() + "[+Gold]  " + player.getShards() + "[+Shards]  "
                + player.getWood() + "[+Wood]  " + player.getStone() + "[+Stone]");
    }
}
