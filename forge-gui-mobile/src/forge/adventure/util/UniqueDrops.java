package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.adventure.data.EnemyData;
import forge.adventure.data.RewardData;
import forge.adventure.data.TuningData;
import forge.adventure.player.AdventurePlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Round 781: items one player keeps only one of - the plane's settings.json {@code uniqueDropItems} (TFR: Witch-Maw
 * Slippers; empty on stock planes). The user: "I want to cut down on that." Once the player owns one - in the
 * inventory, worn, in the Armory storage or on a guard ({@link ItemSets#owns}) - an enemy's payout drops it rather than
 * paying a second. Nothing takes its place: the only entries naming one are single-item drops (a Mythic boss's roll,
 * Atraxa's fixed one). The list lives in tuning data, not on ItemData, because ItemData is saved with every inventory.
 * <p>
 * One {@code [TFR-UniqueDrop]} line per such entry an enemy carries, so a log shows every roll: dropped, missed, or
 * owned already.
 */
public final class UniqueDrops {
    private UniqueDrops() {}

    /** True when the plane's settings.json lists this item in uniqueDropItems. */
    public static boolean isUnique(String itemName) {
        TuningData tuning = Config.instance().getTuningData();
        if (tuning == null || tuning.uniqueDropItems == null || itemName == null)
            return false;
        for (String name : tuning.uniqueDropItems)
            if (itemName.equalsIgnoreCase(name))
                return true;
        return false;
    }

    /**
     * Called at the end of EnemySprite.getRewards(): for each uniqueDrop item named by the enemy's reward entries (its
     * type's and the map's own), says whether the roll paid it, and takes it back out when the player owns one already.
     */
    public static void apply(Array<Reward> rewards, String enemyName, EnemyData data, RewardData[] instanceRewards) {
        Map<String, Float> named = new LinkedHashMap<>();
        collect(named, data == null ? null : data.rewards);
        collect(named, instanceRewards);
        if (named.isEmpty())
            return;
        AdventurePlayer player = Current.player();
        for (Map.Entry<String, Float> entry : named.entrySet()) {
            String item = entry.getKey();
            float chance = entry.getValue();
            String odds = chance <= 0f || chance >= 1f ? "100%" : Math.round(chance * 100f) + "%";
            int paid = 0;
            for (Reward reward : new Array.ArrayIterator<>(rewards))
                if (isItem(reward, item))
                    paid++;
            if (paid == 0) {
                System.out.println("[TFR-UniqueDrop] " + enemyName + ": " + item + " (" + odds + ") missed");
                continue;
            }
            if (ItemSets.owns(player, item)) {
                for (int i = rewards.size - 1; i >= 0; i--)
                    if (isItem(rewards.get(i), item))
                        rewards.removeIndex(i);
                System.out.println("[TFR-UniqueDrop] " + enemyName + ": " + item + " (" + odds
                        + ") rolled, but the player owns one already - not dropped");
            } else {
                System.out.println("[TFR-UniqueDrop] " + enemyName + ": " + item + " (" + odds + ") dropped");
            }
        }
    }

    private static boolean isItem(Reward reward, String itemName) {
        return reward != null && reward.getType() == Reward.Type.Item && reward.getItem() != null
                && itemName.equalsIgnoreCase(reward.getItem().name);
    }

    /** The uniqueDrop items these single-item entries name, each with its chance (0 = always, as RewardData reads it).
     *  Pools (itemNames) are not read: round 781 took every unique item out of them. */
    private static void collect(Map<String, Float> named, RewardData[] entries) {
        if (entries == null)
            return;
        for (RewardData entry : entries) {
            if (entry == null || !"item".equalsIgnoreCase(entry.type) || entry.itemName == null || entry.itemName.isEmpty())
                continue;
            if (isUnique(entry.itemName))
                named.putIfAbsent(entry.itemName, entry.probability);
        }
    }
}
