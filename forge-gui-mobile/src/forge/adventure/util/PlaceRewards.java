package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.adventure.data.EnemyData;
import forge.adventure.data.RewardData;
import forge.adventure.data.TuningData;
import forge.adventure.data.WorldData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.TileMapScene;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.util.MyRandom;

/**
 * Round 299 - reward rules for places that can be fought through more than once. User, once cleared boss lairs were
 * to come back: "Any +Life should only be handed out once. Can't farm. All other rewards should be cut by 50%. That
 * goes for gold and number of cards." - and, asked about items, the recommendation they took: a boss's own signature
 * item once, every other item a coin flip.
 * <ul>
 * <li><b>Once per place, everywhere:</b> a +Life from one enemy (by name) or one pickup in one place is paid a single
 * time, whatever brings that enemy back - a lair's return, a restocked dungeon, a quest reset.</li>
 * <li><b>Once per lair:</b> a vanishing boss lair's boss pays its signature item - a fixed itemName on its own reward
 * list, not a pick from an itemNames list - a single time.</li>
 * <li><b>Return visits</b> to a lair cleared before (World.getLairClearCount()): gold, shards, wood and stone x
 * TuningData.lairReturnRewardFactor (rounded up), the NUMBER of cards and card packs x the same (the fraction a coin
 * flip, so a lone card drops half the time); every other item drops with TuningData.lairReturnItemChance.</li>
 * </ul>
 * Keys live in World.getOncePaidRewards(): "poiId|life|source" and "poiId|item|itemName", value = the day paid (-1 =
 * paid before this round, found gone from the map on a later visit - see noteEarlierDefeat()).
 */
public final class PlaceRewards {
    private PlaceRewards() {
    }

    private static PointOfInterest currentPlace() {
        TileMapScene scene = TileMapScene.instance();
        return scene == null ? null : scene.rootPoint;
    }

    /** MapStage.getReward(): the payout of a duel won inside a place, filtered in place before the reward screen. */
    public static void filterDuelPayout(Array<Reward> loot, EnemyData enemy) {
        PointOfInterest place = currentPlace();
        if (loot == null || enemy == null || place == null || place.getData() == null)
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        String id = place.getID();
        boolean lair = DungeonRotation.isVanishingLair(place.getData());
        StringBuilder notes = new StringBuilder();
        for (int i = loot.size - 1; i >= 0; i--) {
            Reward reward = loot.get(i);
            if (reward.getType() == Reward.Type.Life && reward.getCount() > 0) {
                if (!payOnce(world, id + "|life|" + enemy.getName(), "+" + reward.getCount() + " max life", notes))
                    loot.removeIndex(i);
            } else if (lair && reward.getType() == Reward.Type.Item && reward.getItem() != null
                    && isSignatureItem(enemy, reward.getItem().name)) {
                if (!payOnce(world, id + "|item|" + reward.getItem().name, reward.getItem().name, notes))
                    loot.removeIndex(i);
            }
        }
        int clears = lair ? world.getLairClearCount().getOrDefault(id, 0) : 0;
        if (clears > 0)
            applyReturnVisit(loot, lair ? enemy : null, notes);
        log(place, clears, enemy.getName(), notes);
    }

    /** RewardSprite.getRewards(): a pickup inside a place (chest, gold pile...), filtered once when it is first read. */
    public static void filterPickup(Array<Reward> rewards, int objectId) {
        PointOfInterest place = currentPlace();
        if (rewards == null || place == null || place.getData() == null)
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        String id = place.getID();
        StringBuilder notes = new StringBuilder();
        for (int i = rewards.size - 1; i >= 0; i--) {
            Reward reward = rewards.get(i);
            if (reward.getType() == Reward.Type.Life && reward.getCount() > 0
                    && !payOnce(world, id + "|life|pickup#" + objectId, "+" + reward.getCount() + " max life", notes))
                rewards.removeIndex(i);
        }
        int clears = DungeonRotation.isVanishingLair(place.getData()) ? world.getLairClearCount().getOrDefault(id, 0) : 0;
        if (clears > 0)
            applyReturnVisit(rewards, null, notes);
        log(place, clears, "a pickup", notes);
    }

    /**
     * MapStage's map load, for an enemy placement the save already lists as gone: the enemy was beaten before this
     * round's rules existed, so whatever once-per-place reward it carries was paid then. Marks those as paid and, for a
     * lair's boss, records the boss as down - that is what lets the user's already-emptied Slime Hive leave the map on
     * the next walk-out. Idempotent: only a newly marked key is logged.
     */
    public static void noteEarlierDefeat(Object enemyName) {
        PointOfInterest place = currentPlace();
        if (enemyName == null || place == null || place.getData() == null)
            return;
        EnemyData enemy = WorldData.getEnemy(enemyName.toString());
        if (enemy == null)
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        String id = place.getID();
        boolean lair = DungeonRotation.isVanishingLair(place.getData());
        if (enemy.rewards != null) {
            for (RewardData data : enemy.rewards) {
                if (data == null || data.type == null)
                    continue;
                if ("life".equalsIgnoreCase(data.type) && data.count > 0)
                    markPaidEarlier(world, place, id + "|life|" + enemy.getName(), "+" + data.count + " max life from " + enemy.getName());
                else if (lair && enemy.boss && "item".equalsIgnoreCase(data.type) && data.itemName != null && !data.itemName.isEmpty())
                    markPaidEarlier(world, place, id + "|item|" + data.itemName, data.itemName);
            }
        }
        if (lair && enemy.boss)
            DungeonRotation.onLairBossDefeated(place, enemy.getName(), true);
    }

