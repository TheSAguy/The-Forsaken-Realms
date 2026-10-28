package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import forge.adventure.character.EnemySprite;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.DuelScene;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.MapStage;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.GameView;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.PlayerStatistics;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.screens.match.MatchController;

import java.io.File;
import java.util.Locale;
import java.util.Map;

/**
 * Round 777 (the user: "Is there anything we need to add to the logs we receive to help us improve the game ... if they
 * received the donate prompt and what towns they select. Anything else ... to help us improve / catch errors?"; the
 * audit is notes/log-additions-proposal.md). The summary lines a shared log is read by, so a review starts from a few
 * greppable lines instead of thousands:
 * <ul>
 * <li>{@code [TFR-Session] start} once per launch (version, OS, Java, screen); {@code game load} / {@code game new} for
 * every load and new game (slot, day, level, difficulty, mode, NG+, the rule options); {@code end} at exit, and at a
 * pause when something changed (Android may never come back): duels, reloads after a loss, days, towns, gold.</li>
 * <li>{@code [TFR-Duel]} one line per duel: result, enemy, where, day, turns, both lives, mulligans, seconds.</li>
 * <li>{@code [TFR-Towns]} the held towns and their specialties after each load; {@code taken:} for each town won.</li>
 * <li>{@code [TFR-Perf] save / load} timings.</li>
 * <li>{@code [TFR-Error]} for exceptions Forge.java used to swallow without Sentry, with the trail of the last scenes.</li>
 * <li>{@code [TFR-Images]} one line per 25 card images downloaded, in place of four lines per image.</li>
 * </ul>
 * No character name, user name or path: save slots, world town names and game numbers only. Console: {@code session},
 * {@code session error}.
 */
public final class SessionLog {
    private SessionLog() {
    }

    private static final int TRAIL_SIZE = 8;
    private static final String[] trail = new String[TRAIL_SIZE];
    private static int trailNext, trailCount;

    private static final long launchedAt = System.currentTimeMillis();
    private static int duels, won, lost, draws, reloadsAfterLoss, errors, imagesFetched, imagesFailed;
    private static boolean lastDuelLost;
    private static long duelStartedAt;
    // The first game of this launch, for the footer's "from -> to".
    private static int firstDay = -1, firstLevel, firstTowns, firstGold;
    private static String lastFooterKey;
    private static boolean supportStatusLogged;
    private static boolean throwOnNextScreen;

    // ---- the launch and the game ------------------------------------------------------------------------------------

    /** StartScene, once per launch, after the [TFR-Build] line. */
    public static void onStart() {
        try {
            String os = GuiBase.isAndroid() ? "Android " + GuiBase.getAndroidAPILevel() : System.getProperty("os.name", "?");
            String version = Config.instance().getConfigData().modVersion;
            System.out.println("[TFR-Session] start tfr=" + (version == null ? "?" : version) + " os=\"" + os + "\" java="
                    + System.getProperty("java.version", "?") + " screen=" + Gdx.graphics.getWidth() + "x"
                    + Gdx.graphics.getHeight() + " fullscreen=" + Gdx.graphics.isFullscreen() + " videomode="
                    + Config.instance().getSettingData().videomode);
        } catch (Exception e) {
            System.out.println("[TFR-Session] start line failed: " + e);
        }
    }

    /** WorldSave.load, after a load that worked. */
    public static void onGameLoaded(int slot, long ms) {
        try {
            File file = new File(WorldSave.getSaveFile(slot));
            System.out.println("[TFR-Perf] load slot=" + slotName(slot) + " took " + ms + " ms (" + file.length() / 1024 + " KB)");
            if (lastDuelLost)
                reloadsAfterLoss++;
            lastDuelLost = false;
            System.out.println("[TFR-Session] game load slot=" + slotName(slot) + " " + gameText(true));
            townsSummary();
            firstGame();
        } catch (Exception e) {
            System.out.println("[TFR-Session] load line failed: " + e);
        }
    }

    /** WorldSave.generateNewWorld, at its end. */
    public static void onNewGame(String starterEdition, String colorId) {
        try {
            lastDuelLost = false;
            System.out.println("[TFR-Session] game new color=" + colorId + " starter=" + starterEdition + " "
                    + gameText(false));
            firstGame();
        } catch (Exception e) {
            System.out.println("[TFR-Session] new game line failed: " + e);
        }
    }

