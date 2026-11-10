package forge.adventure.util;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import forge.adventure.data.EnemyData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.stage.PointOfInterestMapSprite;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Round 1001 (the tooltip audit's round D, spots 33-35 and 59): hover tips on the world map's tokens - an AI town's guard
 * dots, a ruined town, an obelisk, a treasure's X, and the pickups (resource piles, the Mystery diamond, the chest).
 * Not regions: the world map has hundreds of tokens and they come and go daily, so TfrTips asks {@link #at} for the
 * token under the mouse each frame (only on the world map, with no dialog holding the input and nothing of the HUD
 * under the mouse) and draws its usual box on the HUD's stage, above the token. A token the player can't see right now
 * (fog) has no tip, as it has no sprite. Its text is a tips.json key, numbers from tuning or the token's own data.
 * The plan said TfrPlates; the tip box was chosen instead so these honor the tooltip setting and look like every other
 * tip. [TFR-Tips].
 */
public final class TfrMapTips {
    private TfrMapTips() {
    }

    /** A token's tip: its tag, its text, and the world box it occupies (x, y, w, h). */
    public static final class Hit {
        public final String tag;
        public final Supplier<String> text;
        public final float x, y, w, h;
        public final String key;

        Hit(String tag, Supplier<String> text, float x, float y, float w, float h) {
            this.tag = tag;
            this.text = text;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            key = tag + "@" + Math.round(x) + "," + Math.round(y);
        }

        boolean contains(float wx, float wy) {
            return wx >= x && wy >= y && wx <= x + w && wy <= y + h;
        }
    }

    /** The token under the world point (WorldStage coordinates), or null. A place's sprite last: a pickup on its edge wins. */
    public static Hit at(float wx, float wy) {
        for (Hit h : tokens())
            if (h.contains(wx, wy))
                return h;
        return null;
    }

    /** tips hover TAG: the first token of that tag on screen (a scripted look), or null. */
    public static Hit first(String tag) {
        WorldStage ws = WorldStage.getInstance();
        com.badlogic.gdx.graphics.Camera cam = ws.getCamera();
        for (Hit h : tokens())
            if (h.tag.equals(tag) && cam.frustum.pointInFrustum(h.x + h.w / 2, h.y + h.h / 2, 0))
                return h;
        return null;
    }

    static List<Hit> tokens() {
        List<Hit> out = new ArrayList<>();
        World world = WorldSave.getCurrentSave() == null ? null : WorldSave.getCurrentSave().getWorld();
        if (world == null)
            return out;
        int ts = world.getTileSize();
        int day = world.getCurrentDay();
        // the pickups (ResourceSpawns: {tileX, tileY, type, value, expiryDay}) - only where the player sees right now
        for (int[] s : world.getResourceSpawns()) {
            if (!seen(world, s[0], s[1]))
                continue;
            int type = s[2], value = s[3], days = Math.max(0, s[4] - day);
            String tag = type == ResourceSpawns.TYPE_MYSTERY ? "map.mystery" : type == ResourceSpawns.TYPE_CHEST ? "map.chest" : "map.pile";
            out.add(new Hit(tag, () -> pileText(type, value, days), s[0] * ts, s[1] * ts, ts, ts));
        }
        // the obelisks (one tile wide, two high) and the treasures' X marks
        if (TreasureHunt.isEnabled()) {
            for (int[] h : world.getTreasureHunts()) {
                if (h[TreasureHunt.H_OBACTIVE] == 0 || h[TreasureHunt.H_FOUND] != 0 || h[TreasureHunt.H_FRAGS] >= TreasureHunt.FRAGMENTS)
                    continue;
                int x = h[TreasureHunt.H_OBX], y = h[TreasureHunt.H_OBY];
                if (seen(world, x, y))
                    out.add(new Hit("map.obelisk", () -> TfrTips.text("map.obelisk", "days", TreasureHunt.OBELISK_DAYS),
                            x * ts, y * ts, ts, ts * 2f));
            }
            for (int[] x : TreasureHunt.xMarks(world)) // a map mark: drawn whatever the fog
                out.add(new Hit("map.xmark", () -> TfrTips.text("map.xmark"), x[0] * ts, x[1] * ts, ts, ts));
        }
        // the places: an AI town's guards, a ruined town
        collectPlaces(WorldStage.getInstance().getRoot(), world, out, 0);
        return out;
    }

    private static boolean seen(World world, int tx, int ty) {
        return world.isExploredWorld(tx, ty) && world.isCurrentlyVisible(tx, ty);
    }

    private static void collectPlaces(Group g, World world, List<Hit> out, int depth) {
        if (depth > 3)
            return;
        for (Actor a : g.getChildren()) {
            if (a instanceof PointOfInterestMapSprite) {
                Hit h = place((PointOfInterestMapSprite) a, world);
                if (h != null)
                    out.add(h);
            } else if (a instanceof Group) {
                collectPlaces((Group) a, world, out, depth + 1);
            }
        }
    }

    private static Hit place(PointOfInterestMapSprite sprite, World world) {
        PointOfInterest poi = sprite.getPointOfInterest();
        if (poi == null || poi.getData() == null || !sprite.isVisible())
            return null;
        int ts = world.getTileSize();
        Vector2 p = poi.getPosition();
        if (!world.isExploredWorld((int) (p.x / ts), (int) (p.y / ts)))
            return null;
        PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
        String[] guards = sprite.aiGuardDots(changes);
        Vector2 lo = sprite.localToStageCoordinates(new Vector2(0, 0));
        float x = lo.x, y = lo.y, w = sprite.getWidth(), h = sprite.getHeight();
        String name = poi.getDisplayName();
        if (guards != null) {
            boolean capital = "capital".equals(poi.getData().type);
            String rank = EnemyData.tierDisplayName(guards[0]);
            return new Hit(capital ? "map.guardCapital" : "map.guard", () -> TfrTips.text(capital ? "map.guardCapital" : "map.guard",
                    "town", name, "rank", rank, "n", guards.length), x, y, w, h);
        }
        if (TownRestoration.isWastelandTown(poi.getData()) && !TownRestoration.isTownRestored(changes))
            return new Hit("map.ruin", () -> TfrTips.text("map.ruin", "town", name), x, y, w, h);
        return null;
    }

    /** A pile's, the Mystery's or the chest's words, with what it holds and the days it has left. */
    static String pileText(int type, int value, int days) {
        if (type == ResourceSpawns.TYPE_MYSTERY)
            return TfrTips.text("map.mystery", "blueprint", pct(ResourceSpawns.MYSTERY_BLUEPRINT_CHANCE),
                    "ambush", pct(ResourceSpawns.MYSTERY_AMBUSH_CHANCE), "days", days);
        if (type == ResourceSpawns.TYPE_CHEST)
            return TfrTips.text("map.chest", "days", days);
        String glyph = type == ResourceSpawns.TYPE_GOLD ? "[+Gold]" : type == ResourceSpawns.TYPE_SHARDS ? "[+Shards]"
                : type == ResourceSpawns.TYPE_WOOD ? "[+Wood]" : "[+Stone]";
        int amount = type == ResourceSpawns.TYPE_GOLD ? value : value + ResourceSpawns.OTHER_PICKUP_BONUS;
        return TfrTips.text("map.pile", "res", glyph, "n", amount, "days", days);
    }

    private static String pct(float f) {
        return TfrTips.num(Math.round(f * 100f));
    }
}
