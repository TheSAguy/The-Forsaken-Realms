package forge.adventure.util;

import java.util.List;
import java.util.Locale;
import java.util.function.IntUnaryOperator;

import org.apache.commons.lang3.tuple.Pair;

/**
 * TFR round 991 (logs-1108c P3-13, the user: "A, fix the source"): the math behind a booster pack's sell floor
 * ({@code AdventurePlayer.expectedPackSellPrice}, round 932). Round 932 valued every slot at CardUtil's rarity defaults
 * (Common 50, Uncommon 150, Rare 300, Mythic 500), so a set whose cards the price list raises (Alpha's dual lands,
 * Legends' Mana Drain, Arabian Nights' City of Brass) resold above its floor, and a deep discount (the player's own
 * town, a Market with the Merchant Guild, town reputation, Haggler, the Fortune Coin: 0.390 of the list price) made
 * buying and opening it a gold loop. Now each slot is valued at its set's average for that rarity, with the restricted
 * cards left out (they never come out of a pack), each card at what it would really sell for (the player's own sell
 * function, with its per-card rounding). Still the set's average, never the pack's own cards, so the price says nothing
 * about what is inside. A set with no listed card keeps round 932's value.
 */
public final class PackFloor {
    public static final int COMMON = 50, UNCOMMON = 150, RARE = 300, MYTHIC = 500, LAND = 5;

    private PackFloor() {
    }

    /**
     * A set's cards that can come out of its packs, summed by rarity, each at {@code value(listPrice)}: the list price
     * itself (the no-argument constructor) or what the card sells for.
     */
    public static final class SetPrices {
        private final IntUnaryOperator value;
        private double sumC, sumU, sumR, sumM;
        private int nC, nU, nR, nM;

        public SetPrices() {
            this(p -> p);
        }

        public SetPrices(IntUnaryOperator value) {
            this.value = value;
        }

        /** One card of the set: rarity 'C', 'U', 'R' or 'M' (any other rarity is ignored) at its list price. */
        public SetPrices add(char rarity, int listPrice) {
            switch (rarity) {
                case 'C': sumC += value.applyAsInt(listPrice); nC++; break;
                case 'U': sumU += value.applyAsInt(listPrice); nU++; break;
                case 'R': sumR += value.applyAsInt(listPrice); nR++; break;
                case 'M': sumM += value.applyAsInt(listPrice); nM++; break;
                default: break;
            }
            return this;
        }

        public float common() { return nC == 0 ? value.applyAsInt(COMMON) : (float) (sumC / nC); }
        public float uncommon() { return nU == 0 ? value.applyAsInt(UNCOMMON) : (float) (sumU / nU); }
        public float rare() { return nR == 0 ? value.applyAsInt(RARE) : (float) (sumR / nR); }
        public float mythic() { return nM == 0 ? value.applyAsInt(MYTHIC) : (float) (sumM / nM); }
        public float land() { return value.applyAsInt(LAND); }

        /** An "UncommonRare" slot draws from the uncommons and rares together. */
        public float uncommonOrRare() {
            return nU + nR == 0 ? (value.applyAsInt(UNCOMMON) + value.applyAsInt(RARE)) / 2f
                    : (float) ((sumU + sumR) / (nU + nR));
        }
    }

    /** What one card of this slot is worth, on average. Slot names as round 932 read them. */
    public static float slotValue(String slotKey, SetPrices p) {
        String s = slotKey == null ? "" : slotKey.toLowerCase(Locale.ROOT);
        if (s.startsWith("raremythic"))
            return (7 * p.rare() + p.mythic()) / 8f;
        if (s.startsWith("uncommonrare"))
            return p.uncommonOrRare();
        if (s.startsWith("mythic"))
            return p.mythic();
        if (s.startsWith("rare"))
            return p.rare();
        if (s.startsWith("uncommon"))
            return p.uncommon();
        if (s.contains("land"))
            return p.land();
        return p.common();
    }

    /** A pack of this template, on average, in the SetPrices' terms (list price or sell price). */
    public static float packValue(List<Pair<String, Integer>> slots, SetPrices p) {
        float total = 0f;
        for (Pair<String, Integer> slot : slots)
            total += slotValue(slot.getKey(), p) * slot.getValue();
        return total;
    }
}
