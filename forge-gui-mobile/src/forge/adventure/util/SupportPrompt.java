package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Scaling;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.data.SettingData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.scene.GameScene;
import forge.adventure.scene.SaveLoadScene;
import forge.adventure.scene.StartScene;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.sound.SoundSystem;

/**
 * Round 756 (the user: "a little prompt in the game after a certain number of game playing hours ... Would be nice to
 * support the developer"). Real play time - every save, only while the window has focus, menus before a game left out -
 * is counted in the profile's adventure settings.json (SettingData.supportPlaySeconds), so a new game does not restart
 * it. At 5, 15, 40 and 75 hours the player is asked once each, on the world map just after a duel, a load or a town (the
 * scene came back and nothing else is open): Support TFR (Ko-fi), Join the Discord, Maybe later, and on the 5 and 15 h
 * asks Not until 40 hours. Round 963 (the user: "change the 40, to always show, even if they turned it off at 5 or 15.
 * Also, add a 75h one, Let's make a big splash for it"): "Not until 40 hours" (round 756's "Don't ask again", the same
 * supportNeverAsk) stops only the 5 and 15 h asks; the 40 h ask and the 75 h splash always come, and for a player who
 * clicked Support TFR in an ask they are thank-yous. The 75 h splash is the last. A pure tip - it unlocks and changes
 * nothing. Only on a plane whose config.json names a supportLink. Grep forge.log for [TFR-Support].
 */
public final class SupportPrompt {
    private SupportPrompt() {
    }

    /** The asks, in hours, and their titles and texts. */
    static final float[] HOURS = {5f, 15f, 40f, 75f};
    /** Round 963: asks from this index on (40 h, 75 h) ignore "Not until 40 hours" and thank a supporter instead. */
    static final int ALWAYS_FROM = 2;
    /** Round 963: the last ask, the splash. */
    static final int SPLASH = 3;
    /** Round 963: the splash's art (the plane's ui folder). The final art replaces the placeholder at this path. */
    static final String SPLASH_ART = "ui/support_75h.png";
    /** Round 963: the 75 h text names the save's duel wins only from this many on - "3 duels won" undercuts the line. */
    static final int WINS_PHRASE_MIN = 50;
    private static final String[] TITLES = {"Five hours in the Realms", "Fifteen hours!", "Forty hours, a true Realm-walker",
            "Seventy-five hours!"};
    private static final String[] TEXTS = {
            "Five hours! That's a whole evening of \"just one more duel.\" TFR is free and always will be, but its "
                    + "developer runs on coffee. If you're enjoying the Realms, a small tip keeps the coffee, and the "
                    + "new content, coming.",
            "A movie costs about $15 and lasts two hours. You've had fifteen hours of the Realms: that's seven and a "
                    + "half movies, with no popcorn tax and nobody kicking your seat. If TFR has earned a ticket or "
                    + "two, the developer would love one. Either way, thanks for playing!",
            // round 963: no longer "This is the last time we'll ask" - the 75 h ask comes after it
            "Forty hours. At movie prices that's about $300 of entertainment, and the Realms never charged you a "
                    + "single coin. If you've enjoyed your time here, a tip goes straight into the next update. Thank "
                    + "you for sticking with it, Realm-walker.",
            null}; // the 75 h text names the wins: text75()
    /** Round 963: a supporter's 40 h and 75 h texts (index 2 and 3; the others are never thank-yous). */
    private static final String THANKS_40 = "Forty hours! You've already stopped by the developer's Ko-fi page, so "
            + "there's no ask here, only a thank-you. Every hour you play, and every bug you report on Discord, helps "
            + "shape the Realms. Onward, Realm-walker!";
    private static final String THANKS_75 = "You've already stopped by the developer's Ko-fi page, so there's no ask "
            + "here, only a thank-you. Players like you are why the Realms keep growing. Here's to the next "
            + "seventy-five, Realm-walker.";

