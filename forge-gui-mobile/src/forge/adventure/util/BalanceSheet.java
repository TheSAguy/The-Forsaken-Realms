package forge.adventure.util;

import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.UIScene;
import forge.adventure.util.ResourceLedger.Bucket;
import forge.adventure.world.WorldSave;

/**
 * Weekly balance sheet (round 147, user request: "We need a financial info page. Weekly Income and
 * Expenses. With all these guards, the player won't know how much he is spending.").
 * <p>
 * Round 148 rebuilt the body on {@link ResourceLedger}. The first version RECOMPUTED every figure
 * from the helpers the weekly sweep pays from, which is exactly right for a rate - mines, interest,
 * wages - and cannot work at all for what the user asked for next: "All other Income... quest
 * rewards, loot, pickups" and "All other expenses... Buildings, Lost to re-rolls, duel lost, arena
 * entry fees, item uses". None of those leave standing state behind to recompute from, so they are
 * recorded as they happen and read back here.
 * <p>
 * What survives from the recompute is the forward look: {@link #compute()} still derives next
 * payday's fixed commitments live, because that one genuinely is a rate and the player wants it
 * BEFORE it is charged rather than a week later.
 * <p>
 * Reachable from the Bank, the Exchange and the World Standings page. Built as a Dialog rather
 * than an InfoTextScene because it has to open over three different screens without a scene
 * switch - switching scenes is what made the guard Info button lose its way in round 146.
 */
public class BalanceSheet {
    private BalanceSheet() {}

