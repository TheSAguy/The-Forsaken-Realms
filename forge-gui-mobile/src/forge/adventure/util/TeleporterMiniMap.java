package forge.adventure.util;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.github.tommyettinger.textra.TextraButton;
import forge.Forge;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.gui.GuiBase;

/**
 * Round 712 (the user: "Just like in TFR2, create a small mini-map to show where the teleport location will be sending
 * you to"). The Teleporter dialog's two mini-maps, as TFR 2's seat card draws them (Tfr2MapPair): the whole world with
 * the destination ringed red with a white center, and a close-up of the land around it, a tenth of the world across.
 * Both come from the HUD minimap's own picture (World.getBiomeImage(), fog included) inside the HUD's minimap border,
 * and the world map shows where you stand with the HUD's player dot.
 * <p>
 * The maps follow the destination the player is on: the mouse's hover, the keyboard or controller focus, and on touch
 * the tapped button - a first tap shows that destination (and focuses its button), a second tap on it travels (the
 * user: "tap once to show the destination on the maps, tap again to travel"). Logs each change as [TFR-Teleport].
 */
final class TeleporterMiniMap {
    /** Each map's size in UI units (the HUD minimap's) and its picture's pixels (3 per unit, as the HUD's tooltip). */
    static final float SIZE = 80f;
    private static final int PX = 240;

    private final World world;
    private final Pixmap biome;
    private final Vector2 home;
    private final Pixmap worldBase;
    private Texture worldTex, closeTex;
    private final Image worldImage = new Image(), closeImage = new Image();
    private PointOfInterest shown, picked;

    /** home: where you stand (the town you are in), for the player dot; null shows none. */
    TeleporterMiniMap(Vector2 home) {
        world = WorldSave.getCurrentSave().getWorld();
        biome = world.getBiomeImage();
        this.home = home;
        worldBase = new Pixmap(PX, PX, Pixmap.Format.RGBA8888);
        worldBase.setFilter(Pixmap.Filter.BiLinear);
        worldBase.drawPixmap(biome, 0, 0, biome.getWidth(), biome.getHeight(), 0, 0, PX, PX);
    }

    /** The world map, framed (its player dot included). */
    Group worldMap() {
        Group g = framed(worldImage);
        if (home != null) {
            Image dot = new Image(Forge.getAssets().getTexture(Config.instance().getFile("ui/minimap_player.png")));
            dot.setPosition(SIZE * fx(home.x) - dot.getWidth() / 2, SIZE * (1 - fy(home.y)) - dot.getHeight() / 2);
            dot.setTouchable(Touchable.disabled);
            g.addActor(dot);
        }
        return g;
    }

    /** The close-up, framed. */
    Group closeUp() {
        return framed(closeImage);
    }

    private static Group framed(Image map) {
        Group g = new Group();
        g.setSize(SIZE, SIZE);
        g.setTouchable(Touchable.disabled);
        map.setBounds(0, 0, SIZE, SIZE);
        g.addActor(map);
        Image border = new Image(Forge.getAssets().getTexture(Config.instance().getFile("ui/minimap.png")));
        border.setBounds(0, 0, SIZE, SIZE);
        g.addActor(border);
        return g;
    }

