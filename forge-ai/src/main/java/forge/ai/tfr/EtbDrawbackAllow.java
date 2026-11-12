package forge.ai.tfr;

import java.util.LinkedHashMap;
import java.util.Map;

import forge.ai.simulation.GameSimulator;
import forge.ai.simulation.GameStateEvaluator;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.AITimeoutTracker;

/**
 * TFR round 1018, rule {@value #ID} (audit pattern 2), a stopgap until upstream fixes {@code AiController.checkETBEffects}.
 *
 * <p>Forge's heuristic AI runs each enter trigger of a permanent it wants to cast as if the trigger were optional, and
 * vetoes the whole card ({@code AiPlayDecision.BadEtbEffects}) when it would not choose to run it. A mandatory drawback
 * then keeps a good creature in hand forever: Crocodile of the Crossing (a -1/-1 counter on a creature you control) sat
 * in hand for 35 turns, Ichor Rats (a poison counter for each player) for 13.</p>
 *
 * <p>This rule weighs the drawback instead of vetoing: when the veto fires, it plays the spell out in a copy of the game
 * (the triggers resolve, the AI picks their targets as it would), scores the copy with Forge's own
 * {@link GameStateEvaluator}, and lifts the veto when the result beats the position without the cast by more than
 * {@code minNetValue}. A drawback that kills the AI or eats the creature itself (Accursed Centaur with no other creature)
 * scores worse, and the veto stands. A spell the AI cannot pay for now is
 * left alone (the veto stands, no copy is played). The answer is kept for the rest of the phase while the board is
 * unchanged; a kept veto logs once a turn per card.</p>
 *
 * <p>Hook: one line in {@code AiController.saSideEffects}. Puzzles: P1_crocodile_hold, P2_ichor_rats_hold.</p>
 */
final class EtbDrawbackAllow {
    static final String ID = "etb-drawback-allow";

    /** card id, turn, phase and a board fingerprint -> the answer; bounded, oldest out. */
    private static final Map<String, Boolean> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Boolean> e) {
            return size() > 256;
        }
    };

    /** game:card:turn of the vetoes already logged, so a held card logs once a turn. */
    private static final Map<String, Boolean> LOGGED = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Boolean> e) {
            return size() > 256;
        }
    };

    private EtbDrawbackAllow() {
    }

    static boolean allow(final Player ai, final Card spellHost, final SpellAbility sa) {
        if (!TfrAiRules.isOn(ID, ai) || sa == null || !sa.isSpell()) {
            return false;
        }
        final Game game = ai.getGame();
        final Card card = sa.getHostCard() != null ? sa.getHostCard() : spellHost;
        if (!forge.ai.ComputerUtilMana.canPayManaCost(sa, ai, 0, false)) {
            return false; // not castable now anyway: no copy to play out, nothing to log
        }
        final String key = game.getId() + ":" + card.getId() + ":" + game.getPhaseHandler().getTurn() + ":"
                + game.getPhaseHandler().getPhase() + ":" + game.getCardsIn(ZoneType.Battlefield).size() + ":"
                + ai.getCardsIn(ZoneType.Hand).size() + ":" + ai.getLife() + ":" + ai.getPoisonCounters();
        synchronized (CACHE) {
            final Boolean known = CACHE.get(key);
            if (known != null) {
                return known;
            }
        }
        final int minNet = TfrAiRules.entry(ID) == null ? 0 : TfrAiRules.entry(ID).intParam("minNetValue", 0);
        boolean allow = false;
        String why;
        GameSimulator sim = null;
        try {
            final GameStateEvaluator eval = new GameStateEvaluator();
            final int before = eval.getScoreForGameState(game, ai).value;
            sim = new GameSimulator(game, ai);
            if (!sim.applySpellAbility(sa)) {
                why = "could not be played out";
            } else {
                final Game copy = sim.getSimulatedGameState();
                final Player me = sim.getSimulatedAiPlayer();
                final int after = eval.getScoreForGameState(copy, me).value;
                final long net = (long) after - before;
                allow = net > minNet;
                why = (copy.isGameOver() ? (me.hasLost() ? "loses the game" : "wins the game") : "net " + (net > 0 ? "+" : "") + net)
                        + " (score " + before + " -> " + after + ", needs > " + minNet + ")";
            }
        } catch (final AITimeoutTracker.Expired e) {
            throw e; // the decision's deadline: not this rule's to swallow
        } catch (final RuntimeException e) {
            why = "simulation failed: " + e;
        } finally {
            if (sim != null) {
                sim.releaseCopier();
            }
        }
        synchronized (CACHE) {
            CACHE.put(key, allow);
        }
        final String at = "turn " + game.getPhaseHandler().getTurn() + " " + ai.getName() + " " + card.getName();
        if (allow) {
            TfrAiRules.fired(ID, at + ": BadEtbEffects", "WillPlay, " + why);
        } else {
            final boolean first;
            synchronized (LOGGED) {
                first = LOGGED.put(game.getId() + ":" + card.getId() + ":" + game.getPhaseHandler().getTurn(), Boolean.TRUE) == null;
            }
            if (first) {
                System.out.println(TfrAiRules.TAG + " " + ID + " kept the veto: " + at + ", " + why);
            }
        }
        return allow;
    }
}
