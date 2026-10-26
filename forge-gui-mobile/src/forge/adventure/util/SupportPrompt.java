package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.data.SettingData;
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
 * it. At 5, 15 and 40 hours the player is asked once each, on the world map just after a duel, a load or a town (the
 * scene came back and nothing else is open): Support TFR (Ko-fi), Join the Discord, Maybe later, Don't ask again. The
 * 40-hour ask is the last, and a Support TFR click in an ask stops the later ones (round 756 follow-up). A pure tip - it unlocks and changes nothing. Only on a plane whose config.json names a
 * supportLink. Grep forge.log for [TFR-Support].
 */
public final class SupportPrompt {
    private SupportPrompt() {
    }

    /** The asks, in hours, and their titles and texts. */
    static final float[] HOURS = {5f, 15f, 40f};
    private static final String[] TITLES = {"Five hours in the Realms", "Fifteen hours!", "Forty hours, a true Realm-walker"};
    private static final String[] TEXTS = {
            "Five hours! That's a whole evening of \"just one more duel.\" TFR is free and always will be, but its "
                    + "developer runs on coffee. If you're enjoying the Realms, a small tip keeps the coffee, and the "
                    + "new content, coming.",
            "A movie costs about $15 and lasts two hours. You've had fifteen hours of the Realms: that's seven and a "
                    + "half movies, with no popcorn tax and nobody kicking your seat. If TFR has earned a ticket or "
                    + "two, the developer would love one. Either way, thanks for playing!",
            "Forty hours. At movie prices that's about $300 of entertainment, and the Realms never charged you a "
                    + "single coin. This is the last time we'll ask: if you've enjoyed your time here, a tip goes "
                    + "straight into the next update. Thank you for sticking with it, Realm-walker."};

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
            if (supportLink() == null || unsaved <= 0f)
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

    /** The ask due now (the last threshold passed that was not asked yet), or -1. */
    static int dueAsk() {
        SettingData s = Config.instance().getSettingData();
        if (s.supportNeverAsk || s.supportClicked || s.supportAsksShown >= HOURS.length)
            return -1;
        float h = hours();
        int due = -1;
        for (int i = s.supportAsksShown; i < HOURS.length; i++)
            if (h >= HOURS[i])
                due = i;
        return due;
    }

    /** Arms when the world map comes back (after a duel, a load, a town); asks once the scene has stayed quiet. */
    private static void watchForCalm(float delta) {
        boolean onWorldMap = Forge.getCurrentScene() instanceof GameScene && !MapStage.getInstance().isInMap();
        if (onWorldMap && !wasOnWorldMap) {
            armedFor = 0f;
            quietFor = 0f;
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
        quietFor = isCalm() ? quietFor + delta : 0f;
        if (quietFor >= QUIET_SECONDS) {
            armedFor = -1f;
            show(dueAsk(), "world map");
        }
    }

    /** Nothing else on screen (no dialog on the map or the HUD, no cutscene or end screen, no transition) and the
     *  player standing still - an ask never cuts into a walk. */
    private static boolean isCalm() {
        WorldStage world = WorldStage.getInstance();
        return !Forge.advFreezePlayerControls && Forge.getTransitionScreen() == null
                && !world.isDialogOnlyInput() && !MapStage.getInstance().isDialogOnlyInput()
                && (world.getPlayerSprite() == null || !world.getPlayerSprite().isMoving());
    }

    /** Shows ask `index` on the world map's dialog. The console's "support show" calls this directly. */
    static void show(int index, String why) {
        SettingData s = Config.instance().getSettingData();
        s.supportAsksShown = index + 1;
        flushNow();
        String link = supportLink(), discord = discordLink();
        System.out.println("[TFR-Support] ask " + (index + 1) + " of " + HOURS.length + " (" + (int) HOURS[index]
                + " h) shown at " + hoursText(hours()) + " played (" + why + ")");
        WorldStage stage = WorldStage.getInstance();
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        TypingLabel title = Controls.newTypingLabel("[%120]" + TITLES[index]);
        title.skipToTheEnd();
        dialog.getContentTable().add(title).padBottom(6f).row();
        TypingLabel label = Controls.newTypingLabel(TEXTS[index]);
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();
        dialog.getButtonTable().add(Controls.newTextButton("Support TFR", () -> {
            // Round 756 follow-up: a supporter is not asked again. Only here - the Settings row's link stops nothing,
            // so a player who looks at the page from Settings still gets the asks they have not answered.
            Config.instance().getSettingData().supportClicked = true;
            answer("support", stage);
            open(link);
        })).width(240f).row();
        if (discord != null)
            dialog.getButtonTable().add(Controls.newTextButton("Join the Discord", () -> {
                answer("discord", stage);
                open(discord);
            })).width(240f).row();
        dialog.getButtonTable().add(Controls.newTextButton("Maybe later", () -> answer("maybe later", stage))).width(240f).row();
        if (index < HOURS.length - 1) // the last ask is the last anyway
            dialog.getButtonTable().add(Controls.newTextButton("Don't ask again", () -> {
                Config.instance().getSettingData().supportNeverAsk = true;
                answer("don't ask again", stage);
            })).width(240f).row();
        dialog.setKeepWithinStage(true);
        stage.showDialog();
    }

    private static void answer(String what, WorldStage stage) {
        stage.hideDialog();
        flushNow();
        SettingData s = Config.instance().getSettingData();
        System.out.println("[TFR-Support] answer: " + what + " (asks shown " + s.supportAsksShown + "/" + HOURS.length
                + stoppedText(s) + ")");
    }

    /** Why no ask comes any more, for the log and the console ("" while asks can still come). */
    private static String stoppedText(SettingData s) {
        if (s.supportClicked)
            return ", asks stopped: Support TFR clicked";
        if (s.supportNeverAsk)
            return ", asks stopped: don't ask again";
        return s.supportAsksShown >= HOURS.length ? ", asks stopped: all asked" : "";
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

    /** Console "support ...": hours N | show [1-3] | reset | info. */
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
                } catch (NumberFormatException e) {
                    return "support show 1 | 2 | 3";
                }
                show(i, "console");
                return "ask " + (i + 1) + " shown";
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
                + stoppedText(s) + (due >= 0 ? ", ask " + (due + 1) + " due" : "")
                + " (support hours N | show [1-3] | reset)";
    }
}
