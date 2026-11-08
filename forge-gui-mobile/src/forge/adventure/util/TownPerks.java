package forge.adventure.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Round 987: the plain arithmetic behind the two town perks (Tournament Regular, Guild Favorite), kept free of the game's
 * state so TownPerksTest can check it - as LootPerks does for round 986's loot perks. The perks are read through
 * Ascendance; AdventureEventData, EventScene, the quest epilogues and QuestExpiry call these with the numbers.
 */
public final class TownPerks {
    private TownPerks() {
    }

    /**
     * An Inn entry fee as shown and as charged: the unscaled fee x the town's price modifier x the perk's factor (1, 0.75,
     * 0.5), rounded half up once - the same rounding the stock reads used, so without the perk every fee is unchanged.
     */
    public static int innFee(int base, float townModifier, float factor) {
        if (base <= 0)
            return Math.max(0, base);
        return Math.round(base * townModifier * Math.max(0f, Math.min(1f, factor)));
    }

    /** "A bracket won": the champion's reward tier - 3 match wins and no loss (not an event abandoned after one win). */
    public static boolean isChampion(int matchesWon, int matchesLost) {
        return matchesLost == 0 && matchesWon >= 3;
    }

    /**
     * The champion card's rarity: a mythic when {@code roll} (0..1) falls under {@code mythicChance} and the set prints a
     * mythic at all; otherwise a rare. A set with no rares but mythics gives a mythic.
     */
    public static boolean championMythic(float roll, float mythicChance, boolean setHasMythic, boolean setHasRare) {
        if (!setHasMythic)
            return false;
        if (!setHasRare)
            return true;
        return roll < mythicChance;
    }

    /** A side quest's gold or shards with Guild Favorite: x (1 + purse), rounded half up (500 -> 750, 5 -> 8). */
    public static int questAmount(int amount, float purse) {
        if (amount <= 0 || purse <= 0f)
            return amount;
        return Math.round(amount * (1f + purse));
    }

    /** A card reward entry's count with Guild Favorite: + {@code extra} on an entry that pays cards at all. */
    public static int questCards(int count, int extra) {
        return count > 0 ? count + Math.max(0, extra) : count;
    }

    /** A new side quest's limit in days: the difficulty's limit + the perk's days. */
    public static int questDays(int baseDays, int extraDays) {
        return baseDays + Math.max(0, extraDays);
    }

    private static final Pattern GOLD_OR_SHARDS = Pattern.compile("\\+(\\d+) (\\[\\+(?:Gold|Shards)\\])");

    /**
     * A quest dialog's own reward line ("(+3 Local [+Reputation], +500 [+Gold])") rewritten to what Guild Favorite pays:
     * every "+N [+Gold]" and "+N [+Shards]" through {@link #questAmount}. Reputation and everything else stay.
     */
    public static String scaleRewardText(String text, float purse) {
        if (text == null || purse <= 0f || text.indexOf('+') < 0)
            return text;
        Matcher m = GOLD_OR_SHARDS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find())
            m.appendReplacement(out, Matcher.quoteReplacement("+" + questAmount(Integer.parseInt(m.group(1)), purse) + " " + m.group(2)));
        m.appendTail(out);
        return out.toString();
    }
}
