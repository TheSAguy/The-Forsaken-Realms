package forge.adventure.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

/**
 * Round 346 - the player's roads (the user, 2026-09-25: "Let's implement the new Roads on the World map. I only want
 * these for the player towns. When you build your capitol, at that point replace the existing roads with the new
 * roads. It should only replace the roads between player towns and capitol. Any new player towns built will be
 * connected to the road network using the new roads. Need to remove all old roads that the new roads pave over ...
 * I want the new roads to increase player movement by 15% more than the old roads." Owned Ring Cities count too).
 * <p>
 * The player road is its own layer on the world's tile map, one bit above the old road (World.playerRoadBit()),
 * drawn from world.json's playerRoadTileset by the same autotile rule as every other layer, and faster underfoot
 * (WorldStage: the old road's 1.5x times TuningData.playerRoadSpeedBonus). It is laid only from the Capitol:
 * <ul>
 * <li>when the Capitol is raised (TownRestoration.upgradeToCapitol), a network to every town the player holds;</li>
 * <li>a town restored or captured later, while the Capitol stands, joins it (connectTown returns true and the caller
 *     skips connectCapturedTownByRoad);</li>
 * <li>a save laid under an older rule gets the network again, once, on load.</li>
 * </ul>
 * Round 351: how the network is routed and laid - growing from the Capitol along the roads already there, upgrading
 * them rather than running beside them - lives in RoadNetwork, with the rules every other road now follows.
 * <p>
 * Held = the Capitol plus every town TownRestoration.isTownRestored() says is the player's, which is how a restored
 * town, a captured town and a captured Ring City are all recorded.
 */
public final class PlayerRoads {
    /** Bumped when the network rule changes and every save should lay it again. */
    public static final int NETWORK_VERSION = 3; // 2: the plazas (round 346c); 3: RoadNetwork's growth (round 351)

    private PlayerRoads() {
    }

    /** The player's holdings other than the Capitol itself. */
    public static List<PointOfInterest> heldTowns(World world, PointOfInterest capitol) {
        List<PointOfInterest> out = new ArrayList<>();
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (!RoadNetwork.isTownOrCapital(poi) || poi.getID().equals(capitol.getID()))
                continue;
            if (TownRestoration.isTownRestored(WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID())))
                out.add(poi);
        }
        return out;
    }

    /**
     * A town the player just restored or captured: its road from the Capitol's network, when the Capitol stands.
     * Returns false when there is no Capitol yet - the caller then links it by old road, as before round 346.
     */
    public static boolean connectTown(World world, PointOfInterest town, String why) {
        return RoadNetwork.connectPlayerTown(world, town, why);
    }

    /** The whole network from the Capitol: the Capitol raised. */
    public static void rebuildNetwork(World world, BiConsumer<Integer, Integer> onTileRepainted, String why) {
        RoadNetwork.rebuildPlayerNetwork(world, onTileRepainted, why, null);
    }

    /** On load: a save laid before round 351's road rules is normalized once (a standing Capitol's network with it). */
    public static void migrateOnLoad(World world) {
        if (world == null)
            return;
        RoadNetwork.migrateOnLoad(world);
        if (world.getPlayerRoadsBuilt() >= NETWORK_VERSION || TownRestoration.findCapitol() == null)
            return;
        rebuildNetwork(world, null, world.getPlayerRoadsBuilt() == 0 ? "a save from before the player roads"
                : "a save laid under network rule " + world.getPlayerRoadsBuilt() + ", now " + NETWORK_VERSION);
    }
}
