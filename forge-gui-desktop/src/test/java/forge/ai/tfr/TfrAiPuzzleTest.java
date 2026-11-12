package forge.ai.tfr;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import com.google.common.eventbus.Subscribe;

import forge.ai.AITest;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.event.GameEvent;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgeConstants;
import forge.player.GamePlayerUtil;
import forge.util.FileSection;
import forge.util.MyRandom;
import forge.view.TimeLimitedCodeBlock;

import static org.testng.Assert.fail;

/**
 * TFR round 1018: the TFR AI rules' regression suite. Plays every {@code res/ai/tfr-puzzles/*.pzl} with the rules on and
 * off, {@code tfr.puzzles.runs} times each (3), at each AI strength in {@code tfr.puzzles.strengths}
 * ({@code off,lite,max}: the heuristic alone, Android's Flat Monte Carlo, the desktop's). A puzzle is Forge's {@code [state]} format, played by two
 * AIs ("ai" is the seat under test), plus {@code [metadata]} lines:
 * <ul>
 *   <li>{@code Turns:N}: stop after N turns;</li>
 *   <li>{@code Expect:...}: checked at the end: {@code winner=ai}, {@code humanlife<=N}, {@code ailife>=N},
 *   {@code <zone> has|lacks NAME} (zone = aibattlefield, humangraveyard, ...), {@code fired <rule id>=N};</li>
 *   <li>{@code RulesOff:pass|fail} or per strength {@code RulesOff:off=fail,lite=fail,max=pass}: what the puzzle does
 *   with the rules off (default pass). With the rules on every puzzle must pass every run.</li>
 * </ul>
 * A puzzle expected to fail with the rules off that passes every run is upstream fixing what the rule covers: the test
 * fails with "retire" so the merge round sees it (merge protocol in {@code CORE_ENGINE_CHANGES.md}).
 * Run: {@code mvn -pl forge-gui-desktop -am test -Dtest=TfrAiPuzzleTest -Dsurefire.failIfNoSpecifiedTests=false}
 * (add {@code -Dtfr.puzzles.only=P3} for one puzzle). The table goes to {@code target/tfr-ai-puzzles.txt}.
 */
public class TfrAiPuzzleTest extends AITest {
    private static final List<String> TABLE = new ArrayList<>();

    /** GameState.applyToGame hands off to a game thread; the puzzle applies in place, at the game's start hook. */
    static final class DirectState extends GameState {
        void applyNow(final Game g) {
            applyGameOnThread(g);
            for (final Player p : g.getPlayers()) {
                p.setMaxHandSize(7);
            }
        }
    }

