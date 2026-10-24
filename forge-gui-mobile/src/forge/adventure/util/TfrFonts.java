package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.github.tommyettinger.textra.Font;
import com.github.tommyettinger.textra.Layout;
import forge.adventure.scene.Scene;
import forge.assets.FSkin;
import forge.localinstance.properties.ForgeConstants;

import java.util.Locale;

/**
 * Round 723: TFR 2's Clean font brought to TFR 1 (the user, 2026-10-24: "I like the font we've implemented in TFR2, can
 * we do the same for TFR1"; the preview and plan approved with every recommendation). A port of TFR 2's Tfr2Fonts,
 * without its trial-only mixed mode.
 * <ul>
 *   <li>Clean, the default: the labels and buttons of the Adventure screens (Controls.getTextraFont) in the duel
 *   screen's font, the default skin's font1.ttf (Samsung Sharp Sans Medium, already shipped by Forge for the duel
 *   screen). The 480x270 stage is stretched over the window, so FreeType draws the font k times larger (k: the window
 *   over the stage, 8 at 3840x2130, 4 on a 2400x1080 phone) and it is shown at 1/k with mipmaps: sharp at any window.
 *   9 stage px on LanaPixel's line of 14, so line spacing and the [+Gold]-style icons (sized to the line) stay.</li>
 *   <li>Pixel: LanaPixel, the stock Adventure font, as before. Settings offers the choice (restart).</li>
 *   <li>Only on a plane with config.json cleanFontEnabled, and only for a Latin-script UI language: Samsung Sharp Sans
 *   has no Cyrillic, Chinese, Japanese or Korean, which LanaPixel covers, so Russian, Chinese, Japanese and Korean keep
 *   LanaPixel.</li>
 *   <li>The symbols Samsung Sharp Sans lacks (PIXEL_ONLY: the quest log's and auto-sell's check boxes, the HUD's
 *   disabled cross, the gender signs) are drawn in LanaPixel through a font family ([@Pixel]...[@]).</li>
 *   <li>The glyphs are packed 2k px apart: text drawn smaller than the k-times glyphs reads a smaller mipmap, where
 *   libGDX's 1 px gap is gone and a neighbor's edge bleeds in (TFR 2's reward card "2|2 Gold").</li>
 * </ul>
 * Text fields, lists and Forge's own screens keep their fonts. Switches for scripted runs: -Dtfr.font=pixel|clean,
 * -Dtfr.fontSize (stage px), -Dtfr.fontY (glyphs moved down, stage px), -Dtfr.fontLine (line height, stage px),
 * -Dtfr.pixelY (the LanaPixel symbols moved down, px).
 * Log: [TFR-Font].
 */
public final class TfrFonts {
    private TfrFonts() {
    }

    /** SettingData.tfrFont's values. */
    public static final String CLEAN = "Clean", PIXEL = "Pixel";
    /** Latin Extended-A (card names like "Bespoke Bō", Shandalar's "Š" names) and the keyboard's shift arrow, on top of
     *  FreeType's ASCII + Latin-1 and the punctuation TFR's text uses. */
    private static final String EXTRA_CHARS = "—–→←↑…‘’“”•≈−★☆" + range('Ā', 'ſ');
    /** Symbols the clean font has no glyph for; LanaPixel has them all. */
    static final String PIXEL_ONLY = "☐☑☓♂♀";
    /** UI languages Samsung Sharp Sans covers (ASCII + Latin-1). */
    private static final String[] LATIN = {"en", "de", "es", "fr", "it", "pt"};
    private static boolean active;

    private static String range(char from, char to) {
        StringBuilder sb = new StringBuilder();
        for (char c = from; c <= to; c++)
            sb.append(c);
        return sb.toString();
    }

    /** The clean font was made: RewardActor then draws a reward card's title over the card at screen resolution, not
     *  into the card's 192 x 256 picture. */
    public static boolean active() {
        return active;
    }

    /** Settings shows the Font choice: the plane opts in. */
    public static boolean offered() {
        return Config.instance().getConfigData().cleanFontEnabled;
    }

    /** "clean" or "pixel", and why. */
    static String[] mode() {
        String p = System.getProperty("tfr.font");
        if (p != null && !p.isEmpty())
            return new String[]{p.toLowerCase(Locale.ROOT), "-Dtfr.font"};
        if (!offered())
            return new String[]{"pixel", "plane"};
        if (PIXEL.equalsIgnoreCase(Config.instance().getSettingData().tfrFont))
            return new String[]{"pixel", "setting"};
        String lang = Config.instance().getLang();
        String l = lang == null ? "en" : lang.toLowerCase(Locale.ROOT);
        for (String latin : LATIN)
            if (l.startsWith(latin))
                return new String[]{"clean", "setting, " + lang};
        return new String[]{"pixel", "language " + lang};
    }

