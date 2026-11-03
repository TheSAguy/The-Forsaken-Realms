package forge.game.combat;

import java.util.concurrent.atomic.AtomicInteger;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.util.AITimeoutTracker;

/**
 * TFR round 929: PhaseHandler's attack declaration loops until a declaration passes. A person can try again; an AI that
 * can't produce a passing declaration froze the game, in a Flat Monte Carlo rollout (Xira, turn 12: 8,473 retries in
 * 10 minutes). So an AI's retry stops at its deadline when one is armed, which ends a rollout, and otherwise gives up
 * after a few tries with no attack: skipping an attack beats a frozen game. A human declarer is never touched.
 */
public final class AttackRetryLimit {
    /** Declarations an AI gets before its attack is skipped. */
    static final int MAX_AI_TRIES = 5;
    /** The log lines of one launch: enough to name the cause, not a flood. */
    private static final int MAX_LINES = 12;
    private static final AtomicInteger LINES = new AtomicInteger();

    private AttackRetryLimit() {
    }

    /**
     * Called before a retry, after {@code tries} failed declarations.
     *
     * @return true when the attack is given up: the attackers are cleared and the caller ends its loop
     * @throws AITimeoutTracker.Expired when this thread's AI deadline has passed
     */
    public static boolean giveUp(final Player declarer, final Combat combat, final int tries, final String failed) {
        if (!declarer.getController().isAI()) {
            return false;
        }
        final boolean last = tries >= MAX_AI_TRIES;
        if (LINES.incrementAndGet() <= MAX_LINES) {
            log(declarer, combat, tries, failed, last);
        }
        AITimeoutTracker.check();
        if (last) {
            combat.clearAttackers();
        }
        return last;
    }

    private static void log(final Player declarer, final Combat combat, final int tries, final String failed, final boolean last) {
        final StringBuilder attackers = new StringBuilder();
        for (final Card c : combat.getAttackers()) {
            attackers.append(attackers.length() == 0 ? "" : ", ").append(c).append(c.isInPlay() ? "" : " not in play")
                    .append(c.isCreature() ? "" : " not a creature").append(c.isTapped() ? " tapped" : "");
        }
        final AttackConstraints constraints = combat.getAttackConstraints();
        System.out.println("[TFR-AttackRetry] " + declarer + " try " + tries + " failed at " + failed
                + (declarer.getGame().isSimulationCopy() ? " in a rollout" : " in the real game")
                + " - violations " + constraints.countViolations(combat.getAttackersAndDefenders())
                + ", best legal " + constraints.getLegalAttackers().getRight() + ", attackers [" + attackers + "]"
                + (last ? " - attack skipped" : ""));
    }
}