    @Test
    public void everyPuzzleWithRulesOnAndOff() throws Exception {
        final File dir = new File(ForgeConstants.AI_PROFILE_DIR, "tfr-puzzles");
        final File[] files = dir.listFiles((d, n) -> n.endsWith(".pzl"));
        if (files == null || files.length == 0) {
            fail("no puzzles in " + dir.getAbsolutePath());
        }
        Arrays.sort(files);
        final int runs = Integer.getInteger("tfr.puzzles.runs", 3);
        final String[] strengths = System.getProperty("tfr.puzzles.strengths", "off,lite,max").split(",");
        final String only = System.getProperty("tfr.puzzles.only", "");
        final List<String> problems = new ArrayList<>();
        TABLE.add(String.format("%-36s %-5s %-9s %-9s %s", "puzzle", "mc", "rules on", "rules off", "verdict"));
        try {
            for (final File f : files) {
                if (!only.isEmpty() && !f.getName().startsWith(only)) {
                    continue;
                }
                final Map<String, List<String>> sec = FileSection.parseSections(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
                for (final String mc : strengths) {
                    final int on = play(f, sec, mc.trim(), true, runs);
                    final int off = play(f, sec, mc.trim(), false, runs);
                    final boolean offFails = "fail".equals(rulesOff(sec, mc.trim()));
                    String verdict = "ok";
                    if (on < runs) {
                        verdict = "RULES ON FAILS";
                    } else if (offFails && off == runs) {
                        verdict = "RETIRE? passes with the rules off";
                    } else if (!offFails && off < runs) {
                        verdict = "RULES OFF FAILS (expected pass)";
                    }
                    final String row = String.format("%-36s %-5s %d/%-7d %d/%-7d %s", f.getName(), mc, on, runs, off, runs, verdict);
                    TABLE.add(row);
                    System.out.println("[TfrAiPuzzleTest] " + row);
                    if (!"ok".equals(verdict)) {
                        problems.add(row);
                    }
                }
            }
        } finally {
            TfrAiRules.force(null);
        }
        if (!problems.isEmpty()) {
            fail("TFR AI puzzles:\n" + String.join("\n", problems));
        }
    }

    @AfterClass(alwaysRun = true)
    public void writeTable() throws Exception {
        System.out.println("[TfrAiPuzzleTest] rules v" + TfrAiRules.version() + "\n" + String.join("\n", TABLE));
        final File out = new File("target", "tfr-ai-puzzles.txt");
        out.getParentFile().mkdirs();
        try (PrintWriter w = new PrintWriter(out, StandardCharsets.UTF_8)) {
            w.println("rules v" + TfrAiRules.version());
            TABLE.forEach(w::println);
        }
    }

    /** What the puzzle is expected to do with the rules off at this strength: pass (default) or fail. */
    static String rulesOff(final Map<String, List<String>> sec, final String mc) {
        for (final String m : sec.get("metadata")) {
            if (!m.startsWith("RulesOff:")) {
                continue;
            }
            final String v = m.substring(9).trim();
            if (!v.contains("=")) {
                return v;
            }
            for (final String part : v.split(",")) {
                final String[] kv = part.trim().split("=");
                if (kv.length == 2 && kv[0].trim().equals(mc)) {
                    return kv[1].trim();
                }
            }
        }
        return "pass";
    }

    /** Plays the puzzle {@code runs} times; returns how many runs met every Expect line. */
    static int play(final File f, final Map<String, List<String>> sec, final String mc, final boolean rulesOn, final int runs) throws Exception {
        int turns = 1;
        final List<String> expects = new ArrayList<>();
        for (final String m : sec.get("metadata")) {
            if (m.startsWith("Turns:")) {
                turns = Integer.parseInt(m.substring(6).trim());
            }
            if (m.startsWith("Expect:")) {
                expects.add(m.substring(7).trim());
            }
        }
        int passes = 0;
        for (int r = 1; r <= runs; r++) {
            TfrAiRules.force(rulesOn);
            MyRandom.setRandom(new Random(1018L * 31 + r));
            final DirectState state = new DirectState();
            state.parse(sec.get("state"));
            final List<RegisteredPlayer> pp = new ArrayList<>();
            final RegisteredPlayer human = new RegisteredPlayer(new Deck());
            human.setStartingHand(0);
            human.setPlayer(GamePlayerUtil.createAiPlayer("Human", 0, "Default"));
            pp.add(human);
            final RegisteredPlayer ai = new RegisteredPlayer(new Deck());
            ai.setStartingHand(0);
            ai.setPlayer(GamePlayerUtil.createAiPlayer("AI", 1, "Default"));
            pp.add(ai);
            final Match match = new Match(new GameRules(GameType.Constructed), pp, "TFR puzzle");
            final Game game = match.createGame();
            game.setNoGUIUser();
            game.AI_FLAT_MONTE_CARLO = !"off".equals(mc);
            game.AI_FLAT_MONTE_CARLO_MAX = "max".equals(mc);
            game.AI_FLAT_MONTE_CARLO_LITE = "lite".equals(mc);
            final int limit = turns;
            game.subscribeToEvents(new Object() {
                int seen = 0;

                @Subscribe
                public void on(final GameEvent ev) {
                    if (ev instanceof GameEventTurnBegan && ++seen > limit) {
                        game.setGameOver(GameEndReason.Draw);
                    }
                }
            });
            TfrAiRules.resetFireCounts();
            final long t0 = System.currentTimeMillis();
            try {
                TimeLimitedCodeBlock.runWithTimeout(() -> match.startGame(game, () -> state.applyNow(game)), 300, TimeUnit.SECONDS);
            } catch (final Exception | StackOverflowError e) {
                System.out.println("[TfrAiPuzzleTest] " + f.getName() + " stopped: " + e);
            } finally {
                if (!game.isGameOver()) {
                    game.setGameOver(GameEndReason.Draw);
                }
            }
            final Map<String, Integer> fired = new HashMap<>(TfrAiRules.fireCounts());
            final Player hp = game.getRegisteredPlayers().get(0);
            final Player ap = game.getRegisteredPlayers().get(1);
            boolean ok = true;
            final StringBuilder why = new StringBuilder();
            for (final String ex : expects) {
                final boolean pass = check(ex, hp, ap, fired);
                ok &= pass;
                why.append(pass ? " pass: " : " FAIL: ").append(ex).append(';');
            }
            if (ok) {
                passes++;
            }
            System.out.println("[TfrAiPuzzleTest] " + f.getName() + " mc=" + mc + " rules=" + (rulesOn ? "on" : "off") + " run " + r
                    + (ok ? " PASS" : " FAIL") + " in " + (System.currentTimeMillis() - t0) + " ms (human life " + hp.getLife()
                    + ", ai life " + ap.getLife() + ", fired " + fired + ")" + why);
        }
        return passes;
    }

    static boolean check(final String ex, final Player hp, final Player ap, final Map<String, Integer> fired) {
        if (ex.equals("winner=ai")) {
            return hp.hasLost() && !ap.hasLost();
        }
        if (ex.startsWith("humanlife<=")) {
            return hp.getLife() <= Integer.parseInt(ex.substring(11));
        }
        if (ex.startsWith("ailife>=")) {
            return ap.getLife() >= Integer.parseInt(ex.substring(8));
        }
        if (ex.startsWith("fired ")) {
            final String[] kv = ex.substring(6).split("=");
            return fired.getOrDefault(kv[0].trim(), 0) == Integer.parseInt(kv[1].trim());
        }
        final String[] w = ex.split(" ", 3);
        final Player p = w[0].startsWith("human") ? hp : ap;
        final ZoneType z = ZoneType.smartValueOf(w[0].replaceFirst("^(human|ai)", ""));
        boolean has = false;
        for (final Card c : p.getCardsIn(z)) {
            if (c.getName().equalsIgnoreCase(w[2])) {
                has = true;
            }
        }
        return w[1].equals("has") == has;
    }
}
