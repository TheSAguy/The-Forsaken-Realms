package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Round 993 (the user, 2026-11-09: "TFR2 makes good use of "Tooltips" that pop-up ... Also, add an option to disable them
 * in Settings/New game difficulty settings. Maybe have them on by default for Easy and Normal and off for Hard and
 * Insane."): TFR 2's hover tips (Tfr2Tips) ported. A place on a screen registers a region - its actor, the scene it shows
 * in, a tag and a text supplier - with no listener in the widget's own (often stock) file; each frame, {@link #frame()}
 * finds the region under the mouse and shows the gold-framed box above it (below when the top has no room, beside it at
 * the right edge). The text is asked as the tip shows and twice a second while it shows, so live numbers stay right.
 * <p>
 * The texts are in the plane's {@code config tables/tips.json}, keyed by tag; a {@code {name}} in one is filled from the
 * call site first, then from {@link #number(String)} (the tuning table and the game's own constants), so a balance
 * change never leaves a tip wrong. A {@code [+Glyph]} keeps its own colors on the tip's ink.
 * <p>
 * The setting (the user's decisions, 2026-11-09): "Show tooltips: Auto / On / Off", one global value
 * ({@code SettingData.tfrTooltips}), default Auto. Auto = on for Easy and Normal, off for Hard and Insane; a custom
 * difficulty counts as its preset (its DifficultyData keeps the preset's name); no game running = on; on the New Game
 * screen Auto follows the difficulty box live. Mouse only: a touch device never shows one (Android has no hover).
 * Card previews ({@code cardTooltipAdj}) are separate and untouched. Behind {@code ConfigData.tfrTooltips}. [TFR-Tips].
 */
public final class TfrTips {
    public static final String AUTO = "Auto", ON = "On", OFF = "Off";
    public static final String[] MODES = {AUTO, ON, OFF};

    private TfrTips() {
    }

    // ------------------------------------------------------------------ the setting

    /** The plane has tips at all (ConfigData.tfrTooltips; stock planes no). */
    public static boolean enabled() {
        try {
            return Config.instance().getConfigData().tfrTooltips;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** "Auto", "On" or "Off" (anything else reads as Auto). */
    public static String mode() {
        String m;
        try {
            m = Config.instance().getSettingData().tfrTooltips;
        } catch (RuntimeException e) {
            m = null;
        }
        return normalize(m);
    }

    static String normalize(String m) {
        if (m != null)
            for (String x : MODES)
                if (x.equalsIgnoreCase(m.trim()))
                    return x;
        return AUTO;
    }

    /**
     * The rule, without the game: mode On or Off decides; Auto is off for a Hard or Insane difficulty name and on for
     * anything else, no difficulty (no game running) included.
     */
    public static boolean resolve(String mode, String difficulty) {
        String m = normalize(mode);
        if (m.equals(ON))
            return true;
        if (m.equals(OFF))
            return false;
        return difficulty == null || !(difficulty.equalsIgnoreCase("Hard") || difficulty.equalsIgnoreCase("Insane"));
    }

    /** New Game's difficulty box while that screen shows (set by NewGameScene), else null. */
    private static Supplier<String> newGameDifficulty;
    private static Object newGameScene;

    /** NewGameScene: Auto follows its difficulty box while it is the screen on show. */
    public static void followNewGame(Object scene, Supplier<String> difficulty) {
        newGameScene = scene;
        newGameDifficulty = difficulty;
    }

    /** The difficulty Auto reads now: New Game's box on that screen, else the running game's, else null. */
    static String difficultyNow() {
        try {
            if (newGameScene != null && newGameDifficulty != null && Forge.getCurrentScene() == newGameScene)
                return newGameDifficulty.get();
            if (CustomDifficulty.gameRunning() && forge.adventure.player.AdventurePlayer.current() != null
                    && forge.adventure.player.AdventurePlayer.current().getDifficultyData() != null)
                return forge.adventure.player.AdventurePlayer.current().getDifficultyData().name;
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static Boolean lastOn;

    /** Show hover tips now? */
    public static boolean on() {
        if (!enabled())
            return false;
        String diff = difficultyNow();
        boolean on = resolve(mode(), diff);
        if (lastOn == null || lastOn != on) {
            System.out.println("[TFR-Tips] tooltips " + describe(mode(), diff, on) + (lastOn == null ? " (first check)" : ""));
            lastOn = on;
        }
        return on;
    }

    static String describe(String mode, String diff, boolean on) {
        return normalize(mode) + (normalize(mode).equals(AUTO) ? " (" + (diff == null ? "no game" : diff) + ")" : "") + ": "
                + (on ? "on" : "off");
    }

    /** The selector's words: "Auto (on)", "On", "Off". */
    public static String label(String mode) {
        return label(mode, difficultyNow());
    }

    /** The same for a given difficulty (New Game's box). */
    public static String label(String mode, String difficulty) {
        String m = normalize(mode);
        return m.equals(AUTO) ? AUTO + " (" + (resolve(m, difficulty) ? "on" : "off") + ")" : m;
    }

    /** Settings, New Game or a script: the global value, saved. */
    public static void set(String mode, String by) {
        String m = normalize(mode), was = mode();
        Config.instance().getSettingData().tfrTooltips = m;
        Config.instance().saveSettings();
        String diff = difficultyNow();
        System.out.println("[TFR-Tips] " + by + ": tooltips " + describe(m, diff, resolve(m, diff))
                + (was.equals(m) ? " (unchanged)" : " (was " + was + ")") + " - for every game");
    }

    /** The next mode in Auto, On, Off order (New Game's button). */
    public static String next(String mode) {
        String m = normalize(mode);
        return m.equals(AUTO) ? ON : m.equals(ON) ? OFF : AUTO;
    }

    // ------------------------------------------------------------------ the texts

    /** The text color of every tip (TFR 2's): a glyph is drawn [WHITE] in it, then this color again. */
    static final String INK = "[#e9e1cc]";
    static final String FILE = "config tables/tips.json";
    private static JsonValue data;

    static JsonValue data() {
        if (data == null) {
            try {
                FileHandle f = Config.instance().getFile(FILE);
                data = f != null && f.exists() ? new JsonReader().parse(f) : new JsonValue(JsonValue.ValueType.object);
                System.out.println("[TFR-Tips] " + FILE + ": " + data.size + " tips");
            } catch (RuntimeException e) {
                System.err.println("[TFR-Tips] " + FILE + " failed to load: " + e);
                data = new JsonValue(JsonValue.ValueType.object);
            }
        }
        return data;
    }

    /** Tip key of tips.json, {name}s filled from kv (pairs of name, value) then {@link #number}; glyphs in their colors. */
    public static String text(String key, Object... kv) {
        String raw = data().getString(key, null);
        if (raw == null)
            return null;
        for (int i = 0; i + 1 < kv.length; i += 2)
            raw = raw.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        return ink(fill(raw));
    }

    /** Each [+Glyph] drawn in its own colors on the tip's ink. */
    static String ink(String s) {
        return s.replaceAll("(\\[\\+\\w+])", "[WHITE]$1" + java.util.regex.Matcher.quoteReplacement(INK));
    }

    /** Every {name} the call site left: {@link #number}, or "?" (logged once) when nothing knows it. */
    static String fill(String raw) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([\\w.]+)}").matcher(raw);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String v = number(m.group(1));
            if (v == null) {
                if (missing.add(m.group(1)))
                    System.err.println("[TFR-Tips] no value for {" + m.group(1) + "}");
                v = "?";
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final java.util.Set<String> missing = new java.util.HashSet<>();

    /**
     * A number a tip says, read where the game reads it: {@code tuning.FIELD} is any settings.json (TuningData) field
     * of the running game's effective table; the rest are named here.
     */
    static String number(String name) {
        try {
            forge.adventure.data.TuningData t = Config.instance().getTuningData();
            if (name.startsWith("tuning.")) {
                java.lang.reflect.Field f = forge.adventure.data.TuningData.class.getField(name.substring(7));
                Object v = f.get(t);
                return v instanceof Number ? num(((Number) v).doubleValue()) : String.valueOf(v);
            }
            String diff = difficultyNow() == null ? "Normal" : difficultyNow();
            switch (name) {
                case "difficulty":
                    return diff;
                case "lossPct":
                    return num(t.defeatGoldLossPercentFor(diff));
                case "lossMin":
                    return num(t.defeatGoldLossFor(diff));
                case "woodWeek":
                    return num(t.mineWeeklyWoodPayout);
                case "stoneWeek":
                    return num(t.mineWeeklyStonePayout);
                case "mapPieces":
                    return num(TreasureHunt.FRAGMENTS);
                case "digShards":
                    return num(TreasureHunt.DIG_SHARDS);
                case "digRadius":
                    return num(TreasureHunt.DIG_RADIUS);
                case "reloadForgive":
                    return num(ReloadJab.HONEST_PER_FORGIVE);
                case "reloadCap":
                    return num(ReloadJab.MAX_LIFE_BONUS);
                default:
                    return null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** A number as a tip says it: whole when it is ("3", not "3.0"), else one decimal. */
    static String num(double v) {
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // ------------------------------------------------------------------ region tips (any screen, polled each frame)

    /** A place with a tip: its actor (weak: screens rebuild theirs), the scene it shows in (null: the map HUD's scenes),
     *  an optional part of the actor (fractions x, y, w, h), a tag for the scripted look, and its text. */
    private static final class Region {
        final WeakReference<Actor> actor;
        final Object scene;
        final String tag;
        final float[] part;
        final Supplier<String> text;

        Region(Actor a, Object scene, String tag, float[] part, Supplier<String> text) {
            actor = new WeakReference<>(a);
            this.scene = scene;
            this.tag = tag;
            this.part = part;
            this.text = text;
        }
    }

    private static final List<Region> regions = new ArrayList<>();

    /**
     * A mouse over actor a while scene is on show (null: the world map or a town map, where the HUD shows) and no dialog
     * covers it shows text's tip. A later call for the same actor and tag replaces the earlier one.
     */
    public static void region(Object scene, Actor a, String tag, Supplier<String> text) {
        region(scene, a, tag, null, text);
    }

    /** The same over one part of a, as fractions of its size (x, y, w, h; y up): a panel with a tip per row. */
    public static void region(Object scene, Actor a, String tag, float[] part, Supplier<String> text) {
        if (a == null || !enabled())
            return;
        for (Iterator<Region> i = regions.iterator(); i.hasNext(); ) {
            Region r = i.next();
            Actor x = r.actor.get();
            if (x == null || x == a && r.tag.equals(tag) || regions.size() > 300 && x.getStage() == null)
                i.remove();
        }
        regions.add(new Region(a, scene, tag, part, text));
    }

    /** A tips.json key as a region's text (null: none). */
    public static void region(Object scene, Actor a, String tag) {
        region(scene, a, tag, () -> text(tag));
    }

    /** A settings-style row: the control and the label laid out just before it in the same table. */
    public static void row(Object scene, Actor control, String tag) {
        if (control == null)
            return;
        region(scene, control, tag);
        if (control.getParent() != null) {
            com.badlogic.gdx.utils.SnapshotArray<Actor> kids = control.getParent().getChildren();
            int i = kids.indexOf(control, true);
            if (i > 0)
                region(scene, kids.get(i - 1), tag);
        }
    }

    private static Texture white;
    private static Group box;
    private static Region boxFor;
    private static String boxText;
    private static float boxAge;
    /** tips hover TAG: that region's tip shown as if the mouse were over it (a scripted look); null: the mouse decides. */
    private static String forced;

    private static boolean showing(Actor a) {
        for (Actor x = a; x != null; x = x.getParent())
            if (!x.isVisible())
                return false;
        return true;
    }

    /** A dialog open on a's stage that a is not part of: it covers a. */
    private static boolean covered(Actor a) {
        for (Actor x : a.getStage().getRoot().getChildren())
            if (x instanceof Dialog && x.isVisible() && !a.isDescendantOf(x))
                return true;
        return false;
    }

    private static boolean sceneShows(Region r, Object scene) {
        if (r.scene != null)
            return r.scene == scene;
        // the HUD's regions: the world map or a town map, and no map dialog (an NPC, a pickup) holding the input
        if (scene instanceof forge.adventure.scene.GameScene)
            return !forge.adventure.stage.WorldStage.getInstance().isDialogOnlyInput();
        if (scene instanceof forge.adventure.scene.TileMapScene)
            return !forge.adventure.stage.MapStage.getInstance().isDialogOnlyInput();
        return false;
    }

    private static Region under() {
        Object scene = Forge.getCurrentScene();
        boolean mouse = !forge.gui.GuiBase.isAndroid();
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            Actor a = r.actor.get();
            if (a == null || !sceneShows(r, scene) || a.getStage() == null || !showing(a))
                continue;
            if (forced != null) {
                if (forced.equals(r.tag))
                    return r;
                continue;
            }
            if (!mouse)
                return null;
            Vector2 m = a.getStage().screenToStageCoordinates(new Vector2(Gdx.input.getX(), Gdx.input.getY()));
            Vector2 l = a.stageToLocalCoordinates(new Vector2(m));
            float x0 = r.part == null ? 0 : r.part[0] * a.getWidth(), y0 = r.part == null ? 0 : r.part[1] * a.getHeight();
            float w = r.part == null ? a.getWidth() : r.part[2] * a.getWidth(), h = r.part == null ? a.getHeight() : r.part[3] * a.getHeight();
            if (l.x >= x0 && l.y >= y0 && l.x <= x0 + w && l.y <= y0 + h && !covered(a))
                return r;
        }
        return null;
    }

    private static void hideBox() {
        if (box != null)
            box.remove();
        box = null;
        boxFor = null;
        boxText = null;
    }

    private static boolean logged;

    /** Forge.render, each adventure frame before the scene draws: the region under the mouse shows its tip, else none. */
    public static void frame() {
        if (!enabled())
            return;
        if (!logged) {
            logged = true;
            System.out.println("[TFR-Tips] at startup: tooltips " + describe(mode(), difficultyNow(), on())
                    + (forge.gui.GuiBase.isAndroid() ? " - a touch device: no hover tips" : ""));
        }
        try {
            poll();
        } catch (RuntimeException e) {
            System.err.println("[TFR-Tips] frame failed: " + e);
            hideBox();
        }
    }

    private static void poll() {
        Region r = on() ? under() : null;
        Actor a = r == null ? null : r.actor.get();
        if (a == null) {
            hideBox();
            return;
        }
        boxAge += Gdx.graphics.getDeltaTime();
        String t = r == boxFor && boxAge < 0.5f ? boxText : r.text.get();
        if (t == null || t.isEmpty()) {
            hideBox();
            return;
        }
        if (r != boxFor || !t.equals(boxText) || box == null || box.getStage() != a.getStage()) {
            boolean fresh = r != boxFor;
            hideBox();
            boxFor = r;
            boxText = t;
            box = build(t);
            a.getStage().addActor(box);
            if (fresh)
                System.out.println("[TFR-Tips] tip " + r.tag + ": " + t.replaceAll("\\[[^\\]]*]", "").replace('\n', ' '));
        }
        if (boxAge >= 0.5f)
            boxAge = 0;
        place(box, a, r.part);
        box.toFront();
    }

    /** The frame around a packed label: 4 units each side, 2 above and below - never narrower than its text. */
    static float[] boxSize(float labelWidth, float labelHeight) {
        return new float[]{labelWidth + 8, labelHeight + 4};
    }

    /** The gold-framed box of TFR 2's tips, its text [%60] on dark; its lines are the text's own ("\n"), unwrapped. */
    static Group build(String text) {
        if (white == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(Color.WHITE);
            pm.fill();
            white = new Texture(pm);
            pm.dispose();
        }
        TextraLabel l = Controls.newTextraLabel("[%60]" + INK + text);
        l.pack();
        // Round 993 (follow-up): no wrapping - a wrapping TextraLabel packs to a near-zero width, so the HUD's wide tips
        // drew as a gold sliver with their text spilling across the map. The texts break their own lines with "\n".
        float[] size = boxSize(l.getWidth(), l.getHeight());
        float w = size[0], h = size[1];
        Group g = new Group();
        Color gold = new Color(0.91f, 0.72f, 0.29f, 1f);
        float[][] parts = {{0, 0, w, h}, {0, 0, w, 1}, {0, h - 1, w, 1}, {0, 0, 1, h}, {w - 1, 0, 1, h}};
        for (int k = 0; k < parts.length; k++) {
            Image i = new Image(white);
            i.setBounds(parts[k][0], parts[k][1], parts[k][2], parts[k][3]);
            i.setColor(k == 0 ? new Color(0.09f, 0.085f, 0.07f, 0.96f) : gold);
            g.addActor(i);
        }
        l.setPosition(4, 2);
        g.addActor(l);
        g.setSize(w, h);
        g.setTouchable(Touchable.disabled);
        return g;
    }

    /** Above the actor's (or part's) middle, else below it; beside it at the right edge; inside the stage. */
    private static void place(Group g, Actor a, float[] part) {
        float px = part == null ? 0 : part[0] * a.getWidth(), py = part == null ? 0 : part[1] * a.getHeight();
        float pw = part == null ? a.getWidth() : part[2] * a.getWidth(), ph = part == null ? a.getHeight() : part[3] * a.getHeight();
        Vector2 lo = a.localToStageCoordinates(new Vector2(px, py)), hi = a.localToStageCoordinates(new Vector2(px + pw, py + ph));
        float sw = a.getStage().getWidth(), sh = a.getStage().getHeight(), w = g.getWidth(), h = g.getHeight();
        float x = (lo.x + hi.x) / 2 - w / 2, y = hi.y + 3 + h <= sh - 2 ? hi.y + 3 : lo.y - h - 3;
        if (hi.y - lo.y > sh * 0.25f) {   // a tall actor (the minimap): by the mouse, or near its top for a scripted look
            Vector2 m = forced != null ? new Vector2(lo.x + 12 + w / 2, hi.y - 30)
                    : a.getStage().screenToStageCoordinates(new Vector2(Gdx.input.getX(), Gdx.input.getY()));
            x = m.x - w / 2;
            y = m.y + 8 + h <= sh - 2 ? m.y + 8 : m.y - h - 8;
        } else if (hi.x > sw - 60 && lo.x > w + 6) {   // at the right edge (the HUD's resource column): left of it
            x = lo.x - w - 4;
            y = (lo.y + hi.y) / 2 - h / 2;
        }
        g.setPosition(Math.round(Math.max(2, Math.min(sw - w - 2, x))), Math.round(Math.max(2, Math.min(sh - h - 2, y))));
    }

    // ------------------------------------------------------------------ console: tips ...

    /**
     * tips [state] | auto | on | off | hover TAG | hover off | list: the setting as Settings' box; hover TAG shows that
     * region's tip as if the mouse were over it (until hover off), for agent screenshots; list prints the tags on show.
     */
    public static String console(String[] s) {
        String what = s.length > 0 ? s[0].toLowerCase(Locale.ROOT) : "state";
        switch (what) {
            case "auto":
            case "on":
            case "off":
                set(what, "console");
                break;
            case "hover":
                forced = s.length > 1 && !s[1].equalsIgnoreCase("off") ? s[1] : null;
                System.out.println("[TFR-Tips] hover " + (forced == null ? "off: the mouse decides" : forced + " (forced)"));
                return forced == null ? "hover off" : "hover " + forced;
            case "list": {
                StringBuilder b = new StringBuilder();
                Object scene = Forge.getCurrentScene();
                for (Region r : regions) {
                    Actor a = r.actor.get();
                    if (a != null && sceneShows(r, scene) && a.getStage() != null && showing(a) && b.indexOf(" " + r.tag + " ") < 0)
                        b.append(' ').append(r.tag).append(' ');
                }
                String line = "tips on show:" + b.toString().replaceAll("\\s+", " ");
                System.out.println("[TFR-Tips] " + line);
                return line;
            }
            case "state":
                break;
            default:
                return "tips [state|auto|on|off|hover TAG|hover off|list]";
        }
        String diff = difficultyNow();
        String line = "tooltips " + describe(mode(), diff, enabled() && resolve(mode(), diff))
                + (enabled() ? "" : " (not on this plane)") + (forced == null ? "" : ", hover forced: " + forced)
                + (box != null && boxFor != null ? ", showing " + boxFor.tag : "");
        System.out.println("[TFR-Tips] state: " + line);
        return line;
    }
}
