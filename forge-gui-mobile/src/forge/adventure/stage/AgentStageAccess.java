package forge.adventure.stage;

import com.badlogic.gdx.utils.Array;
import forge.adventure.character.EnemySprite;
import forge.adventure.character.MapActor;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * Round 161 (agent bridge): the stage package's package-private and protected state, exposed to
 * {@code forge.adventure.agent} through one mod-added file instead of edits to the stock stages.
 */
public final class AgentStageAccess {
    private AgentStageAccess() {}

    /** The overworld's live enemy sprites (roamers and territory mages), including hidden ones. */
    public static List<EnemySprite> worldEnemies() {
        List<EnemySprite> out = new ArrayList<>();
        WorldStage stage = WorldStage.getInstance();
        if (stage == null)
            return out;
        for (Pair<Float, EnemySprite> pair : stage.enemies)
            if (pair.getValue() != null)
                out.add(pair.getValue());
        return out;
    }

    /** Every actor on the current town/dungeon map. */
    public static Array<MapActor> mapActors() {
        return MapStage.getInstance().actors;
    }

    /** The sprite size the map's navigation graph was built for. */
    public static float mapNavSize() {
        return MapStage.getInstance().navMapSize;
    }

    /**
     * Round 175: the point of interest the player stands on, made the game's "just left it" point
     * (WorldStage.collidingPoint), which the entry check skips until the player steps off it.
     * <p>
     * Why the agent needs this and a human does not: TileMapScene.leave() switches the world player's
     * collision OFF (collisionHeight 0) and GameHUD switches it back on after the ~2 s arrival flicker
     * - "at least 2 seconds to get away from problematic collision point". WorldStage.enter() runs
     * inside that window, so its own collidingPoint test (collideWith() is always false at height 0)
     * never marks the town you are standing on. A person walks off during the flicker; the agent
     * issued its next walk after it, still on the town, and walked straight back in - through the
     * portal, then on walks north, east and to a clear tile, in its first isolated session. The test
     * here is geometric (the rectangle CharacterSprite uses at its normal 0.4 height), so it holds
     * whether or not collision is currently on. The field is private in the stock class: reflection
     * keeps the stock file untouched. Returns what it did, or null.
     */
    public static String exemptPoiUnderPlayer() {
        WorldStage stage = WorldStage.getInstance();
        if (stage == null || stage.getPlayerSprite() == null || stage.foregroundSprites == null)
            return null;
        com.badlogic.gdx.scenes.scene2d.Actor p = stage.getPlayerSprite();
        com.badlogic.gdx.math.Rectangle me = new com.badlogic.gdx.math.Rectangle(
                p.getX() + 4, p.getY(), Math.max(1, p.getWidth() - 6), Math.max(1, p.getHeight() * 0.4f));
        try {
            java.lang.reflect.Field field = WorldStage.class.getDeclaredField("collidingPoint");
            field.setAccessible(true);
            for (com.badlogic.gdx.scenes.scene2d.Actor actor : stage.foregroundSprites.getChildren()) {
                if (!(actor instanceof PointOfInterestMapSprite))
                    continue;
                PointOfInterestMapSprite point = (PointOfInterestMapSprite) actor;
                if (!me.overlaps(point.getBoundingRect()))
                    continue;
                Object before = field.get(stage);
                String name = point.getPointOfInterest() == null ? "a point of interest"
                        : point.getPointOfInterest().getDisplayName();
                if (before == point)
                    return name + " (the game had it already)";
                field.set(stage, point);
                return name + " (was " + (before == null ? "unset" : "another point") + ")";
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "failed: " + e;
        }
        return null;
    }

    /** Round 175 diagnostic: the player's collision rectangle, the game's collidingPoint, and every point of
     *  interest sprite within four tiles with its rectangle - logged at the start of a world walk. */
    public static String describeSurroundings() {
        WorldStage stage = WorldStage.getInstance();
        if (stage == null || stage.getPlayerSprite() == null || stage.foregroundSprites == null)
            return "no world stage";
        StringBuilder sb = new StringBuilder();
        com.badlogic.gdx.math.Rectangle me = stage.getPlayerSprite().boundingRect();
        sb.append("player rect ").append(fmt(me)).append(" collisionHeight ").append(stage.getPlayerSprite().getCollisionHeight());
        try {
            java.lang.reflect.Field field = WorldStage.class.getDeclaredField("collidingPoint");
            field.setAccessible(true);
            Object cp = field.get(stage);
            sb.append(", collidingPoint ").append(cp instanceof PointOfInterestMapSprite
                    && ((PointOfInterestMapSprite) cp).getPointOfInterest() != null
                    ? ((PointOfInterestMapSprite) cp).getPointOfInterest().getDisplayName() : String.valueOf(cp));
        } catch (ReflectiveOperationException | RuntimeException e) {
            sb.append(", collidingPoint ?");
        }
        for (com.badlogic.gdx.scenes.scene2d.Actor actor : stage.foregroundSprites.getChildren()) {
            if (!(actor instanceof PointOfInterestMapSprite))
                continue;
            PointOfInterestMapSprite point = (PointOfInterestMapSprite) actor;
            com.badlogic.gdx.math.Rectangle r = point.getBoundingRect();
            float dx = Math.max(0, Math.max(r.x - (me.x + me.width), me.x - (r.x + r.width)));
            float dy = Math.max(0, Math.max(r.y - (me.y + me.height), me.y - (r.y + r.height)));
            if (dx > 64 || dy > 64)
                continue;
            sb.append("; ").append(point.getPointOfInterest() == null ? "?" : point.getPointOfInterest().getDisplayName())
                    .append(" sprite ").append(fmt(r)).append(" poiRect ")
                    .append(point.getPointOfInterest() == null ? "?" : fmt(point.getPointOfInterest().getBoundingRectangle()))
                    .append(stage.getPlayerSprite().collideWith(r) ? " COLLIDING" : "");
        }
        return sb.toString();
    }

    private static String fmt(com.badlogic.gdx.math.Rectangle r) {
        return r == null ? "null" : String.format("[%.0f,%.0f %.0fx%.0f]", r.x, r.y, r.width, r.height);
    }
}
