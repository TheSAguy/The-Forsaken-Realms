package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.player.GamePlayerUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Deck Tester "AI vs AI - No Watch" mode (user spec, 2026-08-13): runs a batch of independent,
 * fully-headless single-game matches between two decks, both AI-piloted, with no scene switch,
 * no HostedMatch/MatchController/spectator pacing at all - just forge-game's own Match/Game
 * engine, exactly the pattern forge-gui-desktop's SimulateMatch.simulateSingleMatch() uses (not
 * reusable directly - that class lives in forge-gui-desktop, which forge-gui-mobile doesn't
 * depend on), run in a loop on a background thread so it doesn't block the render thread. By
 * user decision, matches are a pure, symmetric deck-vs-deck comparison: both sides get the
 * engine's ordinary default starting life/hand (RegisteredPlayer's own defaults - 20 life, 7
 * cards), no player equipped-item/blessing effects, no difficulty scaling, no ante - unlike the
 * existing "Watch" mode, which treats one seat as "the player" (real life/shards/items) and the
 * other as "the enemy" (difficulty-scaled life). Each game is 1-game-per-match
 * (gamesToWinMatch=1, matching a single trial) with its own fresh RegisteredPlayer/Match/Game so
 * no state (extra battlefield cards, etc.) can leak between trials.
 */
public class DeckTesterSimulator {

    public static class BatchResult {
        public int deckAWins = 0;
        public int deckBWins = 0;
        public int draws = 0;
        public int completed = 0;
        public int total = 0;
    }

    // One game per trial is expected to resolve in well under a minute for Adventure-scale AI
    // decks - a per-game timeout guards the whole background batch against a single stuck/looping
    // game hanging it indefinitely (mirrors GameRules.getSimTimeout()'s own 120s default, kept
    // tighter here since we're running many of these back to back).
    private static final int PER_GAME_TIMEOUT_SECONDS = 90;

    /** Cancellation handle returned by {@link #runBatch}: user-facing "End Test" (2026-08-13
     *  follow-up). Calling {@link #cancel()} stops the batch at the next poll (at most ~0.5s
     *  away, even mid-game - see the poll loop below) and still fires onComplete with whatever
     *  partial tally exists, so the caller's UI can never be left waiting indefinitely. */
    public static class Handle {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        public void cancel() { cancelled.set(true); }
    }

    /**
     * Runs {@code count} independent games between deckA and deckB on a background thread.
     * {@code onProgress} fires after each completed game (marshaled onto the GDX render thread
     * via Gdx.app.postRunnable, so it's safe to touch UI directly), {@code onComplete} fires once
     * with the final tally (partial if cancelled). Both callbacks may be null. Returns a
     * {@link Handle} the caller can cancel() to abort the whole batch on demand.
     */
    public static Handle runBatch(String deckAName, Deck deckA, String deckBName, Deck deckB, int count,
                                 IntConsumer onProgress, Consumer<BatchResult> onComplete) {
        // 0 life = "leave the format default alone", which is what every existing caller wants.
        return runBatch(deckAName, deckA, 0, deckBName, deckB, 0, count, onProgress, onComplete);
    }

    /**
     * Round 145 (MOD_SCOPE #116): the same batch, with explicit starting life for either seat.
     * Roaming guards need it - a guard's rank IS its life total, and its opponent is a mage with
     * its own. Without this a simulated guard fight would silently be played at different life
     * totals from a watched one, which would make the Watch/Simulate toggle a balance decision
     * rather than a presentation one.
     */
    public static Handle runBatch(String deckAName, Deck deckA, int lifeA, String deckBName, Deck deckB, int lifeB,
                                  int count, IntConsumer onProgress, Consumer<BatchResult> onComplete) {
        return runBatch(deckAName, deckA, lifeA, null, deckBName, deckB, lifeB, null, count, onProgress, onComplete);
    }