    /**
     * The font Controls.getTextraFont() hands out in Clean mode; null in Pixel mode, or when the clean font can't be made
     * (the stock pixel font then stays). pixel: the skin's LanaPixel; items: the icons' atlas.
     */
    public static Font textraFont(BitmapFont pixel, TextureAtlas items) {
        active = false;
        String[] mode = mode();
        if (!mode[0].equals("clean")) {
            System.out.println("[TFR-Font] pixel (" + mode[1] + ")");
            return null;
        }
        try {
            FileHandle ttf = FSkin.getDefaultSkinFile("font1.ttf");
            if (ttf == null || !ttf.exists())
                ttf = Gdx.files.absolute(ForgeConstants.COMMON_FONTS_DIR + "Roboto-Bold.ttf");
            if (!ttf.exists()) {
                System.err.println("[TFR-Font] no clean font file: the pixel font stays");
                return null;
            }
            float kx = Gdx.graphics.getBackBufferWidth() / (float) Scene.getIntendedWidth();
            float ky = Gdx.graphics.getBackBufferHeight() / (float) Scene.getIntendedHeight();
            int k = Math.max(2, Math.round(Math.min(kx, ky)));
            float size = Float.parseFloat(System.getProperty("tfr.fontSize", "9"));
            float down = Float.parseFloat(System.getProperty("tfr.fontY", "-1"));
            // LanaPixel's line (13 + 1): the same line spacing, and the icons, which the font sizes to its line, as big
            float line = Float.parseFloat(System.getProperty("tfr.fontLine", "14"));
            FreeTypeFontGenerator gen = new FreeTypeFontGenerator(ttf);
            FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
            p.size = Math.round(size * k);
            p.characters = FreeTypeFontGenerator.DEFAULT_CHARS + EXTRA_CHARS;
            p.genMipMaps = true;
            p.minFilter = Texture.TextureFilter.MipMapLinearLinear;
            p.magFilter = Texture.TextureFilter.Linear;
            int pad = 2 * k;  // room for the mipmaps: a glyph drawn at 1/4 still has 2 px of clear space around it
            // the packer keeps its pixmaps, so the pages are managed textures that come back after an Android context loss
            PixmapPacker packer = new PixmapPacker(1024, 1024, Pixmap.Format.RGBA8888, pad, false, new PixmapPacker.SkylineStrategy());
            packer.setTransparentColor(Color.WHITE);
            packer.getTransparentColor().a = 0;
            p.packer = packer;
            BitmapFont big = gen.generateFont(p);
            big.setOwnsTexture(true);  // a font made with its own packer leaves its pages to the caller
            gen.dispose();
            Font f = new Font(big, Font.DistanceFieldType.STANDARD, 0f, down * k, 0f, 0f) {
                @Override
                public Layout markup(String text, Layout appendTo) {
                    return super.markup(pixelSymbols(text), appendTo);
                }
            };
            f.scale(1f / k, 1f / k);
            if (line > 0)
                f.adjustLineHeight(line / f.cellHeight);
            f.addAtlas(items, 0f, 0f, 0f);
            f.integerPosition = false;
            // the stock font's yAdjust (2) plus 2: at 2 the check boxes and gender signs sat about 2 px above the clean text
            float pixelY = Float.parseFloat(System.getProperty("tfr.pixelY", "4"));
            Font px = new Font(pixel, 0f, pixelY, 0f, 1f);
            px.addAtlas(items, 0f, 6f, 0f);
            px.integerPosition = false;
            f.setFamily(new Font.FontFamily(new String[]{"Clean", "Pixel"}, new Font[]{f, px}));
            active = true;
            System.out.println(String.format(Locale.US, "[TFR-Font] clean (%s): %s at %d px, drawn at 1/%d (%.1f stage px, line %.1f), "
                            + "glyphs %d px apart on %d page(s), window %dx%d", mode[1], ttf.name(), p.size, k, size, f.cellHeight,
                    pad, big.getRegions().size, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight()));
            return f;
        } catch (RuntimeException e) {
            System.err.println("[TFR-Font] the clean font failed (" + e + "): the pixel font stays");
            return null;
        }
    }

    /** The text with each PIXEL_ONLY symbol wrapped in [@Pixel]...[@], so LanaPixel draws it. */
    static String pixelSymbols(String text) {
        if (text == null)
            return null;
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (PIXEL_ONLY.indexOf(c) < 0) {
                if (sb != null)
                    sb.append(c);
                continue;
            }
            if (sb == null)
                sb = new StringBuilder(text.length() + 16).append(text, 0, i);
            sb.append("[@Pixel]").append(c).append("[@]");
        }
        return sb == null ? text : sb.toString();
    }
}
