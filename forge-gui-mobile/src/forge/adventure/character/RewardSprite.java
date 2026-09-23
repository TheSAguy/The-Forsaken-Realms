package forge.adventure.character;

import com.badlogic.gdx.utils.Array;
import forge.adventure.data.RewardData;
import forge.adventure.util.EditionProgression;
import forge.adventure.util.JSONStringLoader;
import forge.adventure.util.Reward;

import java.util.Arrays;
import java.util.HashMap;

/**
 * RewardSprite
 * Character sprite that represents reward pickups.
 */

public class RewardSprite extends CharacterSprite {
    private final static String default_reward = "[\n" +
            "\t\t{\n" +
            "\t\t\t\"type\": \"gold\",\n" +
            "\t\t\t\"count\": 10,\n" +
            "\t\t\t\"addMaxCount\": 100,\n" +
            "\t\t}\n" +
            "\t]";

    private static final HashMap<String, RewardData[]> rewardJsonMap = new HashMap<>(256);
    private final Array<Reward> rewardCollection = new Array<>(8);
    private boolean isMapPopulated = false;

    private int id;
    private RewardData[] rewards = null;

    public RewardSprite(String data, String _sprite){
        super(_sprite);

        final String cacheKey = (data != null) ? data : default_reward;

        RewardData[] cachedData = rewardJsonMap.get(cacheKey);
        if (cachedData == null) {
            if (data != null) {
                cachedData = JSONStringLoader.parse(RewardData[].class, data, default_reward);
            } else { // Shouldn't happen, but make sure it doesn't fly by.
                System.err.print("Reward data is null. Using a default reward.");
                cachedData = JSONStringLoader.parse(RewardData[].class, default_reward, default_reward);
            }
            rewardJsonMap.put(cacheKey, cachedData);
        }

        this.rewards = cachedData;
    }

    public RewardSprite(int _id, String data, String _sprite){
        this(data, _sprite);
        this.id = _id; // The ID is for remembering removals.
    }

    @Override
    void updateBoundingRect() { // We want rewards to take a full tile.
        boundingRect.set(getX(), getY(), getWidth(), getHeight());
    }

    // act() -> onActing() MapStage call
    public Array<Reward> getRewards() {
        // Only assemble the reward data array objects once on the initial request pass
        if (!isMapPopulated) {
            isMapPopulated = true;
            rewardCollection.clear();

            if (rewards != null) {
                // Edition-progression restriction (2026-08-13 QC pass) - dungeon treasure/chest pickups
                // previously drew from every edition regardless of whose territory they're in, unlike
                // roaming-monster loot and AI-town shops. See
                // EditionProgression.restrictDungeonRewardsForCurrentPoi()'s own comment for why.
                //
                // Round 289 keeps the restriction INSIDE upstream's one-time fill. It reads the current point
                // of interest, which cannot change while a map is open, so asking once per sprite returns what
                // the old per-call version returned - and the generate() roll is now fixed at the first
                // request instead of re-rolled, which is the point of upstream's cache.
                for (RewardData rdata : EditionProgression.restrictDungeonRewardsForCurrentPoi(Arrays.asList(rewards))) {
                    if (rdata != null) {
                        rewardCollection.addAll(rdata.generate(false, true));
                    }
                }
                // Round 299: +Life once per place, half on a boss lair's return visits - inside the one-time fill, so
                // a pickup is judged exactly once.
                forge.adventure.util.PlaceRewards.filterPickup(rewardCollection, id);
            }
        }
        return rewardCollection;
    }

    public int getId() {
        return id;
    }
}
