package forge.adventure.util;

import forge.card.CardRarity;
import forge.card.ColorSet;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Round 387 (a player on Discord: "I'm guessing there shouldn't be commander specific cards in the pool and in enemy
 * decks? (like arcane signet and command tower)"; the user: "Can we do this audit all decks loot... they should be
 * suppressed"). Two jobs the restricted list never did:
 * <ul>
 * <li><b>Enemy decks:</b> the restricted list only ever filtered the player's reward and shop pools - 620 enemy decks
 * still played Arcane Signet, 575 Command Tower. Every enemy deck loses its commander-only cards
 * (restricted_cards.json commanderOnlyCards) as it is built (EnemyData.generateDeck), each copy swapped for a basic
 * land of the deck's main color, so the deck keeps its size and roughly its mana. Only the commander-only group: a
 * boss's Sol Ring or Black Lotus is part of its design.</li>
 * <li><b>Boosters:</b> a booster is built straight from a set's sheets and never saw the restricted list. Each
 * restricted card (both lists) in a pack is swapped for another card of the same rarity from the same set, or dropped
 * when the set has none.</li>
 * </ul>
 * Grep forge.log for [TFR-CommanderCards].
 */
public final class CommanderCards {
    private CommanderCards() {}

    private static Set<String> commanderOnly;
    private static Set<String> restricted;
    private static final Set<String> LOGGED_DECKS = new HashSet<>();
    private static final Map<String, List<PaperCard>> REPLACEMENTS = new HashMap<>();

    public static synchronized Set<String> commanderOnly() {
        if (commanderOnly == null) {
            String[] names = Config.instance().getConfigData().commanderOnlyCards;
            commanderOnly = names == null ? new HashSet<>() : new HashSet<>(Arrays.asList(names));
        }
        return commanderOnly;
    }

    /** Every restricted name - the commander-only group is merged into restrictedCards when the file loads. */
    public static synchronized Set<String> restricted() {
        if (restricted == null) {
            String[] names = Config.instance().getConfigData().restrictedCards;
            restricted = names == null ? new HashSet<>() : new HashSet<>(Arrays.asList(names));
        }
        return restricted;
    }

    /** An enemy's deck without its commander-only cards - each copy a basic land of the deck's main color. */
    public static Deck stripEnemyDeck(Deck deck, String enemyName) {
        if (deck == null || commanderOnly().isEmpty())
            return deck;
        CardPool main = deck.getOrCreate(DeckSection.Main);
        Map<PaperCard, Integer> found = new HashMap<>();
        for (Map.Entry<PaperCard, Integer> e : main) {
            if (commanderOnly().contains(e.getKey().getName()))
                found.put(e.getKey(), e.getValue());
        }
        if (found.isEmpty())
            return deck;
        int copies = 0;
        StringBuilder names = new StringBuilder();
        for (Map.Entry<PaperCard, Integer> e : found.entrySet()) {
            main.remove(e.getKey(), e.getValue());
            copies += e.getValue();
            names.append(names.length() == 0 ? "" : ", ").append(e.getKey().getName()).append(" x").append(e.getValue());
        }
        String basic = mainBasic(main);
        PaperCard land = FModel.getMagicDb().getCommonCards().getCard(basic);
        if (land != null)
            main.add(land, copies);
        String key = enemyName + "|" + deck.getName();
        if (LOGGED_DECKS.add(key))
            System.out.println("[TFR-CommanderCards] " + enemyName + " (" + deck.getName() + "): " + names
                    + " -> " + copies + " " + basic + (land == null ? " (not found - removed only)" : ""));
        return deck;
    }

    /** The basic land of the color with the most nonland cards in the pool - Wastes for a colorless deck. */
    private static String mainBasic(CardPool pool) {
        int[] counts = new int[5];
        for (Map.Entry<PaperCard, Integer> e : pool) {
            if (e.getKey().getRules().getType().isLand())
                continue;
            ColorSet c = e.getKey().getRules().getColor();
            if (c.hasWhite()) counts[0] += e.getValue();
            if (c.hasBlue()) counts[1] += e.getValue();
            if (c.hasBlack()) counts[2] += e.getValue();
            if (c.hasRed()) counts[3] += e.getValue();
            if (c.hasGreen()) counts[4] += e.getValue();
        }
        String[] basics = {"Plains", "Island", "Swamp", "Mountain", "Forest"};
        int best = -1;
        for (int i = 0; i < 5; i++)
            if (counts[i] > 0 && (best < 0 || counts[i] > counts[best]))
                best = i;
        return best < 0 ? "Wastes" : basics[best];
    }

    /** A booster's cards without restricted ones - each swapped for a same-rarity card of the same set, or dropped. */
    public static List<PaperCard> cleanPack(List<PaperCard> cards, String where) {
        if (cards == null || restricted().isEmpty())
            return cards;
        boolean any = false;
        List<PaperCard> out = new ArrayList<>(cards.size());
        for (PaperCard card : cards) {
            if (!restricted().contains(card.getName())) {
                out.add(card);
                continue;
            }
            any = true;
            PaperCard swap = replacement(card);
            System.out.println("[TFR-CommanderCards] " + where + ": " + card.getName() + " (" + card.getEdition()
                    + ", " + card.getRarity() + ") -> " + (swap == null ? "dropped (nothing else at that rarity)" : swap.getName()));
            if (swap != null)
                out.add(swap);
        }
        return any ? out : cards;
    }

    private static PaperCard replacement(PaperCard card) {
        String key = card.getEdition() + "|" + card.getRarity();
        List<PaperCard> pool;
        synchronized (REPLACEMENTS) {
            pool = REPLACEMENTS.get(key);
            if (pool == null) {
                pool = new ArrayList<>();
                CardRarity rarity = card.getRarity();
                for (PaperCard pc : FModel.getMagicDb().getCommonCards().getAllCards()) {
                    if (!card.getEdition().equals(pc.getEdition()) || pc.getRarity() != rarity)
                        continue;
                    if (restricted().contains(pc.getName()) || pc.getRules().getType().isBasicLand())
                        continue;
                    pool.add(pc);
                }
                REPLACEMENTS.put(key, pool);
            }
        }
        return pool.isEmpty() ? null : pool.get(MyRandom.getRandom().nextInt(pool.size()));
    }

    /** The same for a deck built as a booster (its Main section). */
    public static Deck cleanPack(Deck pack, String where) {
        if (pack == null)
            return null;
        CardPool main = pack.getOrCreate(DeckSection.Main);
        List<PaperCard> before = main.toFlatList();
        List<PaperCard> cleaned = cleanPack(before, where);
        if (cleaned != before) {
            main.clear();
            main.add(cleaned);
        }
        return pack;
    }
}