    /** Counted time is written to settings.json this often (plus at pause and exit). */
    private static final float FLUSH_SECONDS = 300f;
    /** A frame longer than this (a hitch, a debugger) counts as this much. */
    private static final float MAX_FRAME = 0.25f;
    /** After the world map comes back, the ask waits this long for the scene to stay quiet... */
    private static final float QUIET_SECONDS = 1.5f;
    /** ...and gives up after this long (a walk is under way), until the world map comes back again. */
    private static final float ARM_SECONDS = 20f;

    private static float unsaved;
    private static boolean wasOnWorldMap;
    private static float armedFor = -1f;
    private static float quietFor;
    /** Round 967: the "waiting for the duel result" line, once per arming. */
    private static boolean loggedWait;
    /** Round 963: the splash's dark veil over the HUD (removed with the dialog), and its 1x1 white texture. */
    private static Image veil;
    /** Round 963: the console's "support show 4 N" - the 75 h text as if the save had N wins (one show), or -1. */
    private static int winsOverride = -1;
    private static Texture white;

    /** The plane's Ko-fi page, or null (a stock plane: no counting, no ask, no Settings row). */
    public static String supportLink() {
        String link = Config.instance().getConfigData().supportLink;
        return link == null || link.isEmpty() ? null : link;
    }

    /** The plane's Discord invite (the welcome page's link), or null. */
    static String discordLink() {
        String link = Config.instance().getConfigData().welcomePopupLink;
        return link == null || !link.contains("discord") ? null : link;
    }

    /** Forge.render(), every frame. Never throws. */
    public static void tick(float delta) {
        try {
            if (!Forge.isMobileAdventureMode || supportLink() == null)
                return;
            countTime(delta);
            watchForCalm(delta);
        } catch (Exception e) {
            System.err.println("[TFR-Support] tick failed: " + e);
        }
    }

    private static void countTime(float delta) {
        if (!SoundSystem.instance.hasWindowFocus())
            return;
        Object scene = Forge.getCurrentScene();
        if (scene == null || scene instanceof StartScene || scene instanceof SaveLoadScene)
            return;
        float d = Math.min(Math.max(delta, 0f), MAX_FRAME);
        Config.instance().getSettingData().supportPlaySeconds += d;
        unsaved += d;
        if (unsaved >= FLUSH_SECONDS)
            flush("periodic");
    }

    /** Writes the counted time to settings.json (Forge.pause()/dispose(), every five minutes played). */
    public static void flush(String why) {
        try {
            // Round 997 (logs-1106 P3-2): nothing counted first - a pause before the assets exist (the first download)
            // built Config from a missing config.json and printed [TFR-Config] FATAL.
            if (unsaved <= 0f || supportLink() == null)
                return;
            unsaved = 0f;
            Config.instance().saveSettings();
            System.out.println("[TFR-Support] play time " + hoursText(hours()) + " saved (" + why + ")");
        } catch (Exception e) {
            System.err.println("[TFR-Support] saving play time failed: " + e);
        }
    }

    public static float hours() {
        return (float) (Config.instance().getSettingData().supportPlaySeconds / 3600d);
    }

    /** The ask due now, or -1. */
    static int dueAsk() {
        SettingData s = Config.instance().getSettingData();
        return dueAsk(hours(), s.supportAsksShown, s.supportNeverAsk, s.supportClicked);
    }

    /** The ask due: the last threshold passed that was not asked yet and that this player may get, or -1. A total
     *  past several marks gets only the latest (show() counts the earlier ones as asked) - one ask, not two in a row.
     *  Round 963: "Not until 40 hours" and a Support click stop only the asks before ALWAYS_FROM. */
    static int dueAsk(float hours, int shown, boolean notUntil40, boolean clicked) {
        int due = -1;
        for (int i = Math.max(0, shown); i < HOURS.length; i++)
            if (hours >= HOURS[i] && mayAsk(i, notUntil40, clicked))
                due = i;
        return due;
    }

    /** The next ask this player can still get, or -1 (the status line and the console). */
    static int nextAsk(int shown, boolean notUntil40, boolean clicked) {
        for (int i = Math.max(0, shown); i < HOURS.length; i++)
            if (mayAsk(i, notUntil40, clicked))
                return i;
        return -1;
    }

