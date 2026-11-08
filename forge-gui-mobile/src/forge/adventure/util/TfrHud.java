package forge.adventure.util;

import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;

/**
 * Round 989: the HUD inside a cave or dungeon, ported from TFR 2's Tfr2Hud (the user, 2026-11-08: "In TFR2, we had the
 * HUD minimized when entering a dungeon. I'd like that in TFR1 also."). Entering a cave or dungeon map (the root point's
 * type "cave" or "dungeon") starts the HUD in its hidden view, the one a double tap on the avatar toggles
 * (GameHUD.showHideHUD): only the avatar shows. The player can still toggle it inside; a next level keeps whatever they
 * chose there. Every dungeon starts hidden again (nothing saved). Leaving puts the HUD back as it was on the world map.
 * Towns and other maps are untouched. Hooks: MapStage.loadMap (mapLoaded) and MapStage.clearIsInMap (mapLeft). [TFR-Hud]
 */
public final class TfrHud {
    private TfrHud() {
    }

    /** The HUD's hidden view, as this class sees it (GameHUD in the game, a fake in TfrHudTest). */
    interface View {
        boolean hidden();

        void hide(boolean hide);
    }

    /** Inside a cave or dungeon map that this class hid the HUD for. */
    private static boolean inside;
    /** The world map's HUD view when the player went in: hidden or not. */
    private static boolean worldHidden;

    private static final View GAME = new View() {
        @Override
        public boolean hidden() {
            return GameHUD.getInstance().isHudHidden();
        }

        @Override
        public void hide(boolean hide) {
            GameHUD.getInstance().showHideHUD(hide);
        }
    };

    /** MapStage.loadMap, once the map stands: the first level of a dungeon hides the HUD, a later level keeps the choice. */
    public static void mapLoaded(String targetMap) {
        mapLoaded(GAME, dungeonKind(), targetMap);
    }

    /** MapStage.clearIsInMap, after showHideMap(true): the world map's view comes back. */
    public static void mapLeft() {
        mapLeft(GAME);
    }

    static void mapLoaded(View hud, String kind, String targetMap) {
        if (inside) {
            // showHideMap(false) at each level's load fades the HUD back in: put the player's hidden view back
            if (hud.hidden())
                hud.hide(true);
            System.out.println("[TFR-Hud] next level " + targetMap + ": " + (hud.hidden() ? "kept hidden" : "kept shown"));
            return;
        }
        if (kind == null)
            return;
        inside = true;
        worldHidden = hud.hidden();
        hud.hide(true);
        System.out.println("[TFR-Hud] entered a " + kind + " (" + targetMap + "): HUD hidden; the world map's was "
                + (worldHidden ? "hidden" : "shown"));
    }

    static void mapLeft(View hud) {
        if (!inside)
            return;
        inside = false;
        boolean was = hud.hidden();
        hud.hide(worldHidden);   // always: showHideMap(true) has just faded every group back in
        System.out.println("[TFR-Hud] left the dungeon: HUD " + (worldHidden ? "hidden" : "shown") + " as on the world map"
                + " (it was " + (was ? "hidden" : "shown") + " inside)");
    }

    /** "cave" or "dungeon" for those point types, else null. */
    static String kindOf(String type) {
        return "cave".equals(type) || "dungeon".equals(type) ? type : null;
    }

    /** "cave" or "dungeon" when the map entered is one, else null. */
    private static String dungeonKind() {
        PointOfInterest root = TileMapScene.instance().rootPoint;
        if (root == null || root.getData() == null)
            return null;
        return kindOf(root.getData().type);
    }

    /** Test reset. */
    static void reset() {
        inside = false;
        worldHidden = false;
    }

    /** Console "hud [state|toggle|hide|show]": the HUD's hidden view. */
    public static String console(String[] s) {
        String what = s.length > 0 ? s[0] : "state";
        if (what.equals("toggle"))
            GAME.hide(!GAME.hidden());
        else if (what.equals("hide"))
            GAME.hide(true);
        else if (what.equals("show"))
            GAME.hide(false);
        else if (!what.equals("state"))
            return "hud [state|toggle|hide|show]";
        String line = "HUD " + (GAME.hidden() ? "hidden" : "shown") + ", in a map " + MapStage.getInstance().isInMap()
                + ", dungeon " + (inside ? dungeonKind() : "no") + ", the world map's " + (worldHidden ? "hidden" : "shown");
        System.out.println("[TFR-Hud] state" + (what.equals("state") ? "" : " after " + what) + ": " + line);
        return line;
    }
}
