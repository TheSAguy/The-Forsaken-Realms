package forge.ai.tfr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import forge.ai.simulation.FlatMonteCarloPicker;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;

/**
 * TFR round 1019, rule {@value #ID} (the board stall of audit game g021, puzzle {@code P3b}): Flat Monte Carlo replaces
 * the heuristic's attack with "no attack"; the rule keeps the heuristic's attack.
 *
 * <p>Why: on g021's board (25 Eldrazi tokens against 10 crocodiles) Flat Monte Carlo cut the crocodile side's attack to
 * nothing on 10 turns in a row and the board stalled for 20 turns. Round 1019 measured why: the rollouts are almost
 * deterministic (about 95% of the lines with two samples in 56 bench games had two identical samples), so the cut is not
 * noise but the position score, which values the creatures kept home more than the damage dealt. More samples would
 * not change it; a guard on the sample count would not either.</p>
 *
 * <p>The rule stays out (and logs why) when the heuristic's attack is the risky one:</p>
 * <ul>
 *   <li>the defender has an attack or block trigger (puzzle P4: Giggling Skitterspike kills the attacker before damage);</li>
 *   <li>the swing back could be lethal: every creature of the opponent's attacks next turn into the AI's creatures that
 *   stay untapped (the heuristic's attackers count as tapped unless they have vigilance), best blocks for the AI, no
 *   tricks, and the attack is assumed to kill nothing - a count that errs towards staying out (puzzle P5);</li>
 *   <li>params: {@code cuts} = {@code "to-nothing"} (default) reviews only a cut to no attack, {@code "any"} every
 *   smaller attack; {@code belowSamples} &gt; 0 reviews only cuts made on fewer samples per line than that (0 = any).</li>
 * </ul>
 * A bigger attack than the heuristic's is never reviewed. The stock hook is one line in
 * {@code AiController.refineAttackWithFlatMonteCarlo}, listed in {@code CORE_ENGINE_CHANGES.md}.
 */
final class McAttackCutGuard {
    static final String ID = "mc-attack-cut-guard";

    private McAttackCutGuard() {
    }

    /**
     * Whether to keep the heuristic's attack ({@code heuristic}, still in the combat) instead of Flat Monte Carlo's
     * {@code result}. Called only when the rollouts chose a different attack.
     */
    static boolean keepHeuristic(final Player ai, final Map<Card, GameEntity> heuristic, final FlatMonteCarloPicker.Result result) {
        if (result == null || result.attackers == null || heuristic == null || !TfrAiRules.isOn(ID, ai)) {
            return false;
        }
        final int sent = heuristic.size();
        final int chosen = result.attackers.size();
        if (chosen >= sent) {
            return false; // not a cut: a bigger or equal attack is Flat Monte Carlo's to make
        }
        final TfrAiRules.Entry entry = TfrAiRules.entry(ID);
        final boolean anyCut = entry != null && "any".equals(entry.params.get("cuts"));
        if (chosen > 0 && !anyCut) {
            return false; // a smaller attack, not the stall: Flat Monte Carlo sparing an attacker is left alone
        }
        final int belowSamples = entry == null ? 0 : entry.intParam("belowSamples", 0);
        final int lines = Math.max(1, result.candidatesEvaluated);
        // Every line gets its first sample in round one, then whole rounds: rollouts/lines (rounded down) is what the
        // thinnest line is sure of. It counts samples, not information: most samples repeat the first one exactly.
        final int perLine = result.rollouts / lines;
        final Game game = ai.getGame();
        final String head = "turn " + game.getPhaseHandler().getTurn() + " " + ai.getName() + " FlatMC cut the attack from "
                + sent + " to " + chosen + " (" + power(heuristic) + " to " + power(result.attackers) + " power; "
                + result.rollouts + " rollouts over " + lines + " lines, " + perLine + " per line)";
        if (belowSamples > 0 && perLine >= belowSamples) {
            log(ID + " stays out: " + head + ", enough samples (belowSamples " + belowSamples + ")");
            return false;
        }
        final Player d = soleOpponent(ai);
        if (d == null) {
            log(ID + " stays out: " + head + ", not one opponent");
            return false;
        }
        final Card trap = AlphaStrikeCount.combatTrigger(d);
        if (trap != null) {
            log(ID + " stays out: " + head + ", " + trap.getName() + " has a combat trigger that acts before damage");
            return false;
        }
        final String crackBack = crackBackLethal(ai, d, heuristic);
        if (crackBack != null) {
            log(ID + " stays out: " + head + ", " + crackBack);
            return false;
        }
        final String after = "keep the heuristic's " + sent + " attackers (opponent life " + d.getLife() + ", own life "
                + ai.getLife() + ")";
        if (entry != null && entry.boolParam("shadow", false)) {
            TfrAiRules.shadow(ID, head, after);
            return false;
        }
        TfrAiRules.fired(ID, head, after);
        return true;
    }

