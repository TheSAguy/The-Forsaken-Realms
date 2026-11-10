package forge.adventure.util;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import forge.Graphics;

import java.util.HashMap;
import java.util.Map;

/**
 * Round 1011: fits a name on the duel's VS screen (TransitionScreen) inside its half of the screen. At 1280x720 the
 * screen's font (a fixed scale of the skin font) holds about 24 characters across half the window, so a ranked name
 * such as "Archangel of the Gilded Dawn (Archmage)" ran off the right edge, and in portrait (540 wide) about 20.
 *
 * <p>A name that fits keeps its size and place. One that does not either shrinks on one line (when it needs at most a
 * quarter off) or goes on two lines - split before the rank "(...)" when that split is nearly as even as the most even
 * one, else at the most even space - shrunk until the wider line fits and the two lines fit the height the caller
 * has. Whichever draws bigger wins. Names are never cut.
 *
 * <p>Coordinates are the font's (y up); {@code top} is the top of the first line, as BitmapFont.draw takes it.
 */
public final class VsNameFit {
    /** A single line may shrink to this share of the base size before two lines are tried. */
    private static final float ONE_LINE_MIN = 0.75f;
    /** The rank split ("Name" / "(Rank)") is used when its wider line is at most this much wider than the most even split's. */
    private static final float RANK_SPLIT_SLACK = 1.3f;

    private static final Map<String, VsNameFit> CACHE = new HashMap<>();

    public final String[] lines;
    /** Font scale to draw at (the font's own scale when the name fits as it was). */
    public final float scale;
    private final float baseScale, baseCap, baseLine;

    private VsNameFit(String[] lines, float scale, float baseScale, float baseCap, float baseLine) {
        this.lines = lines;
        this.scale = scale;
        this.baseScale = baseScale;
        this.baseCap = baseCap;
        this.baseLine = baseLine;
    }

    /**
     * @param font     the VS screen font, at its base scale
     * @param layout   a scratch layout
     * @param maxWidth the widest a line may be
     * @param room     the tallest the whole name may be, first line's top to the last line's baseline
     */
    public static VsNameFit fit(BitmapFont font, GlyphLayout layout, String text, float maxWidth, float room) {
        if (text == null)
            text = "";
        float base = font.getData().scaleX;
        String key = text + '|' + Math.round(maxWidth) + '|' + Math.round(room) + '|' + base;
        VsNameFit cached = CACHE.get(key);
        if (cached != null)
            return cached;
        float cap = font.getCapHeight(), line = font.getLineHeight();
        layout.setText(font, text);
        float w1 = layout.width;
        VsNameFit result;
        String how;
        if (w1 <= maxWidth || maxWidth <= 0) {
            result = new VsNameFit(new String[]{text}, base, base, cap, line);
            how = "fits";
        } else {
            float s1 = base * maxWidth / w1;
            String[] two = s1 >= base * ONE_LINE_MIN ? null : split(font, layout, text);
            float s2 = 0;
            if (two != null) {
                float wMax = Math.max(width(font, layout, two[0]), width(font, layout, two[1]));
                s2 = base * Math.min(1f, maxWidth / wMax);
                if (cap + line > 0)
                    s2 = Math.min(s2, base * room / (cap + line));
            }
            if (two != null && s2 > s1) {
                result = new VsNameFit(two, s2, base, cap, line);
                how = "2 lines at " + pct(s2 / base);
            } else {
                result = new VsNameFit(new String[]{text}, s1, base, cap, line);
                how = "1 line at " + pct(s1 / base);
            }
        }
        if (!"fits".equals(how))
            System.out.println("[TFR-VsName] \"" + text + "\": " + how + " (" + Math.round(w1) + " px into "
                    + Math.round(maxWidth) + " px, " + Math.round(room) + " px tall)");
        if (CACHE.size() > 64)
            CACHE.clear();
        CACHE.put(key, result);
        return result;
    }

    /** Height from the first line's top to the last line's baseline, at the fitted scale. */
    public float blockHeight() {
        float k = scale / baseScale;
        return baseCap * k + (lines.length - 1) * baseLine * k;
    }

    /** How far below a one-line name at the base size this name's bottom now reaches (0 when it does not). */
    public float extraBelow() {
        return Math.max(0f, blockHeight() - baseCap);
    }

    /** Draws each line centered on centerX, the first line's top at top; leaves the font at its base scale. */
    public void draw(Graphics g, BitmapFont font, GlyphLayout layout, float centerX, float top, Color color, float alpha) {
        font.getData().setScale(scale, scale);
        try {
            float lineHeight = font.getLineHeight();
            for (int i = 0; i < lines.length; i++) {
                layout.setText(font, lines[i]);
                g.drawText(lines[i], font, centerX - layout.width / 2, top - i * lineHeight, color, alpha);
            }
        } finally {
            font.getData().setScale(baseScale, baseScale);
        }
    }

    private static String[] split(BitmapFont font, GlyphLayout layout, String text) {
        String[] best = null;
        float bestW = Float.MAX_VALUE;
        for (int i = text.indexOf(' '); i > 0; i = text.indexOf(' ', i + 1)) {
            String a = text.substring(0, i).trim(), b = text.substring(i + 1).trim();
            if (a.isEmpty() || b.isEmpty())
                continue;
            float w = Math.max(width(font, layout, a), width(font, layout, b));
            if (w < bestW) {
                bestW = w;
                best = new String[]{a, b};
            }
        }
        int rank = text.lastIndexOf(" (");
        if (rank > 0 && text.endsWith(")")) {
            String a = text.substring(0, rank).trim(), b = text.substring(rank + 1).trim();
            float w = Math.max(width(font, layout, a), width(font, layout, b));
            if (!a.isEmpty() && w <= bestW * RANK_SPLIT_SLACK)
                return new String[]{a, b};
        }
        return best;
    }

    private static float width(BitmapFont font, GlyphLayout layout, String s) {
        layout.setText(font, s);
        return layout.width;
    }

    private static String pct(float f) {
        return Math.round(f * 100) + "%";
    }
}
