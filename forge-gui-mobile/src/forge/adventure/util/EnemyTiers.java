package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.adventure.data.EnemyData;
import forge.adventure.data.WorldData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Round 643: enemy tier families. The user (2026-10-15): "take each enemy ... and create Apprentice, Adept and Master
 * versions for each. (Archmage should be unique art)", with the decks at least 60 / 50 / 40 cards, and "Bosses / War
 * Champions / Legends and Arch Mages [stay] unique".
 *
 * <p>world/enemy_tiers.json (written by dev-tools/enemy_tiers/make_tier_families.py) names, for every ordinary enemy, an
 * Apprentice, an Adept and a Master deck for each of its decks, and its life at each tier. The unique enemies (tier
 * Mythic, bosses, legends, spawnRate 0 champions, the "Apprentice Red Wizard" style families) are not in it.
 *
 * <ul>
 * <li>The catalog: a family enemy plays its own tier's family deck everywhere it appears as written (a map
 * placement, an arena seat, a town defender) - {@link #applyCatalog}.</li>
 * <li>The roaming roll: BiomeData puts each family enemy of a biome in the roll ONCE PER TIER
 * ({@link #expand}), so the week's tier row picks the tier and the creature is any of the biome's. A variant is a
 * clone of the catalog entry with the tier, its decks and its life - its size, name suffix, loot budget, Power and
 * Walls follow from the tier as they always did.</li>
 * <li>A map's own placement (round 647): on the first visit an ordinary placement of a family creature takes the rank
 * the week's tier row rolls in that land ({@link #rollPlacement}), before DungeonUpgrades' win-streak lift.</li>
 * <li>Saves keep enemies by name; a variant saves as "Name|Tier" ({@link #saveName}) and
 * {@link WorldData#getEnemy} resolves it back ({@link #resolveSaved}). An older save's plain name loads the catalog
 * tier.</li>
 * </ul>
 * Without the table nothing changes.
 */
public final class EnemyTiers {
    public static final String TABLE = "world/enemy_tiers.json";
    private static final String[] TIERS = {"Common", "Uncommon", "Rare"};
    private static final char SEP = '|';

    private static final class Family {
        int[] life;
        String[][] decks; // [deck index][tier]
    }

    private static Map<String, Family> table;
    private static final Map<String, EnemyData> variants = new HashMap<>();

    private EnemyTiers() {
    }

    private static Map<String, Family> table() {
        if (table != null)
            return table;
        Map<String, Family> loaded = new HashMap<>();
        try {
            FileHandle handle = Config.instance().getFile(TABLE);
            if (handle != null && handle.exists()) {
                JsonValue root = new JsonReader().parse(handle);
                for (JsonValue e = root.child; e != null; e = e.next) {
                    Family f = new Family();
                    f.life = e.get("life").asIntArray();
                    JsonValue decks = e.get("decks");
                    f.decks = new String[decks.size][];
                    int i = 0;
                    for (JsonValue d = decks.child; d != null; d = d.next)
                        f.decks[i++] = d.asStringArray();
                    if (f.life.length == TIERS.length)
                        loaded.put(e.name, f);
                }
            }
        } catch (RuntimeException ex) {
            System.err.println("[TFR-EnemyTiers] could not read " + TABLE + ": " + ex);
            loaded.clear();
        }
        table = loaded;
        System.out.println("[TFR-EnemyTiers] " + loaded.size() + " enemy families (Apprentice / Adept / Master)");
        return table;
    }

    private static int index(String tier) {
        for (int i = 0; i < TIERS.length; i++)
            if (TIERS[i].equalsIgnoreCase(tier))
                return i;
        return -1;
    }

    private static Family family(EnemyData data) {
        if (data == null || data.name == null || data.deck == null)
            return null;
        Family f = table().get(data.name);
        return f != null && f.decks.length == data.deck.length ? f : null;
    }

    /** True for an enemy that comes in three tiers. */
    public static boolean isFamily(EnemyData data) {
        return family(data) != null && index(data.tier) >= 0;
    }

    /** Once, on the freshly read catalog: a family enemy plays its own tier's family deck. */
    public static void applyCatalog(Array<EnemyData> catalog) {
        if (catalog == null)
            return;
        int applied = 0;
        for (EnemyData e : new Array.ArrayIterator<>(catalog)) {
            Family f = family(e);
            int t = e == null ? -1 : index(e.tier);
            if (f == null || t < 0)
                continue;
            String[] decks = new String[f.decks.length];
            for (int i = 0; i < decks.length; i++)
                decks[i] = f.decks[i][t];
            e.deck = decks;
            applied++;
        }
        System.out.println("[TFR-EnemyTiers] " + applied + " catalog enemies play their family deck");
    }

    /** The enemy at another tier: a clone with that tier's decks and life (cached - one object a name and tier). The
     *  catalog entry itself for its own tier, and for anything that is not a family. */
    public static EnemyData variant(EnemyData catalog, String tier) {
        Family f = family(catalog);
        int t = index(tier);
        if (f == null || t < 0 || index(catalog.tier) < 0 || TIERS[t].equalsIgnoreCase(catalog.tier))
            return catalog;
        String key = catalog.name + SEP + TIERS[t];
        EnemyData v = variants.get(key);
        if (v == null) {
            v = new EnemyData(catalog);
            v.tier = TIERS[t];
            v.life = f.life[t];
            String[] decks = new String[f.decks.length];
            for (int i = 0; i < decks.length; i++)
                decks[i] = f.decks[i][t];
            v.deck = decks;
            variants.put(key, v);
        }
        return v;
    }

    /** A biome roster entry as the roaming roll sees it: a family enemy once per tier, anything else as itself. */
    public static List<EnemyData> expand(EnemyData catalog) {
        if (!isFamily(catalog))
            return Collections.singletonList(catalog);
        List<EnemyData> out = new ArrayList<>(TIERS.length);
        for (String t : TIERS)
            out.add(variant(catalog, t));
        return out;
    }

    /**
     * Round 647 (the user: "let hand-placed ordinary dungeon enemies roll their rank by the week, like roaming ones"):
     * the rank a map's own placement of a family creature takes on the first visit - the week's tier row in the land
     * the place stands on now (SpawnTierWeighting.targetTierWeight, the same row the roaming roll uses), Archmage left
     * out (a family has none). Null when it is not a family creature, the weighting is off or the row is empty; MapStage
     * then keeps the placement as written. The caller records the result in the place's roster.
     */
    public static EnemyData rollPlacement(EnemyData placed, forge.adventure.pointofintrest.PointOfInterest poi, int objectId) {
        if (!isFamily(placed) || !SpawnTierWeighting.isEnabled() || Current.world() == null)
            return null;
        EnemyData catalog = WorldData.getEnemy(placed.name);
        if (catalog == null)
            return null;
        int week = SpawnTierWeighting.currentWeek(Current.world());
        String land = poi == null ? null : TerritoryControl.currentColorAtPoi(Current.world(), poi);
        float[] weights = new float[TIERS.length];
        float total = 0f;
        for (int i = 0; i < TIERS.length; i++) {
            weights[i] = Math.max(0f, SpawnTierWeighting.targetTierWeight(TIERS[i], week, land));
            total += weights[i];
        }
        if (total <= 0f)
            return null;
        float r = forge.util.MyRandom.getRandom().nextFloat() * total;
        int pick = TIERS.length - 1;
        for (int i = 0; i < TIERS.length; i++) {
            r -= weights[i];
            if (r <= 0f) {
                pick = i;
                break;
            }
        }
        EnemyData out = variant(catalog, TIERS[pick]);
        System.out.println("[TFR-EnemyTiers] " + (poi == null ? "?" : poi.getDisplayName()) + " #" + objectId + ": "
                + catalog.getName() + " placed as " + EnemyData.tierDisplayName(catalog.tier) + " -> "
                + EnemyData.tierDisplayName(out.tier) + " (week " + week + ", " + land + " row "
                + String.format("%.0f/%.0f/%.0f", weights[0], weights[1], weights[2]) + ")");
        return out;
    }

    /** What a save stores for this enemy: "Name|Tier" for a variant, else {@code plain} (the caller's usual name). */
    public static String saveName(EnemyData data, String plain) {
        if (data == null || data.name == null || !isFamily(data))
            return plain;
        EnemyData catalog = WorldData.getEnemy(data.name);
        if (catalog == null || data.tier.equalsIgnoreCase(catalog.tier))
            return plain;
        return data.name + SEP + data.tier;
    }

    /** A saved "Name|Tier" back to its variant; null when it is not one. */
    public static EnemyData resolveSaved(String saved) {
        int bar = saved == null ? -1 : saved.lastIndexOf(SEP);
        if (bar <= 0)
            return null;
        EnemyData catalog = WorldData.getEnemy(saved.substring(0, bar));
        return catalog == null ? null : variant(catalog, saved.substring(bar + 1));
    }
}