    /** WorldSave.save, after a save that worked. */
    public static void onSaved(int slot, long ms) {
        System.out.println("[TFR-Perf] save slot=" + slotName(slot) + " took " + ms + " ms ("
                + new File(WorldSave.getSaveFile(slot)).length() / 1024 + " KB)");
    }

    private static String slotName(int slot) {
        return slot == WorldSave.AUTO_SAVE_SLOT ? "auto" : slot == WorldSave.QUICK_SAVE_SLOT ? "quick" : String.valueOf(slot);
    }

    private static void firstGame() {
        if (firstDay < 0) {
            firstDay = day();
            firstLevel = Ascendance.level();
            firstTowns = TownRestoration.countPlayerTowns();
            firstGold = AdventurePlayer.current().getGold();
        }
        if (!supportStatusLogged) {
            supportStatusLogged = true;
            SupportPrompt.logStatus();
        }
    }

    private static String gameText(boolean loaded) {
        AdventurePlayer p = AdventurePlayer.current();
        StringBuilder sb = new StringBuilder();
        if (loaded)
            sb.append("day=").append(day()).append(" level=").append(Ascendance.level()).append(' ');
        sb.append("race=").append(p.raceName())
                .append(" colors=").append(p.getColorIdentityLong())
                .append(" mode=").append(p.getAdventureMode())
                .append(" difficulty=").append(p.getDifficulty() == null ? "?" : p.getDifficulty().name)
                .append(" ngplus=").append(p.getCharacterFlag("newGamePlus") > 0 ? 1 : 0)
                .append(" notoriety=").append(AdventurePlayer.notorietyDisabled() ? "off" : "on")
                .append(" specialties=").append(p.hasTownSpecialties() ? "on" : "off")
                .append(" milestones=").append(p.getRealmMilestones() != null ? "on" : "off")
                .append(" research=").append(p.isResearchV2() ? "v2" : "classic");
        if (loaded)
            sb.append(" towns=").append(TownRestoration.countPlayerTowns())
                    .append(" gold=").append(p.getGold()).append(" shards=").append(p.getShards());
        sb.append(" deck=").append(p.getSelectedDeck() == null ? 0 : p.getSelectedDeck().getMain().countAll());
        return sb.toString();
    }

    private static int day() {
        World world = WorldSave.getCurrentSave() == null ? null : WorldSave.getCurrentSave().getWorld();
        return world == null ? 0 : world.getCurrentDay();
    }

    /** Forge.pause() ("pause", printed only when something changed since the last footer) and Forge.dispose() ("exit"). */
    public static void end(String why) {
        try {
            if (firstDay < 0) {
                if ("exit".equals(why))
                    System.out.println("[TFR-Session] end (exit) no game this launch, " + hours(System.currentTimeMillis() - launchedAt)
                            + " in the menus, errors=" + errors + " lastScene=" + lastScene());
                return;
            }
            String key = duels + "/" + day() + "/" + TownRestoration.countPlayerTowns() + "/" + errors;
            if (("pause".equals(why) || "exit".equals(why)) && key.equals(lastFooterKey))
                return; // nothing new since the last footer (an exit right after its own pause, a second minimize)
            lastFooterKey = key;
            System.out.println("[TFR-Session] end (" + why + ") launched " + hours(System.currentTimeMillis() - launchedAt)
                    + " ago, played " + String.format(Locale.ROOT, "%.2f", SupportPrompt.hours()) + " h in all, duels=" + duels
                    + " won=" + won + " lost=" + lost + " draws=" + draws + " reloadsAfterLoss=" + reloadsAfterLoss
                    + " days=" + firstDay + "->" + day() + " level=" + firstLevel + "->" + Ascendance.level()
                    + " towns=" + firstTowns + "->" + TownRestoration.countPlayerTowns()
                    + " gold=" + signed(AdventurePlayer.current().getGold() - firstGold)
                    + " images=" + imagesFetched + " errors=" + errors + " lastScene=" + lastScene());
        } catch (Exception e) {
            System.out.println("[TFR-Session] end line failed: " + e);
        }
    }

    private static String hours(long ms) {
        return String.format(Locale.ROOT, "%.2f h", ms / 3600000d);
    }

    private static String signed(int n) {
        return n >= 0 ? "+" + n : String.valueOf(n);
    }

    // ---- duels ------------------------------------------------------------------------------------------------------