    private static boolean mayAsk(int index, boolean notUntil40, boolean clicked) {
        return index >= ALWAYS_FROM || !notUntil40 && !clicked;
    }

    /** Round 963: a supporter's 40 h and 75 h asks are thank-yous. */
    static boolean isThanks(int index, boolean clicked) {
        return clicked && index >= ALWAYS_FROM;
    }

    /** Round 963: the 75 h ask's text; the save's duel wins lead it from WINS_PHRASE_MIN on. */
    static String text75(int wins) {
        return (wins >= WINS_PHRASE_MIN ? wins + " duels won. " : "")
                + "You're not visiting the Realms anymore. You live here.\n\n"
                + "Every hour was free, and it stays free, for you and for every new player. What keeps the updates "
                + "coming is players who decide it was worth something to them.\n\n"
                + "If TFR has given you even a slice of what a $60 game does, a coffee-sized tip goes straight into "
                + "the next update. This is the last time we'll ask. Thank you, Realm-walker.";
    }

    /** Arms when the world map comes back (after a duel, a load, a town); asks once the scene has stayed quiet. */
    private static void watchForCalm(float delta) {
        boolean onWorldMap = Forge.getCurrentScene() instanceof GameScene && !MapStage.getInstance().isInMap();
        if (onWorldMap && !wasOnWorldMap) {
            armedFor = 0f;
            quietFor = 0f;
            loggedWait = false;
        }
        wasOnWorldMap = onWorldMap;
        if (armedFor < 0f)
            return;
        if (!onWorldMap || armedFor > ARM_SECONDS) {
            armedFor = -1f;
            return;
        }
        armedFor += delta;
        if (dueAsk() < 0)
            return;
        WorldStage world = WorldStage.getInstance();
        boolean resultPending = world.hasPendingResultTask() || world.isPaused();
        if (resultPending && !loggedWait) {
            loggedWait = true;
            System.out.println("[TFR-Support] ask " + (dueAsk() + 1) + " due - waiting for the duel result (the loot"
                    + " screen, a defeat) first");
        }
        quietFor = quietTime(quietFor, delta, isCalm(world, resultPending));
        if (quietFor >= QUIET_SECONDS) {
            armedFor = -1f;
            show(dueAsk(), "world map");
        }
    }

    /** Round 967: the quiet time so far - any frame that is not calm starts it over. */
    static float quietTime(float quietFor, float delta, boolean calm) {
        return calm ? quietFor + delta : 0f;
    }

    private static boolean isCalm(WorldStage world, boolean resultPending) {
        return isCalm(Forge.advFreezePlayerControls, Forge.getTransitionScreen() != null,
                world.isDialogOnlyInput() || MapStage.getInstance().isDialogOnlyInput(),
                world.getPlayerSprite() != null && world.getPlayerSprite().isMoving(), resultPending);
    }

    /** Nothing else on screen (no dialog on the map or the HUD, no cutscene or end screen, no transition), the player
     *  standing still - an ask never cuts into a walk - and round 967: no duel result still to come. A win plays the
     *  attack and death animations (a Timer task, then a pause) before the loot screen opens, a loss its hit pause, and
     *  a defeat's teleport or boss dialog sits behind another result task; the 5 h ask fired inside that window and the
     *  loot screen opened over it (logs-1105 P3-3). The loot screen takes the world map away, so the ask re-arms when
     *  it closes and waits its full quiet time again. */
    static boolean isCalm(boolean frozen, boolean transition, boolean dialog, boolean moving, boolean resultPending) {
        return !frozen && !transition && !dialog && !moving && !resultPending;
    }

