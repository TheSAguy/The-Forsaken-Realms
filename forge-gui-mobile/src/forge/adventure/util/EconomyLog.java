package forge.adventure.util;

import forge.adventure.player.AdventurePlayer;

/**
 * Round 997 (notes/logs-1108.md P3-8): Trader trades and bank moves logged nothing, so "154,080 gold in 10 minutes with
 * no duel" could not be traced to either. {@code [TFR-Trade]} for each exchange, {@code [TFR-Bank]} for each deposit or
 * withdrawal at the Bank's buttons.
 *
 * <p>A player sells in long runs (that purse was about 1,900 clicks), so a run of the same action logs its first
 * {@link #FIRST} and then every {@link #EVERY}th, and each line carries the run's count and totals: the last line before
 * a different action is at most {@link #EVERY} - 1 clicks behind. Never throws.
 */
public final class EconomyLog {
    static final int FIRST = 3;
    static final int EVERY = 25;
    static final String[] RESOURCES = {"gold", "shards", "wood", "stone"};

    private static String runKey;
    private static int runCount;
    private static final long[] runTotal = new long[RESOURCES.length];

    private EconomyLog() {
    }

    static boolean shouldLog(int count) {
        return count <= FIRST || count % EVERY == 0;
    }

    /** Counts one more of {@code key} (a different key starts a new run) and adds the deltas (gold, shards, wood,
     *  stone); returns the run's count. */
    static synchronized int count(String key, int... deltas) {
        if (!key.equals(runKey)) {
            runKey = key;
            runCount = 0;
            java.util.Arrays.fill(runTotal, 0L);
        }
        runCount++;
        for (int i = 0; i < deltas.length && i < runTotal.length; i++)
            runTotal[i] += deltas[i];
        return runCount;
    }

    static synchronized String totals() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runTotal.length; i++) {
            if (runTotal[i] == 0)
                continue;
            sb.append(sb.length() == 0 ? "" : ", ").append(RESOURCES[i]).append(' ')
                    .append(runTotal[i] > 0 ? "+" : "").append(runTotal[i]);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    static synchronized void reset() {
        runKey = null;
        runCount = 0;
        java.util.Arrays.fill(runTotal, 0L);
    }

    /** "gold 100, wood 5" for the non-zero amounts, or "nothing". */
    static String amounts(int gold, int shards, int wood, int stone) {
        int[] a = {gold, shards, wood, stone};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++)
            if (a[i] != 0)
                sb.append(sb.length() == 0 ? "" : ", ").append(RESOURCES[i]).append(' ').append(a[i]);
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    /** EconomyBuildings' Trade.apply, after the trade. */
    public static void trade(String verb, int giveGold, int giveShards, int giveWood, int giveStone,
                             int getGold, int getShards, int getWood, int getStone, AdventurePlayer player) {
        try {
            String gave = amounts(giveGold, giveShards, giveWood, giveStone);
            String got = amounts(getGold, getShards, getWood, getStone);
            int n = count("trade|" + verb + "|" + gave + "|" + got, getGold - giveGold, getShards - giveShards,
                    getWood - giveWood, getStone - giveStone);
            if (shouldLog(n))
                System.out.println("[TFR-Trade] " + verb + ": gave " + gave + ", got " + got + " (x" + n
                        + " in a row: " + totals() + "); " + purse(player));
        } catch (Exception e) {
            System.err.println("[TFR-Trade] not logged: " + e);
        }
    }

    /** EconomyBuildings' Bank buttons, after the move. {@code amount} is positive for a deposit. */
    public static void bank(String what, int amount, int balance, AdventurePlayer player) {
        try {
            int n = count("bank|" + what, -amount);
            if (shouldLog(n))
                System.out.println("[TFR-Bank] " + what + " " + Math.abs(amount) + ", balance " + balance
                        + " (x" + n + " in a row: purse " + totals() + "); " + purse(player));
        } catch (Exception e) {
            System.err.println("[TFR-Bank] not logged: " + e);
        }
    }

    private static String purse(AdventurePlayer player) {
        if (player == null)
            return "purse unknown";
        return "purse gold=" + player.getGold() + " shards=" + player.getShards() + " wood=" + player.getWood()
                + " stone=" + player.getStone();
    }
}
