package forge.adventure.util;

import com.badlogic.gdx.utils.XmlReader;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.TileMapScene;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

/**
 * Round 400 (the user: "when doing a NG+ to ask the player if they want resources spent to restore towns and building to
 * be refunded also. If Yes, then calculate the cost of all buildings/towns/capitol and add that to the players starting
 * resources"; "Only active buildings"). What the player's standing towns and buildings cost to put up, at this run's prices
 * - read from the old world before New Game+ wipes it, while the old run's difficulty still sets the prices. Every town the
 * player holds: its restore fee (not for a town taken by force - TownRestoration.wasRestoredForAFee), the Capitol's raise,
 * and each building standing in it (EconomyBuildings.standingCost). A town lost to a color is no longer the player's and
 * counts for nothing. Research and blueprints are not buildings. Grep forge.log for [TFR-NewGamePlus].
 */
public final class NewGamePlusRefund {
    private NewGamePlusRefund() {}

    /** {gold, wood, stone, shards}. */
    public static int[] invested(World world) {
        int[] total = new int[4];
        if (world == null)
            return total;
        int towns = 0;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (poi.getData() == null)
                continue;
            PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            boolean capitol = TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name);
            if (changes == null || !(capitol || TownRestoration.isTownRestored(changes)))
                continue;
            int[] town = new int[4];
            StringBuilder what = new StringBuilder();
            if (capitol) { // Orazca restored, then raised
                EconomyBuildings.addScaled(town, TownRestoration.restoreCostBase());
                EconomyBuildings.addScaled(town, TownRestoration.capitolCostBase());
                what.append("restored + raised to the Capitol");
            } else if (TownRestoration.wasRestoredForAFee(poi, changes)) {
                EconomyBuildings.addScaled(town, TownRestoration.restoreCostBase());
                what.append("restored");
            } else {
                what.append("taken, no fee");
            }
            int buildings = 0;
            for (XmlReader.Element object : TownRestoration.readMapObjects(TileMapScene.resolveMapPath(poi))) {
                int[] cost = EconomyBuildings.standingCost(changes, object.getIntAttribute("id", -1),
                        object.getAttribute("template", ""), property(object, "commonShopList"),
                        TownRestoration.hasTrueProperty(object, "fixedShop"), capitol);
                if (cost == null)
                    continue;
                for (int i = 0; i < 4; i++)
                    town[i] += cost[i];
                buildings++;
            }
            for (int i = 0; i < 4; i++)
                total[i] += town[i];
            towns++;
            System.out.println("[TFR-NewGamePlus] invested in " + poi.getDisplayName() + " (" + what + ", " + buildings
                    + " building(s)): " + label(town));
        }
        System.out.println("[TFR-NewGamePlus] invested in " + towns + " held town(s): " + label(total)
                + " (difficulty " + Current.player().getDifficultyData().name + " prices)");
        return total;
    }

    /** Round 425: the towns the player holds - restored or taken, the Capitol counting as one, as invested() counts them. */
    public static int heldTowns(World world) {
        int towns = 0;
        if (world == null)
            return towns;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (poi.getData() == null)
                continue;
            PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            boolean capitol = TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name);
            if (changes != null && (capitol || TownRestoration.isTownRestored(changes)))
                towns++;
        }
        return towns;
    }

    public static String label(int[] c) {
        return c[0] + " gold, " + c[1] + " wood, " + c[2] + " stone, " + c[3] + " shards";
    }

    private static String property(XmlReader.Element object, String name) {
        XmlReader.Element properties = object.getChildByName("properties");
        if (properties == null)
            return null;
        for (XmlReader.Element property : properties.getChildrenByName("property"))
            if (name.equals(property.getAttribute("name", "")))
                return property.getAttribute("value", property.getText());
        return null;
    }
}
