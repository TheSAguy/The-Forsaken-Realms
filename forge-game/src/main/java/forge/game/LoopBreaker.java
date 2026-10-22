package forge.game;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 *   game moves on;</li>
 *   <li><b>a trigger chain nobody can stop</b>: the same trigger of the same card coming back {@link #TRIGGER_DRAW_AT}
 *   times with no player choosing to cast or activate anything in between ends the game as a draw. A trigger is counted
 *   only when no copy of it is already waiting on the stack, so a board wipe's batch of death triggers is one, not
 *   thirty;</li>
 *   <li><b>the human's way out</b>: once either count reaches {@link #DRAW_OFFER_AT} this turn, the AI accepts the Game
 *   menu's Offer Draw (PlayerControllerAi.acceptsDrawOffer). Below that it declines, as it always did, so the offer
 *   cannot dodge an ordinary losing duel.</li>
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

    private static final class State {
        int turn = Integer.MIN_VALUE;
        final Map<String, Integer> aiRepeats = new HashMap<>();
        final Map<String, Integer> triggerRepeats = new HashMap<>();
        final Set<String> barred = new HashSet<>();
        int most;          // the highest count this turn
        String mostWhat;   // and what it was
        String endReason;  // why this game ended as a draw, when it did
    }

    private static final Map<Game, State> states = new WeakHashMap<>();

    private static State state(Game game) {
        State s = states.computeIfAbsent(game, g -> new State());
        int turn = game.getPhaseHandler() == null ? 0 : game.getPhaseHandler().getTurn();
        if (turn != s.turn) {
            s.turn = turn;
            s.aiRepeats.clear();
            s.triggerRepeats.clear();
            s.barred.clear();
            s.most = 0;
            s.mostWhat = null;
        }
        return s;
    }

    private static String key(SpellAbility sa) {
        Card host = sa.getHostCard();
        return (host == null ? "?" : host.getId()) + "|" + (sa.isTrigger() ? "T" + sa.getSourceTrigger() : sa.getDescription());
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
            Player activator = sa.getActivatingPlayer();
            if (activator == null || activator.getController() == null || !activator.getController().isAI())
                return false;
            int n = s.aiRepeats.merge(key, 1, Integer::sum);
            note(s, n, sa);
            if (n == AI_BAR_AT) {
                s.barred.add(key);
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
        int n = s.triggerRepeats.merge(key, 1, Integer::sum);
        note(s, n, sa);
        if (n < TRIGGER_DRAW_AT)
            return false;
        s.endReason = label(sa) + " triggered " + n + " times in a row with nobody acting";
        String msg = "Loop breaker: " + s.endReason + " - the game is a draw.";
        System.out.println("[TFR-LoopBreak] " + msg);
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
        return s != null && !s.barred.isEmpty() && s.turn == currentTurn(game) && s.barred.contains(key(sa));
    }

    /** PlayerControllerAi.acceptsDrawOffer: has this turn shown a loop ({@link #DRAW_OFFER_AT} repeats or more)? */
    public static synchronized boolean loopSeen(Game game) {
        State s = game == null ? null : states.get(game);
        boolean seen = s != null && s.turn == currentTurn(game) && s.most >= DRAW_OFFER_AT;
        System.out.println("[TFR-LoopBreak] draw offered - " + (seen ? "accepted: " + s.mostWhat + " repeated " + s.most
                + " times this turn" : "declined: no loop this turn (most repeats " + (s == null ? 0 : s.most) + ")"));
        if (seen)
            s.endReason = s.mostWhat + " repeated " + s.most + " times - you offered a draw";
        return seen;
    }

    /** Why this game ended as a loop draw, or null when it did not. */
    public static synchronized String drawReason(Game game) {
        State s = game == null ? null : states.get(game);
        return s == null ? null : s.endReason;
    }

    private static int currentTurn(Game game) {
        return game.getPhaseHandler() == null ? 0 : game.getPhaseHandler().getTurn();
    }
}
