package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.adventure.data.EnemyData;
import forge.adventure.data.RewardData;
import forge.adventure.data.TuningData;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Locale;
import java.util.Random;

/**
 * Round 241: the resource purse - what a defeated enemy pays in gold, shards, wood and stone, sized by its rank
 * and mixed by its color. The companion of round 237's {@link CardBudget}, which did the same for cards.
 * <p>
 * User: "We reworked the card drop counts/system, but did not touch the resources you get from drops ... I'm
 * thinking I want Green to drop more wood, Red more Stone, White more gold, Blue more Shards, black be balanced.
 * Neutral will also be balanced, but just less than black." and, approving the proposal: "add the Base Purse
 * size per rank as a variable to the config/settings file."
 * <p>
 * What it replaces: 1,059 of the 1,070 roaming enemies carried a hand-written gold entry (median 20) and 406 a
 * shards entry; none listed wood or stone - those came only from a side rule in EnemySprite (a gold reward had a
 * 25% chance to become wood or stone at HALF the gold amount, which at the Exchange's 16-20 gold per unit was
 * worth about eight times the gold it replaced, so two thirds of a duel's value came from that one roll). Color
 * made no difference at all.
 * <p>
 * The purse, in gold-equivalent value:
 * <pre>
 *   rank         purse      x difficulty   Easy 1.5 / Normal 1.25 / Hard 1 / Insane 0.8
 *   Apprentice     60       x 1.5 on the first win over this enemy (the card budget's rule)
 *   Adept          90       x 0.7 for a colorless enemy ("balanced, but just less than black")
 *   Master        130       x a little luck (resourcePurseVariance, +-20%)
 *   Archmage      160
 * </pre>
 * resourcePurseGoldShare (35%) of it is always paid as gold. The rest is paid as ONE bonus resource, rolled on
 * the enemy's color - the favored resource at resourcePurseFavoredWeight (55), the other three at
 * resourcePurseOtherWeight (15) each:
 * <pre>
 *   White -> gold     Blue -> shards     Red -> stone     Green -> wood
 *   Black and colorless -> balanced (the average of the four rows above: 25 each)
 *   two or more colors  -> the average of those colors' rows (Green-Red leans to wood AND stone)
 * </pre>
 * A unit of wood, stone or shards is worth resourcePurseUnitValue (16) gold, the Exchange's selling price, so
 * the purse is worth the same whichever way the roll goes. A Green Adept on Hard pays about 32 gold and then
 * one of about 4 wood (55% of the time), 4 stone, 4 shards, or 58 more gold.
 * <p>
 * It REPLACES the enemy type's own gold / shards / wood / stone entries - EnemySprite.getRewards() skips them
 * before they roll (so a deck card's gold fallback, which is compensation for a card, is never mistaken for
 * one). Everything else in the list is untouched: cards, items, life.
 * <p>
 * NOT covered, exactly as for the card budget: bosses, arena and event fighters and every other spawnRate-0
 * enemy (SpawnTierWeighting.isExempt - their rewards are dedicated), fixed-deck and deck-copying enemies,
 * enemies with no rank, the extra rewards a map places on one specific enemy, chests, and quest rewards. With
 * the purse on, the old 25% gold swap is off for every payout, those included.
 */
public final class ResourcePurse {
    private ResourcePurse() {}

    private static final String[] TIERS = {"Common", "Uncommon", "Rare", "Mythic"};
    private static final String[] RANKS = {"Apprentice", "Adept", "Master", "Archmage"};
    private static final int GOLD = 0, SHARDS = 1, WOOD = 2, STONE = 3;
    private static final String[] RESOURCES = {"gold", "shards", "wood", "stone"};
    private static final Reward.Type[] TYPES = {Reward.Type.Gold, Reward.Type.Shards, Reward.Type.Wood, Reward.Type.Stone};

    /** Is the purse switched on for this plane? While it is, EnemySprite's old gold swap does not run. */
    public static boolean isEnabled() {
        TuningData tuning = Config.instance().getTuningData();
        return tuning != null && tuning.resourcePurseEnabled;
    }

    /** Does the purse pay this enemy's resources - the card budget's own exemptions, plus "has a rank"? */
    public static boolean appliesTo(EnemyData data) {
        if (!isEnabled() || data == null)
            return false;
        if (SpawnTierWeighting.isExempt(data) || data.fixedDeck != null || data.copyPlayerDeck)
            return false;
        return tierIndex(data.tier) >= 0;
    }

    /** Is this an entry of an enemy's reward list that the purse pays instead? */
    public static boolean isResourceEntry(RewardData entry) {
        if (entry == null || entry.type == null)
            return false;
        switch (entry.type.toLowerCase(Locale.ROOT)) {
            case "gold":
            case "shards":
            case "mana": // the stock spelling RewardData still reads as shards
            case "wood":
            case "stone":
                return true;
            default:
                return false;
        }
    }

    /**
     * @param enemyName       the statistics key - the raw enemy name DuelScene records wins under
     * @param data            the enemy's data; appliesTo(data) must already be true
     * @param replacedEntries how many gold/shards/wood/stone entries of its own list were skipped (for the log)
     * @return one gold reward and, unless the bonus roll also came up gold, one resource reward
     */
    public static Array<Reward> generate(String enemyName, EnemyData data, int replacedEntries) {
        TuningData tuning = Config.instance().getTuningData();
        if (tuning == null || data == null)
            return new Array<>();
        Pair<Integer, Integer> record = Current.player().getStatistic().getWinLossRecord().get(enemyName);
        int wins = record == null ? 0 : record.getLeft(); // DuelScene.recordStatistics() has already counted this win - see CardBudget
        // Loot is deliberately unseeded, like RewardData.generate()'s drops.
        return pay(enemyName, data, wins, Current.player().getDifficulty().name, tuning, new Random(), replacedEntries);
    }

