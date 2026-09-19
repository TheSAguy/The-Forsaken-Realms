package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.adventure.data.EnemyData;
import forge.adventure.data.RewardData;
import forge.adventure.data.TuningData;
import forge.adventure.stage.GameHUD;
import forge.card.CardRarity;
import forge.item.PaperCard;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Round 237: the card budget - how MANY cards a defeated enemy pays, by rank and by whether this is the
 * player's first win against it.
 * <p>
 * User: "I want to cut down on the number of cards. I feel like currently you receive so many cards so fast and
 * that makes you 'hunt' for those special cards less exciting. Less cards also means more duels. I want enemy
 * rank to matter more. Defeating an enemy for the first time should count more than a repeat win."
 * <p>
 * Every enemy's hand-written reward list still decides WHAT can drop - the Angelic Page's Angel, a wizard's
 * color cards, the set-unlock restriction, the two-copy cap and the gold fallback all run exactly as before.
 * This class only runs afterwards, on the cards that list produced, and keeps the budgeted number:
 * <pre>
 *   rank         repeat win   first win
 *   Apprentice        1           2
 *   Adept             2           3
 *   Master            2           4
 *   Archmage          3           5
 * </pre>
 * <ul>
 * <li><b>Which cards are kept.</b> Master and Archmage keep their best rarities first; Apprentice and Adept
 *     keep random ones. A FIRST win keeps the best first at every rank. So a low rank is stingy with its
 *     special drops on a repeat win, and a high rank loses only its filler.</li>
 * <li><b>A target, not only a cap.</b> A list that yields fewer cards than the budget is topped up from the
 *     enemy's own deck (basic lands excluded, the color's set restriction applied). Those top-up cards are what
 *     makes some enemies pay MORE than they used to (Master Red Wizard 2 -> 4 on a first win), so the user set
 *     their quality: "weight the payout to Common and taper drastically down to Rare". Each one rolls its
 *     rarity on cardBudgetTopUpWeight* (80 / 17 / 3, never Mythic) and, when the deck has nothing legal at
 *     that rarity, steps DOWN to the commoner ones before it ever steps up. What still cannot be paid becomes
 *     deckCardFallbackGold per card, the rule round 203 already set for an unpayable deck card.</li>
 * <li><b>The first-win bonus card</b> (user, approving the table: "for first win, add +1 random Common or
 *     Uncommon from enemy deck on Normal, Hard and Insane and +1 random common or uncommon or rare for easy.
 *     All non-land"). It is on top of the budget, is never trimmed, and its rarity is NOT relaxed when the
 *     deck has nothing that fits - a deck of rares pays the gold fallback rather than a bonus rare.</li>
 * <li><b>Gear that adds reward cards</b> (Generous items, the victory medals; capped at +3 by
 *     AdventurePlayer.bonusDeckCards()) is left as it was (user: "leave Gear that adds reward cards as is"):
 *     it always added that many cards to a payout, so it raises the budget by that many on every win.</li>
 * <li><b>Easy</b> gets cardBudgetEasyBonus extra cards per win: the random extras that used to separate the
 *     difficulties (rewardMaxFactor) are trimmed away by the budget, so this is what keeps Easy generous.</li>
 * </ul>
 * NOT budgeted: gold, shards, items, life; the ante card; bosses, arena and event fighters and every other
 * spawnRate-0 enemy (SpawnTierWeighting.isExempt - their rewards are dedicated); fixed-deck enemies, which never
 * record a win and so would count as a first win forever; and the extra rewards a map places on one specific
 * enemy, which EnemySprite adds after this runs.
 * <p>
 * "First win" reads the saved win/loss record (PlayerStatistic). DuelScene.recordStatistics() runs BEFORE the
 * winner callback that builds the loot, so on a first win the record already says one.
 */
public final class CardBudget {
    private CardBudget() {}

    private static final String[] TIERS = {"Common", "Uncommon", "Rare", "Mythic"};
    private static final String[] RANKS = {"Apprentice", "Adept", "Master", "Archmage"};

    /**
     * @param standard          what the enemy type's own reward list just generated (cards and everything else)
     * @param enemyName         the statistics key - the raw enemy name DuelScene records wins under
     * @param data              the enemy's data
     * @param deckNoBasicLands  the enemy's deck without basic lands, or null when there is no deck
     * @param editionRestriction the color's unlocked editions for this loot, or null when unrestricted
     * @return the payout to hand over - the same array when the budget does not apply
     */
    public static Array<Reward> apply(Array<Reward> standard, String enemyName, EnemyData data,
                                      List<PaperCard> deckNoBasicLands, List<String> editionRestriction) {
        TuningData tuning = Config.instance().getTuningData();
        if (tuning == null || !tuning.cardBudgetEnabled || data == null || standard == null)
            return standard;
        if (SpawnTierWeighting.isExempt(data) || data.fixedDeck != null || data.copyPlayerDeck)
            return standard;
        int tier = tierIndex(data.tier);
        if (tier < 0) {
            System.out.println("[TFR-CardBudget] " + enemyName + " has no rank (tier=" + data.tier + ") - payout left as written");
            return standard;
        }

        Pair<Integer, Integer> record = Current.player().getStatistic().getWinLossRecord().get(enemyName);
        int wins = record == null ? 0 : record.getLeft();
        boolean firstWin = wins <= 1;
        boolean easy = "Easy".equalsIgnoreCase(Current.player().getDifficulty().name);

        int gearApplied = Math.max(0, Current.player().bonusDeckCards()); // in full, first win or repeat - "as is"
        int difficultyBonus = easy ? Math.max(0, tuning.cardBudgetEasyBonus) : 0;
        int budget = Math.max(0, base(tuning, tier, firstWin)) + gearApplied + difficultyBonus;

        List<Reward> cards = new ArrayList<>();
        Array<Reward> result = new Array<>();
        for (Reward reward : standard) {
            if (reward.getType() == Reward.Type.Card && reward.getCard() != null)
                cards.add(reward);
            else
                result.add(reward);
        }
        int candidates = cards.size();

        boolean bestFirst = firstWin || tier >= 2;
        Random random = new Random(); // loot is deliberately unseeded, like RewardData.generate()'s drops
        Collections.shuffle(cards, random);
        if (bestFirst)
            cards.sort((a, b) -> Integer.compare(rarityRank(b.getCard()), rarityRank(a.getCard()))); // stable: ties stay shuffled
        List<Reward> kept = new ArrayList<>(cards.subList(0, Math.min(budget, cards.size())));

        int unpayable = 0;
        int toppedUp = 0;
        int shortfall = budget - kept.size();
        List<String> topUpRarities = new ArrayList<>();
        if (shortfall > 0 && deckNoBasicLands != null) {
            for (int i = 0; i < shortfall; i++) {
                PaperCard card = drawTopUpCard(tuning, deckNoBasicLands, editionRestriction, random);
                if (card != null) {
                    kept.add(new Reward(card));
                    topUpRarities.add(String.valueOf(card.getRarity()));
                    toppedUp++;
                }
            }
            unpayable += shortfall - toppedUp;
        }

        List<String> bonusNames = new ArrayList<>();
        int bonusWanted = firstWin ? Math.max(0, tuning.cardBudgetFirstWinBonusCards) : 0;
        if (bonusWanted > 0 && deckNoBasicLands != null) {
            List<PaperCard> nonLand = new ArrayList<>();
            for (PaperCard card : deckNoBasicLands) {
                if (card != null && !card.getRules().getType().isLand())
                    nonLand.add(card);
            }
            String[] rarities = easy ? new String[]{"Common", "Uncommon", "Rare"} : new String[]{"Common", "Uncommon"};
            for (PaperCard card : CardUtil.generateCards(nonLand, deckEntry(rarities, editionRestriction), bonusWanted, random)) {
                if (card != null) {
                    kept.add(new Reward(card));
                    bonusNames.add(card.getName());
                }
            }
            unpayable += bonusWanted - bonusNames.size();
        }

        for (Reward reward : kept)
            result.add(reward);
        int goldPerCard = tuning.deckCardFallbackGold;
        if (unpayable > 0 && goldPerCard > 0)
            result.add(new Reward(unpayable * goldPerCard));

        System.out.println("[TFR-CardBudget] " + enemyName + " (" + RANKS[tier] + ", " + (firstWin ? "FIRST win" : "win #" + wins)
                + "): the list rolled " + candidates + " card(s), budget " + budget
                + " (" + base(tuning, tier, firstWin) + " base" + (gearApplied > 0 ? " +" + gearApplied + " gear" : "")
                + (difficultyBonus > 0 ? " +" + difficultyBonus + " Easy" : "") + ") -> kept "
                + Math.min(budget, candidates) + (bestFirst ? " best-first" : " at random")
                + (toppedUp > 0 ? ", topped up " + toppedUp + " from its deck " + topUpRarities : "")
                + (bonusWanted > 0 ? ", first-win bonus " + (bonusNames.isEmpty() ? "could not be paid" : bonusNames) : "")
                + (unpayable > 0 ? ", " + unpayable + " unpayable -> " + (unpayable * goldPerCard) + " gold" : ""));
        if (firstWin)
            GameHUD.getInstance().addNotification("First victory over " + enemyName + " - bonus loot!");
        return result;
    }

    private static int tierIndex(String tier) {
        for (int i = 0; i < TIERS.length; i++) {
            if (TIERS[i].equalsIgnoreCase(tier))
                return i;
        }
        return -1;
    }

    private static int base(TuningData tuning, int tier, boolean firstWin) {
        switch (tier) {
            case 0: return firstWin ? tuning.cardBudgetFirstCommon : tuning.cardBudgetRepeatCommon;
            case 1: return firstWin ? tuning.cardBudgetFirstUncommon : tuning.cardBudgetRepeatUncommon;
            case 2: return firstWin ? tuning.cardBudgetFirstRare : tuning.cardBudgetRepeatRare;
            default: return firstWin ? tuning.cardBudgetFirstMythic : tuning.cardBudgetRepeatMythic;
        }
    }

    /**
     * One top-up card: roll a rarity on the tuning weights (Common-heavy, never Mythic), then look for a legal
     * deck card of it. A deck with nothing at the rolled rarity steps DOWN to the commoner rarities first and
     * only then up (never past Rare), so a thin deck costs the player quality before it costs them the card.
     */
    private static PaperCard drawTopUpCard(TuningData tuning, List<PaperCard> deck, List<String> editionRestriction, Random random) {
        String[] ladder = {"Common", "Uncommon", "Rare"};
        int[] weights = {Math.max(0, tuning.cardBudgetTopUpWeightCommon), Math.max(0, tuning.cardBudgetTopUpWeightUncommon),
                Math.max(0, tuning.cardBudgetTopUpWeightRare)};
        int total = weights[0] + weights[1] + weights[2];
        int rolled = 0;
        if (total > 0) {
            int roll = random.nextInt(total);
            rolled = roll < weights[0] ? 0 : roll < weights[0] + weights[1] ? 1 : 2;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = rolled; i >= 0; i--)
            order.add(i);
        for (int i = rolled + 1; i < ladder.length; i++)
            order.add(i);
        for (int rarity : order) {
            for (PaperCard card : CardUtil.generateCards(deck, deckEntry(new String[]{ladder[rarity]}, editionRestriction), 1, random)) {
                if (card != null)
                    return card;
            }
        }
        return null;
    }

    /** Best first: mythic, rare (and the odd Special), uncommon, common, then anything else. */
    private static int rarityRank(PaperCard card) {
        CardRarity rarity = card == null ? null : card.getRarity();
        if (rarity == CardRarity.MythicRare)
            return 4;
        if (rarity == CardRarity.Rare || rarity == CardRarity.Special)
            return 3;
        if (rarity == CardRarity.Uncommon)
            return 2;
        if (rarity == CardRarity.Common)
            return 1;
        return 0;
    }

    /** A "deckCard"-shaped filter for CardUtil.generateCards(): optional rarities, the color's editions. */
    private static RewardData deckEntry(String[] rarities, List<String> editionRestriction) {
        RewardData entry = new RewardData();
        entry.type = "deckCard";
        entry.probability = 1f;
        entry.count = 1;
        entry.rarity = rarities;
        if (editionRestriction != null && !editionRestriction.isEmpty())
            entry.editions = editionRestriction.toArray(new String[0]);
        return entry;
    }
}