    private static Player soleOpponent(final Player ai) {
        final List<Player> opps = new ArrayList<>();
        for (final Player o : ai.getOpponents()) {
            if (o.isInGame()) {
                opps.add(o);
            }
        }
        return opps.size() == 1 ? opps.get(0) : null;
    }

    private static int power(final Map<Card, GameEntity> attack) {
        int p = 0;
        for (final Card c : attack.keySet()) {
            p += Math.max(0, c.getNetCombatDamage());
        }
        return p;
    }

    /**
     * Whether the opponent's next attack could kill the AI after the heuristic's attack: null when it cannot, else the
     * reason. Every creature of the opponent's (defenders aside) attacks; the AI blocks with what stays untapped.
     */
    static String crackBackLethal(final Player ai, final Player d, final Map<Card, GameEntity> heuristic) {
        final List<Card> theirs = new ArrayList<>();
        for (final Card c : d.getCreaturesInPlay()) {
            if (!c.hasKeyword(Keyword.DEFENDER)) {
                theirs.add(c);
            }
        }
        if (theirs.isEmpty()) {
            return null;
        }
        final List<Card> blockers = new ArrayList<>();
        for (final Card b : ai.getCreaturesInPlay()) {
            final boolean tapsToAttack = heuristic.containsKey(b) && !b.hasKeyword(Keyword.VIGILANCE);
            if (!tapsToAttack && !b.isTapped() && CombatUtil.canBlock(b, true)) {
                blockers.add(b);
            }
        }
        final int n = theirs.size();
        final int[] dmg = new int[n];
        final int[] psn = new int[n];
        for (int i = 0; i < n; i++) {
            final Card a = theirs.get(i);
            final int base = Math.max(0, a.getNetCombatDamage()) * (a.hasDoubleStrike() ? 2 : 1);
            if (a.hasKeyword(Keyword.INFECT)) {
                psn[i] = base;
            } else {
                dmg[i] = base;
                if (a.hasKeyword(Keyword.TOXIC) || a.hasKeyword(Keyword.POISONOUS)) {
                    psn[i] = 1;
                }
            }
        }
        final boolean[][] can = new boolean[n][blockers.size()];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < blockers.size(); j++) {
                can[i][j] = CombatUtil.canBlock(theirs.get(i), blockers.get(j), true);
            }
        }
        final int[] cap = new int[blockers.size()];
        for (int j = 0; j < cap.length; j++) {
            final Card b = blockers.get(j);
            cap[j] = b.canBlockAny() ? n : 1 + Math.max(0, b.canBlockAdditional());
        }
        final int through = AlphaStrikeCount.through(dmg, can, cap);
        final int poison = AlphaStrikeCount.through(psn, can, cap);
        final boolean lifeLethal = ai.canLoseLife() && !ai.cantLoseForZeroOrLessLife() && through >= ai.getLife();
        final boolean poisonLethal = ai.canReceiveCounters(CounterEnumType.POISON)
                && poison >= ai.getGame().getRules().getPoisonCountersToLose() - ai.getPoisonCounters();
        if (!lifeLethal && !poisonLethal) {
            return null;
        }
        return "the swing back could be lethal (" + through + " dmg/" + poison + " poison past " + blockers.size()
                + " untapped blockers, own life " + ai.getLife() + ", poison " + ai.getPoisonCounters() + ")";
    }

    private static void log(final String line) {
        System.out.println(TfrAiRules.TAG + " " + line);
    }
}