    /** A boss's own item: a fixed itemName on its reward list. A pick from an itemNames list is ordinary loot. */
    static boolean isSignatureItem(EnemyData enemy, String itemName) {
        if (enemy == null || !enemy.boss || enemy.rewards == null || itemName == null)
            return false;
        for (RewardData data : enemy.rewards)
            if (data != null && "item".equalsIgnoreCase(data.type) && itemName.equals(data.itemName))
                return true;
        return false;
    }

    private static boolean payOnce(World world, String key, String label, StringBuilder notes) {
        Integer paidDay = world.getOncePaidRewards().get(key);
        if (paidDay != null) {
            note(notes, label + " withheld (paid " + (paidDay >= 0 ? "on day " + paidDay : "on an earlier visit") + ")");
            return false;
        }
        world.getOncePaidRewards().put(key, world.getCurrentDay());
        note(notes, label + " paid (once per place)");
        return true;
    }

    private static void markPaidEarlier(World world, PointOfInterest place, String key, String label) {
        if (world.getOncePaidRewards().containsKey(key))
            return;
        world.getOncePaidRewards().put(key, -1);
        System.out.println("[TFR-PlaceRewards] " + place.getDisplayName() + ": " + label
                + " was paid on an earlier visit - recorded, never paid again here");
    }

    /**
     * The return-visit cut. Signature items are left to the once-per-lair rule above (they are always either withheld
     * already or paid for the first time); every other item takes the coin flip.
     */
    private static void applyReturnVisit(Array<Reward> rewards, EnemyData boss, StringBuilder notes) {
        TuningData tuning = Config.instance().getTuningData();
        float factor = clamp01(tuning.lairReturnRewardFactor, 0.5f);
        float itemChance = clamp01(tuning.lairReturnItemChance, 0.5f);
        int cards = 0, packs = 0;
        for (Reward reward : rewards) {
            if (reward.getType() == Reward.Type.Card)
                cards++;
            else if (reward.getType() == Reward.Type.CardPack)
                packs++;
        }
        int keepCards = keepCount(cards, factor), keepPacks = keepCount(packs, factor);
        int seenCards = 0, seenPacks = 0;
        for (int i = 0; i < rewards.size; i++) {
            Reward reward = rewards.get(i);
            switch (reward.getType()) {
                case Gold:
                case Shards:
                case Stone:
                case Wood: {
                    int after = keep(reward.getCount(), factor);
                    if (after != reward.getCount()) {
                        note(notes, reward.getType().name().toLowerCase() + " " + reward.getCount() + "->" + after);
                        rewards.set(i, new Reward(reward.getType(), after));
                    }
                    break;
                }
                case Card:
                    if (++seenCards > keepCards)
                        rewards.removeIndex(i--);
                    break;
                case CardPack:
                    if (++seenPacks > keepPacks)
                        rewards.removeIndex(i--);
                    break;
                case Item:
                    if (reward.getItem() != null && !isSignatureItem(boss, reward.getItem().name)
                            && MyRandom.getRandom().nextFloat() >= itemChance) {
                        note(notes, "item " + reward.getItem().name + " did not drop (return-visit coin flip)");
                        rewards.removeIndex(i--);
                    }
                    break;
                default:
                    break;
            }
        }
        if (keepCards != cards)
            note(notes, "cards " + cards + "->" + keepCards);
        if (keepPacks != packs)
            note(notes, "packs " + packs + "->" + keepPacks);
    }

    /** An amount (gold, shards, wood, stone) x factor, rounded up - never below 1 for anything that was there. */
    private static int keep(int n, float factor) {
        if (n <= 0)
            return n;
        return Math.max(1, (int) Math.ceil(n * factor));
    }

    /**
     * A COUNT of cards or packs x factor: the whole part is kept and the fraction is a coin flip, so 8 cards give
     * exactly 4 and a lone card drops half the time. Rounding up, the first version, let the 63 single-card entries of
     * the lairs' 189 card pickups through uncut - the agent test's log showed it.
     */
    private static int keepCount(int n, float factor) {
        if (n <= 0)
            return n;
        float exact = n * factor;
        int whole = (int) Math.floor(exact);
        return whole + (MyRandom.getRandom().nextFloat() < exact - whole ? 1 : 0);
    }

    private static float clamp01(float value, float fallback) {
        return value >= 0f && value <= 1f ? value : fallback;
    }

    private static void note(StringBuilder notes, String text) {
        notes.append(notes.length() == 0 ? "" : "; ").append(text);
    }

    private static void log(PointOfInterest place, int clears, String source, StringBuilder notes) {
        if (notes.length() == 0)
            return;
        System.out.println("[TFR-PlaceRewards] " + place.getDisplayName()
                + (clears > 0 ? " (return visit after clear #" + clears + ")" : "") + ", " + source + ": " + notes);
    }
}