    /** Shows ask `index` on the world map's dialog. The console's "support show" calls this directly. */
    static void show(int index, String why) {
        SettingData s = Config.instance().getSettingData();
        boolean thanks = isThanks(index, s.supportClicked);
        boolean overrode = index >= ALWAYS_FROM && s.supportNeverAsk;
        s.supportAsksShown = index + 1;
        flushNow();
        String link = supportLink(), discord = discordLink();
        System.out.println("[TFR-Support] " + (thanks ? "thank-you " : "ask ") + (index + 1) + " of " + HOURS.length
                + " (" + (int) HOURS[index] + " h" + (index == SPLASH ? ", splash" : "") + ") shown at "
                + hoursText(hours()) + " played (" + why + ")"
                + (thanks ? " - Support TFR was clicked in an earlier ask" : "")
                + (overrode ? " - shown although the player picked Not until 40 hours" : ""));
        String text = index == SPLASH ? (thanks ? THANKS_75 : text75(wins())) : thanks ? THANKS_40 : TEXTS[index];
        WorldStage stage = WorldStage.getInstance();
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        Table buttons = index == SPLASH ? new Table() : dialog.getButtonTable();
        float buttonWidth = index == SPLASH ? (Forge.isLandscapeMode() ? 230f : 240f) : 240f;
        if (!thanks)
            buttons.add(Controls.newTextButton(index == SPLASH ? "Support TFR on Ko-fi" : "Support TFR", () -> {
                // Round 756 follow-up: a supporter is not asked again. Only here - the Settings row's link stops nothing,
                // so a player who looks at the page from Settings still gets the asks they have not answered.
                Config.instance().getSettingData().supportClicked = true;
                answer("support", stage);
                open(link);
            })).width(buttonWidth).row();
        if (discord != null)
            buttons.add(Controls.newTextButton("Join the Discord", () -> {
                answer("discord", stage);
                open(discord);
            })).width(buttonWidth).row();
        if (index == SPLASH || thanks)
            buttons.add(Controls.newTextButton("Back to the Realms", () -> answer("back to the realms", stage))).width(buttonWidth).row();
        else
            buttons.add(Controls.newTextButton("Maybe later", () -> answer("maybe later", stage))).width(buttonWidth).row();
        if (index < ALWAYS_FROM) // round 963: "Don't ask again" was not true any more - the 40 h ask comes anyway
            buttons.add(Controls.newTextButton("Not until 40 hours", () -> {
                Config.instance().getSettingData().supportNeverAsk = true;
                answer("not until 40 hours", stage);
            })).width(buttonWidth).row();
        if (index == SPLASH)
            layoutSplash(dialog, text, buttons);
        else {
            TypingLabel title = Controls.newTypingLabel("[%120]" + TITLES[index]);
            title.skipToTheEnd();
            dialog.getContentTable().add(title).padBottom(6f).row();
            TypingLabel label = Controls.newTypingLabel(text);
            label.setWrap(true);
            label.skipToTheEnd();
            dialog.getContentTable().add(label).width(250f).row();
        }
        dialog.setKeepWithinStage(true);
        stage.showDialog();
        if (index == SPLASH)
            addVeil(dialog);
    }

    /** Round 963: the 75 h splash - the art (SPLASH_ART, 164 units square, a gold line round it) beside the gold title,
     *  the text and the buttons in landscape, above them in portrait. All of it goes in the button table, so
     *  showDialog's focus list (it walks the button table's nested tables) still finds every button. Sized to fit
     *  the 270-unit-high landscape stage: a paragraph is its own label with a small gap, not a blank line (a whole
     *  line high), and the buttons sit with no gap of their own (the first agent shot ran off the top of the screen). */
    private static void layoutSplash(Dialog dialog, String text, Table buttons) {
        boolean landscape = Forge.isLandscapeMode();
        float textWidth = landscape ? 262f : 240f;
        Table column = new Table();
        TypingLabel title = Controls.newTypingLabel("[%140][#F4CC60]" + TITLES[SPLASH]);
        title.skipToTheEnd();
        column.add(title).padBottom(1f).row();
        for (String paragraph : text.split("\n\n")) {
            TypingLabel label = Controls.newTypingLabel(paragraph);
            label.setWrap(true);
            label.skipToTheEnd();
            column.add(label).width(textWidth).padBottom(3f).row();
        }
        column.add(buttons).row();
        Table body = dialog.getButtonTable();
        Container<Image> art = splashArt();
        if (art != null) {
            body.add(art).size(166f).pad(2f);
            if (!landscape)
                body.row();
        }
        body.add(column).pad(2f);
    }

