package forge.adventure.util;

import com.badlogic.gdx.scenes.scene2d.Actor;
import forge.adventure.data.ItemData;

/**
 * Round 993: the world-map HUD's hover tips (the tooltip audit's round A, spots 1-15): one call from the end of GameHUD's
 * constructor names its parts, and each gets its tips.json text through {@link TfrTips#region}. The HUD shows on the
 * world map and in town maps, so these regions use the null scene (TfrTips: GameScene or TileMapScene). [TFR-Tips].
 */
public final class TfrHudTips {
    private TfrHudTips() {
    }

    /** GameHUD's constructor: every HUD part with a tip. A null part (a plane without it) is skipped. */
    public static void register(Actor miniMap, Actor clock, Actor speedUp, Actor waitBox, Actor resources, Actor ascendance,
                                Actor avatar, Actor gold, Actor shards, Actor life, Actor treasureMaps, Actor info, Actor log,
                                Actor logbook, Actor bookmark, Actor zoom, Actor deck, Actor inventory, Actor menu, Actor exit) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(null, miniMap, "hud.minimap");
        TfrTips.region(null, clock, "hud.clock");
        TfrTips.region(null, speedUp, "hud.speedUp");
        TfrTips.region(null, waitBox, "hud.wait");
        // the wood row is the panel's top half, stone the bottom (ResourceDisplayActor, y up)
        TfrTips.region(null, resources, "hud.wood", new float[]{0, 0.5f, 1, 0.5f}, () -> TfrTips.text("hud.wood"));
        TfrTips.region(null, resources, "hud.stone", new float[]{0, 0, 1, 0.5f}, () -> TfrTips.text("hud.stone"));
        TfrTips.region(null, ascendance, "hud.ascendance");
        TfrTips.region(null, avatar, "hud.avatar");
        TfrTips.region(null, gold, "hud.gold");
        TfrTips.region(null, shards, "hud.shards");
        TfrTips.region(null, life, "hud.life");
        TfrTips.region(null, treasureMaps, "hud.treasureMaps");
        TfrTips.region(null, info, "hud.info");
        TfrTips.region(null, log, "hud.log");
        TfrTips.region(null, logbook, "hud.logbook");
        TfrTips.region(null, bookmark, "hud.bookmark");
        TfrTips.region(null, zoom, "hud.zoom");
        TfrTips.region(null, deck, "hud.deck");
        TfrTips.region(null, inventory, "hud.inventory");
        TfrTips.region(null, menu, "hud.menu");
        TfrTips.region(null, exit, "hud.exit");
        System.out.println("[TFR-Tips] the HUD's tips registered");
    }

    /** GameHUD.setAbilityButton: the rune's tip - the item's name, what it does, its cost and where it works. */
    public static void ability(Actor button, ItemData item) {
        if (button == null || item == null || !TfrTips.enabled())
            return;
        TfrTips.region(null, button, "hud.ability", () -> {
            // the item's own text without its trailing "5 [+Shards]" cost (the tip says the cost, icon first)
            String desc = item.getDescription() == null ? "" : item.getDescription().replaceAll("\\d+ \\[\\+Shards]\\s*$", "").trim();
            String where = item.usableOnWorldMap && item.usableInPoi ? TfrTips.text("hud.abilityAnywhere")
                    : item.usableInPoi ? TfrTips.text("hud.abilityInPlaces") : TfrTips.text("hud.abilityWorldMap");
            return TfrTips.text("hud.ability", "name", item.getDisplayName(), "effect", desc.isEmpty() ? "" : "\n" + desc,
                    "cost", item.shardsNeeded, "where", where == null ? "" : where);
        });
    }
}
