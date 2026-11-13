package forge.game;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * TFR round 708 (the user: "I just had an endless loop in my game. Anyway to create a failsafe for this? Maybe break out
 * of loop after 25x or a menu option to skip a phase... I had to concede the match because of it."). Forge already ends
 * a game as a draw when the stack passes 999 items or a mandatory, untargeted trigger repeats 50 times with nobody
 * acting (MagicStack.isRepeatingTrigger), and stops the AI after 999 actions in one priority window. This catches what
 * those let through, counted per turn:
 * <ul>
 *   <li><b>the AI repeating itself</b>: the same ability of the same card put on the stack by the AI
 *   {@link #AI_BAR_AT} times in one turn is barred for the AI for the rest of that turn (AiController skips it), so the
 *   game moves on. Round 994: the count starts over each time an opponent of the AI has lost life since its last
 *   activation (round 840's rule for trigger chains), so a loop that is killing the player - upstream's FlatMC
 *   LoopRunner, Mikaeus and a Triskelion - runs to the end; one that loses nobody life is still barred at 25. A loop
 *   that drains life but cannot kill (Platinum Angel, a life-gain offset) stops at {@link #AI_BACKSTOP_AT} uses of an
 *   ability of that card name in a turn, whatever happened - upstream's own LoopRunner ceiling;</li>
 *   <li><b>a trigger chain nobody can stop</b>: the same trigger of the same card coming back {@link #TRIGGER_DRAW_AT}
 *   times with no player choosing to cast or activate anything in between, and nobody losing life, ends the game as a
 *   draw. A trigger is counted only when no copy of it is already waiting on the stack, so a board wipe's batch of
 *   death triggers is one, not thirty. Round 840: any life lost starts every chain over (upstream's own rule,
 *   Player.loseLife / MagicStack.clearTriggerRepeats, #12127: "a chain of triggers that costs life ends by itself"), so
 *   a drain or burn combo runs to the end; {@link #TRIGGER_BACKSTOP_AT} repeats of one trigger in a turn with nobody
 *   acting are a draw whatever happened, for a drain loop that cannot end (a "can't lose" effect). Round 930: a chain
 *   starts over each time it comes back on a stack lower than it has ever seen, so a batch's "whenever you gain life"
 *   payoff, alone on the stack after each life gain, doesn't draw its own board. A stack can't make new lows forever, so
 *   a real loop still counts (MagicStack.isRepeatingTrigger takes the same rule);</li>
 *   <li><b>the human's way out</b>: the AI accepts the Game menu's Offer Draw (PlayerControllerAi.acceptsDrawOffer) only
 *   once the breaker has barred an AI ability this turn, or while a trigger chain is live at {@link #DRAW_OFFER_AT} or
 *   more right now (round 840; it was any count reaching 10 this turn, which an AI pump used 10 times also met).
 *   Round 1028: or when the game has made no progress for {@link #NO_PROGRESS_TURNS} turns (see {@link #progress}) - a
 *   two-sided lifegain stall that only a concession, and the ante it costs, could end before.
 *   Otherwise it declines, as it always did, so the offer cannot dodge an ordinary losing duel.</li>
 * </ul>
 * The human's own repeated activations are never barred or turned into a draw - a combo is the player's to run.
 * [TFR-LoopBreak] logs every bar and draw with the card and ability, and the game log says it too.
 */
public final class LoopBreaker {
    private LoopBreaker() {
    }

    public static final int AI_BAR_AT = 25;
    public static final int TRIGGER_DRAW_AT = 25;
    public static final int DRAW_OFFER_AT = 10;
    public static final int TRIGGER_BACKSTOP_AT = 1000;
    /** Round 994: uses of one ability of one card name in a turn by the AI, life lost or not (LoopRunner.MAX_ACTIVATIONS). */
    public static final int AI_BACKSTOP_AT = 300;
    /** Round 1028: the no-progress draw's window, in game turns (each player's turn counts). */
    public static final int NO_PROGRESS_TURNS = 8;

    // round 1028: a snapshot's fields
    private static final int LIFE = 0, POISON = 1, LIBRARY = 2, POWER = 3, LOYALTY = 4;

    private static final class State {
        int turn = Integer.MIN_VALUE;
        final Map<String, Integer> aiRepeats = new HashMap<>();
        final Map<String, Integer> aiRawRepeats = new HashMap<>();   // round 994: by card name, never reset by life lost
        final Map<Integer, Integer> aiOppLifeMarks = new HashMap<>(); // round 994: each AI's opponents' life lost, at its last use
        final Set<String> barredRaw = new HashSet<>();               // round 994: name keys over AI_BACKSTOP_AT
        final Map<String, Integer> triggerRepeats = new HashMap<>(); // round 840: reset by any life lost too
        final Map<String, Integer> rawRepeats = new HashMap<>();     // round 840: reset only by a player acting
        final Map<String, Integer> triggerLows = new HashMap<>();    // round 930: each live chain's lowest stack size
        final Map<String, String> labels = new HashMap<>();          // round 840: key -> label, for the log
        final Set<String> barred = new HashSet<>();
        String barredWhat; // round 840: the first ability barred this turn
        int lifeLostMark;  // round 840: every player's life lost this turn, at the last trigger
        int most;          // the highest count this turn (for the log)
        String mostWhat;   // and what it was
        String endReason;  // why this game ended as a draw, when it did
        int bars;          // round 735: every bar this game, never reset by a new turn
        // round 1028: each turn's start, turn -> player id -> {life, poison, library, power, loyalty}; never reset by a turn
        final TreeMap<Integer, Map<Integer, int[]>> history = new TreeMap<>();
        boolean noProgressDraw; // round 1028: the offered draw was accepted for no progress
    }

    private static final Map<Game, State> states = new WeakHashMap<>();

    private static State state(Game game) {
        State s = states.computeIfAbsent(game, g -> new State());
        int turn = game.getPhaseHandler() == null ? 0 : game.getPhaseHandler().getTurn();
        if (turn != s.turn) {
            s.turn = turn;
            s.aiRepeats.clear();
            s.aiRawRepeats.clear();
            s.aiOppLifeMarks.clear();
            s.barredRaw.clear();
            s.triggerRepeats.clear();
            s.rawRepeats.clear();
            s.triggerLows.clear();
            s.labels.clear();
            s.barred.clear();
            s.barredWhat = null;
            s.lifeLostMark = lifeLost(game);
            s.most = 0;
            s.mostWhat = null;
        }
        return s;
    }

    private static String key(SpellAbility sa) {
        Card host = sa.getHostCard();
        return (host == null ? "?" : host.getId()) + "|" + (sa.isTrigger() ? "T" + sa.getSourceTrigger() : sa.getDescription());
    }

    /** Round 994: the backstop's key - the card's name, not its id, so a card that comes back as a new object counts on. */
    private static String rawKey(SpellAbility sa) {
        Card host = sa.getHostCard();
        return (host == null ? "?" : host.getName()) + "|" + sa.getDescription();
    }

    private static String label(SpellAbility sa) {
        Card host = sa.getHostCard();
        String what = sa.getDescription();
        if (what != null && what.length() > 90)
            what = what.substring(0, 87) + "...";
        return (host == null ? "?" : host.getName()) + (what == null || what.isEmpty() ? "" : " - " + what);
    }

    private static void note(State s, int count, SpellAbility sa) {
        if (count > s.most) {
            s.most = count;
            s.mostWhat = label(sa);
        }
    }

    /**
     * Round 930: the one rule for both trigger counts. A chain with a count remembers the lowest stack size it has come
     * back on, and a new low starts it over: the stack is draining, so this isn't a loop. A count cleared by its owner
     * (a player acting, life lost) has no chain left, so the next size it sees is its first.
     */
    public static <K> void newLowStartsOver(Map<K, Integer> lows, Map<K, Integer> repeats, K key, int stackSize) {
        if (!repeats.containsKey(key) || stackSize < lows.get(key)) {
            lows.put(key, stackSize);
            repeats.remove(key);
        }
    }

    /**
     * MagicStack.add(), for a new item about to go on the stack. True when this ended the game as a draw - the caller
     * then adds nothing.
     */
    public static synchronized boolean onStackAdd(Game game, SpellAbility sa, Iterable<SpellAbilityStackInstance> stack) {
        if (game == null || sa == null || game.isGameOver())
            return false;
        State s = state(game);
        String key = key(sa);
        if (!sa.isTrigger()) {
            s.triggerRepeats.clear(); // a player chose to do something: any trigger chain starts over
            s.rawRepeats.clear();
            Player activator = sa.getActivatingPlayer();
            if (activator == null || activator.getController() == null || !activator.getController().isAI())
                return false;
            // round 994: an opponent lost life since this AI's last use - the loop is going somewhere, every count starts over
            int oppLost = opponentsLifeLost(activator);
            Integer mark = s.aiOppLifeMarks.put(activator.getId(), oppLost);
            if (mark != null && mark != oppLost)
                s.aiRepeats.clear();
            int raw = s.aiRawRepeats.merge(rawKey(sa), 1, Integer::sum);
            if (raw == AI_BACKSTOP_AT) {
                s.barredRaw.add(rawKey(sa));
                if (s.barredWhat == null)
                    s.barredWhat = label(sa);
                s.bars++;
                String msg = "Loop breaker: " + activator.getName() + " used " + label(sa) + " " + raw
                        + " times this turn, life lost or not - the AI stops using it until next turn.";
                System.out.println("[TFR-LoopBreak] " + msg);
                game.getGameLog().add(GameLogEntryType.INFORMATION, msg);
            }
            int n = s.aiRepeats.merge(key, 1, Integer::sum);
            note(s, n, sa);
            if (n == AI_BAR_AT) {
                s.barred.add(key);
                if (s.barredWhat == null)
                    s.barredWhat = label(sa);
                s.bars++;
                String msg = "Loop breaker: " + activator.getName() + " used " + label(sa) + " " + n
                        + " times this turn - the AI stops using it until next turn.";
                System.out.println("[TFR-LoopBreak] " + msg);
                game.getGameLog().add(GameLogEntryType.INFORMATION, msg);
            }
            return false;
        }
        for (SpellAbilityStackInstance si : stack) {
            SpellAbility waiting = si.getSpellAbility();
            if (waiting != null && key.equals(key(waiting)))
                return false; // the same trigger is already waiting: a batch, not a loop
        }
        int lost = lifeLost(game);
        if (lost != s.lifeLostMark) {
            s.triggerRepeats.clear(); // round 840: someone lost life - the chain is going somewhere, it ends by itself
            s.lifeLostMark = lost;
        }
        int size = game.getStack().size();
        newLowStartsOver(s.triggerLows, s.triggerRepeats, key, size);
        int n = s.triggerRepeats.merge(key, 1, Integer::sum);
        int raw = s.rawRepeats.merge(key, 1, Integer::sum);
        s.labels.computeIfAbsent(key, k -> label(sa));
        note(s, n, sa);
        if (raw == TRIGGER_DRAW_AT && n < TRIGGER_DRAW_AT && !game.isSimulationCopy())
            System.out.println("[TFR-LoopBreak] " + label(sa) + " came back " + raw + " times, its chain at " + n
                    + ": the stack is at " + size + " or life was lost");
        if (n >= TRIGGER_DRAW_AT)
            s.endReason = label(sa) + " triggered " + n + " times in a row with nobody acting";
        else if (raw >= TRIGGER_BACKSTOP_AT)
            s.endReason = label(sa) + " triggered " + raw + " times this turn with nobody acting";
        else
            return false;
        String msg = "Loop breaker: " + s.endReason + " - the game is a draw.";
        System.out.println("[TFR-LoopBreak] " + msg + (game.isSimulationCopy() ? " (in a rollout)" : ""));
        game.getGameLog().add(GameLogEntryType.INFORMATION, msg);
        for (Player p : game.getPlayers())
            p.intentionalDraw();
        game.setGameOver(GameEndReason.Draw);
        return true;
    }

    /** AiController: is this ability barred for the AI this turn? */
    public static synchronized boolean isBarred(Game game, SpellAbility sa) {
        if (game == null || sa == null)
            return false;
        State s = states.get(game);
        return s != null && s.turn == currentTurn(game)
                && (s.barred.contains(key(sa)) || s.barredRaw.contains(rawKey(sa))); // round 994: the backstop too
    }

    /**
     * PlayerControllerAi.acceptsDrawOffer: is there a loop? Round 840: yes once the breaker has barred an AI ability this
     * turn, or while a trigger chain is live at {@link #DRAW_OFFER_AT} or more right now (any action or life lost resets
     * it). The turn's highest count is only logged. Round 1028: with no loop, it also accepts when nothing has moved for
     * {@link #NO_PROGRESS_TURNS} turns ({@link #progress}); the loop test runs first and is unchanged.
     */
    public static synchronized boolean loopSeen(Game game) {
        State s = game == null ? null : states.get(game);
        boolean now = s != null && s.turn == currentTurn(game);
        String chainKey = null;
        int chain = 0;
        if (now) {
            for (Map.Entry<String, Integer> e : s.triggerRepeats.entrySet()) {
                if (e.getValue() > chain) {
                    chain = e.getValue();
                    chainKey = e.getKey();
                }
            }
        }
        String why = null;
        if (now && s.barredWhat != null)
            why = s.barredWhat + " used " + AI_BAR_AT + " times this turn";
        else if (chain >= DRAW_OFFER_AT)
            why = s.labels.get(chainKey) + " triggered " + chain + " times in a row";
        if (why != null) {
            System.out.println("[TFR-LoopBreak] draw offered - accepted: " + why);
            s.endReason = why + " - you offered a draw";
            return true;
        }
        String noLoop = "no loop (no AI ability barred, live trigger chain " + chain + "; most repeats this turn "
                + (now ? s.most : 0) + (now && s.mostWhat != null ? ", " + s.mostWhat : "") + ")";
        // round 1028: the second test - a game that has stopped moving
        StringBuilder held = new StringBuilder();
        String progress = s == null ? "no turn seen yet" : progress(game, s, held);
        if (progress != null) {
            System.out.println("[TFR-LoopBreak] draw offered - declined: " + noLoop + "; progress: " + progress);
            return false;
        }
        System.out.println("[TFR-LoopBreak] draw offered - accepted: no progress for " + NO_PROGRESS_TURNS + " turns ("
                + held + ")");
        s.endReason = "no progress for " + NO_PROGRESS_TURNS + " turns - you offered a draw";
        s.noProgressDraw = true;
        game.getGameLog().add(GameLogEntryType.INFORMATION, "Loop breaker: neither side made progress in the last "
                + NO_PROGRESS_TURNS + " turns - the draw is accepted.");
        return true;
    }

    /**
     * Round 1028: PhaseHandler, as each turn begins - every player's life, poison, library, creature power and planeswalker
     * loyalty, for the no-progress draw. A rollout's copy keeps no history.
     */
    public static synchronized void onTurnBegan(Game game) {
        if (game == null || game.isSimulationCopy())
            return;
        State s = state(game);
        Map<Integer, int[]> snap = new HashMap<>();
        for (Player p : game.getRegisteredPlayers())
            snap.put(p.getId(), snapshot(p));
        s.history.put(s.turn, snap);
        while (s.history.size() > NO_PROGRESS_TURNS + 1)
            s.history.pollFirstEntry();
    }

    private static int[] snapshot(Player p) {
        int power = 0, loyalty = 0;
        for (Card c : p.getCreaturesInPlay())
            power += Math.max(0, c.getNetPower());
        for (Card c : p.getPlaneswalkersInPlay())
            loyalty += c.getCurrentLoyalty();
        return new int[] {p.getLife(), p.getPoisonCounters(), p.getCardsIn(ZoneType.Library).size(), power, loyalty};
    }

    /** Round 1028: how far one side's creature power may move over the window and still be "no progress". */
    public static int powerSlack(int start) {
        return Math.max(3, start / 4);
    }

    /**
     * Round 1028: what moved over the last {@link #NO_PROGRESS_TURNS} turns, or null when nothing did - every player in
     * the game, now against the snapshot at the window's start (and its middle, for life):
     * <ul>
     *   <li>life lower than {@code NO_PROGRESS_TURNS} or half as many turns ago (a race, a beatdown, a drain);</li>
     *   <li>poison counters up;</li>
     *   <li>a library that would run out within twice the window at the window's rate (mill, or the end of the deck);</li>
     *   <li>creature power up or down by more than {@link #powerSlack} (a side building, or trading away its board);</li>
     *   <li>planeswalker loyalty up (an ultimate coming).</li>
     * </ul>
     * A side heading for a win moves one of these, so the player can't use the offer to dodge a loss on its way. Life
     * that only goes up - a two-sided lifegain stall - is no progress. {@code held} gets each player's numbers.
     */
    static String progress(Game game, State s, StringBuilder held) {
        int turn = currentTurn(game);
        Map<Integer, int[]> start = s.history.get(turn - NO_PROGRESS_TURNS);
        Map<Integer, int[]> mid = s.history.get(turn - NO_PROGRESS_TURNS / 2);
        if (start == null || mid == null)
            return "too early (turn " + turn + ", needs turn " + (NO_PROGRESS_TURNS + 1) + "+ with every turn seen)";
        for (Player p : game.getPlayers()) {
            int[] a = start.get(p.getId()), m = mid.get(p.getId());
            if (a == null || m == null)
                return "too early (" + p.getName() + " not seen " + NO_PROGRESS_TURNS + " turns ago)";
            int[] b = snapshot(p);
            String who = p.getName();
            if (b[LIFE] < a[LIFE] || b[LIFE] < m[LIFE])
                return who + "'s life " + a[LIFE] + "->" + m[LIFE] + "->" + b[LIFE];
            if (b[POISON] > a[POISON])
                return who + "'s poison " + a[POISON] + "->" + b[POISON];
            int gone = a[LIBRARY] - b[LIBRARY];
            if (b[LIBRARY] == 0 || (gone > 0 && b[LIBRARY] <= 2 * gone))
                return who + "'s library " + a[LIBRARY] + "->" + b[LIBRARY] + " runs out within " + 2 * NO_PROGRESS_TURNS
                        + " turns";
            if (Math.abs(b[POWER] - a[POWER]) > powerSlack(a[POWER]))
                return who + "'s creature power " + a[POWER] + "->" + b[POWER];
            if (b[LOYALTY] > a[LOYALTY])
                return who + "'s planeswalker loyalty " + a[LOYALTY] + "->" + b[LOYALTY];
            if (held.length() > 0)
                held.append("; ");
            held.append(who).append(" life ").append(a[LIFE]).append("->").append(b[LIFE]).append(", power ")
                    .append(a[POWER]).append("->").append(b[POWER]).append(", library ").append(a[LIBRARY]).append("->")
                    .append(b[LIBRARY]);
        }
        return null;
    }

    /** Round 1028: did this game end in a draw the AI accepted because nothing was moving? */
    public static synchronized boolean noProgressDraw(Game game) {
        State s = game == null ? null : states.get(game);
        return s != null && s.noProgressDraw;
    }

    /** Why this game ended as a loop draw, or null when it did not. */
    public static synchronized String drawReason(Game game) {
        State s = game == null ? null : states.get(game);
        return s == null ? null : s.endReason;
    }

    /** Round 735, ReloadJab's mid-duel quit: has the breaker barred an AI loop or drawn this game at any point? */
    public static synchronized boolean firedIn(Game game) {
        State s = game == null ? null : states.get(game);
        return s != null && (s.bars > 0 || s.endReason != null);
    }

    private static int lifeLost(Game game) {
        int sum = 0;
        for (Player p : game.getRegisteredPlayers())
            sum += p.getLifeLostThisTurn();
        return sum;
    }

    /** Round 994: life lost this turn by the player's opponents. */
    private static int opponentsLifeLost(Player p) {
        int sum = 0;
        for (Player o : p.getOpponents())
            sum += o.getLifeLostThisTurn();
        return sum;
    }

    private static int currentTurn(Game game) {
        return game.getPhaseHandler() == null ? 0 : game.getPhaseHandler().getTurn();
    }
}
