package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TextraLabel;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.util.TreasureHunt;
import forge.adventure.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Round 478 - the treasure maps (util/TreasureHunt). The user: "one map button that when clicked, will open a new interface
 * showing the 6 biome maps. Then you click on each of those to see the progress/map itself." Opened from the inventory's
 * Treasure Maps button. Two views on one parchment page: the six maps side by side, each with its region and pieces found,
 * and one map large with what it means. Back from a map returns to the six; back from the six leaves.
 */
public class TreasureMapScene extends UIScene {
    private static TreasureMapScene object;
    private final TextraLabel titleLabel;
    private final Table area;
    private final List<Texture> textures = new ArrayList<>();
    private int shownRegion = -1; // -1 = the six maps

    private TreasureMapScene() {
        super(Forge.isLandscapeMode() ? "ui/treasure_map.json" : "ui/treasure_map_portrait.json");
        titleLabel = ui.findActor("title");
        area = ui.findActor("mapArea");
        com.badlogic.gdx.scenes.scene2d.Actor frame = ui.findActor("scrollWindow");
        if (frame != null)
            frame.setTouchable(Touchable.disabled); // InfoTextScene's round-151 lesson: a touched Window jumps to the front
        ui.onButtonPress("return", this::onReturn);
    }

    public static TreasureMapScene instance() {
        if (object == null)
            object = new TreasureMapScene();
        return object;
    }

    /** The inventory's Treasure Maps button. */
    public static void show() {
        show(-1);
    }

    /** One region's map straight away (console "treasure map <region>"); -1 = the six maps. */
    public static void show(int region) {
        instance().shownRegion = region;
        instance().rebuild();
        // Already showing: just the new view. Switching to itself pushed this scene onto the history a second time, and
        // the first Back then came back here (the user: "When I hit back ... they just go black, but don't exit").
        if (Forge.getCurrentScene() != instance())
            Forge.switchScene(instance(), true);
    }

    private void onReturn() {
        if (shownRegion >= 0) {
            shownRegion = -1;
            rebuild();
            return;
        }
        // The pictures stay until the next rebuild(): Back takes a screenshot of this page for the transition, and
        // freeing them first painted the six maps black on the way out.
        back();
    }

    private void disposeTextures() {
        for (Texture t : textures)
            t.dispose();
        textures.clear();
    }

    private Texture mapTexture(World world, int[] h) {
        Pixmap pixmap = TreasureHunt.renderMap(world, h);
        Texture texture = new Texture(pixmap);
        pixmap.dispose();
        textures.add(texture);
        return texture;
    }

    private void rebuild() {
        disposeTextures();
        area.clear();
        World world = Current.world();
        if (world == null)
            return;
        if (shownRegion >= 0) {
            int[] h = TreasureHunt.hunt(world, shownRegion);
            if (h != null) {
                buildOne(world, h);
                return;
            }
            shownRegion = -1;
        }
        buildAll(world);
    }

    private void buildAll(World world) {
        titleLabel.setText("Treasure Maps");
        boolean landscape = Forge.isLandscapeMode();
        int columns = landscape ? 3 : 2;
        float side = landscape ? 78f : 96f;
        int n = 0;
        for (int r = 0; r < TreasureHunt.REGIONS.length; r++) {
            final int region = r;
            int[] h = TreasureHunt.hunt(world, r);
            Table cell = new Table();
            if (h != null) {
                Image image = new Image(mapTexture(world, h));
                image.setTouchable(Touchable.enabled);
                image.addListener(new ClickListener() {
                    @Override
                    public void clicked(InputEvent event, float x, float y) {
                        shownRegion = region;
                        rebuild();
                    }
                });
                cell.add(image).size(side, side).row();
            } else {
                cell.add().size(side, side).row();
            }
            String state = h == null ? "no map" : h[TreasureHunt.H_FOUND] != 0 ? "claimed"
                    : h[TreasureHunt.H_FRAGS] + " of " + TreasureHunt.FRAGMENTS;
            TypingLabel label = Controls.newTypingLabel("[%80]" + TreasureHunt.REGION_NAMES[r] + " - " + state);
            label.setColor(Color.BLACK);
            label.skipToTheEnd();
            cell.add(label).padTop(1);
            area.add(cell).pad(2);
            if (++n % columns == 0)
                area.row();
        }
    }

    private void buildOne(World world, int[] h) {
        String name = TreasureHunt.REGION_NAMES[h[TreasureHunt.H_REGION]];
        int frags = h[TreasureHunt.H_FRAGS];
        titleLabel.setText("The " + name + " Map - " + frags + " of " + TreasureHunt.FRAGMENTS + " pieces");
        // The cost in the shard icon's own colors (round 367's pattern): black text, the icon WHITE (untinted).
        String cost = TreasureHunt.DIG_SHARDS + " [WHITE][+Shards][BLACK]";
        String text;
        if (h[TreasureHunt.H_FOUND] != 0)
            text = "You have claimed the " + name + " treasure.";
        else if (frags >= TreasureHunt.FRAGMENTS)
            text = "The map is whole. The X marks the spot, and it shows on your world map too: walk onto it and dig ("
                    + cost + ").";
        else if (frags > 0) // round 480: the first piece is the center, with the X
            text = "The X marks the treasure. Recognize that spot on the land and dig there with the Spade (" + cost
                    + " a dig, it finds anything within " + TreasureHunt.DIG_RADIUS + " tiles). Each " + name
                    + " obelisk adds a piece of the land around it - one a week, as an obelisk moves on after a week - and"
                    + " with all nine the X shows on your world map too.";
        else
            text = "No pieces yet. Look for the " + name + " obelisk somewhere in the " + name + " lands - it moves on after"
                    + " a week.";
        boolean landscape = Forge.isLandscapeMode();
        Image image = new Image(mapTexture(world, h));
        TypingLabel label = Controls.newTypingLabel("[BLACK]" + text);
        label.setWrap(true);
        label.skipToTheEnd();
        if (landscape) {
            area.add(image).size(185f, 185f).align(Align.top).padRight(8);
            area.add(label).width(190f).align(Align.topLeft);
        } else {
            area.add(image).size(240f, 240f).row();
            area.add(label).width(240f).padTop(6);
        }
    }

    @Override
    public void dispose() {
        disposeTextures();
    }
}