    /** DuelScene.GameEnd, once the result is known (before the ante popups, so a quit in them still logs). */
    public static void onDuelEnd(EnemySprite enemy, Game game, Match match, RegisteredPlayer human, boolean winner,
                                 boolean drawn, boolean arena, boolean event, boolean watched) {
        try {
            String result = drawn ? "draw" : winner ? "won" : "lost";
            if (!watched) {
                duels++;
                if (drawn)
                    draws++;
                else if (winner)
                    won++;
                else
                    lost++;
                lastDuelLost = !winner && !drawn;
            }
            int turns = -1, mulligans = -1;
            String life = "?", enemyLife = "?";
            if (game != null) {
                turns = game.getPhaseHandler().getTurn();
                // registered players, not getPlayers(): the loser has already left getPlayers() (round 802)
                for (Player p : game.getRegisteredPlayers()) {
                    String l = p.getLife() + "/" + p.getStartingLife();
                    if (p.getRegisteredPlayer() == human) {
                        life = l;
                        mulligans = p.getStats().getMulliganCount();
                    } else {
                        enemyLife = l;
                    }
                }
            } else if (match != null && !match.getOutcomes().isEmpty()) {
                GameOutcome last = null;
                for (GameOutcome go : match.getOutcomes())
                    last = go;
                turns = last.getLastTurnNumber();
                for (Map.Entry<RegisteredPlayer, PlayerStatistics> e : last)
                    if (e.getKey() == human)
                        mulligans = e.getValue().getMulliganCount();
                // The usual case: HostedMatch has already let go of the game. The match screen keeps its view.
                GameView view = MatchController.instance.getGameView();
                String humanName = human == null || human.getPlayer() == null ? null : human.getPlayer().getName();
                if (view != null && view.getPlayers() != null)
                    for (PlayerView pv : view.getPlayers()) {
                        if (humanName != null && humanName.equals(pv.getLobbyPlayerName()))
                            life = String.valueOf(pv.getLife());
                        else
                            enemyLife = String.valueOf(pv.getLife());
                    }
            }
            long secs = duelStartedAt > 0 ? (System.currentTimeMillis() - duelStartedAt) / 1000 : -1;
            System.out.println("[TFR-Duel] " + result + " enemy=\"" + (enemy == null ? "?" : enemy.getTieredDisplayName())
                    + "\" boss=" + (enemy != null && enemy.getData().boss) + " arena=" + arena + " event=" + event
                    + (watched ? " watched=true" : "") + " where=" + where() + " day=" + day() + " turns=" + turns
                    + " life=" + life + " enemyLife=" + enemyLife + " mulligans=" + mulligans + " secs=" + secs);
        } catch (Exception e) {
            System.out.println("[TFR-Duel] line failed: " + e);
        }
    }

    private static String where() {
        if (!MapStage.getInstance().isInMap())
            return "world";
        PointOfInterest poi = TileMapScene.instance() == null ? null : TileMapScene.instance().rootPoint;
        return poi == null ? "map" : "\"" + poi.getDisplayName() + "\"";
    }

    // ---- towns ------------------------------------------------------------------------------------------------------

