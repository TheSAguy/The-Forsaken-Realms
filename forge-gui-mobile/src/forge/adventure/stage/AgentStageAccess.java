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
}