    /** The purse itself, free of any game state - generate() supplies the win count and the difficulty. */
    static Array<Reward> pay(String enemyName, EnemyData data, int wins, String difficulty, TuningData tuning,
                             Random random, int replacedEntries) {
        Array<Reward> result = new Array<>();
        int tier = tierIndex(data.tier);
        if (tier < 0)
            return result;
        boolean firstWin = wins <= 1;

        float[] weights = new float[4];
        boolean colorless = colorWeights(data.colors, tuning, weights);

        int base = base(tuning, tier);
        float difficultyFactor = tuning.resourcePurseFactorFor(difficulty);
        float colorFactor = colorless ? Math.max(0f, tuning.resourcePurseColorlessFactor) : 1f;
        float firstWinFactor = firstWin ? Math.max(0f, tuning.resourcePurseFirstWinFactor) : 1f;
        float spread = Math.max(0f, Math.min(0.9f, tuning.resourcePurseVariance));
        float luck = 1f + (random.nextFloat() * 2f - 1f) * spread;
        int purse = Math.max(0, Math.round(base * difficultyFactor * colorFactor * firstWinFactor * luck));
        if (purse <= 0) {
            System.out.println("[TFR-ResourcePurse] " + enemyName + " (" + RANKS[tier] + "): the purse works out to nothing"
                    + " (base " + base + ", difficulty x" + fmt(difficultyFactor) + ") - no resources paid");
            return result;
        }

        float goldShare = Math.max(0f, Math.min(1f, tuning.resourcePurseGoldShare));
        int gold = Math.round(purse * goldShare);
        int bonus = purse - gold;

        int rolled = roll(weights, random);
        int unitValue = Math.max(1, tuning.resourcePurseUnitValue);
        int units = 0;
        if (rolled == GOLD || bonus <= 0)
            gold += bonus;
        else
            units = Math.max(1, Math.round(bonus / (float) unitValue));

        if (gold > 0)
            result.add(new Reward(gold));
        if (units > 0)
            result.add(new Reward(TYPES[rolled], units));

        System.out.println("[TFR-ResourcePurse] " + enemyName + " (" + RANKS[tier] + ", colors "
                + (colorless ? "none" : data.colors) + ", " + (firstWin ? "FIRST win" : "win #" + wins) + ", " + difficulty
                + "): purse " + purse + " = " + base + " base x" + fmt(difficultyFactor) + " difficulty"
                + (colorless ? " x" + fmt(colorFactor) + " colorless" : "")
                + (firstWin ? " x" + fmt(firstWinFactor) + " first win" : "") + " x" + fmt(luck) + " luck -> "
                + gold + " gold" + (units > 0 ? " + " + units + " " + RESOURCES[rolled] : " (the bonus roll came up gold too)")
                + "; bonus " + bonus + " rolled on gold " + fmt(weights[GOLD]) + " / shards " + fmt(weights[SHARDS])
                + " / wood " + fmt(weights[WOOD]) + " / stone " + fmt(weights[STONE])
                + "; replaces " + replacedEntries + " resource entr" + (replacedEntries == 1 ? "y" : "ies") + " of its own list");
        return result;
    }

    /**
     * The bonus-resource weights for an enemy's color string ("G", "RG", "UBRWG", "" ...), written into
     * {@code out} in gold / shards / wood / stone order.
     * @return true when the enemy has no color at all (balanced weights, and the smaller purse)
     */
    static boolean colorWeights(String colors, TuningData tuning, float[] out) {
        float favored = Math.max(0f, tuning.resourcePurseFavoredWeight);
        float other = Math.max(0f, tuning.resourcePurseOtherWeight);
        float balanced = (favored + 3f * other) / 4f; // Black and colorless: the average of the four leaning rows
        boolean[] seen = new boolean[5];
        int count = 0;
        float[] sum = new float[4];
        String letters = colors == null ? "" : colors.toUpperCase(Locale.ROOT);
        for (int i = 0; i < letters.length(); i++) {
            int color = "WUBRG".indexOf(letters.charAt(i));
            if (color < 0 || seen[color])
                continue;
            seen[color] = true;
            count++;
            int favors = color == 0 ? GOLD : color == 1 ? SHARDS : color == 3 ? STONE : color == 4 ? WOOD : -1; // Black: none
            for (int r = 0; r < 4; r++)
                sum[r] += favors < 0 ? balanced : r == favors ? favored : other;
        }
        for (int r = 0; r < 4; r++)
            out[r] = count == 0 ? balanced : sum[r] / count;
        return count == 0;
    }

    private static int roll(float[] weights, Random random) {
        float total = 0f;
        for (float w : weights)
            total += w;
        if (total <= 0f)
            return GOLD;
        float at = random.nextFloat() * total;
        for (int r = 0; r < weights.length; r++) {
            at -= weights[r];
            if (at < 0f)
                return r;
        }
        return weights.length - 1;
    }

    private static int base(TuningData tuning, int tier) {
        switch (tier) {
            case 0: return Math.max(0, tuning.resourcePurseCommon);
            case 1: return Math.max(0, tuning.resourcePurseUncommon);
            case 2: return Math.max(0, tuning.resourcePurseRare);
            default: return Math.max(0, tuning.resourcePurseMythic);
        }
    }

    private static int tierIndex(String tier) {
        for (int i = 0; i < TIERS.length; i++) {
            if (TIERS[i].equalsIgnoreCase(tier))
                return i;
        }
        return -1;
    }

    private static String fmt(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