    /** Next payday's fixed commitments. Resources are kept apart because they are not
     *  interchangeable - a player can be gold-poor and stone-rich, and a single "net" number
     *  would hide that. */
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
            // here either - this half of the sheet predicts the next payday, not contracts held.
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
        open(stage, onClose, false);
    }

    private static void open(forge.adventure.stage.MapStage stage, Runnable onClose, boolean lastWeek) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        fill(dialog, lastWeek);
        int[] column = {0};
        addToggle(dialog, column, lastWeek, () -> open(stage, onClose, !lastWeek));
        EconomyBuildings.addHalfButton(dialog, column, "Back", true, onClose);
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        dialog.setKeepWithinStage(true);
        stage.showDialog();
    }

    public static void open(UIScene scene) {
        open(scene, false);
    }

    private static void open(UIScene scene, boolean lastWeek) {
        Dialog dialog = new Dialog("Balance Sheet", Controls.getSkin());
        fill(dialog, lastWeek);
        int[] column = {0};
        addToggle(dialog, column, lastWeek, () -> {
            scene.removeDialog();
            open(scene, !lastWeek);
        });
        EconomyBuildings.addHalfButton(dialog, column, "Close", true, scene::removeDialog);
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    /** Greyed out rather than hidden until a week has actually rolled, so the player can see the
     *  view exists instead of wondering why it appeared later. */
    private static void addToggle(Dialog dialog, int[] column, boolean lastWeek, Runnable action) {
        EconomyBuildings.addHalfButton(dialog, column, lastWeek ? "This Week" : "Last Week",
                lastWeek || ResourceLedger.hasLastWeek(), action);
    }

    // ------------------------------------------------------------------ the rows

    private static void fill(Dialog dialog, boolean lastWeek) {
        AdventurePlayer player = AdventurePlayer.current();
        int start = Math.max(0, ResourceLedger.weekStartDay(lastWeek));
        EconomyBuildings.addContentRow(dialog, "[%110]Days " + start + "-" + (start + 6)
                + (lastWeek ? "[%]" : " (so far)[%]"));

        EconomyBuildings.addContentRow(dialog, "[%100]Income[%]");
        EconomyBuildings.addContentRow(dialog, "[%90]Mines: " + amounts(income(lastWeek, Bucket.MINES)));
        EconomyBuildings.addContentRow(dialog, "[%90]Bank interest: " + amounts(income(lastWeek, Bucket.INTEREST)));
        EconomyBuildings.addContentRow(dialog, "[%90]Everything else: " + amounts(income(lastWeek, Bucket.OTHER)));
        EconomyBuildings.addContentRow(dialog, "[%90][GREEN]Total in:[] " + amounts(total(lastWeek, true)));

        EconomyBuildings.addContentRow(dialog, "[%100]Expenses[%]");
        EconomyBuildings.addContentRow(dialog, "[%90]Local guards: " + amounts(expense(lastWeek, Bucket.GUARD_LOCAL)));
        if (RoamingGuards.isEnabled())
            EconomyBuildings.addContentRow(dialog, "[%90]Roaming guards: "
                    + amounts(expense(lastWeek, Bucket.GUARD_ROAMING)));
        EconomyBuildings.addContentRow(dialog, "[%90]Everything else: " + amounts(expense(lastWeek, Bucket.OTHER)));
        EconomyBuildings.addContentRow(dialog, "[%90][RED]Total out:[] " + amounts(total(lastWeek, false)));

        EconomyBuildings.addContentRow(dialog, "[%100]Net: " + signed(net(lastWeek)) + "[%]");

        // The one figure that still has to be derived rather than recorded: what is already
        // committed for the payday ahead. A player who hired a guard yesterday needs to see the
        // bill before it lands, and no amount of history shows that.
        Weekly next = compute();
        int nextPayday = ((WorldSave.getCurrentSave().getWorld().getCurrentDay() / 7) + 1) * 7;
        // Round 151 (user playtest: "Gold seems to vary slightly 322, 304, 75... I only had one
        // gold mine, so the income each week should have only been 75"). It was, and the recorded
        // Mines line said so every week - the varying figure was this projection, which folded BANK
        // INTEREST into one total without naming it. Interest moves with the bank balance, so the
        // number moved. Broken out, it explains itself.
        EconomyBuildings.addContentRow(dialog, "[%90]Day " + nextPayday + " payday: mines [GREEN]+"
                + next.goldIn + "[+Gold][]"
                + (next.interestIn > 0 ? ", interest [GREEN]+" + next.interestIn + "[+Gold][]" : "")
                + ", wages [RED]-" + next.goldOut() + "[+Gold]"
                + (next.shardsOut() > 0 ? " -" + next.shardsOut() + "[+Shards]" : "") + "[]");

        EconomyBuildings.addContentRow(dialog, "[%90]On hand: " + player.getGold() + "[+Gold]  "
                + player.getShards() + "[+Shards]  " + player.getWood() + "[+Wood]  "
                + player.getStone() + "[+Stone]");
        EconomyBuildings.addContentRow(dialog, "[%75]Bank transfers and Exchange trades move what you "
                + "already own, so neither column counts them.");
    }

    // ------------------------------------------------------------------ number wrangling

    private static int[] income(boolean lastWeek, Bucket bucket) {
        int[] values = new int[ResourceLedger.RESOURCES];
        for (int r = 0; r < values.length; r++)
            values[r] = ResourceLedger.income(lastWeek, bucket, r);
        return values;
    }

    private static int[] expense(boolean lastWeek, Bucket bucket) {
        int[] values = new int[ResourceLedger.RESOURCES];
        for (int r = 0; r < values.length; r++)
            values[r] = ResourceLedger.expense(lastWeek, bucket, r);
        return values;
    }

    private static int[] total(boolean lastWeek, boolean incoming) {
        int[] values = new int[ResourceLedger.RESOURCES];
        for (int r = 0; r < values.length; r++)
            values[r] = incoming ? ResourceLedger.totalIncome(lastWeek, r) : ResourceLedger.totalExpense(lastWeek, r);
        return values;
    }

    private static int[] net(boolean lastWeek) {
        int[] values = new int[ResourceLedger.RESOURCES];
        for (int r = 0; r < values.length; r++)
            values[r] = ResourceLedger.totalIncome(lastWeek, r) - ResourceLedger.totalExpense(lastWeek, r);
        return values;
    }

    /** Only the resources that actually moved - a row of four zeroes is harder to read than a
     *  dash, and most weeks touch two of the four. */
    private static String amounts(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < values.length; r++) {
            if (values[r] == 0)
                continue;
            if (sb.length() > 0)
                sb.append("  ");
            sb.append(values[r]).append(ResourceLedger.GLYPH[r]);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static String signed(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < values.length; r++) {
            if (values[r] == 0)
                continue;
            if (sb.length() > 0)
                sb.append("  ");
            sb.append(values[r] > 0 ? "[GREEN]+" : "[RED]").append(values[r])
                    .append(ResourceLedger.GLYPH[r]).append("[]");
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }
}