    /** After each load: every held town and its specialty ("seat" for the Capitol and Orazca, "-" none yet). */
    public static void townsSummary() {
        WorldSave save = WorldSave.getCurrentSave();
        int held = 0, unchosen = 0;
        int[] counts = new int[TownSpecialties.Specialty.values().length];
        StringBuilder list = new StringBuilder();
        for (PointOfInterest poi : save.getWorld().getAllPointOfInterest()) {
            PointOfInterestChanges changes = save.peekPointOfInterestChanges(poi.getID());
            if (!TownRestoration.isTownRestored(changes))
                continue;
            held++;
            String what;
            if (TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name) || TownRestoration.isOrazca(poi.getData())) {
                what = "seat";
            } else {
                TownSpecialties.Specialty s = TownSpecialties.of(changes);
                if (s == null) {
                    unchosen++;
                    what = "-";
                } else {
                    counts[s.ordinal()]++;
                    what = s.label;
                }
            }
            list.append(list.length() == 0 ? "" : ", ").append(poi.getDisplayName()).append('=').append(what);
        }
        StringBuilder sb = new StringBuilder("[TFR-Towns] held=").append(held);
        if (TownSpecialties.enabled()) {
            for (TownSpecialties.Specialty s : TownSpecialties.Specialty.values())
                sb.append(' ').append(s.label).append('=').append(counts[s.ordinal()]);
            sb.append(" unchosen=").append(unchosen);
        } else {
            sb.append(" (no specialties in this save)");
        }
        System.out.println(sb.append(" | ").append(list));
    }

    /** TownRestoration: a town the player just won - "restore" (the fee), "capture" (an assault), "gates" (a Ring City). */
    public static void townTaken(String town, String how) {
        System.out.println("[TFR-Towns] taken: " + town + " by " + how + " on day " + day() + " (towns held "
                + TownRestoration.countPlayerTowns() + ")");
    }

    // ---- the trail and errors ---------------------------------------------------------------------------------------

    /** Forge.switchScene / switchToLast: one step of the trail; a duel's clock starts here. */
    public static void onScene(Object scene) {
        if (scene instanceof DuelScene)
            duelStartedAt = System.currentTimeMillis();
        String label = scene == null ? "none" : scene.getClass().getSimpleName();
        if (scene instanceof TileMapScene && ((TileMapScene) scene).rootPoint != null)
            label += ":" + ((TileMapScene) scene).rootPoint.getDisplayName();
        addTrail(label);
    }

    /** Forge.setCurrentScreen: the classic screens (the duel's match screen) join the trail too. */
    public static void onScreen(Object screen) {
        if (screen != null)
            addTrail("screen:" + screen.getClass().getSimpleName());
    }

    private static synchronized void addTrail(String label) {
        String lastAdded = trailCount == 0 ? null : trail[(trailNext + TRAIL_SIZE - 1) % TRAIL_SIZE];
        if (label.equals(lastAdded))
            return;
        trail[trailNext] = label;
        trailNext = (trailNext + 1) % TRAIL_SIZE;
        trailCount = Math.min(TRAIL_SIZE, trailCount + 1);
    }

    private static synchronized String trailText() {
        StringBuilder sb = new StringBuilder();
        for (int i = trailCount; i > 0; i--)
            sb.append(sb.length() == 0 ? "" : " > ").append(trail[(trailNext + TRAIL_SIZE - i) % TRAIL_SIZE]);
        return sb.length() == 0 ? "(empty)" : sb.toString();
    }

    private static synchronized String lastScene() {
        return trailCount == 0 ? "none" : trail[(trailNext + TRAIL_SIZE - 1) % TRAIL_SIZE];
    }

    /** Forge.java's catch blocks that used to report only to Sentry (off by default): always the trace and the trail. */
    public static void onError(String where, Throwable ex) {
        errors++;
        System.out.println("[TFR-Error] " + where + " failed: " + ex);
        ex.printStackTrace(System.out);
        System.out.println("[TFR-Error] trail: " + trailText());
    }

    /** Forge.setCurrentScreen, inside its try: the console's "session error" throws here once, to see [TFR-Error]. */
    public static void testThrow() {
        if (throwOnNextScreen) {
            throwOnNextScreen = false;
            throw new IllegalStateException("forced by the console's \"session error\" (a test)");
        }
    }

    // ---- card images ------------------------------------------------------------------------------------------------

    /** LibGDXImageFetcher: one line per 25 images instead of four lines per image; failures keep their own lines. */
    public static synchronized void imageFetched(boolean ok) {
        if (ok)
            imagesFetched++;
        else
            imagesFailed++;
        if (ok && imagesFetched % 25 == 0)
            System.out.println("[TFR-Images] " + imagesFetched + " card image(s) downloaded this launch (" + imagesFailed
                    + " failed)");
    }

    // ---- the console --------------------------------------------------------------------------------------------------

    /** "session" - the summary lines now; "session error" - the next screen change throws once (Forge.setCurrentScreen). */
    public static String console(String[] args) {
        if (args.length > 0 && "error".equalsIgnoreCase(args[0])) {
            throwOnNextScreen = true;
            System.out.println("[TFR-Session] console: the next screen change throws once");
            return "The next screen change throws once - grep forge.log for [TFR-Error].";
        }
        if (WorldSave.getCurrentSave() == null || AdventurePlayer.current() == null)
            return "No game running.";
        System.out.println("[TFR-Session] console: game " + gameText(true));
        townsSummary();
        SupportPrompt.logStatus();
        lastFooterKey = null;
        end("console");
        return "Session lines written to forge.log ([TFR-Session], [TFR-Towns], [TFR-Support] status).";
    }
}
