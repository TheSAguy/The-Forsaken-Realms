package forge.adventure.util;

import forge.adventure.data.AdventureEventData.AdventureEventReward;
import forge.deck.Deck;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TFR round 996 (notes/logs-1109b.md P2-2): an Inn event's prize state, checked and repaired before it is read.
 * <p>
 * A player's saved Inn Draft came back with no reward packs (the Inn's description threw an NPE) and, in the next launch,
 * with a three-slot prize table (collecting the champion's prize threw an ArrayIndexOutOfBoundsException writing slot 3).
 * No constructor builds either shape: every one builds four slots and, for Draft and Sealed, three reward packs. The
 * repair is done in AdventureEventData.repairRewardState; this class holds its pieces with no game state, so the unit
 * test (InnEventRepairTest) runs them.
 */
public final class InnEventRepair {
    /** The prize table's slots: tiers 0-2 (0 wins, 1+ wins, 2+ wins) and slot 3, the champion's, which giveRewards writes. */
    public static final int SLOTS = 4;
    /** Draft and Sealed: the 0-wins pack and the two 1+-wins packs. */
    public static final int PACKS = 3;
    /** The tiers a constructor fills; slot 3 is written by giveRewards. */
    public static final int TIERS = 3;

    private InnEventRepair() {
    }

    /** True when all three reward packs are there. */
    public static boolean packsWhole(Deck[] packs) {
        if (packs == null || packs.length < PACKS)
            return false;
        for (int i = 0; i < PACKS; i++)
            if (packs[i] == null)
                return false;
        return true;
    }

    /**
     * What is broken in a prize state, for the log line ("rewards.length=3, rewardPacks=null"), or null when it is whole.
     * {@code usesPacks}: Draft and Sealed read the packs; Jumpstart has none by design.
     */
    public static String brokenShape(AdventureEventReward[] rewards, Deck[] packs, boolean usesPacks) {
        List<String> broken = new ArrayList<>();
        if (rewards == null)
            broken.add("rewards=null");
        else {
            if (rewards.length < SLOTS)
                broken.add("rewards.length=" + rewards.length);
            for (int i = 0; i < Math.min(TIERS, rewards.length); i++)
                if (rewards[i] == null)
                    broken.add("rewards[" + i + "]=null");
        }
        if (usesPacks && !packsWhole(packs))
            broken.add(packs == null ? "rewardPacks=null" : "rewardPacks=" + Arrays.toString(labels(packs)));
        return broken.isEmpty() ? null : String.join(", ", broken);
    }

    /** The prize table grown to {@link #SLOTS}, its entries kept (a new, empty table for null). The same array when long enough. */
    public static AdventureEventReward[] grown(AdventureEventReward[] rewards) {
        if (rewards == null)
            return new AdventureEventReward[SLOTS];
        return rewards.length >= SLOTS ? rewards : Arrays.copyOf(rewards, SLOTS);
    }

    /**
     * The three reward packs read back from the prize table, where the constructors put the same Deck objects: tier 0's
     * pack, then tier 1's two. Null when the table does not hold them.
     */
    public static Deck[] packsFromTiers(AdventureEventReward[] rewards) {
        if (rewards == null || rewards.length < 2 || rewards[0] == null || rewards[1] == null)
            return null;
        Deck[] zero = rewards[0].cardRewards, one = rewards[1].cardRewards;
        if (zero == null || zero.length < 1 || one == null || one.length < 2)
            return null;
        Deck[] packs = {zero[0], one[0], one[1]};
        return packsWhole(packs) ? packs : null;
    }

    /**
     * The name of reward pack {@code i} in the description ("%s Booster"): the pack's comment, its set code, as stock
     * writes it; for a missing pack the event's own set or block ({@code fallback}), else "Random".
     */
    public static String packLabel(Deck[] packs, int i, String fallback) {
        if (packs != null && i < packs.length && packs[i] != null && packs[i].getComment() != null)
            return packs[i].getComment();
        return fallback == null || fallback.isEmpty() ? "Random" : fallback;
    }

    /**
     * A Draft or Sealed tier as setupDraftRewards / setupSealedRewards build it: 0 wins pays pack 0, 1-3 wins packs 1 and
     * 2, 2-3 wins the format's coin. For a lost tier only; a missing pack is left out of the tier.
     */
    public static AdventureEventReward packTier(int tier, Deck[] packs, String coin) {
        AdventureEventReward r = new AdventureEventReward();
        switch (tier) {
            case 0:
                r.minWins = 0;
                r.maxWins = 0;
                r.cardRewards = present(packs, 0);
                break;
            case 1:
                r.minWins = 1;
                r.maxWins = 3;
                r.cardRewards = present(packs, 1, 2);
                break;
            default:
                r.minWins = 2;
                r.maxWins = 3;
                r.itemRewards = new String[]{coin};
                break;
        }
        return r;
    }

    /** The tiers that pay at {@code wins}, in table order; a missing tier is skipped. */
    public static List<AdventureEventReward> payingTiers(AdventureEventReward[] rewards, int wins) {
        List<AdventureEventReward> paying = new ArrayList<>();
        if (rewards == null)
            return paying;
        for (AdventureEventReward r : rewards)
            if (r != null && r.minWins <= wins && r.maxWins >= wins)
                paying.add(r);
        return paying;
    }

    private static Deck[] present(Deck[] packs, int... indexes) {
        List<Deck> out = new ArrayList<>();
        for (int i : indexes)
            if (packs != null && i < packs.length && packs[i] != null)
                out.add(packs[i]);
        return out.toArray(new Deck[0]);
    }

    private static String[] labels(Deck[] packs) {
        String[] out = new String[packs.length];
        for (int i = 0; i < packs.length; i++)
            out[i] = packs[i] == null ? "null" : packs[i].getComment();
        return out;
    }
}
