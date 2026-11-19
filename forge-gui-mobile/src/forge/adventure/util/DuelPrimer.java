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
 * Settings, How a Duel Works opens them any time. [TFR-DuelPrimer].
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

    /** {title, body} per page, numbers filled from the running game. */
    public static String[][] pages() {
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
            String body = raw("duel.page" + i).replace("{lifePct}", String.valueOf(lifePct)).replace("{ante}", anteText);
            out[i - 1] = new String[]{TfrTips.fill(raw("duel.title" + i)), TfrTips.fill(body)};
        }
        return out;
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