    /**
     * Round 163 (MOD_SCOPE #118): the same batch with a per-seat hook that runs on each freshly built
     * RegisteredPlayer after its life is set. Roaming guards use it to apply their equipment through
     * DuelScene.applyEffects(), so a simulated guard fight honours the same items a watched one does.
     * Either hook may be null.
     */
    public static Handle runBatch(String deckAName, Deck deckA, int lifeA, Consumer<RegisteredPlayer> customizeA,
                                  String deckBName, Deck deckB, int lifeB, Consumer<RegisteredPlayer> customizeB,
                                  int count, IntConsumer onProgress, Consumer<BatchResult> onComplete) {
        Handle handle = new Handle();
        Thread batchThread = new Thread(() -> {
            BatchResult result = new BatchResult();
            result.total = count;
            // Adversarial review (2026-08-13) - the original version only wrapped the game-run
            // itself in try/catch; per-game SETUP (RegisteredPlayer.forVariants/new Match/
            // createGame/getWinner) and the pre-loop createAiPlayer calls were unprotected, so any
            // exception there (this headless path bypasses whatever legality checks a normal
            // GameLobby/HostedMatch flow applies to a deck) killed this thread before it ever
            // reached onComplete - and since ArenaScene.launchDeckTesterBatch's onComplete callback
            // is the ONLY place that resets `enable` back to true for this flow, that permanently
            // soft-locked the Arena screen (no exit, no restart, an undismissable progress dialog)
            // until the app restarted. Two layers now: an inner per-game catch keeps one bad game
            // from aborting the whole batch (counts as a draw, same as a timeout), and an outer
            // try/finally guarantees onComplete ALWAYS fires - even if something throws before the
            // loop even starts - so the caller's UI can never get stuck waiting on it.
            try {
                Set<GameType> variants = EnumSet.of(GameType.Adventure);
                LobbyPlayer playerA = GamePlayerUtil.createAiPlayer(deckAName, "");
                LobbyPlayer playerB = GamePlayerUtil.createAiPlayer(deckBName, "");
                for (int i = 0; i < count; i++) {
                    if (handle.cancelled.get()) {
                        System.out.println("[TFR-DeckTesterBatch] batch ended by user after "
                                + result.completed + "/" + count + " games");
                        break;
                    }
                    RegisteredPlayer winner = null;
                    RegisteredPlayer rpA = null, rpB = null;
                    boolean userAborted = false;
                    try {
                        rpA = RegisteredPlayer.forVariants(2, variants, deckA, null, false, null, null);
                        rpA.setPlayer(playerA);
                        if (lifeA > 0)
                            rpA.setStartingLife(lifeA);
                        if (customizeA != null)
                            customizeA.accept(rpA); // round 163
                        rpB = RegisteredPlayer.forVariants(2, variants, deckB, null, false, null, null);
                        rpB.setPlayer(playerB);
                        if (lifeB > 0)
                            rpB.setStartingLife(lifeB);
                        if (customizeB != null)
                            customizeB.accept(rpB); // round 163
                        List<RegisteredPlayer> players = new ArrayList<>();
                        players.add(rpA);
                        players.add(rpB);

                        GameRules rules = new GameRules(GameType.Adventure);
                        rules.setGamesPerMatch(1);
                        rules.setPlayForAnte(false);
                        rules.setManaBurn(false);
                        rules.setWarnAboutAICards(false);

                        Match match = new Match(rules, players, "Deck Tester Batch");
                        boolean timedOut = false;
                        final int gameNumber = i + 1;
                        // A fresh single-use executor per game (not one shared across the whole
                        // batch) - if a game hangs past the timeout, its still-running worker
                        // thread is abandoned (interrupted via shutdownNow(), daemon so it can't
                        // block JVM exit) rather than blocking every subsequent trial behind it on
                        // a shared queue. createGame() runs INSIDE this executor now, not before
                        // it - the original version called it synchronously on the batch thread
                        // itself, unprotected by the timeout below entirely, so a hang there (not
                        // a thrown exception, an actual hang) blocked the whole batch forever with
                        // no timeout and no error ever logged. Confirmed via forge.log: games 1-3
                        // completed normally, then total silence with no "batch aborted" line -
                        // proof the thread was still alive and blocked, not dead.
                        ExecutorService gameExecutor = Executors.newSingleThreadExecutor(r -> {
                            Thread t = new Thread(r, "DeckTesterBatch-Game-" + gameNumber);
                            t.setDaemon(true);
                            return t;
                        });
                        final AtomicReference<Game> gameRef = new AtomicReference<>();
                        try {
                            Future<?> future = gameExecutor.submit(() -> {
                                Game game = match.createGame();
                                gameRef.set(game);
                                match.startGame(game);
                            });
                            // Poll in short slices rather than one 90s blocking get() - lets the
                            // End Test button (handle.cancelled) interrupt within ~0.5s even
                            // mid-game, without needing the hung task itself to cooperate (same
                            // "abandon and move on" semantics as a real timeout, just triggered by
                            // the user instead of the clock).
                            long deadline = System.currentTimeMillis() + PER_GAME_TIMEOUT_SECONDS * 1000L;
                            while (true) {
                                try {
                                    future.get(500, TimeUnit.MILLISECONDS);
                                    break;
                                } catch (TimeoutException pollTimeout) {
                                    if (handle.cancelled.get()) {
                                        timedOut = true;
                                        userAborted = true;
                                        System.out.println("[TFR-DeckTesterBatch] game " + gameNumber
                                                + "/" + count + " abandoned - user ended the test");
                                        break;
                                    }
                                    if (System.currentTimeMillis() >= deadline) {
                                        timedOut = true;
                                        System.err.println("[TFR-DeckTesterBatch] game " + gameNumber
                                                + "/" + count + " timed out after " + PER_GAME_TIMEOUT_SECONDS + "s");
                                        break;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            timedOut = true;
                            System.err.println("[TFR-DeckTesterBatch] game " + gameNumber + "/" + count
                                    + " failed or timed out, counting as a draw: " + e);
                        } finally {
                            gameExecutor.shutdownNow();
                            // 2026-08-13 holistic review: shutdownNow() only delivers an interrupt,
                            // which forge-game's engine loop largely ignores - an abandoned game
                            // kept simulating at full CPU on its daemon thread indefinitely (one
                            // per timed-out/cancelled game). setGameOver(Draw) is how stock
                            // SimulateMatch.simulateSingleMatch()'s own timeout handler stops an
                            // abandoned game's loop - same cross-thread call, same precedent.
                            if (timedOut && gameRef.get() != null) {
                                try {
                                    gameRef.get().setGameOver(GameEndReason.Draw);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        winner = timedOut ? null : match.getWinner();
                    } catch (Exception | StackOverflowError e) {
                        // StackOverflowError alongside Exception matches forge-gui-desktop's own
                        // SimulateMatch.simulateSingleMatch() precedent for exactly this kind of
                        // headless AI-vs-AI simulation loop - deliberately not a blanket Throwable
                        // catch, so a truly fatal Error (OutOfMemoryError etc.) still propagates.
                        System.err.println("[TFR-DeckTesterBatch] game " + (i + 1) + "/" + count
                                + " threw during setup, counting as a draw: " + e);
                    }

                    // 2026-08-13 holistic review: a game the user aborted mid-run via End Test was
                    // previously tallied as a completed draw (and, when it was the FINAL game,
                    // made the batch look like a clean full run with a phantom draw in it - the
                    // caller's "ended early" check compares completed vs total). An aborted game
                    // is simply not a result - skip the tally entirely and let the loop-top
                    // cancelled check log the batch-level "ended by user" line and exit.
                    if (userAborted)
                        continue;

                    if (winner != null && winner == rpA)
                        result.deckAWins++;
                    else if (winner != null && winner == rpB)
                        result.deckBWins++;
                    else
                        result.draws++;
                    result.completed = i + 1;

                    System.out.println("[TFR-DeckTesterBatch] game=" + result.completed + "/" + count
                            + " winner=" + (winner != null && winner == rpA ? deckAName : winner != null && winner == rpB ? deckBName : "draw/timeout/error")
                            + " tally=" + result.deckAWins + "-" + result.deckBWins + "-" + result.draws);

                    if (onProgress != null) {
                        int completedSoFar = result.completed;
                        Gdx.app.postRunnable(() -> onProgress.accept(completedSoFar));
                    }
                }
            } catch (Exception | StackOverflowError e) {
                System.err.println("[TFR-DeckTesterBatch] batch aborted early after " + result.completed
                        + "/" + count + " games: " + e);
            } finally {
                if (onComplete != null)
                    Gdx.app.postRunnable(() -> onComplete.accept(result));
            }
        }, "DeckTesterBatch");
        batchThread.setDaemon(true);
        batchThread.start();
        return handle;
    }
}
