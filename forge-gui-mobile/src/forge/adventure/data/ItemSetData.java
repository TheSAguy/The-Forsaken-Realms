package forge.adventure.data;

/**
 * Round 629 - one gear set (world/item_sets.json, read by util/ItemSets). Seven items, one per main slot; wearing 3, 5
 * or 7 of them (distinct names, uncracked) adds the matching tiers' effects to a duel, on top of the items' own.
 */
public class ItemSetData {
    /** Stable key, e.g. "dawnward". */
    public String id;
    /** What the player reads, e.g. "Dawnward Regalia". */
    public String name;
    /** Regalia, Bane or Legend. */
    public String family;
    /** A Bane set's target color as a letter (W, U, B, R, G); null for the other families. */
    public String against;
    /** The member items by name. A name the catalog does not hold (yet) simply never counts. */
    public String[] items = new String[0];
    /** The bonuses, smallest first. */
    public Tier[] tiers = new Tier[0];

    public static class Tier {
        /** Worn pieces needed. */
        public int pieces;
        /** What this tier adds to the duel - the same fields an item's effect has. */
        public EffectData effect;
        /** Only in a duel against an enemy of the set's {@code against} color (a Bane set's life). */
        public boolean onlyAgainst;
        /** A lower tier this one replaces (the 7-piece card takes the 5-piece card's place); 0 = none. */
        public int replaces;
        /** One line for the screens, e.g. "+2 life". */
        public String text;
    }
}
