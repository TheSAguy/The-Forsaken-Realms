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
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 * <li>{@code [TFR-LogQuiet]} round 878: the engine's "Did not have activator set" line, once per caller a launch and
 * then a count per duel, in place of up to half the log.</li>
 * <li>{@code [TFR-Memory]} round 898: {@link MemoryWatch}, what the heap holds when it runs high.</li>
 * <li>{@code [TFR-LastExit]} round 948: {@link LastExit}, desktop only, how the last session ended.</li>
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
    private static boolean renderErrorLogged;

    // ---- the launch and the game ------------------------------------------------------------------------------------

    /** StartScene, once per launch, after the [TFR-Build] line. */
    public static void onStart() {
        QuietStream.install(); // round 878
        MemoryWatch.start(); // round 898
        LastExit.onStart(); // round 948: how the last desktop session ended
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
                .append(" difficulty=").append(p.getDifficulty() == null ? "?" : CustomDifficulty.logName(p)) // round 822: Custom(Hard)
                .append(" startedOn=").append(CustomDifficulty.startedOn(p)) // round 939: the level the run began on
                .append(" levelChanges=").append(CustomDifficulty.levelChanges(p))
                .append(" custom=").append(CustomDifficulty.count(p))
                .append(" rules=").append(CustomDifficulty.rules(p).isEmpty() ? "-" : CustomDifficulty.rules(p)) // round 825
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
        if ("exit".equals(why))
            LastExit.onCleanExit(); // round 948
        QuietStream.flush("exit".equals(why) ? "at exit" : "since the last duel"); // round 878
        try {
            if (firstDay < 0) {
                if ("exit".equals(why))
                    System.out.println("[TFR-Session] end (exit) no game this launch, " + hours(System.currentTimeMillis() - launchedAt)
                            + " in the menus, errors=" + errors + " lastScene=" + lastScene()
                            + " trail=" + trailText()); // round 878: where a player who never started a game gave up
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
                    + " images=" + imagesFetched + " errors=" + errors
                    + " custom=" + CustomDifficulty.count(AdventurePlayer.current()) // round 822: overrides, and changes this launch
                    + " rules=" + (CustomDifficulty.rules(AdventurePlayer.current()).isEmpty() ? "-"
                    : CustomDifficulty.rules(AdventurePlayer.current())) // round 825
                    + (CustomDifficulty.changesThisLaunch() > 0 ? " customChanges=" + CustomDifficulty.changesThisLaunch() : "")
                    + " lastScene=" + lastScene());
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
            String aiCap = forge.ai.LiteTurnCap.duelSummary(); // round 924
            if (aiCap != null)
                System.out.println(aiCap);
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
        QuietStream.flush("this duel"); // round 878
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
        if (scene instanceof DuelScene) {
            duelStartedAt = System.currentTimeMillis();
            MemoryWatch.onDuel(); // round 898
        }
        if (scene instanceof forge.adventure.scene.GameScene)
            SpriteSweep.onWorldMap(); // round 969: unload enemy atlases nothing draws from any more
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

    /**
     * Round 815 (the 2026-10-28 log review, P3: {@code errors=0} right after a render crash), BugReportDialog.show: a
     * crash report is on screen. BugReporter printed the trace already; this counts it for the footer, with the trail.
     */
    public static void onCrashReport(String report) {
        onCrashReport(report, true);
    }

    /** Round 934 (Fable P3-59): shown false when no dialog can open (an adventure scene has no Forge screen) - counted and
     *  logged all the same, where the crash outside a duel used to leave no line at all. */
    public static void onCrashReport(String report, boolean shown) {
        errors++;
        String first = report == null ? "?" : report.trim().split("\\R", 2)[0];
        System.out.println("[TFR-Error] crash report " + (shown ? "shown (the bug-report dialog)"
                : "not shown (no dialog outside a duel)") + ": " + first);
        System.out.println("[TFR-Error] trail: " + trailText());
        if (first.contains("OutOfMemoryError"))
            MemoryWatch.report("oom"); // round 916
    }

    /** Round 815, Classic.render's catch (no dialog there, and it can fail every frame): counted, the trail once a launch. */
    public static void onRenderError(Throwable ex) {
        errors++;
        if (renderErrorLogged)
            return;
        renderErrorLogged = true;
        System.out.println("[TFR-Error] render failed: " + ex + " (logged once a launch; the footer counts every one)");
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

    // ---- the activator flood (round 878) ------------------------------------------------------------------------------

    /**
     * Round 878 (the player-log reviews notes/logs-1029b.md P3-1, logs-1029c.md P3-4): SpellAbilityCondition.areMet
     * prints "&lt;card&gt; Did not have activator set in SpellAbility_Condition.checkConditions()" each time a mana
     * ability is asked about before anyone activated it - nearly all basic lands, 45% of the user's live log and 52-66%
     * of shared ones. The engine falls back to the card's controller, which is right (in the agent check every one came
     * from the AI's mulligan test, OpeningHand.producedColors, asking a land in hand or library what it makes), so the
     * line only buries the [TFR-*] lines. This stream wraps System.out (already forge.log's tee) so the stock file stays
     * untouched: the first time a caller (up to six a launch) prints it, the line goes out with its call chain; after
     * that it is counted, and a [TFR-LogQuiet] line gives the count per card at each duel's end (and at the footer for
     * any left over). Every other line passes.
     */
    private static final class QuietStream extends PrintStream {
        private static final String ACTIVATOR = " Did not have activator set in SpellAbility_Condition.checkConditions()";
        private static final int MAX_CHAINS = 6;
        private static final Map<String, Integer> counts = new LinkedHashMap<>();
        private static final Set<String> chains = new HashSet<>();
        private static int total;
        private static QuietStream installed;

        private QuietStream(PrintStream out) {
            super(out, true);
        }

        static synchronized void install() {
            if (installed != null || System.out instanceof QuietStream)
                return;
            installed = new QuietStream(System.out);
            System.setOut(installed);
        }

        @Override
        public void println(String line) {
            if (line == null || !line.endsWith(ACTIVATOR)) {
                super.println(line);
                return;
            }
            String card = line.substring(0, line.length() - ACTIVATOR.length());
            String[] keyAndChain = callers();
            String chain = keyAndChain[1];
            boolean first;
            int n;
            synchronized (QuietStream.class) {
                counts.merge(card, 1, Integer::sum);
                total++;
                first = chains.size() < MAX_CHAINS && chains.add(keyAndChain[0]);
                n = chains.size();
            }
            if (first) {
                super.println(line);
                super.println("[TFR-LogQuiet] the activator line above, caller " + n + "/" + MAX_CHAINS + " this launch: "
                        + chain + " (repeats are counted per duel)");
            }
        }

        /**
         * The frames above areMet, short: "SpellAbility.canThisProduce:298 < OpeningHand.producedColors:193 < ...", and
         * the key a caller is told apart by: its first frame outside forge.game.spellability ("OpeningHand.producedColors"),
         * so one caller reached by several paths (the AI's mulligan check) takes one of the MAX_CHAINS lines, not all.
         */
        private static String[] callers() {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            List<String> out = new ArrayList<>();
            String key = null;
            boolean seen = false;
            for (StackTraceElement e : st) {
                String cls = e.getClassName();
                if (!seen) {
                    seen = cls.endsWith("SpellAbilityCondition");
                    continue;
                }
                String shortName = cls.substring(cls.lastIndexOf('.') + 1) + "." + e.getMethodName();
                if (key == null && !cls.startsWith("forge.game.spellability."))
                    key = shortName;
                if (out.size() < 7)
                    out.add(shortName + ":" + e.getLineNumber());
                if (key != null && out.size() >= 7)
                    break;
            }
            String chain = out.isEmpty() ? "?" : String.join(" < ", out);
            return new String[] { key == null ? chain : key, chain };
        }

        /** The count since the last flush, per card (the most first), if there was any. */
        static void flush(String when) {
            String text;
            synchronized (QuietStream.class) {
                if (total == 0)
                    return;
                List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
                list.sort((a, b) -> b.getValue() - a.getValue());
                StringBuilder sb = new StringBuilder("[TFR-LogQuiet] \"Did not have activator set\" x").append(total)
                        .append(' ').append(when).append(':');
                int shown = 0, rest = 0;
                for (Map.Entry<String, Integer> e : list) {
                    if (shown++ < 8)
                        sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
                    else
                        rest += e.getValue();
                }
                if (rest > 0)
                    sb.append(" others=").append(rest);
                text = sb.toString();
                counts.clear();
                total = 0;
            }
            System.out.println(text);
        }
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
