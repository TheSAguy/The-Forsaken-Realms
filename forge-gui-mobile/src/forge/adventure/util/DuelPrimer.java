package forge.adventure.util;

import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.GameStage;

/**
 * TFR round 1047: "How a Duel Works", five short pages shown once, before a new save's first duel, on every platform.
 * The new-player study (notes\newplayer-1118.md) found new Android players get no duel help at all: TFR's hover tips need
 * a mouse, stock Forge's tutorial sits behind the Classic menu TFR locks, and the stock End Turn button skips the attack.
 * The leavers lost or conceded early duels at full life. Built like The War Begins (WarIntro): tips.json's duel.* keys,
 * the same page dialog (Back / Next / Understood).
 *
 * <p>When: the world map's and a map's (town, dungeon) fight start - after the attack animation, before the transition
 * screen and the duel. The duel does not exist yet, so nothing waits on the duel thread (round 161's ante prompt held
 * the match start on Android); the fight starts when the player closes the last page. Arena, Inn, guard and Capitol
 * fights never show it (a new player meets an overworld foe first), and a save whose first duel was one of those still
 * sees the pages before its first ordinary fight, since the rule is "no ordinary duel yet".
 *
 * <p>Who: a save with no duel on its record (wins + losses = 0) and no {@link #FLAG}. An existing save that has fought
 * never sees it; one that has not fought yet sees it once. New Game+ never (a returning player). The flag is a character
 * flag, saved with the player; the fight start autosaves right after, so a reload never shows the pages again.
 * Settings, How a Duel Works opens them any time, and (round 1048) so does the duel's Game menu. [TFR-DuelPrimer].
 */
public final class DuelPrimer {
    private DuelPrimer() {
    }

    public static final int PAGES = 5;
    /** The character flag: 1 once the pages were shown on this save. */
    public static final String FLAG = "duelPrimerShown";

    /** Launch-only: building the pages threw (a broken text); stay quiet until the next launch, the flag stays armed. */
    private static boolean failed;

    /** The whole decision, pure for the unit test. */
    public static boolean due(boolean shown, boolean newGamePlus, int duelsOnRecord) {
        return !shown && !newGamePlus && duelsOnRecord <= 0;
    }

