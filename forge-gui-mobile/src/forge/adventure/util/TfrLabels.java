package forge.adventure.util;

import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.DialogData;

/**
 * Round 992 (the tooltip audit's label list, 2026-11-09): visible text for the spots a hover tip can't help - Android has
 * no mouse, and a player who doesn't know to hover never sees one. The Abandon Quest confirm names what failing costs,
 * Repair shows its price, a deck slot shows its card count against the duel minimum, a greyed Armory Re-roll says when
 * it comes back, and the Speed-Up box shows its multiplier while it is on. Behind ConfigData.tfrHelpLabels (TFR plane on,
 * stock planes untouched). [TFR-Labels].
 */
public final class TfrLabels {
    private TfrLabels() {
    }

    public static boolean on() {
        try {
            return Config.instance().getConfigData().tfrHelpLabels;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ Abandon Quest

    /**
     * The most reputation one way through the quest's failure dialog takes (abandoning plays it, as a failure does):
     * its own actions plus the worst of its options, all the way down. 0 when it takes none. The sum of negative
     * addMapReputation only; a gain (none today) is not counted.
     */
    public static int failureReputation(DialogData d) {
        if (d == null)
            return 0;
        int own = 0;
        if (d.action != null)
            for (DialogData.ActionData a : d.action)
                if (a != null && a.addMapReputation < 0)
                    own += a.addMapReputation;
        int worst = 0;
        if (d.options != null)
            for (DialogData o : d.options)
                worst = Math.min(worst, failureReputation(o));
        return own + worst;
    }

    /** True when some reputation loss in the failure dialog names another place (POIReference). */
    private static boolean failureElsewhere(DialogData d) {
        if (d == null)
            return false;
        if (d.action != null)
            for (DialogData.ActionData a : d.action)
                if (a != null && a.addMapReputation < 0 && a.POIReference != null && !a.POIReference.isEmpty())
                    return true;
        if (d.options != null)
            for (DialogData o : d.options)
                if (failureElsewhere(o))
                    return true;
        return false;
    }

    /** The Abandon Quest confirm: the stock question, then what it costs ("[+Reputation] -2 in that town"). */
    public static String abandonConfirm(AdventureQuestData quest, String stock) {
        if (!on() || quest == null)
            return stock;
        int rep = failureReputation(quest.failureDialog);
        String cost = rep == 0 ? "It counts as failing the quest."
                : "It counts as failing the quest: " + ResourceGlyphs.reputation() + " " + rep
                + (failureElsewhere(quest.failureDialog) ? " with the town it names." : " in the town that gave it.");
        System.out.println("[TFR-Labels] abandon confirm for \"" + quest.name + "\": reputation " + rep);
        return stock + "\n" + cost;
    }

    // ------------------------------------------------------------------ deck slots

    /** "Deck name  [grey]23/40" - the count red while under the duel minimum. */
    public static String deckSlot(String name, int size, int min) {
        if (!on() || min <= 0)
            return name;
        return name + "  [%80]" + (size < min ? "[#e05050]" : "[#a0a0a0]") + size + "/" + min + "[]";
    }

    // ------------------------------------------------------------------ Repair

    /** The Repair button with its price: "[+ToolBox] Repair [+Gold] 120". */
    public static String repairButton(String stock, int cost) {
        if (!on())
            return stock;
        return "[%70][+ToolBox] " + forge.Forge.getLocalizer().getMessage("lblRepair") + " [+Gold] " + cost;
    }

    // ------------------------------------------------------------------ Armory Re-roll

    /** Days until a re-roll stamped on lastDay comes back (cooldown 7): 0 when it is ready. */
    public static int daysLeft(Integer lastDay, int currentDay, int cooldown) {
        if (lastDay == null)
            return 0;
        return Math.max(0, cooldown - (currentDay - lastDay));
    }

    /** The Armory's Re-roll button: its price, and while it waits, the days left instead. */
    public static String armoryReroll(int cost, int daysLeft) {
        if (!on())
            return "[%80]Re-roll Inventory (" + cost + " [+Shards])";
        if (daysLeft > 0)
            return "[%80]Re-roll Inventory in " + daysLeft + (daysLeft == 1 ? " day" : " days");
        return "[%80]Re-roll Inventory ([+Shards] " + cost + ")";
    }

    // ------------------------------------------------------------------ Speed-Up

    /** "50x" beside the Speed-Up box while it is on (the multiplier from the tuning table). */
    public static String speedTag(float multiplier) {
        return "[%80][GOLD]" + (multiplier == Math.round(multiplier) ? String.valueOf(Math.round(multiplier)) : String.valueOf(multiplier)) + "x";
    }
}
