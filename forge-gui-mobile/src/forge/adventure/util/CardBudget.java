package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.adventure.data.EnemyData;
import forge.adventure.data.RewardData;
import forge.adventure.data.TuningData;
import forge.adventure.stage.GameHUD;
import forge.card.CardRarity;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.item.PaperCard;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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
 * <li><b>Variety</b> (round 419). A payout prefers a card name it has not paid yet. A second copy the list rolled waits.
 *     When the deck has nothing new, the slot is filled from the color's own sets in the deck's colors (non-land, the same
 *     rarity roll and set restriction). Only then does a second copy get paid, and only then gold. The first-win
 *     bonus works the same way.</li>
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
 * NOT budgeted: gold (except the list's own gold for deck cards it could not pay, which is dropped - round 406),
 * shards, items, life; the ante card; bosses, arena and event fighters and every other
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
        return run(standard, enemyName, data, deckNoBasicLands, editionRestriction, 0);
    }

    /**
     * Round 413 (the user, of a cave spider: "The rewards seem a little extreme"; they chose "double their rank's
     * budget", for the war champions too). A cave champion or a war champion met outside the arena pays its arena list
     * through the budget: TuningData.championLootFactor x its rank's FIRST-win count, best rarities first, no extra
     * first-win bonus card (the factor covers it), and gear / Easy bonuses as usual. The caller keeps its signature card
     * apart and adds it on top. Skrelv (Apprentice) paid 7 cards with 4 rares at the very least; it pays 4 + Skrelv now.
     * A factor of 0 leaves the arena list whole.
     */
    public static Array<Reward> applyChampion(Array<Reward> standard, String enemyName, EnemyData data,
                                              List<PaperCard> deckNoBasicLands, List<String> editionRestriction) {
        TuningData tuning = Config.instance().getTuningData();
        int factor = tuning == null ? 0 : tuning.championLootFactor;
        if (factor <= 0)
            return standard;
        return run(standard, enemyName, data, deckNoBasicLands, editionRestriction, factor);
    }

    private static Array<Reward> run(Array<Reward> standard, String enemyName, EnemyData data,
                                     List<PaperCard> deckNoBasicLands, List<String> editionRestriction, int championFactor) {
        TuningData tuning = Config.instance().getTuningData();
        boolean champion = championFactor > 0;
        if (tuning == null || !tuning.cardBudgetEnabled || data == null || standard == null)
            return standard;
        if ((!champion && SpawnTierWeighting.isExempt(data)) || data.fixedDeck != null || data.copyPlayerDeck)
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
        int rankBase = champion ? championFactor * Math.max(0, base(tuning, tier, true)) : Math.max(0, base(tuning, tier, firstWin));
        int budget = rankBase + gearApplied + difficultyBonus
                + (firstWin && !champion ? Ascendance.firstWinCardBonus() : 0); // round 494: Spoilsman

        List<Reward> cards = new ArrayList<>();
        Array<Reward> result = new Array<>();
        // Round 406: the list's own gold for deck cards it could not pay is dropped. Those cards were candidates like
        // any other - the budget would have trimmed them on a list that already fills it - and a real shortfall is
        // topped up below, with this method's own gold for whatever the deck still cannot pay. Kor Duelist (Insane,
        // a first win) paid 128 gold for a 28-gold purse: 50 for the bonus card, 50 for a list card past the budget.
        int droppedFallbackGold = 0;
        for (Reward reward : standard) {
            if (reward.getType() == Reward.Type.Card && reward.getCard() != null)
                cards.add(reward);
            else if (reward.getType() == Reward.Type.Gold && reward.isCardFallbackGold())
                droppedFallbackGold += reward.getCount();
            else
                result.add(reward);
        }
        int candidates = cards.size();

        boolean bestFirst = champion || firstWin || tier >= 2;
        Random random = new Random(); // loot is deliberately unseeded, like RewardData.generate()'s drops
        Collections.shuffle(cards, random);
        if (bestFirst)
            cards.sort((a, b) -> Integer.compare(rarityRank(b.getCard()), rarityRank(a.getCard()))); // stable: ties stay shuffled
        // Round 419 (the user, after the Djinn survey: "do ... the loot-variety fix"): a payout prefers a name it has not
        // paid yet. A second copy the list rolled is set aside and paid only when the deck AND the color's sets have
        // nothing new left - the Djinn paid Volatile Fjord twice from a deck with one other legal name.
        Set<String> paidNames = new HashSet<>();
        List<Reward> kept = new ArrayList<>();
        List<Reward> secondCopies = new ArrayList<>();
        for (Reward reward : cards) {
            if (kept.size() >= budget)
                break;
            if (paidNames.add(reward.getCard().getName()))
                kept.add(reward);
            else
                secondCopies.add(reward);
        }
        int fromList = kept.size();

        int unpayable = 0;
        int toppedUp = 0;
        int shortfall = budget - kept.size();
        List<String> topUpRarities = new ArrayList<>();
        if (shortfall > 0 && deckNoBasicLands != null) {
            for (int i = 0; i < shortfall; i++) {
                PaperCard card = drawTopUpCard(tuning, unpaid(deckNoBasicLands, paidNames), editionRestriction, random);
                if (card != null) {
                    kept.add(new Reward(card));
                    paidNames.add(card.getName());
                    topUpRarities.add(String.valueOf(card.getRarity()));
                    toppedUp++;
                }
            }
        }
        shortfall = budget - kept.size();
        // Round 419: what the deck cannot pay comes from the color's own sets, in the deck's colors, before any gold.
        List<String> fromColorSets = new ArrayList<>();
        ColorSetPool colorPool = null;
        for (int i = 0; i < shortfall; i++) {
            if (colorPool == null)
                colorPool = colorSetPool(data, deckNoBasicLands, editionRestriction);
            PaperCard card = drawTopUpCard(tuning, unpaid(colorPool.cards, paidNames), editionRestriction, random);
            if (card == null)
                break;
            kept.add(new Reward(card));
            paidNames.add(card.getName());
            fromColorSets.add(card.getName());
        }
        shortfall = budget - kept.size();
        int secondCopiesPaid = 0;
        while (shortfall > 0 && !secondCopies.isEmpty()) {
            kept.add(secondCopies.remove(0));
            secondCopiesPaid++;
            shortfall--;
        }
        unpayable += shortfall;

        List<String> bonusNames = new ArrayList<>();
        boolean bonusFromColorSets = false;
        int bonusWanted = firstWin && !champion ? Math.max(0, tuning.cardBudgetFirstWinBonusCards) : 0;
        if (bonusWanted > 0) {
            String[] rarities = easy ? new String[]{"Common", "Uncommon", "Rare"} : new String[]{"Common", "Uncommon"};
            if (deckNoBasicLands != null) {
                List<PaperCard> nonLand = new ArrayList<>();
                for (PaperCard card : unpaid(deckNoBasicLands, paidNames)) {
                    if (!card.getRules().getType().isLand())
                        nonLand.add(card);
                }
                for (PaperCard card : CardUtil.generateCards(nonLand, deckEntry(rarities, editionRestriction), bonusWanted, random)) {
                    if (card != null && paidNames.add(card.getName())) {
                        kept.add(new Reward(card));
                        bonusNames.add(card.getName());
                    }
                }
            }
            // Round 419: the bonus too, from the color's sets at the same rarities, before it turns into gold.
            int missing = bonusWanted - bonusNames.size();
            if (missing > 0) {
                if (colorPool == null)
                    colorPool = colorSetPool(data, deckNoBasicLands, editionRestriction);
                for (PaperCard card : CardUtil.generateCards(unpaid(colorPool.cards, paidNames), deckEntry(rarities, editionRestriction), missing, random)) {
                    if (card != null && paidNames.add(card.getName())) {
                        kept.add(new Reward(card));
                        bonusNames.add(card.getName());
                        bonusFromColorSets = true;
                    }
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
                + (champion ? ", CHAMPION x" + championFactor : "")
                + "): the list rolled " + candidates + " card(s), budget " + budget
                + " (" + rankBase + " base" + (gearApplied > 0 ? " +" + gearApplied + " gear" : "")
                + (difficultyBonus > 0 ? " +" + difficultyBonus + " Easy" : "") + ") -> kept "
                + fromList + (bestFirst ? " best-first" : " at random")
                + (secondCopies.size() + secondCopiesPaid > 0 ? " (" + (secondCopies.size() + secondCopiesPaid)
                        + " second cop" + (secondCopies.size() + secondCopiesPaid == 1 ? "y" : "ies") + " set aside)" : "")
                + (toppedUp > 0 ? ", topped up " + toppedUp + " from its deck " + topUpRarities : "")
                + (!fromColorSets.isEmpty() ? ", " + fromColorSets.size() + " from its color's sets " + fromColorSets
                        + " (" + colorPool.label + ")" : "")
                + (secondCopiesPaid > 0 ? ", " + secondCopiesPaid + " second cop" + (secondCopiesPaid == 1 ? "y" : "ies")
                        + " paid - nothing new left" : "")
                + (bonusWanted > 0 ? ", first-win bonus " + (bonusNames.isEmpty() ? "could not be paid" : bonusNames
                        + (bonusFromColorSets ? " (color's sets)" : "")) : "")
                + (unpayable > 0 ? ", " + unpayable + " unpayable -> " + (unpayable * goldPerCard) + " gold" : "")
                + (droppedFallbackGold > 0 ? ", dropped the list's " + droppedFallbackGold + " gold for unpaid deck card(s)" : ""));
        if (firstWin && !champion)
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

    /** Round 241 (user: "Add the guarantee card drop count per tier as a variable the player can change in the
     *  config file. Maybe make it a range 1-5"): the settings value, held to MIN_BASE..MAX_BASE. */
    private static final int MIN_BASE = 1;
    private static final int MAX_BASE = 5;
    private static boolean rangeWarningLogged;

    private static int base(TuningData tuning, int tier, boolean firstWin) {
        int configured = configuredBase(tuning, tier, firstWin);
        int held = Math.max(MIN_BASE, Math.min(MAX_BASE, configured));
        if (held != configured && !rangeWarningLogged) {
            rangeWarningLogged = true; // once a session - this runs on every win
            System.out.println("[TFR-CardBudget] settings.json asks for " + configured + " card(s) for a "
                    + RANKS[Math.max(0, Math.min(RANKS.length - 1, tier))] + (firstWin ? " first win" : " repeat win")
                    + " - the counts run " + MIN_BASE + " to " + MAX_BASE + ", using " + held);
        }
        return held;
    }

    private static int configuredBase(TuningData tuning, int tier, boolean firstWin) {
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

    /** Round 419: the cards of a pool whose name this payout has not paid yet. */
    private static List<PaperCard> unpaid(List<PaperCard> pool, Set<String> paidNames) {
        List<PaperCard> out = new ArrayList<>(pool.size());
        for (PaperCard card : pool) {
            if (card != null && !paidNames.contains(card.getName()))
                out.add(card);
        }
        return out;
    }

    /** Round 419: the color's sets in the deck's colors - non-land cards of the reward pool. */
    private static final class ColorSetPool {
        final List<PaperCard> cards;
        final String label;

        ColorSetPool(List<PaperCard> cards, String label) {
            this.cards = cards;
            this.label = label;
        }
    }

    private static final String[] NON_LAND_TYPES = {"Creature", "Artifact", "Enchantment", "Instant", "Sorcery", "Planeswalker", "Battle"};
    private static final String[] COLOR_NAMES = {"white", "blue", "black", "red", "green"};
    private static final byte[] COLOR_BITS = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN};
    /** Built over the whole reward pool, so kept per colors + sets + pool instance (a rebuilt pool is a new list). */
    private static final Map<String, List<PaperCard>> colorPoolCache = new HashMap<>();

    /**
     * The deck's colors are its non-land cards' colors (an Owl's deck is blue-black even when the enemy has no color),
     * else the enemy's own colors; none at all means colorless cards. The set restriction is the same as the loot's,
     * so a top-up from here still only hands out what the color's sets hold.
     */
    private static ColorSetPool colorSetPool(EnemyData data, List<PaperCard> deck, List<String> editionRestriction) {
        int mask = 0;
        if (deck != null) {
            for (PaperCard card : deck) {
                if (card != null && !card.getRules().getType().isLand())
                    mask |= card.getRules().getColor().getColor();
            }
        }
        if (mask == 0 && data.colors != null && !data.colors.isEmpty())
            mask = ColorSet.fromNames(data.colors.toCharArray()).getColor();
        RewardData entry = new RewardData();
        entry.type = "card";
        entry.probability = 1f;
        entry.count = 1;
        entry.cardTypes = NON_LAND_TYPES;
        List<String> colors = new ArrayList<>();
        for (int i = 0; i < COLOR_BITS.length; i++) {
            if ((mask & COLOR_BITS[i]) != 0)
                colors.add(COLOR_NAMES[i]);
        }
        if (colors.isEmpty())
            entry.colorType = "Colorless";
        else
            entry.colors = colors.toArray(new String[0]);
        if (editionRestriction != null && !editionRestriction.isEmpty())
            entry.editions = editionRestriction.toArray(new String[0]);
        Iterable<PaperCard> all = RewardData.getAllCards();
        String label = (colors.isEmpty() ? "colorless" : String.join("/", colors))
                + (entry.editions == null ? ", every set" : ", " + entry.editions.length + " set(s)");
        String key = System.identityHashCode(all) + "|" + colors + "|" + (editionRestriction == null ? "-" : new java.util.TreeSet<>(editionRestriction));
        List<PaperCard> cards = colorPoolCache.get(key);
        if (cards == null) {
            if (colorPoolCache.size() > 16)
                colorPoolCache.clear();
            cards = CardUtil.getPredicateResult(all, entry);
            colorPoolCache.put(key, cards);
            System.out.println("[TFR-CardBudget] color-set pool " + label + ": " + cards.size() + " non-land card(s)");
        }
        return new ColorSetPool(cards, label);
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
