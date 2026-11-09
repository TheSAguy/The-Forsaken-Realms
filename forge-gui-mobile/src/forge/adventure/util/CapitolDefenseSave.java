package forge.adventure.util;

import java.util.function.Function;

import forge.adventure.data.EnemyData;

/**
 * TFR round 998 (Fable review 2026-11-02, P1-2's last note): the mage waiting at the Capitol for its forced duel lived
 * only in a static field, so a save and quit while it waited - or during its duel, whose autosave is taken after the
 * mage was consumed - lost the attack: the duel never came. WorldStage.save() writes it here and load() queues it again.
 * Two keys in the world stage's save data; a save without them (every save before this round) loads as none pending.
 */
public final class CapitolDefenseSave {
    static final String MAGE_KEY = "capitolDefenseMage";
    static final String COLOR_KEY = "capitolDefenseColor";

    private CapitolDefenseSave() {
    }

    /** The mage a load queues again: its enemy data and its color. */
    public static final class Pending {
        public final EnemyData data;
        public final String color;
        public final String savedName;

        Pending(EnemyData data, String color, String savedName) {
            this.data = data;
            this.color = color;
            this.savedName = savedName;
        }
    }

    /**
     * Writes the waiting mage. {@code savedName} is what the roaming enemies save for it
     * ({@code EnemyTiers.saveName}, so a tiered mage keeps its tier); nothing is written when no mage waits.
     */
    public static void write(SaveFileData data, String savedName, String color) {
        if (savedName == null || savedName.isEmpty() || color == null || color.isEmpty())
            return;
        data.store(MAGE_KEY, savedName);
        data.store(COLOR_KEY, color);
    }

    /** The waiting mage a save holds, or null: none saved, or a name that no longer resolves (logged, then dropped). */
    public static Pending read(SaveFileData data, Function<String, EnemyData> resolver) {
        if (data == null || !data.containsKey(MAGE_KEY))
            return null;
        String name = data.readString(MAGE_KEY);
        String color = data.containsKey(COLOR_KEY) ? data.readString(COLOR_KEY) : null;
        if (name == null || name.isEmpty() || color == null || color.isEmpty()) {
            System.err.println("[TFR-CapitolDefense] the save's waiting Capitol mage is incomplete (name=" + name
                    + ", color=" + color + ") - dropped");
            return null;
        }
        EnemyData enemy = resolver.apply(name);
        if (enemy == null) {
            System.err.println("[TFR-CapitolDefense] the save's waiting Capitol mage \"" + name
                    + "\" no longer resolves (renamed or removed from enemies.json?) - dropped");
            return null;
        }
        return new Pending(enemy, color, name);
    }
}
