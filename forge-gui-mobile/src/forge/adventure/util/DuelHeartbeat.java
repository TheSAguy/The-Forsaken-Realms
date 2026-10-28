package forge.adventure.util;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.LoopBreaker;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.game.event.GameEvent;
import forge.gamemodes.match.HostedMatch;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Round 735 (a log review: players closed the game mid-rematch right after a loss, which skipped the loss and round
 * 726's reload count; the user: count it, but not when "the game is stuck or looping"): the pulse of the player's duel in
 * progress, kept in ReloadJab's store so a quit can be judged on the next load.
 *
 * <p>Every game event (a spell or ability put on the stack or resolved, an attack, a life change, a new turn or phase)
 * is "the game changed". Once a second a daemon thread also looks at the game's threads (Forge runs a game's work on a
 * pool of "Game-N" threads, so every one that posted an event): when one is inside the AI and the game has not changed
 * since, the AI is thinking. It notes whether round 708's LoopBreaker fired in this match, and
 * writes all of it at most every {@link #WRITE_MS} ms (sooner when who the game waits on changes). ReloadJab judges
 * the leftover record against {@link #STALL_MS}.
 *
 * <p>A finished game waits on the player's tap on the result screen, and DuelScene.GameEnd only runs after that tap. So
 * the pulse reads the result itself: a decided match is written as won, lost or drawn (a quit at the defeat screen is
 * a loss, whatever the clock says), and a game over with the match still going (best of three) keeps the pulse fresh -
 * the game is waiting on the player there, not stuck.
 */
public final class DuelHeartbeat {
    /** A quit this long after the game last changed, or with the AI thinking this long, was a stall: not counted. */
    public static final long STALL_MS = 20_000;
    private static final long SAMPLE_MS = 1_000;
    private static final long WRITE_MS = 5_000;

    private static final Listener LISTENER = new Listener();
    private static ScheduledExecutorService timer;
    private static ScheduledFuture<?> task;
    private static HostedMatch match;
    private static RegisteredPlayer human;
    private static String decided;
    private static Game subscribed;
    private static volatile long lastChangeAt;
    private static final Set<Thread> gameThreads = ConcurrentHashMap.newKeySet();
    private static long aiSince;
    private static boolean loopFired;
    private static String waitingOn = "nobody yet";
    private static long lastWriteAt;
    /** Round 783: the app is paused (Forge.pause) - the pulse holds still until it resumes. */
    private static boolean paused;
    /**
     * Round 816: nothing to mark - no duel of this launch's own is on record, or its record carries its error already.
     * True at launch, so a crash before any duel never marks a record left by the last session's quit.
     */
    private static volatile boolean errorMarked = true;

    private DuelHeartbeat() {
    }

    /** The game's own event bus, on the game thread: anything happening is the game changing. */
    public static final class Listener {
        @Subscribe
        public void onEvent(GameEvent event) {
            lastChangeAt = System.currentTimeMillis();
            Thread t = Thread.currentThread();
            if (t.getName().startsWith("Game")) // ThreadUtil.isGameThread()
                gameThreads.add(t);
        }
    }

    /**
     * DuelScene.enter, a duel of the player's own just started: the record begins (ReloadJab.onDuelInProgress). Round 795:
     * {@code notoriety} - a loss of it would cost Notoriety (not the Arena, not an Inn tournament).
     */
    public static synchronized void start(HostedMatch hostedMatch, RegisteredPlayer humanPlayer, String enemyName,
                                          boolean notoriety) {
        stop();
        errorMarked = true;
        if (hostedMatch == null || !ReloadJab.onDuelInProgress(enemyName, notoriety))
            return;
        errorMarked = false;
        match = hostedMatch;
        human = humanPlayer;
        decided = null;
        subscribed = null;
        gameThreads.clear();
        lastChangeAt = System.currentTimeMillis();
        aiSince = 0;
        loopFired = false;
        waitingOn = "nobody yet";
        lastWriteAt = lastChangeAt;
        paused = false;
        if (timer == null)
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "TFR-DuelHeartbeat");
                t.setDaemon(true);
                return t;
            });
        task = timer.scheduleWithFixedDelay(DuelHeartbeat::sample, SAMPLE_MS, SAMPLE_MS, TimeUnit.MILLISECONDS);
    }

    /** DuelScene.GameEnd / afterGameEnd, or the next duel's start: the match is over, the pulse stops. */
    public static synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        match = null;
        subscribed = null;
    }

    /**
     * Round 783 (the 2026-10-27 log review, P1: two Android logs ended paused mid-duel, then closed): Forge.pause() - the
     * app went to Android's background, or the window was minimized or is closing. One last pulse, the pause time in the
     * record, and no more writes: a quit is judged at the moment the player left, not by how long the app then sat in
     * the background. Before this, 20 s in the background made any unfinished duel "stalled", and a quick kill after
     * the AI's turn ran on in the background counted. The record's own pausedAt goes with the duel.
     */
    public static synchronized void onPause() {
        if (match == null || paused)
            return;
        lastWriteAt = 0; // the last pulse is written now, whatever the clock
        sample();
        paused = true;
        long now = System.currentTimeMillis();
        ReloadJab.duelPaused(now);
        System.out.println("[TFR-ReloadJab] the app left mid-duel - a quit from here is judged as of now (last change "
                + (now - lastChangeAt) / 1000 + " s ago, waiting on " + waitingOn + ")");
    }

    /** Round 783, Forge.resume(): back from the pause - the time away counts as a change, and the pulse goes on. */
    public static synchronized void onResume() {
        if (match == null || !paused)
            return;
        paused = false;
        lastChangeAt = System.currentTimeMillis();
        aiSince = 0;
        lastWriteAt = 0;
        ReloadJab.duelPaused(0);
        System.out.println("[TFR-ReloadJab] the app came back mid-duel - the pulse goes on");
    }

    /**
     * Round 816, BugReportDialog.show (a crash report) and Classic.render's catch: an error while the player's duel is
     * on. The record says so once a duel, and the next load never counts a quit from here (ReloadJab.duelError). Not
     * synchronized: a render error repeats every frame, so after the first one this is a field read.
     */
    public static void onError(String what) {
        if (errorMarked)
            return;
        errorMarked = true;
        try {
            ReloadJab.duelError(what);
        } catch (Throwable t) {
            System.err.println("[TFR-ReloadJab] marking the duel's error failed: " + t);
        }
    }

    private static synchronized void sample() {
        try {
            HostedMatch m = match;
            if (m == null || paused)
                return;
            long now = System.currentTimeMillis();
            Game game = m.getGame();
            if (game != null && game != subscribed) { // each game of a best-of-three is its own Game
                game.subscribeToEvents(LISTENER);
                subscribed = game;
                lastChangeAt = now;
            }
            if (game != null && !loopFired && LoopBreaker.firedIn(game))
                loopFired = true;
            Game last = game != null ? game : subscribed; // an all-AI seat's match drops its game when one ends
            if (last != null && last.isGameOver()) {
                String result = resultOf(m.getMatch());
                if (result == null)
                    lastChangeAt = now; // between games: the result screen waits on the player
                else if (!result.equals(decided)) {
                    decided = result;
                    ReloadJab.duelResult(result);
                }
            }
            String who = whoIsDeciding();
            long changed = lastChangeAt;
            if ("the AI".equals(who)) {
                if (aiSince == 0 || changed > aiSince)
                    aiSince = now; // thinking counts from the last change it made
            } else {
                aiSince = 0;
            }
            boolean shift = !who.equals(waitingOn);
            waitingOn = who;
            if (shift || now - lastWriteAt >= WRITE_MS) {
                lastWriteAt = now;
                ReloadJab.duelHeartbeat(changed, now, who, aiSince, loopFired);
            }
        } catch (Throwable t) {
            System.err.println("[TFR-ReloadJab] heartbeat sample failed: " + t);
        }
    }

    /** "won", "lost" or "draw" once the match is decided (DuelScene.isLastGameDrawn's test for a draw), else null. */
    private static String resultOf(Match m) {
        if (m == null)
            return null;
        GameOutcome lastOutcome = m.getLastOutcome();
        if (lastOutcome != null && lastOutcome.isDraw() && m.getWinner() == null)
            return "draw";
        if (!m.isMatchOver())
            return null;
        return human != null && human == m.getWinner() ? "won" : "lost";
    }

    /** Across the game's threads, the first of "the AI", "the player", "the engine", else "nobody" (between games). */
    private static String whoIsDeciding() {
        gameThreads.removeIf(t -> !t.isAlive());
        String best = "nobody";
        for (Thread t : gameThreads) {
            String who = whoIsOn(t);
            if (who.equals("the AI"))
                return who;
            if (who.equals("the player") || (who.equals("the engine") && best.equals("nobody")))
                best = who;
        }
        return best;
    }

    /**
     * What one game thread is on: "the AI" (running AI code, or waiting on the AI's own timed evaluation), "the player"
     * (parked waiting for input), "the engine" (running game code), or "nobody" (an idle pool thread).
     */
    private static String whoIsOn(Thread t) {
        Thread.State state = t.getState();
        StackTraceElement[] stack = t.getStackTrace();
        boolean running = state == Thread.State.RUNNABLE;
        for (StackTraceElement frame : stack) {
            String c = frame.getClassName();
            if (!c.startsWith("forge."))
                continue;
            if (c.startsWith("forge.ai."))
                return "the AI";
            if (!running)
                return "the player"; // parked in the human's input or a choice dialog
        }
        return running ? "the engine" : "nobody";
    }
}
