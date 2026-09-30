package forge.adventure.util;

import forge.adventure.data.RewardData;
import forge.card.CardEdition;
import forge.card.CardRules;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Round 372 (the user: "For all sets, the entire Print count (minus lands) needs to be part of each set ... When you find
 * a card that has copies across multiple sets, it should only count to the set that matches the print you found").
 * <p>
 * The reward pool holds ONE printing per card name (CardDb.getUniqueCards), so anything that read a set's cards off
 * that pool saw only the cards whose preferred printing happened to be that set - Amonkhet printed 254 cards and read
 * as 29. This is the set as printed: for each edition, every card name in its [cards] section that the reward pool
 * can hand out (so restricted cards, rebalanced variants and the like stay out), basic lands excluded. It is the one
 * answer for the Research Lab (total and what counts as found), the master edition list and the Smith.
 * <p>
 * Built once from the edition files and dropped with the reward pool (RewardData.invalidateCardPool).
 */
public final class PrintingIndex {
    private static Map<String, Set<String>> namesByEdition;
    private static Map<String, Set<String>> editionsByName;

    private PrintingIndex() {
    }

    public static synchronized void invalidate() {
        namesByEdition = null;
        editionsByName = null;
    }

    private static synchronized void build() {
        if (namesByEdition != null)
            return;
        Set<String> legal = new HashSet<>();
        for (PaperCard pc : RewardData.getAllCards())
            legal.add(pc.getName());
        Map<String, Set<String>> byEdition = new HashMap<>();
        Map<String, Set<String>> byName = new HashMap<>();
        if (legal.isEmpty()) {
            // The card database is not loaded yet - answer empty, but do not cache it.
            namesByEdition = null;
            return;
        }
        Map<String, Boolean> basic = new HashMap<>();
        for (CardEdition ed : FModel.getMagicDb().getEditions()) {
            if (ed == null || ed.getCards() == null)
                continue;
            String code = ed.getCode();
            for (CardEdition.EditionEntry entry : ed.getCards()) {
                String name = entry.name();
                if (name == null || !legal.contains(name))
                    continue;
                if (basic.computeIfAbsent(name, PrintingIndex::isBasicLand))
                    continue;
                byEdition.computeIfAbsent(code, k -> new HashSet<>()).add(name);
                byName.computeIfAbsent(name, k -> new HashSet<>()).add(code);
            }
        }
        namesByEdition = byEdition;
        editionsByName = byName;
        int printings = 0;
        for (Set<String> s : byEdition.values())
            printings += s.size();
        System.out.println("[TFR-Printings] " + byEdition.size() + " edition(s), " + byName.size() + " card name(s), "
                + printings + " set printing(s) (basic lands left out)");
    }

    private static boolean isBasicLand(String name) {
        try {
            CardRules rules = FModel.getMagicDb().getCommonCards().getRules(name, false);
            return rules != null && rules.getType() != null && rules.getType().isBasicLand();
        } catch (Exception e) {
            return false;
        }
    }

    /** The whole index, edition code -> its card names (read-only; empty before the card database loads). */
    public static Map<String, Set<String>> byEdition() {
        build();
        Map<String, Set<String>> m = namesByEdition;
        return m == null ? Collections.emptyMap() : Collections.unmodifiableMap(m);
    }

    /** Every card name printed in this edition that the reward pool can hand out, basic lands excluded. */
    public static Set<String> namesIn(String editionCode) {
        build();
        Map<String, Set<String>> m = namesByEdition;
        Set<String> s = m == null || editionCode == null ? null : m.get(editionCode);
        return s == null ? Collections.emptySet() : s;
    }

    /** The edition's size as a set - its printed, obtainable, non-basic cards. */
    public static int total(String editionCode) {
        return namesIn(editionCode).size();
    }

    /** Every edition that printed this card (of those the index holds). */
    public static Set<String> editionsOf(String cardName) {
        build();
        Map<String, Set<String>> m = editionsByName;
        Set<String> s = m == null || cardName == null ? null : m.get(cardName);
        return s == null ? Collections.emptySet() : s;
    }

    /** Does this card have a printing in any of these editions? */
    public static boolean printedInAny(String cardName, java.util.Collection<String> editionCodes) {
        if (editionCodes == null)
            return true;
        for (String code : editionsOf(cardName))
            if (editionCodes.contains(code))
                return true;
        return false;
    }

    /** Does this owned printing count toward its own edition's research? */
    public static boolean counts(PaperCard printing) {
        return printing != null && namesIn(printing.getEdition()).contains(printing.getName());
    }
}
