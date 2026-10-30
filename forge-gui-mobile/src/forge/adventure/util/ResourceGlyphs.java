package forge.adventure.util;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import forge.Forge;
import forge.Graphics;
import forge.adventure.player.AdventurePlayer;
import forge.assets.FSkinImageInterface;

/**
 * Round 881 (the user: "That should be a rule for all display of resources"): resource icons for the text the [+Gold]
 * markup cannot reach. FOptionPane and FButton draw through the engine's TextRenderer, which knows {W}-style symbols,
 * not TextraLabel's markup, so these register the items atlas's regions as {Gold}, {Shards}, {Wood} and {Stone}.
 * Everything drawn by a TextraLabel or TypingLabel keeps using [+Gold].
 */
public final class ResourceGlyphs {
    private static final String[] NAMES = {"Gold", "Shards", "Wood", "Stone"};

    private ResourceGlyphs() {
    }

    /** On the render thread (it may load the atlas). Again on every entry: Assets clears the symbols on a reload. */
    public static void register() {
        for (String name : NAMES) {
            Sprite sprite = Config.instance().getItemSprite(name);
            if (sprite != null)
                Forge.getAssets().symbolLookup().put(name, new Symbol(sprite));
        }
    }

    /** Round 886: "[+Reputation]" for a label, the word in a plane whose atlas has no laurel (an unknown tag draws a "+"). */
    public static String reputation() {
        return AdventurePlayer.resolveStatusGlyph("Reputation") != null ? "[+Reputation]" : "reputation";
    }

    /** "300 {Gold}" for a popup or its buttons, safe off the render thread; the plain word if no symbol is registered. */
    public static String amount(int count, String name) {
        return count + " " + (Forge.getAssets().symbolLookup().containsKey(name) ? "{" + name + "}" : name.toLowerCase());
    }

    /** A translated popup text that says "N gold": the same words with the coin. On the render thread. */
    public static String goldInText(String text, int count) {
        register();
        return text.replace(count + " gold", amount(count, "Gold"));
    }

    private static final class Symbol implements FSkinImageInterface {
        private final TextureRegion region;

        Symbol(TextureRegion region) {
            this.region = region;
        }

        @Override
        public void load(Pixmap preferredIcons) {
        }

        @Override
        public TextureRegion getTextureRegion() {
            return region;
        }

        @Override
        public float getNearestHQWidth(float baseWidth) {
            return baseWidth;
        }

        @Override
        public float getNearestHQHeight(float baseHeight) {
            return baseHeight;
        }

        @Override
        public float getWidth() {
            return region.getRegionWidth();
        }

        @Override
        public float getHeight() {
            return region.getRegionHeight();
        }

        @Override
        public void draw(Graphics g, float x, float y, float w, float h) {
            g.drawImage(region, x, y, w, h);
        }
    }
}
