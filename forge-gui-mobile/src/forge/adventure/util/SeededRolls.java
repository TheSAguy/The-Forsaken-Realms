package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.PlayerStatistic;
import forge.adventure.world.World;

import java.util.Arrays;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Round 972 (the Fable review's P2-12: results a reload could roll again). Seeds for the rolls rounds 870, 925 and 932
 * left unseeded: quest and dialog rewards, an Inn event's openings, the Temple's weekly offering, a realm boon offer. Every seed is built from
 * saved state only - the world seed, the site and the progress that tells two legitimate rolls apart - so a reload
 * reaches the same seed and the same result. No save field: nothing here stores a counter.
 */
public final class SeededRolls {
    /** Site salts, so two sites with the same parts never share a seed. */
    static final long SALT_REWARD = 0x5245574152444C4CL, SALT_TEMPLE = 0x54454D504C455F31L, SALT_BOON = 0x424F4F4E5F4F4646L;

    private SeededRolls() {}

    /** A seed from a base and parts, each part mixed in through round 870's SplitMix64 finalizer. A part is hashed by
     *  its string (String.hashCode is fixed by the Java spec, so the same text gives the same seed on every run). */
    public static long seedOf(long base, Object... parts) {
        long h = ResourceSpawns.mixSeed(base);
        for (Object part : parts)
            h = ResourceSpawns.mixSeed(h ^ (part instanceof Number n ? n.longValue() : String.valueOf(part).hashCode()));
        return h;
    }

    /** Runs a roll on {@code random} at {@code seed}; the stream resumes afterwards from a value drawn before, as
     *  ResourceSpawns.rollSeeded does for the world's Random. */
    public static <T> T withSeed(Random random, long seed, Supplier<T> roll) {
        long resume = random.nextLong();
        random.setSeed(seed);
        try {
            return roll.get();
        } finally {
            random.setSeed(resume);
        }
    }

    // ---- quest and dialog rewards -------------------------------------------------------------------------------

    /**
     * The seed of a dialog's reward grant: the world seed, where it is granted (the place's level and the speaker), what
     * it grants, and the player's progress - the day, the duels fought and the quests held. A reload from before the
     * talk reaches the same seed; the same dialog on a later day, after a duel, or with another quest done rolls anew.
     */
    public static long rewardSeed(long worldSeed, String place, int speaker, String what, int day, int duels, String quests) {
        return seedOf(worldSeed ^ SALT_REWARD, place, speaker, what, day, duels, quests);
    }

    /** What a grant gives, as text: the authored fields of each RewardData. Two grants that read alike roll alike. */
    public static String rewardKey(RewardData[] rewards) {
        StringBuilder sb = new StringBuilder();
        for (RewardData r : rewards) {
            if (r == null)
                continue;
            sb.append(r.type).append(':').append(r.count).append('+').append(r.addMaxCount).append(':')
                    .append(r.probability).append(':').append(r.cardName).append(Arrays.toString(r.cardNames))
                    .append(r.itemName).append(Arrays.toString(r.itemNames)).append(Arrays.toString(r.editions))
                    .append(Arrays.toString(r.colors)).append(Arrays.toString(r.rarity)).append(Arrays.toString(r.subTypes))
                    .append(Arrays.toString(r.cardTypes)).append(r.cardText).append(r.sourceDeck).append(';');
        }
        return sb.toString();
    }

    /** The player's quests as text (ids, in order), for the reward seed. */
    static String questsKey(AdventurePlayer player) {
        StringBuilder sb = new StringBuilder();
        if (player != null)
            for (forge.adventure.data.AdventureQuestData q : player.getQuests())
                sb.append(q.getID()).append(',');
        return sb.toString();
    }

    /**
     * MapDialog and MenuScene: a dialog's grantRewards / grantRewardsChoice, rolled from the world's Random at the
     * grant's seed instead of a fresh unseeded Random (stock's {@code generate(false, true)}). {@code site} names the
     * caller for the log line. Without a world (a menu before any game) it rolls as stock did.
     */
    public static Array<Reward> generateRewards(RewardData[] rewards, String site, String place, int speaker) {
        Array<Reward> ret = new Array<>();
        World world = Current.world();
        AdventurePlayer player = Current.player();
        if (world == null || player == null) {
            for (RewardData r : rewards)
                ret.addAll(r.generate(false, true));
            return ret;
        }
        PlayerStatistic stats = player.getStatistic();
        int duels = stats == null ? 0 : stats.totalWins() + stats.totalLoss();
        String what = rewardKey(rewards);
        long seed = rewardSeed(world.getSeed(), place, speaker, what, world.getCurrentDay(), duels, questsKey(player));
        ResourceSpawns.rollSeeded(world, seed, () -> {
            for (RewardData r : rewards)
                ret.addAll(r.generate(false, false));
        });
        System.out.println("[TFR-Seed] " + site + " reward at " + place + " #" + speaker + " (" + rewards.length
                + " entr" + (rewards.length == 1 ? "y" : "ies") + ", day " + world.getCurrentDay() + ", duels " + duels
                + "): seed " + seed + " -> " + describe(ret));
        return ret;
    }

    /** The rewards as a log reads them: "Shock, Opt, 250 Gold". */
    static String describe(Array<Reward> rewards) {
        StringBuilder sb = new StringBuilder();
        for (Reward r : rewards) {
            sb.append(sb.length() == 0 ? "" : ", ");
            if (r.getCard() != null)
                sb.append(r.getCard().getName());
            else if (r.getItem() != null)
                sb.append(r.getItem().name);
            else
                sb.append(r.getCount()).append(' ').append(r.getType());
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    // ---- Inn events -----------------------------------------------------------------------------------------------

    /** An Inn event's seed: the stored one, or for an event saved before round 972 (it stored 0) one built from what
     *  that save holds - the world seed, the Inn, the block, the week it was dealt in and the format. */
    public static long eventSeed(long stored, long worldSeed, String inn, String block, int week, Object format) {
        return stored != 0 ? stored : seedOf(worldSeed, inn, block, week, format);
    }

    /** One opening of an event (the draft's packs, the Sealed pool). The event seed is stock's real-world date plus the
     *  Inn, so the block and the in-game week are mixed in: two weeks' events at one Inn on one real day differ. */
    public static long openingSeed(long eventSeed, String what, String block, int week) {
        return seedOf(eventSeed, what, block, week);
    }

    // ---- the Temple and the realm boon --------------------------------------------------------------------------

    /** The Temple's offering for a closed week: the world seed and the week. */
    public static long templeSeed(long worldSeed, int week) {
        return seedOf(worldSeed ^ SALT_TEMPLE, week);
    }

    /** A realm boon offer: the world seed, the milestone it is for and the boons already taken. */
    public static long boonSeed(long worldSeed, int milestone, int boonsTaken) {
        return seedOf(worldSeed ^ SALT_BOON, milestone, boonsTaken);
    }
}
