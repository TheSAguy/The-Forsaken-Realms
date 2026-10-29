package forge.adventure.util;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;

/**
 * Round 865 (the user: "I think it would be helpful it the "Pillaged" quest had a details section. Maybe inside that a
 * mini-map of the location"): the quest log's raid Details page shows this - a square of the world map centered on the
 * pillaged town.
 * <ul>
 *   <li>The ground is a crop of World.getBiomeImage(), the image MapViewScene shows: with fog of war on that is the fog
 *   overlay, so the crop shows exactly what the world map does (unexplored ground black, icons baked in). No renderer of
 *   its own - one small Pixmap, made into a Texture, freed by dispose().</li>
 *   <li>An orange ring: pillageSpawnRangeTiles, how near the player must be for raiders to come out.</li>
 *   <li>A red dot per raider out now (WorldStage.getPillageRaiders), only on a tile the player can see now
 *   (World.isCurrentlyVisible - the world map's mage-dot gate).</li>
 *   <li>The player's map portrait, pinned to the edge when the player is off the crop.</li>
 * </ul>
 */
public final class RaidMiniMap extends Group {
    /** Tiles shown beyond the raid ring on each side. */
    private static final int MARGIN_TILES = 4;
    private Texture texture;
    private int dots, hidden;

    public RaidMiniMap(TownPillage.Raid raid, float size) {
        setSize(size, size);
        World world = Current.world();
        Pixmap src = world.getBiomeImage();
        int ts = world.getTileSize();
        float pxPerWorld = src.getWidth() / (float) (world.getWidthInTiles() * ts);
        float halfWorld = (raid.rangeTiles + MARGIN_TILES) * ts;
        int side = Math.max(8, Math.round(2 * halfWorld * pxPerWorld));
        Vector2 c = raid.poi.getCenter();
        // the image is y-down: world y counts up from its bottom row
        int sx = Math.round(c.x * pxPerWorld) - side / 2;
        int sy = Math.round(src.getHeight() - c.y * pxPerWorld) - side / 2;
        Pixmap crop = new Pixmap(side, side, Pixmap.Format.RGBA8888);
        crop.setColor(0, 0, 0, 1);
        crop.fill();
        int x0 = Math.max(0, sx), y0 = Math.max(0, sy);
        int x1 = Math.min(src.getWidth(), sx + side), y1 = Math.min(src.getHeight(), sy + side);
        if (x1 > x0 && y1 > y0)
            crop.drawPixmap(src, x0, y0, x1 - x0, y1 - y0, x0 - sx, y0 - sy, x1 - x0, y1 - y0);
        crop.setColor(0.9f, 0.45f, 0.15f, 1f);
        int ring = Math.round(raid.rangeTiles * ts * pxPerWorld);
        crop.drawCircle(side / 2, side / 2, ring);
        crop.drawCircle(side / 2, side / 2, ring - 1);
        texture = new Texture(crop);
        crop.dispose();

        Image frame = new Image(Forge.getAssets().getWhiteTexture());
        frame.setColor(0.24f, 0.25f, 0.31f, 1f);
        frame.setBounds(-2, -2, size + 4, size + 4);
        addActor(frame);
        Image ground = new Image(texture);
        ground.setBounds(0, 0, size, size);
        addActor(ground);

        float left = c.x - halfWorld, bottom = c.y - halfWorld, scale = size / (2 * halfWorld);
        // the player first: a raider beside the player keeps its dot on top
        Vector2 me = WorldStage.getInstance().getPlayerSprite().pos();
        Image player = new Image(Current.player().avatar());
        player.setSize(12, 12);
        player.setPosition(clamp((me.x - left) * scale, size, 6) - 6, clamp((me.y - bottom) * scale, size, 6) - 6);
        addActor(player);
        for (EnemySprite raider : WorldStage.getInstance().getPillageRaiders(raid.id)) {
            float wx = raider.getX() + raider.getWidth() / 2f, wy = raider.getY() + raider.getHeight() / 2f;
            if (!world.isCurrentlyVisible((int) (wx / ts), (int) (wy / ts))) {
                hidden++;
                continue;
            }
            Image dot = new Image(Forge.getAssets().getTexture(Config.instance().getFile("ui/minimap_player.png")));
            dot.setColor(Color.RED);
            dot.setSize(6, 6);
            dot.setPosition(clamp((wx - left) * scale, size) - 3f, clamp((wy - bottom) * scale, size) - 3f);
            addActor(dot);
            dots++;
        }
    }

    private static float clamp(float v, float size) {
        return clamp(v, size, 3f);
    }

    private static float clamp(float v, float size, float pad) {
        return Math.max(pad, Math.min(size - pad, v));
    }

    /** Raiders drawn, and raiders out but in the fog. */
    public int dots() {
        return dots;
    }

    public int hidden() {
        return hidden;
    }

    public void dispose() {
        if (texture != null) {
            texture.dispose();
            texture = null;
        }
    }
}
