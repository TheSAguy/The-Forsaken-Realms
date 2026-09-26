package forge.adventure.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.WorldStage;
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
 * <li>when the Capitol is raised (TownRestoration.upgradeToCapitol), every town the player holds gets a route from the
 *     Capitol - Dijkstra over every town and capital with cost = distance squared, the same rule as
 *     TerritoryControl.connectCapturedTownByRoad, so a chain of short hops through the towns roughly between beats one
 *     long line - and the player road is laid along it, the old road under it paved over (its bit cleared) tile for
 *     tile. Old roads the new road does not cover stay;</li>
 * <li>a town restored or captured later, while the Capitol stands, gets the same route instead of the old-road link
 *     (connectTown returns true and the caller skips connectCapturedTownByRoad);</li>
 * <li>a save from before this round with a Capitol already standing gets the network once on load (migrateOnLoad,
 *     World.playerRoadsBuilt).</li>
 * </ul>
 * Held = the Capitol plus every town TownRestoration.isTownRestored() says is the player's, which is how a restored
 * town, a captured town and a captured Ring City are all recorded.
 */
public final class PlayerRoads {
    /** Bumped when the network rule changes and every save should lay it again. */
    public static final int NETWORK_VERSION = 2; // 2: the plazas (round 346c)

    private PlayerRoads() {
    }

    private static boolean isTownOrCapital(PointOfInterest poi) {
        String type = poi == null || poi.getData() == null ? null : poi.getData().type;
        return "town".equals(type) || "capital".equals(type);
    }

    /** The player's holdings other than the Capitol itself. */
    public static List<PointOfInterest> heldTowns(World world, PointOfInterest capitol) {
        List<PointOfInterest> out = new ArrayList<>();
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (!isTownOrCapital(poi) || poi.getID().equals(capitol.getID()))
                continue;
            if (TownRestoration.isTownRestored(WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID())))
                out.add(poi);
        }
        return out;
    }

    /**
     * The waypoints from one town to another, through the towns roughly between: Dijkstra over every town and capital
     * (any allegiance), cost = distance squared, no edge across the barrier. Null when nothing joins them.
     */
    public static List<PointOfInterest> routeThroughTowns(World world, PointOfInterest from, PointOfInterest to) {
        List<PointOfInterest> nodes = new ArrayList<>();
        int source = -1, target = -1;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (!isTownOrCapital(poi))
                continue;
            if (poi.getID().equals(from.getID()))
                source = nodes.size();
            else if (poi.getID().equals(to.getID()))
                target = nodes.size();
            nodes.add(poi);
        }
        if (source < 0 || target < 0)
            return null;
        int n = nodes.size();
        double[] best = new double[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        java.util.Arrays.fill(prev, -1);
        best[source] = 0;
        for (int iter = 0; iter < n; iter++) {
            int u = -1;
            double uBest = Double.MAX_VALUE;
            for (int i = 0; i < n; i++)
                if (!done[i] && best[i] < uBest) {
                    uBest = best[i];
                    u = i;
                }
            if (u < 0)
                break;
            done[u] = true;
            if (u == target)
                break;
            for (int v = 0; v < n; v++) {
                if (done[v] || world.roadLineCrossesBarrier(nodes.get(u), nodes.get(v)))
                    continue;
                double cost = best[u] + nodes.get(u).getPosition().dst2(nodes.get(v).getPosition());
                if (cost < best[v]) {
                    best[v] = cost;
                    prev[v] = u;
                }
            }
        }
        if (prev[target] < 0)
            return null;
        List<PointOfInterest> route = new ArrayList<>();
        for (int i = target; i >= 0; i = prev[i])
            route.add(nodes.get(i));
        java.util.Collections.reverse(route);
        return route;
    }

    private static String routeText(List<PointOfInterest> route) {
        StringBuilder sb = new StringBuilder();
        for (PointOfInterest poi : route) {
            if (sb.length() > 0)
                sb.append(" -> ");
            sb.append(poi.getDisplayName());
        }
        return sb.toString();
    }

    private static int layRoute(World world, PointOfInterest capitol, PointOfInterest town,
                                BiConsumer<Integer, Integer> onTileRepainted, String why) {
        List<PointOfInterest> route = routeThroughTowns(world, capitol, town);
        if (route == null) {
            System.out.println("[TFR-Roads] player road (" + why + "): no route from " + capitol.getDisplayName() + " to "
                    + town.getDisplayName() + " (the barrier, or a town off the graph)");
            return 0;
        }
        int laid = world.buildPlayerRoad(route, onTileRepainted);
        int pavedOver = world.lastRoadPavedOver();
        // Round 346c: a plaza under each end - the Capitol and the held town - a tile or two out from the icon.
        int margin = Config.instance().getTuningData().playerRoadTownPatch;
        int plaza = world.stampPlayerRoadPatch(capitol, margin, onTileRepainted);
        pavedOver += world.lastRoadPavedOver();
        plaza += world.stampPlayerRoadPatch(town, margin, onTileRepainted);
        pavedOver += world.lastRoadPavedOver();
        System.out.println("[TFR-Roads] player road (" + why + "): " + routeText(route) + " - " + laid
                + " tile(s) laid, " + plaza + " plaza tile(s), " + pavedOver + " old road tile(s) paved over");
        return laid + plaza;
    }

    private static BiConsumer<Integer, Integer> liveRepaint() {
        WorldStage stage = WorldStage.getInstance();
        return stage == null ? null : stage::refreshBackgroundTile;
    }

    /**
     * A town the player just restored or captured: its road from the Capitol, when one stands. Returns false when
     * there is no Capitol yet - the caller then links it by old road, as before this round.
     */
    public static boolean connectTown(World world, PointOfInterest town, String why) {
        PointOfInterest capitol = TownRestoration.findCapitol();
        if (world == null || capitol == null || town == null)
            return false;
        if (!town.getID().equals(capitol.getID()))
            layRoute(world, capitol, town, liveRepaint(), why);
        return true;
    }

    /** The whole network from the Capitol: a route to every held town. The Capitol raised, or a save migrating. */
    public static void rebuildNetwork(World world, BiConsumer<Integer, Integer> onTileRepainted, String why) {
        PointOfInterest capitol = TownRestoration.findCapitol();
        if (world == null || capitol == null)
            return;
        List<PointOfInterest> held = heldTowns(world, capitol);
        int laid = 0;
        for (PointOfInterest town : held)
            laid += layRoute(world, capitol, town, onTileRepainted, why);
        world.setPlayerRoadsBuilt(NETWORK_VERSION);
        System.out.println("[TFR-Roads] player road network (" + why + "): " + held.size() + " held town(s) routed from "
                + capitol.getDisplayName() + ", " + laid + " tile(s) laid in all");
    }

    /** A save whose Capitol predates the player roads lays its network once. */
    public static void migrateOnLoad(World world) {
        if (world == null || world.getPlayerRoadsBuilt() >= NETWORK_VERSION)
            return;
        if (TownRestoration.findCapitol() == null)
            return; // laid when the Capitol is raised
        rebuildNetwork(world, null, world.getPlayerRoadsBuilt() == 0 ? "a save from before the player roads"
                : "a save laid under network rule " + world.getPlayerRoadsBuilt() + ", now " + NETWORK_VERSION);
    }
}