    /** True when this plane has the pages (TFR's tips.json); a stock plane never shows them. */
    public static boolean available() {
        try {
            return TfrTips.data().getString("duel.title1", null) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * A fight start on the world map or a map: shows the pages first when they are due, then {@code startDuel} when the
     * last page is closed; otherwise {@code startDuel} at once. Never throws: a failure starts the duel.
     */
    public static void beforeDuel(GameStage stage, String enemyName, Runnable startDuel) {
        String[][] pages = null;
        try {
            AdventurePlayer player = AdventurePlayer.current();
            if (!failed && player != null && available()) {
                int duels = player.getStatistic().totalWins() + player.getStatistic().totalLoss();
                boolean ngPlus = player.getCharacterFlag("newGamePlus") > 0;
                if (due(player.getCharacterFlag(FLAG) > 0, ngPlus, duels)) {
                    pages = pages();
                    player.setCharacterFlag(FLAG, 1);
                    System.out.println("[TFR-DuelPrimer] shown before the first duel (vs " + enemyName + ", day "
                            + (Current.world() == null ? "?" : Current.world().getCurrentDay()) + ", "
                            + (forge.gui.GuiBase.isAndroid() ? "Android" : "desktop") + ")");
                }
            }
        } catch (RuntimeException e) {
            failed = true;
            pages = null;
            System.err.println("[TFR-DuelPrimer] pages failed - duel starts without them, left armed for the next launch: " + e);
        }
        if (pages == null || stage == null) {
            startDuel.run();
            return;
        }
        showPage(stage, pages, 0, startDuel);
    }

    private static void showPage(GameStage stage, String[][] pages, int page, Runnable startDuel) {
        Dialog dialog = stage.getDialog();
        dialog.clearListeners();
        WarIntro.fill(dialog, pages, page, next -> showPage(stage, pages, next, startDuel), () -> {
            stage.hideDialog();
            System.out.println("[TFR-DuelPrimer] closed - the duel starts");
            stage.startPause(0.35f, startDuel); // the dialog's hide (0.3 s) is over before the transition's screenshot
        });
        dialog.setKeepWithinStage(true);
        stage.showDialog();
        dialog.toFront();
    }

    /** {title, body} per page, numbers filled from the running game, in the words of this device's pointer. */
    public static String[][] pages() {
        return pages(forge.gui.GuiBase.isMobile());
    }

    /**
     * Round 1048: the pages name the player's pointer. {@code touch} (Android, iOS): "Tap", press and hold a card to
     * read it. Desktop: "Click", point at a card (the duel screen's hover preview) or hold the mouse button on it (the
     * same long press opens the card zoom). Right-click only toggles the hover preview, so the text never says it.
     */
    static String[][] pages(boolean touch) {
        String ante = "duel.anteOff";
        int anteDay = 1;
        if (forge.model.FModel.getPreferences().getPrefBoolean(forge.localinstance.properties.ForgePreferences.FPref.UI_ANTE)) {
            ante = "duel.anteNow";
            AdventurePlayer player = AdventurePlayer.current();
            forge.adventure.data.TuningData tuning = Config.instance().getTuningData();
            if (player != null && player.getDifficulty() != null && tuning != null && Current.world() != null) {
                int weeks = tuning.anteGraceWeeksFor(player.getDifficulty().name);
                boolean ngPlus = player.getCharacterFlag("newGamePlus") > 0;
                if (AnteGrace.inGrace(weeks, Current.world().getCurrentDay(), ngPlus)) { // round 1046: ante not yet on
                    ante = "duel.anteLater";
                    anteDay = AnteGrace.anteFromDay(weeks);
                }
            }
        }
        int lifePct = 0;
        AdventurePlayer player = AdventurePlayer.current();
        if (player != null && player.getDifficulty() != null)
            lifePct = Math.round(player.getDifficulty().lifeLoss * 100f);
        String anteText = raw(ante).replace("{anteDay}", String.valueOf(anteDay));
        String[][] out = new String[PAGES][];
        for (int i = 1; i <= PAGES; i++) {
            String body = pointerWords(raw("duel.page" + i), touch, raw(touch ? "duel.readTouch" : "duel.readMouse"))
                    .replace("{lifePct}", String.valueOf(lifePct)).replace("{ante}", anteText);
            out[i - 1] = new String[]{TfrTips.fill(raw("duel.title" + i)), TfrTips.fill(body)};
        }
        return out;
    }

    /** Round 1048, pure for the unit test: {Tap}/{tap} and {readCard} in the touch or the mouse words. */
    static String pointerWords(String body, boolean touch, String readCard) {
        return body.replace("{Tap}", touch ? "Tap" : "Click").replace("{tap}", touch ? "tap" : "click")
                .replace("{readCard}", readCard);
    }

    private static String raw(String key) {
        String raw = TfrTips.data().getString(key, null);
        if (raw == null) {
            System.err.println("[TFR-DuelPrimer] tips.json has no " + key);
            return "";
        }
        return raw;
    }

    /** Settings, How a Duel Works: the same pages, opened by the player's request. */
    public static void reopen(forge.adventure.scene.UIScene scene) {
        String[][] pages = pages();
        System.out.println("[TFR-DuelPrimer] reopened from Settings");
        showInScene(scene, pages, 0);
    }

    private static void showInScene(forge.adventure.scene.UIScene scene, String[][] pages, int i) {
        Dialog dialog = new Dialog("", Controls.getSkin());
        WarIntro.fill(dialog, pages, i, next -> {
            scene.removeDialog();
            showInScene(scene, pages, next);
        }, scene::removeDialog);
        scene.showDialog(dialog);
    }

    // ------------------------------------------------------------------ round 1048: the duel's Game menu

    /** Shown in the duel's Game menu: an adventure duel on a plane that has the pages (stock planes never). */
    public static boolean inGameMenu() {
        return forge.Forge.isMobileAdventureMode && available();
    }

    /**
     * The duel's Game menu, How a Duel Works: the same pages over the match in Forge's own popup (the duel screen is
     * not a scene2d stage, so the page dialog the world map uses cannot draw there). Only the UI opens a popup: the
     * game thread is not touched, so the match neither ends nor pauses, and closing the last page returns to it.
     */
    public static void openFromGameMenu() {
        String[][] pages;
        try {
            pages = pages();
        } catch (RuntimeException e) {
            System.err.println("[TFR-DuelPrimer] pages failed from the Game menu: " + e);
            return;
        }
        ResourceGlyphs.register(); // {Gold} in the popup (Assets clears the symbols on a reload)
        int turn = -1;
        try {
            turn = forge.screens.match.MatchController.instance.getGameView().getTurn();
        } catch (RuntimeException ignored) {
        }
        System.out.println("[TFR-DuelPrimer] reopened from the Game menu (source=game-menu, turn " + turn + ", "
                + (forge.gui.GuiBase.isMobile() ? "touch" : "desktop") + ")");
        showOverDuel(pages, 0);
    }

    // "Done", not the page dialog's "Understood": Forge's popup puts the last button in a narrow corner slot, where
    // "Understood" was squeezed to an unreadable size (agent shot, round 1048).
    static final String BACK = "Back", NEXT = "Next", CLOSE = "Close", DONE = "Done";

    /** The buttons of page {@code i} over the duel: Back (not on page 1), Next or Done, and Close mid-way. */
    static java.util.List<String> duelButtons(int i, int count) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (i > 0)
            out.add(BACK);
        out.add(i < count - 1 ? NEXT : DONE);
        if (i < count - 1)
            out.add(CLOSE);
        return out;
    }

    private static void showOverDuel(String[][] pages, int i) {
        java.util.List<String> buttons = duelButtons(i, pages.length);
        java.util.Set<String> symbols = forge.Forge.getAssets().symbolLookup().keySet();
        forge.toolbox.FOptionPane.showOptionDialog(forPopup(pages[i][1], symbols::contains),
                pages[i][0] + "  (" + (i + 1) + "/" + pages.length + ")", null, buttons,
                buttons.indexOf(i < pages.length - 1 ? NEXT : DONE), choice -> {
                    String b = choice == null || choice < 0 || choice >= buttons.size() ? CLOSE : buttons.get(choice);
                    if (BACK.equals(b))
                        showOverDuel(pages, i - 1);
                    else if (NEXT.equals(b))
                        showOverDuel(pages, i + 1);
                    else
                        System.out.println("[TFR-DuelPrimer] closed from the Game menu on page " + (i + 1) + " - back to the duel");
                });
    }

    /**
     * Pure for the unit test: a page's TextraLabel markup in the engine TextRenderer's terms - [GOLD] and [RED] as its
     * color tags, [+Gold] as the {Gold} symbol (the word when no symbol is registered), any other [TAG] dropped.
     */
    static String forPopup(String text, java.util.function.Predicate<String> hasSymbol) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([^\\]]*)\\]").matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String tag = m.group(1), r;
            if (tag.startsWith("+"))
                r = hasSymbol.test(tag.substring(1)) ? "{" + tag.substring(1) + "}" : tag.substring(1).toLowerCase();
            else if (tag.isEmpty())
                r = forge.assets.TextRenderer.endColor();
            else if ("GOLD".equals(tag))
                r = forge.assets.TextRenderer.startColor(com.badlogic.gdx.graphics.Color.GOLD);
            else if ("RED".equals(tag))
                r = forge.assets.TextRenderer.startColor(new com.badlogic.gdx.graphics.Color(1f, 0.45f, 0.4f, 1f));
            else
                r = "";
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** primer [reset | state] - test hooks (cheats): arm the pages again for this save, or say whether they are due. */
    public static String console(String[] s) {
        AdventurePlayer player = AdventurePlayer.current();
        if (player == null)
            return "no game loaded";
        int duels = player.getStatistic().totalWins() + player.getStatistic().totalLoss();
        String what = s.length > 0 ? s[0] : "";
        if ("reset".equals(what)) {
            player.setCharacterFlag(FLAG, 0);
            System.out.println("[TFR-DuelPrimer] console: flag cleared (" + duels + " duel(s) on record)");
            return "primer flag cleared - due only while no duel is on record (" + duels + " now)";
        }
        return "primer shown=" + (player.getCharacterFlag(FLAG) > 0) + " duels=" + duels + " due="
                + due(player.getCharacterFlag(FLAG) > 0, player.getCharacterFlag("newGamePlus") > 0, duels);
    }
}