    /** The splash's art in a 1-unit gold frame, or null (no file: the splash shows without it). */
    private static Container<Image> splashArt() {
        try {
            Texture tex = Forge.getAssets().getTexture(Config.instance().getFile(SPLASH_ART), false);
            if (tex == null) {
                System.out.println("[TFR-Support] no " + SPLASH_ART + " - the splash shows without art");
                return null;
            }
            tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            Image image = new Image(new TextureRegionDrawable(new TextureRegion(tex)));
            image.setScaling(Scaling.fit);
            Container<Image> frame = new Container<>(image);
            // the background first: setBackground resets the padding to the drawable's (none), which hid the gold line
            frame.setBackground(new TextureRegionDrawable(new TextureRegion(white())).tint(new Color(0xF4CC60FF)));
            frame.fill().pad(1f);
            System.out.println("[TFR-Support] splash art " + SPLASH_ART + " " + tex.getWidth() + "x" + tex.getHeight());
            return frame;
        } catch (Exception e) {
            System.err.println("[TFR-Support] could not load " + SPLASH_ART + ": " + e);
            return null;
        }
    }

    /** Darkens everything behind the splash (the world and the HUD); answer() removes it. */
    private static void addVeil(Dialog dialog) {
        Stage st = dialog.getStage();
        if (st == null)
            return;
        removeVeil();
        veil = new Image(new TextureRegionDrawable(new TextureRegion(white())));
        veil.setColor(0.03f, 0.02f, 0.06f, 0.6f);
        veil.setTouchable(Touchable.disabled);
        veil.setSize(st.getWidth(), st.getHeight());
        st.addActor(veil);
        dialog.toFront();
    }

    private static void removeVeil() {
        if (veil != null) {
            veil.remove();
            veil = null;
        }
    }

    private static Texture white() {
        if (white == null) {
            Pixmap p = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            p.setColor(Color.WHITE);
            p.fill();
            white = new Texture(p);
            p.dispose();
        }
        return white;
    }

    /** Round 963: this save's duel wins (0 with no player), logged with whether the 75 h text names them. */
    private static int wins() {
        int wins = 0;
        try {
            wins = winsOverride >= 0 ? winsOverride : AdventurePlayer.current().getStatistic().totalWins();
        } catch (Exception e) {
            System.err.println("[TFR-Support] could not read the duel wins: " + e);
        }
        winsOverride = -1;
        System.out.println("[TFR-Support] 75 h text: wins=" + wins + ", wins phrase " + (wins >= WINS_PHRASE_MIN
                ? "shown" : "left out (under " + WINS_PHRASE_MIN + ")"));
        return wins;
    }

    private static void answer(String what, WorldStage stage) {
        stage.hideDialog();
        removeVeil();
        SettingData s = Config.instance().getSettingData();
        s.supportLastAnswer = what; // round 777: for the status line; flushNow saves it
        flushNow();
        System.out.println("[TFR-Support] answer: " + what + " (asks shown " + s.supportAsksShown + "/" + HOURS.length
                + stoppedText(s) + ")");
    }

    /** Round 777: one line per launch (SessionLog, the first game) - the asks so far and the next, whatever session the
     *  ask itself was in. */
    static void logStatus() {
        if (supportLink() == null)
            return;
        SettingData s = Config.instance().getSettingData();
        int next = nextAsk(s.supportAsksShown, s.supportNeverAsk, s.supportClicked);
        String stopped = stoppedText(s);
        System.out.println("[TFR-Support] status played=" + String.format(java.util.Locale.ROOT, "%.2fh", hours()) + " asks=" + s.supportAsksShown + "/"
                + HOURS.length + " last=" + (s.supportLastAnswer == null ? "none" : "\"" + s.supportLastAnswer + "\"")
                + " next=" + (next < 0 ? "none" : (int) HOURS[next] + "h" + (isThanks(next, s.supportClicked) ? " (thank-you)" : ""))
                + " stopped=" + (stopped.isEmpty() ? "no" : "\"" + stopped.substring(stopped.indexOf(':') + 2) + "\""));
    }

