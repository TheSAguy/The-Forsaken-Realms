package forge.adventure.util;

import com.badlogic.gdx.maps.MapProperties;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.EnemyData;
import forge.adventure.data.TuningData;
import forge.adventure.data.WorldData;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Castle champions (round 407, user: "add a aggressive patrolling war champion to each AI castle. two on Insane. Must
 * be Arch-mage level"; on the pick, the user chose a roster Archmage over the arena war champions, so a guard that
 * returns on every castle visit pays ordinary Archmage loot under the card budget and cannot be farmed for a legend's
 * jackpot).
 * <p>
 * A castle map marks the placement with {@code castleChampion=<color>}; the map's own {@code spawn.*} flags decide how
 * many appear (one on every difficulty, a second on Insane only). The first visit picks a random Archmage from that
 * color's own roster ({@link TerritoryControl#grandmasterRoster}) - never the same one twice in one castle - and the
 * POI's fixed roster keeps it for every later visit. The authored {@code enemy} is only a stand-in for a world whose
 * roster has no Archmage left (content filter). Patrol and reaction ranges are the map's; the chase speed is floored
 * here.
 */
public final class CastleChampions {
    private CastleChampions() {}

    /** The castle color this placement champions, or null for an ordinary placement. */
    public static String colorOf(MapProperties prop) {
        Object value = prop == null ? null : prop.get("castleChampion");
        if (value == null)
            return null;
        String color = value.toString().trim().toLowerCase();
        return color.isEmpty() ? null : color;
    }

    /**
     * The champion for this placement: the name the castle's roster recorded on an earlier visit, else a fresh pick
     * from the color's Archmages, skipping the names already standing in this castle. Null keeps the authored enemy.
     */
    public static EnemyData resolve(World world, PointOfInterestChanges changes, int objectId, String color, Set<String> taken) {
        String stored = changes != null ? changes.getFixedEnemy(objectId) : null;
        EnemyData recorded = stored == null ? null : WorldData.getEnemy(stored);
        if (recorded != null) {
            taken.add(recorded.getName());
            return recorded;
        }
        if (world == null)
            return null;
        List<EnemyData> pool = new ArrayList<>();
        for (EnemyData e : TerritoryControl.grandmasterRoster(world, color)) {
            // speed 0 = a creature authored to stand still (the four Behemoths) - it cannot walk a patrol
            if (e.speed > 0f && !taken.contains(e.getName()) && ContentFilterTables.isEnemyIncluded(e.getName()))
                pool.add(e);
        }
        if (pool.isEmpty()) {
            System.out.println("[TFR-CastleChampion] " + color + " castle #" + objectId
                    + ": no Archmage left in the roster - keeping the map's own stand-in");
            return null;
        }
        EnemyData pick = pool.get(world.getRandom().nextInt(pool.size()));
        taken.add(pick.getName());
        System.out.println("[TFR-CastleChampion] " + color + " castle #" + objectId + ": " + pick.getName()
                + " picked from " + pool.size() + " Archmage(s) - kept for this world");
        return pick;
    }

    /** The chase speed floor, the same shape as a robbed booster guard's: the player's base speed x the factor. */
    public static void applySpeedFloor(EnemySprite mob) {
        TuningData tuning = Config.instance().getTuningData();
        float factor = tuning == null ? 0f : tuning.castleChampionSpeedFactor;
        if (mob == null || mob.getData() == null || factor <= 0f)
            return;
        float floor = Config.instance().getConfigData().playerBaseSpeed * factor;
        if (mob.getData().speed + mob.speedModifier < floor)
            mob.speedModifier = floor - mob.getData().speed;
        System.out.println("[TFR-CastleChampion] " + mob.getData().getName() + " patrols at speed " + mob.speed()
                + " (own " + mob.getData().speed + ", floor " + floor + "), threat " + mob.threatRange
                + " / pursue " + mob.pursueRange);
    }
}