    /**
     * Makes a destination button follow the maps: hover and focus show its destination; a click travels, except on touch
     * where the first tap on a destination only shows it. Returns the action the button's click should run.
     */
    Runnable follow(TextraButton button, PointOfInterest dest, Runnable travel) {
        button.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (pointer == -1)  // the mouse moving over it, not a touch going down
                    pick(dest, "hover");
            }
        });
        button.addListener(new FocusListener() {
            @Override
            public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean focused) {
                if (focused && event.getTarget() == button)
                    pick(dest, "focus");
            }
        });
        return () -> {
            if (GuiBase.isAndroid() && picked != dest) {
                pick(dest, "tap");
                if (button.getStage() != null)
                    button.getStage().setKeyboardFocus(button);  // the focused look marks the one a second tap sends you to
                return;
            }
            System.out.println("[TFR-Teleport] travel to " + dest.getDisplayName());
            travel.run();
        };
    }

    /** Shows dest without picking it (the dialog's first look: on touch, the first tap still only shows). */
    void show(PointOfInterest dest) {
        draw(dest, "open");
    }

    private void pick(PointOfInterest dest, String how) {
        picked = dest;
        if (dest != shown)
            draw(dest, how);
    }

    private void draw(PointOfInterest dest, String how) {
        shown = dest;
        Vector2 p = dest.getPosition();
        float fx = fx(p.x), fy = fy(p.y);

        Pixmap w = new Pixmap(PX, PX, Pixmap.Format.RGBA8888);
        w.drawPixmap(worldBase, 0, 0);
        mark(w, Math.round(fx * PX), Math.round((1 - fy) * PX));
        worldTex = swap(worldImage, worldTex, w);

        // the close-up: a tenth of the world across, kept inside the world at its edges (the ring then moves off center)
        int span = Math.max(8, biome.getWidth() / 10);
        int bx = Math.round(fx * biome.getWidth()), by = Math.round((1 - fy) * biome.getHeight());
        int sx = Math.max(0, Math.min(biome.getWidth() - span, bx - span / 2));
        int sy = Math.max(0, Math.min(biome.getHeight() - span, by - span / 2));
        Pixmap c = new Pixmap(PX, PX, Pixmap.Format.RGBA8888);
        c.setFilter(Pixmap.Filter.NearestNeighbour);
        c.drawPixmap(biome, sx, sy, span, span, 0, 0, PX, PX);
        ring(c, (bx - sx) * PX / span, (by - sy) * PX / span);
        closeTex = swap(closeImage, closeTex, c);

        System.out.println("[TFR-Teleport] mini-map on " + dest.getDisplayName() + " (" + how + "): tile "
                + (int) (p.x / world.getTileSize()) + "," + (int) (p.y / world.getTileSize())
                + ", close-up " + span + " px from " + sx + "," + sy + " of " + biome.getWidth());
    }

    /** The share of the world's width / height (y up) at a world position. */
    private float fx(float x) {
        return x / (world.getTileSize() * (float) world.getWidthInTiles());
    }

    private float fy(float y) {
        return y / (world.getTileSize() * (float) world.getHeightInTiles());
    }

    private static Texture swap(Image image, Texture old, Pixmap pixmap) {
        Texture t = new Texture(pixmap);
        pixmap.dispose();
        image.setDrawable(new TextureRegionDrawable(t));
        if (old != null)
            old.dispose();
        return t;
    }

    /** TFR 2's seat mark on the world map: a red disc with a white center, outlined dark so it reads on any land. */
    private static void mark(Pixmap m, int x, int y) {
        m.setColor(0.08f, 0.05f, 0.05f, 1f);
        m.fillCircle(x, y, 10);
        m.setColor(0.85f, 0.1f, 0.1f, 1f);
        m.fillCircle(x, y, 8);
        m.setColor(1f, 1f, 1f, 1f);
        m.fillCircle(x, y, 4);
    }

    /** On the close-up an open ring, so the town's own icon shows inside it. */
    private static void ring(Pixmap m, int x, int y) {
        for (int dy = -27; dy <= 27; dy++)
            for (int dx = -27; dx <= 27; dx++) {
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d < 20 || d > 27)
                    continue;
                if (d < 22 || d > 25)
                    m.setColor(0.08f, 0.05f, 0.05f, 1f);
                else
                    m.setColor(0.85f, 0.1f, 0.1f, 1f);
                m.drawPixel(x + dx, y + dy);
            }
    }

    /** Frees the pictures (the dialog was rebuilt or closed). */
    void dispose() {
        worldBase.dispose();
        if (worldTex != null)
            worldTex.dispose();
        if (closeTex != null)
            closeTex.dispose();
        worldTex = closeTex = null;
    }
}