    /** What holds the asks back, for the log and the console ("" while every ask can still come). */
    private static String stoppedText(SettingData s) {
        if (s.supportAsksShown >= HOURS.length)
            return ", asks stopped: all asked";
        if (s.supportClicked)
            return ", asks held: Support TFR clicked (40 and 75 h are thank-yous)";
        if (s.supportNeverAsk && s.supportAsksShown < ALWAYS_FROM)
            return ", asks held: not until 40 hours";
        return "";
    }

    /** Opens a link in the browser (Gdx.net.openURI - desktop and Android). TFR_SUPPORT_NO_BROWSER=1 only logs it. */
    public static void open(String url) {
        if (url == null)
            return;
        if ("1".equals(System.getenv("TFR_SUPPORT_NO_BROWSER"))) {
            System.out.println("[TFR-Support] would open " + url + " (TFR_SUPPORT_NO_BROWSER)");
            return;
        }
        boolean ok = Gdx.net.openURI(url);
        System.out.println("[TFR-Support] open " + url + (ok ? "" : " - the system did not open it"));
    }

    private static void flushNow() {
        unsaved = 0f;
        Config.instance().saveSettings();
    }

    static String hoursText(float h) {
        return String.format(java.util.Locale.ROOT, "%.2f h", h);
    }

    /** Console "support ...": hours N | show [1-4] [wins, for the 75 h text] | never | clicked | reset | info. */
    public static String console(String[] s) {
        if (supportLink() == null)
            return "no supportLink on this plane";
        SettingData set = Config.instance().getSettingData();
        String op = s.length > 0 ? s[0].toLowerCase() : "info";
        switch (op) {
            case "hours":
                if (s.length < 2)
                    return "support hours N";
                try {
                    set.supportPlaySeconds = Double.parseDouble(s[1]) * 3600d;
                } catch (NumberFormatException e) {
                    return "not a number: " + s[1];
                }
                flushNow();
                System.out.println("[TFR-Support] console: play time set to " + hoursText(hours()));
                return "play time " + hoursText(hours()) + "; " + info();
            case "show":
                int i;
                try {
                    i = s.length > 1 ? Math.max(1, Math.min(HOURS.length, Integer.parseInt(s[1]))) - 1 : Math.max(0, dueAsk());
                    winsOverride = s.length > 2 ? Integer.parseInt(s[2]) : -1;
                } catch (NumberFormatException e) {
                    return "support show 1 | 2 | 3 | 4 [wins]";
                }
                show(i, "console");
                return (isThanks(i, set.supportClicked) ? "thank-you " : "ask ") + (i + 1) + " shown";
            case "never": // round 963: as if "Not until 40 hours" was picked (round 756's "Don't ask again")
                set.supportNeverAsk = true;
                flushNow();
                System.out.println("[TFR-Support] console: not until 40 hours set");
                return info();
            case "clicked": // round 963: as if Support TFR was clicked in an ask
                set.supportClicked = true;
                flushNow();
                System.out.println("[TFR-Support] console: Support TFR clicked set");
                return info();
            case "reset":
                set.supportPlaySeconds = 0d;
                set.supportAsksShown = 0;
                set.supportNeverAsk = false;
                set.supportClicked = false;
                flushNow();
                System.out.println("[TFR-Support] console: reset");
                return "reset; " + info();
            default:
                return info();
        }
    }

    static String info() {
        SettingData s = Config.instance().getSettingData();
        int due = dueAsk();
        return "played " + hoursText(hours()) + ", asks shown " + s.supportAsksShown + "/" + HOURS.length
                + stoppedText(s) + (due >= 0 ? ", " + (isThanks(due, s.supportClicked) ? "thank-you " : "ask ") + (due + 1) + " due" : "")
                + " (support hours N | show [1-4] | never | clicked | reset)";
    }
}
